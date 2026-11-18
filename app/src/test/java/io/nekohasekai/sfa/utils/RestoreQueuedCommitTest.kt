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
    @Test fun manualResponseCannotOverwriteSameSourceRestoredWhileItWasInFlight() = runBlocking(Dispatchers.IO) {
        Settings.closeDatabase()
        ProfileManager.closeDatabase()
        val context = RuntimeEnvironment.getApplication()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        try {
            val path = java.io.File(context.filesDir, "configs/manual-inflight.json").absolutePath
            val before = ProfileManager.create(remote().apply { typed.path = path })
            val epoch = RestoreStorageAccess.snapshot()
            // Model a successful response delayed after fetch/validation, before its Room commit.
            BackupRestoreTransaction.run(context) {
                ProfileManager.clearForRestore()
                runBlocking { ProfileManager.create(remote(before.id).apply { typed.path = path; typed.autoUpdate = false }) }
                ProfileBindingStorage.writeAtomically(java.io.File(path), "restored-content")
            }
            val result = ProfileManager.commitRemoteContent(before.id, before.typed.remoteURL, path,
                "stale-response", null, epoch, { false })
            assertNull("pre-restore manual response must not overwrite restored same-source profile", result)
            assertEquals("restored-content", java.io.File(path).readText())
            // A new, explicit post-restore request is still allowed for that same subscription.
            val fresh = ProfileManager.commitRemoteContent(before.id, before.typed.remoteURL, path,
                "fresh-response", null, RestoreStorageAccess.snapshot(), { false })
            assertNotNull(fresh)
            assertEquals("fresh-response", java.io.File(path).readText())
        } finally {
            androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase()
            Settings.closeDatabase()
            ProfileManager.closeDatabase()
        }
    }
}
