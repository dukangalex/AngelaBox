package io.nekohasekai.sfa.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualProfileCommitTest {
    private data class Row(var source: RemoteProfileSource, var name: String = "before", var file: String = "initial")
    private val old = RemoteProfileSource("https://old.example/sub", "/profile/one")

    @Test
    fun deleteBeforeManualDownloadCommitRejectsFileWrite() = runBlocking {
        val gate = ProfileMutationGate()
        var row: Row? = Row(old)
        gate.withLock(1) { row = null }
        var wrote = false
        val result = commitPendingProfileImport(gate, 1, { false }, { row }, { matchesRemoteProfileSource(true, it.source, old) }) {
            wrote = true
            true
        }
        assertFalse(result)
        assertFalse(wrote)
    }

    @Test
    fun urlOrPathChangeRejectsOldManualDownload() = runBlocking {
        for (change in listOf(old.copy(url = "https://new.example/sub"), old.copy(path = "/profile/two"))) {
            val gate = ProfileMutationGate()
            val row = Row(old)
            gate.withLock(2) { row.source = change }
            val result = commitPendingProfileImport(gate, 2, { false }, { row }, { matchesRemoteProfileSource(true, it.source, old) }) {
                it.file = "old download"
                true
            }
            assertFalse(result)
            assertEquals("initial", row.file)
        }
        assertFalse(matchesRemoteProfileSource(false, old, old))
    }

    @Test
    fun unrelatedEditsArePreservedByCommittingFreshRow() = runBlocking {
        val gate = ProfileMutationGate()
        val row = Row(old)
        gate.withLock(3) { row.name = "new name" }
        val result = commitPendingProfileImport(gate, 3, { false }, { row }, { matchesRemoteProfileSource(true, it.source, old) }) {
            it.file = "updated"
            true
        }
        assertTrue(result)
        assertEquals("new name", row.name)
        assertEquals("updated", row.file)
    }

    @Test
    fun deleteWaitsForManualCommitAndCannotInterleave() = runBlocking {
        val gate = ProfileMutationGate()
        var row: Row? = Row(old)
        val entered = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val manual = async {
            commitPendingProfileImport(gate, 4, { false }, { row }, { matchesRemoteProfileSource(true, it.source, old) }) {
                entered.complete(Unit)
                allowCommit.await()
                it.file = "updated"
                events += "commit"
                true
            }
        }
        entered.await()
        val deletion = launch {
            gate.withLock(4) {
                row = null
                events += "delete"
            }
        }
        allowCommit.complete(Unit)
        assertTrue(manual.await())
        deletion.join()
        assertEquals(listOf("commit", "delete"), events)
    }

    @Test
    fun cancelledManualDownloadDoesNotCommit() = runBlocking {
        val row = Row(old)
        val result = commitPendingProfileImport(ProfileMutationGate(), 5, { true }, { row }, { true }) {
            it.file = "updated"
            true
        }
        assertFalse(result)
        assertEquals("initial", row.file)
    }

    @Test
    fun localSnapshotRejectsChangedOrMissingFile() {
        val dir = kotlin.io.path.createTempDirectory("profile-snapshot").toFile()
        try {
            val file = java.io.File(dir, "profile.json")
            file.writeText("updated by subscription")
            assertFalse(ProfileFiles.writeIfUnchanged(file, "old snapshot", "editor content"))
            assertEquals("updated by subscription", file.readText())
            file.delete()
            assertFalse(ProfileFiles.writeIfUnchanged(file, "old snapshot", "editor content"))
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun localSnapshotCommitsAtomicallyWhenUnchanged() {
        val dir = kotlin.io.path.createTempDirectory("profile-snapshot").toFile()
        try {
            val file = java.io.File(dir, "profile.json")
            file.writeText("old")
            assertTrue(ProfileFiles.writeIfUnchanged(file, "old", "new"))
            assertEquals("new", file.readText())
            assertFalse(java.io.File(dir, "profile.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
