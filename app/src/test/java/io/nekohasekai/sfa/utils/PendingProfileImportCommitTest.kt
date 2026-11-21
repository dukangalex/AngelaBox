package io.nekohasekai.sfa.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PendingProfileImportCommitTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private data class ProfileState(var url: String, var deleted: Boolean = false, var content: String? = null)

    @Test
    fun cancelledImportDoesNotCommit() = runBlocking {
        val gate = ProfileMutationGate()
        val current = ProfileState("https://old.example/sub")
        var writes = 0
        val committed = commitPendingProfileImport(
            gate,
            7L,
            { true },
            { current },
            { it.url == "https://old.example/sub" },
        ) {
            writes++
            true
        }
        assertFalse(committed)
        assertEquals(0, writes)
        assertEquals(null, current.content)
    }

    @Test
    fun retryCanCommitAfterTransientFailure() = runBlocking {
        val gate = ProfileMutationGate()
        val current = ProfileState("https://old.example/sub")
        var attempts = 0
        suspend fun attempt(): Boolean = commitPendingProfileImport(
            gate,
            9L,
            { false },
            { current },
            { it.url == "https://old.example/sub" },
        ) {
            attempts++
            if (attempts == 1) {
                false
            } else {
                it.content = "fresh"
                true
            }
        }
        assertFalse(attempt())
        assertTrue(attempt())
        assertEquals(2, attempts)
        assertEquals("fresh", current.content)
    }

    @Test
    fun deleteBeforeCommitPreventsLateFileWrite() = runBlocking {
        val gate = ProfileMutationGate()
        val current = ProfileState("https://old.example/sub")
        val deleteHoldsLock = CompletableDeferred<Unit>()
        val allowDelete = CompletableDeferred<Unit>()
        val deleted = launch {
            gate.withLock(11L) {
                deleteHoldsLock.complete(Unit)
                allowDelete.await()
                current.deleted = true
            }
        }
        deleteHoldsLock.await()
        var writes = 0
        val import = async {
            commitPendingProfileImport(
                gate,
                11L,
                { false },
                { if (current.deleted) null else current },
                { it.url == "https://old.example/sub" },
            ) {
                writes++
                true
            }
        }
        allowDelete.complete(Unit)
        deleted.join()
        assertFalse(import.await())
        assertEquals(0, writes)
        assertTrue(current.deleted)
    }

    @Test
    fun editBeforeCommitRejectsOldUrlResult() = runBlocking {
        val gate = ProfileMutationGate()
        val current = ProfileState("https://old.example/sub")
        gate.withLock(13L) { current.url = "https://new.example/sub" }
        var writes = 0
        val committed = commitPendingProfileImport(
            gate,
            13L,
            { false },
            { current },
            { it.url == "https://old.example/sub" },
        ) {
            writes++
            true
        }
        assertFalse(committed)
        assertEquals(0, writes)
        assertEquals("https://new.example/sub", current.url)
    }

    @Test
    fun editAndDeleteWaitForAlreadyStartedCommitInsteadOfInterleaving() = runBlocking {
        val gate = ProfileMutationGate()
        val current = ProfileState("https://old.example/sub")
        val insideCommit = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val import = launch {
            commitPendingProfileImport(
                gate,
                15L,
                { false },
                { current },
                { it.url == "https://old.example/sub" },
            ) {
                insideCommit.complete(Unit)
                finishCommit.await()
                it.content = "old-url-content"
                events += "commit"
                true
            }
        }
        insideCommit.await()
        val editAndDelete = launch {
            gate.withLock(15L) {
                current.url = "https://new.example/sub"
                events += "edit"
                current.deleted = true
                events += "delete"
            }
        }
        finishCommit.complete(Unit)
        import.join()
        editAndDelete.join()
        assertEquals(listOf("commit", "edit", "delete"), events)
        assertTrue(current.deleted)
    }

    @Test
    fun periodicUpdateQueuedAfterDeleteCannotWrite() = runBlocking {
        val gate = ProfileMutationGate()
        val current = ProfileState("https://old.example/sub")
        val deletionStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()
        val deletion = launch {
            gate.withLock(21L) {
                deletionStarted.complete(Unit)
                allowDeletion.await()
                current.deleted = true
            }
        }
        deletionStarted.await()
        val periodic = async {
            commitPendingProfileImport(gate, 21L, { false }, { if (current.deleted) null else current }, { true }) {
                it.content = "stale"
                true
            }
        }
        allowDeletion.complete(Unit)
        deletion.join()
        assertFalse(periodic.await())
        assertEquals(null, current.content)
    }

    @Test
    fun periodicUpdateQueuedAfterUrlEditCannotWrite() = runBlocking {
        val gate = ProfileMutationGate()
        val current = ProfileState("https://old.example/sub")
        val editing = CompletableDeferred<Unit>()
        val finishEdit = CompletableDeferred<Unit>()
        val edit = launch {
            gate.withLock(22L) {
                editing.complete(Unit)
                finishEdit.await()
                current.url = "https://new.example/sub"
            }
        }
        editing.await()
        val periodic = async {
            commitPendingProfileImport(gate, 22L, { false }, { current }, { it.url == "https://old.example/sub" }) {
                it.content = "stale"
                true
            }
        }
        finishEdit.complete(Unit)
        edit.join()
        assertFalse(periodic.await())
        assertEquals(null, current.content)
    }

    @Test
    fun disabledPeriodicUpdateDoesNotCommit() = runBlocking {
        val gate = ProfileMutationGate()
        var autoUpdate = true
        gate.withLock(23L) { autoUpdate = false }
        var writes = 0
        assertFalse(
            commitPendingProfileImport(gate, 23L, { false }, { autoUpdate }, { it }) {
                writes++
                true
            },
        )
        assertEquals(0, writes)
    }

    @Test
    fun staleDownloadKeepsRealFileUnchanged() = runBlocking {
        val path = temporary.newFile("profile.json")
        path.writeText("previous")
        val row = ProfileState("https://new.example/sub")
        val committed = commitPendingProfileImport(ProfileMutationGate(), 41L, { false }, { row }, { it.url == "https://old.example/sub" }) {
            ProfileFiles.writeAtomically(path, "stale")
            true
        }
        assertFalse(committed)
        assertEquals("previous", path.readText())
        assertFalse(path.resolveSibling("profile.json.tmp").exists())
    }

    @Test
    fun acceptedDownloadReplacesRealFileAndCleansTemporaryFile() = runBlocking {
        val path = temporary.newFile("profile.json")
        path.writeText("previous")
        val row = ProfileState("https://old.example/sub")
        val committed = commitPendingProfileImport(ProfileMutationGate(), 42L, { false }, { row }, { it.url == "https://old.example/sub" }) {
            ProfileFiles.writeAtomically(path, "fresh")
            true
        }
        assertTrue(committed)
        assertEquals("fresh", path.readText())
        assertFalse(path.resolveSibling("profile.json.tmp").exists())
    }

    @Test
    fun failedRenameCleansTemporaryFileAndLeavesDestination() = runBlocking {
        val target = temporary.newFolder("occupied")
        val sentinel = target.resolve("keep.txt")
        sentinel.writeText("keep")
        var thrown = false
        try {
            commitPendingProfileImport(ProfileMutationGate(), 43L, { false }, { ProfileState("ok") }, { true }) {
                ProfileFiles.writeAtomically(target, "replacement")
                true
            }
        } catch (_: IOException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals("keep", sentinel.readText())
        assertFalse(target.resolveSibling("occupied.tmp").exists())
    }

    @Test
    fun remoteEmptyBlobIsPendingButCachedAndLocalConfigsAreNot() {
        assertTrue(isUnmaterializedRemoteConfig(true, ""))
        assertTrue(isUnmaterializedRemoteConfig(true, " { \n } "))
        assertFalse(isUnmaterializedRemoteConfig(true, "{\"outbounds\":[]}"))
        assertFalse(isUnmaterializedRemoteConfig(true, "broken"))
        assertFalse(isUnmaterializedRemoteConfig(false, "{}"))
    }

    @Test
    fun pendingBlobIsPersistedBeforeRetryAndNeverWrittenOrSelected() = runBlocking {
        val events = mutableListOf<String>()
        var durableId = -1L
        persistImportedProfile(
            true,
            true,
            writeConfig = { error("placeholder must not be materialized") },
            create = { select ->
                assertFalse(select)
                durableId = 42L
                events += "create"
            },
            enqueueInitial = {
                assertEquals(42L, durableId)
                events += "retry"
            },
            updateSchedule = { events += "schedule" },
        )
        assertEquals(listOf("create", "retry", "schedule"), events)
    }

    @Test
    fun cachedImportKeepsSelectionAndDoesNotQueueInitialDownload() = runBlocking {
        val events = mutableListOf<String>()
        persistImportedProfile(
            false,
            true,
            writeConfig = { events += "write" },
            create = { select ->
                assertTrue(select)
                events += "create"
            },
            enqueueInitial = { error("ready configuration must not queue initial import") },
            updateSchedule = { events += "schedule" },
        )
        assertEquals(listOf("write", "create", "schedule"), events)
    }

    @Test
    fun failedPersistenceCannotEnqueueUncommittedProfile() = runBlocking {
        var queued = false
        var scheduled = false
        var failed = false
        try {
            persistImportedProfile(
                true,
                false,
                {},
                { throw IOException("database failure") },
                { queued = true },
                { scheduled = true },
            )
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(queued)
        assertFalse(scheduled)
    }
}
