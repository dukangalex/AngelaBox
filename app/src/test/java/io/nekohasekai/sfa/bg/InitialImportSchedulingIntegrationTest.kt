package io.nekohasekai.sfa.bg

import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileDatabase
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.persistImportedProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room/WorkManager scheduling; worker body substitutes the native/network dependency. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class InitialImportSchedulingIntegrationTest {
    @Test
    fun committedIntentReplaysAfterReopenAndCleansOnlyOnMaterializationOrDelete() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        val name = "initial-intent-restart.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ProfileDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            val profile = Profile(
                name = "pending",
                typed = TypedProfile().apply {
                    type = TypedProfile.Type.Remote
                    remoteURL = "https://example.com/first"
                    autoUpdate = false
                },
            )
            // Inject a transaction failure: neither the profile nor intent may survive.
            try {
                db.runInTransaction {
                    db.insertProfile(profile, true)
                    error("injected before transaction commit")
                }
            } catch (_: IllegalStateException) { }
            assertTrue(db.profileDao().list().isEmpty())
            assertTrue(db.initialImportDao().pendingIds().isEmpty())
            profile.id = db.insertProfile(profile, true)
            // Simulate death after profile commit but BEFORE WorkManager enqueue.
            db.close()
            db = open()
            assertEquals(listOf(profile.id), db.initialImportDao().pendingIds())
            assertFalse(db.profileDao().get(profile.id)!!.typed.autoUpdate)
            UpdateProfileWork.enqueuePendingInitialImports(db.initialImportDao().pendingIds(), manager)
            val unique = "UpdateProfileInitial_${profile.id}"
            val first = manager.getWorkInfosForUniqueWork(unique).get().single()
            assertEquals(WorkInfo.State.ENQUEUED, first.state)
            // Reopen/replay after enqueue commits: KEEP preserves request/backoff identity.
            db.close()
            db = open()
            UpdateProfileWork.enqueuePendingInitialImports(db.initialImportDao().pendingIds(), manager)
            assertEquals(first.id, manager.getWorkInfosForUniqueWork(unique).get().single().id)
            // Changing a source must retain explicit intent, without stale URL in the outbox.
            profile.typed.remoteURL = "https://example.com/replacement"
            db.updateProfile(profile)
            assertEquals(listOf(profile.id), db.initialImportDao().pendingIds())
            try {
                db.runInTransaction {
                    db.updateProfile(profile, materialized = true)
                    error("injected before materialization transaction commit")
                }
            } catch (_: IllegalStateException) { }
            assertEquals(listOf(profile.id), db.initialImportDao().pendingIds())
            assertEquals(1, db.updateProfile(profile, materialized = true))
            db.close()
            db = open()
            assertTrue(db.initialImportDao().pendingIds().isEmpty())
            assertEquals("https://example.com/replacement", db.profileDao().get(profile.id)!!.typed.remoteURL)
            // Replay after completion cannot manufacture a fresh download.
            manager.cancelAllWork().result.get()
            UpdateProfileWork.enqueuePendingInitialImports(db.initialImportDao().pendingIds(), manager)
            assertTrue(manager.getWorkInfosForUniqueWork(unique).get().all { it.state.isFinished })
            // Changing the profile away from Remote cancels its explicit intent atomically.
            val converted = Profile(name = "converted", typed = TypedProfile().apply { type = TypedProfile.Type.Remote })
            converted.id = db.insertProfile(converted, true)
            converted.typed.type = TypedProfile.Type.Local
            db.updateProfile(converted)
            assertTrue(db.initialImportDao().pendingIds().isEmpty())
            val deleted = Profile(name = "delete-pending")
            deleted.id = db.insertProfile(deleted, true)
            db.profileDao().delete(deleted)
            assertTrue(db.initialImportDao().pendingIds().isEmpty())
        } finally {
            manager.cancelAllWork().result.get()
            db.close()
            context.deleteDatabase(name)
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    @Test
    fun versionThreeMigrationPreservesProfilesWithoutInferringDownloadIntent() {
        val context = RuntimeEnvironment.getApplication()
        val name = "initial-intent-migration.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, ProfileDatabase::class.java, name).allowMainThreadQueries().build()
        val profile = Profile(name = "existing")
        profile.id = db.insertProfile(profile, false)
        // Version 3 has identical existing tables, but no outbox table.
        db.openHelper.writableDatabase.execSQL("DROP TABLE initial_import_intents")
        db.openHelper.writableDatabase.execSQL("UPDATE room_master_table SET identity_hash = '5e9ed567b06755e1a22c503a7390dea5' WHERE id = 42")
        db.openHelper.writableDatabase.version = 3
        db.close()
        db = Room.databaseBuilder(context, ProfileDatabase::class.java, name)
            .addMigrations(ProfileDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals("existing", db.profileDao().get(profile.id)!!.name)
            assertTrue(db.initialImportDao().pendingIds().isEmpty())
            db.initialImportDao().insert(io.nekohasekai.sfa.database.InitialImportIntent(profile.id))
            db.profileDao().clear()
            assertTrue(db.initialImportDao().pendingIds().isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun persistedAutoUpdateDisabledImportWaitsForNetworkThenRetries() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var attempts = 0
        val attemptedIds = mutableListOf<Long>()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != UpdateProfileWork.UpdateTask::class.java.name) return null
                return object : Worker(appContext, workerParameters) {
                    override fun doWork(): Result {
                        attempts++
                        attemptedIds.add(inputData.getLong("initial_profile_id", -1L))
                        return if (runAttemptCount == 0) Result.retry() else Result.success()
                    }
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        val name = "pending-import-integration.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ProfileDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            val profile = Profile(
                name = "pending",
                typed = TypedProfile().apply {
                    type = TypedProfile.Type.Remote
                    remoteURL = "https://example.com/subscription"
                    autoUpdate = false
                },
            )
            persistImportedProfile(true, true, { error("must not write placeholder") }, { selected ->
                assertFalse(selected)
                profile.id = db.insertProfile(profile, true)
            }, {
                assertNotNull(db.profileDao().get(profile.id))
                assertEquals(listOf(profile.id), db.initialImportDao().pendingIds())
                UpdateProfileWork.enqueueInitialImport(profile.id, manager)
            }, {})
            db.close()
            db = open()
            assertFalse(db.profileDao().get(profile.id)!!.typed.autoUpdate)
            val uniqueName = "UpdateProfileInitial_${profile.id}"
            val request = manager.getWorkInfosForUniqueWork(uniqueName).get().single()
            assertEquals(WorkInfo.State.ENQUEUED, request.state)
            assertEquals(0, attempts)
            // KEEP must retain the same durable request rather than replacing its backoff.
            UpdateProfileWork.enqueueInitialImport(profile.id, manager)
            assertEquals(request.id, manager.getWorkInfosForUniqueWork(uniqueName).get().single().id)
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
            driver.setAllConstraintsMet(request.id)
            assertEquals(1, attempts)
            assertEquals(1, manager.getWorkInfoById(request.id).get()!!.runAttemptCount)
            driver.setAllConstraintsMet(request.id)
            assertEquals(2, attempts)
            assertEquals(listOf(profile.id, profile.id), attemptedIds)
            assertEquals(WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(request.id).get()!!.state)
        } finally {
            manager.cancelAllWork().result.get()
            db.close()
            context.deleteDatabase(name)
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
