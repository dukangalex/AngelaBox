package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [RestoreDirectoryOsShadow::class])
class RestoreSideEffectTest {
    @org.junit.Before fun resetCachedRoomHandles() {
        Settings.closeDatabase()
        ProfileManager.closeDatabase()
    }

    @Test fun rollbackDoesNotPublishProvisionalProfiles() = runBlocking(Dispatchers.IO) {
        val seen = mutableListOf<String>()
        val callback: () -> Unit = { seen += Settings.themeMode }
        ProfileManager.registerCallback(callback)
        try {
            Settings.themeMode = "before"
            try {
                BackupRestoreTransaction.run(RuntimeEnvironment.getApplication()) {
                    Settings.themeMode = "provisional"
                    runBlocking { ProfileManager.create(Profile(name = "uncommitted")) }
                    throw IOException("abort")
                }
            } catch (_: IOException) {}
            assertEquals("before", Settings.themeMode)
            assertTrue("provisional profile callbacks escaped rollback: $seen", seen.isEmpty())
        } finally {
            ProfileManager.unregisterCallback(callback)
        }
    }

    @Test fun rollbackDoesNotPublishProvisionalSettings() = runBlocking(Dispatchers.IO) {
        val seen = mutableListOf<String>()
        Settings.themeMode = "before"
        val listener = object : io.nekohasekai.sfa.database.preference.OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: androidx.preference.PreferenceDataStore, key: String) {
                if (key == io.nekohasekai.sfa.constant.SettingsKey.THEME_MODE) seen += Settings.themeMode
            }
        }
        Settings.dataStore.registerChangeListener(listener)
        try {
            try {
                BackupRestoreTransaction.run(RuntimeEnvironment.getApplication()) {
                    Settings.themeMode = "provisional"
                    throw IOException("abort")
                }
            } catch (_: IOException) {}
            assertTrue(seen.isEmpty())
            assertEquals("before", Settings.themeMode)
        } finally {
            Settings.dataStore.unregisterChangeListener(listener)
        }
    }

    @Test fun successfulCommitCoalescesEffectsAndRunsOutsideBarrier() {
        val seen = mutableListOf<String>()
        RestoreStorageAccess.exclusive {
            RestoreStorageAccess.afterCommit("key") { seen += "discard" }
            RestoreStorageAccess.afterCommit("key") {
                val reader = Thread { RestoreStorageAccess.access { seen += "committed" } }
                reader.start()
                reader.join(3000)
                assertFalse(reader.isAlive)
            }
            assertTrue(seen.isEmpty())
        }
        assertEquals(listOf("committed"), seen)
    }

    @Test fun thrownObserverCannotMakeCommittedRestoreFailOrSkipOtherEffects() {
        var last = false
        val result = RestoreStorageAccess.exclusive {
            RestoreStorageAccess.afterCommit("bad") { error("observer failed") }
            RestoreStorageAccess.afterCommit("good") { last = true }
            "committed"
        }
        assertEquals("committed", result)
        assertTrue(last)
    }

    @Test fun processDeathDiscardsProvisionalEffects() {
        var called = false
        try {
            RestoreStorageAccess.exclusive {
                RestoreStorageAccess.afterCommit("key") { called = true }
                throw AssertionError("simulated death")
            }
        } catch (_: AssertionError) {}
        RestoreStorageAccess.exclusive { }
        assertFalse(called)
    }

    @Test fun failedRestoreKeepsWorkManagerRequestButCommittedRestoreCancelsIt() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
        val wm = androidx.work.WorkManager.getInstance(context)
        try {
            val profile = ProfileManager.create(Profile(name = "pending"), pendingInitialImport = true)
            io.nekohasekai.sfa.bg.UpdateProfileWork.enqueueInitialImport(profile.id)
            val name = "UpdateProfileInitial_${profile.id}"
            val before = wm.getWorkInfosForUniqueWork(name).get().single()
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, before.state)
            try {
                BackupRestoreTransaction.run(context) {
                    ProfileManager.clearForRestore()
                    assertEquals(androidx.work.WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(name).get().single().state)
                    throw IOException("abort")
                }
            } catch (_: IOException) {}
            assertEquals(listOf(profile.id), ProfileManager.pendingInitialImportIds())
            assertEquals(before.id, wm.getWorkInfosForUniqueWork(name).get().single().id)
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(name).get().single().state)
            BackupRestoreTransaction.run(context) { ProfileManager.clearForRestore() }
            assertEquals(androidx.work.WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(name).get().single().state)
        } finally {
            androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    @Test fun restoreCannotCrossASuspendedProductionProfileCommit() = runBlocking(Dispatchers.IO) {
        val current = ProfileManager.create(
            Profile(
                name = "remote",
                typed = TypedProfile().apply {
                    type = TypedProfile.Type.Remote
                    remoteURL = "https://example.com/config"
                    path = "lease-test.json"
                },
            ),
        )
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writer = async(Dispatchers.Default) {
            ProfileManager.commitPendingImport(current, { false }) {
                entered.complete(Unit)
                release.await()
                true
            }
        }
        entered.await()
        var mutated = false
        var denied = false
        try {
            try {
                BackupRestoreTransaction.run(RuntimeEnvironment.getApplication()) { mutated = true }
            } catch (_: IllegalStateException) {
                denied = true
            }
        } finally {
            release.complete(Unit)
        }
        assertTrue(writer.await())
        assertTrue("restore must reject while an admitted profile commit is suspended", denied)
        assertFalse(mutated)
        BackupRestoreTransaction.run(RuntimeEnvironment.getApplication()) { mutated = true }
        assertTrue(mutated)
    }

    @Test fun workerRetriesWhenRestoreRejectsItsInitialRead() {
        val context = RuntimeEnvironment.getApplication()
        val worker = androidx.work.testing.TestListenableWorkerBuilder<io.nekohasekai.sfa.bg.UpdateProfileWork.UpdateTask>(context).build()
        val result = RestoreStorageAccess.exclusive {
            runBlocking {
                async(Dispatchers.IO) { worker.doWork() }.await()
            }
        }
        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
    }

    @Test fun restoreContentionIsNotRecordedAsPermanentSubscriptionFailure() = runBlocking {
        var recorded = false
        val busy = RestoreStorageUnavailable("busy")
        var caught: Throwable? = null
        try {
            io.nekohasekai.sfa.bg.runProfileUpdateAttempt({ false }, { recorded = true }) {
                throw IllegalStateException("transport wrapper", busy)
            }
        } catch (e: RestoreStorageUnavailable) {
            caught = e
        }
        assertSame(busy, caught)
        assertFalse(recorded)
    }
}
