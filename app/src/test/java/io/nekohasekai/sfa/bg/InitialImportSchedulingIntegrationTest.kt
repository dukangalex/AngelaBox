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
                profile.id = db.profileDao().insert(profile)
            }, {
                assertNotNull(db.profileDao().get(profile.id))
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
