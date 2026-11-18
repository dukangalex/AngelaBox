package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.*
import io.nekohasekai.sfa.bg.UpdateProfileWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Production Room commit revalidation after a restore reuses the same source identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [RestoreDirectoryOsShadow::class])
class RestoreQueuedCommitTest {
    private fun remote(id: Long = 0) = Profile(id = id, name = "queued", typed = TypedProfile().apply {
        type = TypedProfile.Type.Remote
        remoteURL = "https://example.invalid/not-requested"
        path = "queued-restore-test.json"
        autoUpdate = true
    })

    private fun exercise(initial: Boolean) = runBlocking(Dispatchers.IO) {
        Settings.closeDatabase()
        ProfileManager.closeDatabase()
        val context = RuntimeEnvironment.getApplication()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        try {
            val before = ProfileManager.create(remote(), pendingInitialImport = initial)
            // The source matches exactly, so row/URL/path checks alone cannot reject stale work.
            BackupRestoreTransaction.run(context) {
                ProfileManager.clearForRestore()
                runBlocking { ProfileManager.create(remote(before.id).apply { typed.autoUpdate = false }) }
            }
            assertTrue(ProfileManager.pendingInitialImportIds().isEmpty())
            var committed = false
            val result = ProfileManager.commitPendingImport(before, { false },
                requireAutoUpdate = !initial, requireInitialIntent = initial) {
                committed = true
                true
            }
            assertFalse("pre-restore worker must revalidate durable intent/auto-update", result)
            assertFalse("stale queued work reached its file-writing closure", committed)
            val restored = ProfileManager.get(before.id)!!
            assertEquals(before.typed.remoteURL, restored.typed.remoteURL)
            assertEquals(before.typed.path, restored.typed.path)
            assertFalse(restored.typed.autoUpdate)
            assertTrue(UpdateProfileWork.selectUpdateProfiles(listOf(restored), if (initial) before.id else -1L,
                ProfileManager.pendingInitialImportIds()).isEmpty())
        } finally {
            androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase()
            Settings.closeDatabase()
            ProfileManager.closeDatabase()
        }
    }

    @Test fun queuedInitialImportCannotCommitAfterIntentIsRemovedByRestore() = exercise(true)
    @Test fun queuedPeriodicImportCannotCommitAfterAutoUpdateIsDisabledByRestore() = exercise(false)
}
