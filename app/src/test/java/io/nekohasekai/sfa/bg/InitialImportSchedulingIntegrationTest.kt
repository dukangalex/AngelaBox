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
import kotlinx.coroutines.launch
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
    fun wrappedCancellationIsNotReclassifiedAsNetworkFailure() = runBlocking {
        val cancelled = kotlinx.coroutines.CancellationException("transport cancelled")
        var failed = false
        var caught: Exception? = null
        try {
            runProfileUpdateAttempt({ false }, { failed = true }) {
                throw IllegalStateException("friendly transport wrapper", cancelled)
            }
        } catch (error: Exception) {
            caught = error
        }
        assertSame(cancelled, caught)
        assertFalse(failed)
    }

    @Test
    fun cancelledCoroutineCannotStartAnotherAttemptEvenWhenWorkerFlagLags() = runBlocking {
        var downloaded = false
        var failed = false
        var cancelled = false
        val child = launch {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            try {
                runProfileUpdateAttempt({ false }, { failed = true }) { downloaded = true }
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
        }
        child.join()
        assertTrue(cancelled)
        assertFalse(downloaded)
        assertFalse(failed)
    }

    @Test
    fun stopDuringSuccessfulBlockingAttemptCannotStartNextProfile() = runBlocking {
        var stopped = false
        val seen = mutableListOf<Int>()
        var cancelled = false
        try {
            for (id in listOf(1, 2)) {
                runProfileUpdateAttempt({ stopped }, { fail("no failure expected") }) {
                    seen.add(id)
                    stopped = true
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(listOf(1), seen)
    }

    @Test
    fun stoppedWarmupReturnsWithoutAccessingApplicationOrDownloading() {
        RuleSetWarmup.warmNow("{}", Long.MAX_VALUE) { false }
    }

    @Test
    fun cancelledAttemptDoesNotRecordFailureOrStartNextProfile() = runBlocking {
        val seen = mutableListOf<Int>()
        val failures = mutableListOf<Exception>()
        val cancelled = kotlinx.coroutines.CancellationException("worker cancelled")
        var caught: Exception? = null
        try {
            for (id in listOf(1, 2)) {
                runProfileUpdateAttempt({ false }, { failures.add(it) }) {
                    seen.add(id)
                    if (id == 1) throw cancelled
                }
            }
        } catch (error: Exception) {
            caught = error
        }
        assertSame(cancelled, caught)
        assertEquals(listOf(1), seen)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun stoppedAttemptDoesNotStartDownload() = runBlocking {
        var downloaded = false
        var failed = false
        var cancelled = false
        try {
            runProfileUpdateAttempt({ true }, { failed = true }) { downloaded = true }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(downloaded)
        assertFalse(failed)
    }

    @Test
    fun stopDuringBlockingFailureDoesNotRecordNetworkError() = runBlocking {
        var stopped = false
        var failed = false
        var cancelled = false
        try {
            runProfileUpdateAttempt({ stopped }, { failed = true }) {
                stopped = true
                throw java.io.IOException("disconnect after work stopped")
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(failed)
    }

    @Test
    fun activeNetworkFailureStillRecordsFailureAndAllowsNextProfile() = runBlocking {
        val seen = mutableListOf<Int>()
        val network = java.io.IOException("temporary failure")
        val failures = mutableListOf<Exception>()
        for (id in listOf(1, 2)) {
            runProfileUpdateAttempt({ false }, { failures.add(it) }) {
                seen.add(id)
                if (id == 1) throw network
            }
        }
        assertEquals(listOf(1, 2), seen)
        assertEquals(listOf(network), failures)
    }

    @Test
    fun wholeDatabaseRestoreHonorsSensitiveGateAndNeverPresetsScriptBindings() {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("restore-sensitive.db")
        context.deleteDatabase(file.name)
        file.parentFile!!.mkdirs()
        fun open() = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        val keys = io.nekohasekai.sfa.constant.SettingsKey
        val scripts = """[{"id":"remote-code","name":"Backup script","code":"{}","enabled":true}]"""
        val bindings = """{"1":["remote-code"]}"""
        val chains = """{"1":{"entryTag":"proxy","landingProfileId":2,"landingTag":"exit"}}"""
        fun seed() {
            open().use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS KeyValueEntity (`key` TEXT NOT NULL PRIMARY KEY, valueType INTEGER NOT NULL, value BLOB NOT NULL)")
                for ((key, value) in mapOf(keys.OVERLAY_SCRIPTS to scripts, keys.OVERLAY_SCRIPT_BINDINGS to bindings, keys.CHAIN_BINDINGS to chains, keys.CHAIN_ENABLED to "legacy", "unrelated_setting" to "keep")) {
                    db.execSQL("INSERT OR REPLACE INTO KeyValueEntity VALUES (?, 4, ?)", arrayOf(key, value.toByteArray()))
                }
            }
        }
        fun read(key: String): String? = open().use { db ->
            db.rawQuery("SELECT value FROM KeyValueEntity WHERE `key`=?", arrayOf(key)).use { c ->
                if (c.moveToFirst()) String(c.getBlob(0)) else null
            }
        }
        try {
            seed()
            val skipped = io.nekohasekai.sfa.utils.BackupManager.sanitizeRestoredSensitiveSettings(file, false)
            assertNull("Default restore must not install backup script code", read(keys.OVERLAY_SCRIPTS))
            assertNull("Default restore must not install backup script bindings", read(keys.OVERLAY_SCRIPT_BINDINGS))
            assertNull("Default restore must not install backup chain bindings", read(keys.CHAIN_BINDINGS))
            assertNull("Legacy chain state must not bypass the gate", read(keys.CHAIN_ENABLED))
            assertEquals("keep", read("unrelated_setting"))
            assertEquals(listOf("Backup script"), skipped.skippedScriptNames)
            assertEquals(1, skipped.skippedScriptBindingCount)
            assertEquals(1, skipped.skippedChainBindingCount)
            seed()
            val optedIn = io.nekohasekai.sfa.utils.BackupManager.sanitizeRestoredSensitiveSettings(file, true)
            assertEquals(scripts, read(keys.OVERLAY_SCRIPTS))
            assertEquals(chains, read(keys.CHAIN_BINDINGS))
            assertNull("Even opted-in scripts must land unbound", read(keys.OVERLAY_SCRIPT_BINDINGS))
            assertEquals(emptyList<String>(), optedIn.skippedScriptNames)
            assertEquals(0, optedIn.skippedChainBindingCount)
            assertEquals(1, optedIn.skippedScriptBindingCount)
        } finally {
            context.deleteDatabase(file.name)
        }
    }

    @Test
    fun nativeRestoreClearsLegacyChainAndMalformedBindingsAndRejectsInvalidDatabase() {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("restore-legacy-sensitive.db")
        context.deleteDatabase(file.name)
        file.parentFile!!.mkdirs()
        val keys = io.nekohasekai.sfa.constant.SettingsKey
        fun open() = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            open().use { db ->
                db.execSQL("CREATE TABLE KeyValueEntity (`key` TEXT NOT NULL PRIMARY KEY, valueType INTEGER NOT NULL, value BLOB NOT NULL)")
                val items = listOf(
                    io.nekohasekai.sfa.database.preference.KeyValueEntity(keys.CHAIN_ENABLED).put(true),
                    io.nekohasekai.sfa.database.preference.KeyValueEntity(keys.CHAIN_BOUND_PROFILE_ID).put(1L),
                    io.nekohasekai.sfa.database.preference.KeyValueEntity(keys.CHAIN_ENTRY_TAG).put("entry"),
                    io.nekohasekai.sfa.database.preference.KeyValueEntity(keys.CHAIN_LANDING_PROFILE_ID).put(2L),
                    io.nekohasekai.sfa.database.preference.KeyValueEntity(keys.CHAIN_LANDING_TAG).put("exit"),
                    io.nekohasekai.sfa.database.preference.KeyValueEntity(keys.OVERLAY_SCRIPT_BINDINGS).put("not-json"),
                    io.nekohasekai.sfa.database.preference.KeyValueEntity(keys.OVERLAY_SCRIPTS).put("not-json"),
                )
                for (item in items) db.execSQL("INSERT INTO KeyValueEntity VALUES (?, ?, ?)", arrayOf(item.key, item.valueType, item.value))
            }
            val outcome = io.nekohasekai.sfa.utils.BackupManager.sanitizeRestoredSensitiveSettings(file, false)
            assertEquals(1, outcome.skippedChainBindingCount)
            open().use { db ->
                db.rawQuery("SELECT COUNT(*) FROM KeyValueEntity", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(0, c.getInt(0))
                }
                db.execSQL("DROP TABLE KeyValueEntity")
            }
            assertTrue(
                "Invalid staged settings must abort, not bypass the gate",
                runCatching {
                    io.nekohasekai.sfa.utils.BackupManager.sanitizeRestoredSensitiveSettings(file, false)
                }.isFailure,
            )
        } finally {
            context.deleteDatabase(file.name)
        }
    }

    @Test
    fun restoredDatabaseDiscardsUnconfirmedInitialImportIntent() {
        val context = RuntimeEnvironment.getApplication()
        val name = "restored-initial-intent.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ProfileDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            val profile = Profile(
                name = "restored",
                typed = TypedProfile().apply {
                    type = TypedProfile.Type.Remote
                    remoteURL = "https://example.com/restored"
                    path = "/backup/configs/restored.json"
                    autoUpdate = true
                },
            )
            profile.id = db.insertProfile(profile, true)
            assertEquals(listOf(profile.id), db.initialImportDao().pendingIds())
            assertEquals(listOf(profile), UpdateProfileWork.selectUpdateProfiles(listOf(profile), profile.id, db.initialImportDao().pendingIds()))
            db.close()
            io.nekohasekai.sfa.utils.BackupManager.pinProfilePathsToConfigs(context.getDatabasePath(name), java.io.File(context.filesDir, "configs"))
            db = open()
            assertFalse(db.profileDao().get(profile.id)!!.typed.autoUpdate)
            assertEquals("https://example.com/restored", db.profileDao().get(profile.id)!!.typed.remoteURL)
            assertTrue("Restore must not replay network intent from the backup", db.initialImportDao().pendingIds().isEmpty())
            // WorkManager may retain a pre-restore request with the same numeric profile ID.
            assertTrue(UpdateProfileWork.selectUpdateProfiles(listOf(db.profileDao().get(profile.id)!!), profile.id, db.initialImportDao().pendingIds()).isEmpty())
            assertTrue(UpdateProfileWork.selectUpdateProfiles(listOf(db.profileDao().get(profile.id)!!), -1L, emptyList()).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

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
