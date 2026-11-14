package io.nekohasekai.sfa.utils

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import org.junit.Assert.*
import org.junit.Test

class RestoreRecoveryJournalTest {
    private val host = object : RestoreRecoveryJournal.Durability {
        override fun isSymbolicLink(file: File) = Files.isSymbolicLink(file.toPath())
        override fun syncDirectory(directory: File) { FileChannel.open(directory.toPath(), READ).use { it.force(true) } }
    }
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("restore-journal-test").toFile().canonicalFile
        try {
            listOf("databases/settings.db", "databases/profiles.db", "files/configs/a", "files/configs/b").forEach {
                val file = File(root, it)
                file.parentFile.mkdirs()
                file.writeText("old-$it")
            }
            block(root)
        } finally { root.deleteRecursively() }
    }
    private fun bytes(root: File) = RestoreRecoveryJournal.targets.associateWith { path ->
        val file = File(root, path)
        if (file.isDirectory) file.walkTopDown().filter { it.isFile }.associate { it.relativeTo(file).path to it.readText() }
        else if (file.isFile) file.readText() else null
    }
    private fun mutate(root: File) {
        File(root, "databases/settings.db").writeText("new-settings")
        File(root, "databases/settings.db-wal").writeText("new-wal")
        File(root, "databases/profiles.db").writeText("new-profiles")
        File(root, "databases/profiles.db-journal").writeText("new-rollback")
        File(root, "files/configs/a").writeText("new-a")
        File(root, "files/configs/b").delete()
        File(root, "files/configs/c").writeText("new-c")
    }
    @Test fun preparedTransactionRestoresBothDatabasesSidecarsAndWholeConfigSet() = fixture { root ->
        File(root, "databases/profiles.db-wal").writeText("old-wal")
        File(root, "databases/profiles.db-shm").writeText("old-shm")
        File(root, "databases/settings.db-journal").writeText("old-rollback")
        val original = bytes(root)
        RestoreRecoveryJournal.open(root, host).use { journal -> journal.prepare(); mutate(root) }
        RestoreRecoveryJournal.open(root, host).use { it.recover() }
        assertEquals(original, bytes(root))
        assertFalse(File(root, ".restore-recovery").exists())
    }
    @Test fun commitPreservesNewState() = fixture { root ->
        RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root); it.commit() }
        val expected = bytes(root)
        RestoreRecoveryJournal.open(root, host).use { it.recover() }
        assertEquals(expected, bytes(root))
    }
    @Test fun absenceOfOriginalConfigsAndDatabasesIsPreservedOnRollback() {
        val root = Files.createTempDirectory("restore-empty").toFile().canonicalFile
        try {
            RestoreRecoveryJournal.open(root, host).use { it.prepare(); File(root, "files/configs").mkdirs(); mutate(root.also { File(it,"databases").mkdirs(); File(it,"files/configs/b").writeText("x") }) }
            RestoreRecoveryJournal.open(root, host).use { it.recover() }
            RestoreRecoveryJournal.targets.forEach { assertFalse(File(root, it).exists()) }
        } finally { root.deleteRecursively() }
    }
    @Test fun refusesAnotherOwnerAndPreparationWithoutRecovery() = fixture { root ->
        RestoreRecoveryJournal.open(root, host).use {
            assertTrue(runCatching { RestoreRecoveryJournal.open(root, host) }.isFailure)
            it.prepare()
            assertTrue(runCatching { it.prepare() }.isFailure)
        }
    }
    @Test fun missingPreimageStopsBeforeAnyLiveMutation() = fixture { root ->
        RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root) }
        File(root, ".restore-recovery/old/${RestoreRecoveryJournal.targets.indexOf("databases/profiles.db")}/present").delete()
        val before = bytes(root)
        RestoreRecoveryJournal.open(root, host).use { assertTrue(runCatching { it.recover() }.isFailure) }
        assertEquals(before, bytes(root))
        assertTrue(File(root, ".restore-recovery/PREPARED").exists())
    }
    @Test fun corruptedPreimageStopsBeforeAnyLiveMutation() = fixture { root ->
        RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root) }
        File(root, ".restore-recovery/old/${RestoreRecoveryJournal.targets.indexOf("databases/profiles.db")}/data").writeText("corrupt")
        val before = bytes(root)
        RestoreRecoveryJournal.open(root, host).use { assertTrue(runCatching { it.recover() }.isFailure) }
        assertEquals(before, bytes(root))
    }
    @Test fun unknownJournalVersionIsRetained() = fixture { root ->
        RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root) }
        File(root, ".restore-recovery/manifest").writeText("future version\n")
        val before = bytes(root)
        RestoreRecoveryJournal.open(root, host).use { assertTrue(runCatching { it.recover() }.isFailure) }
        assertEquals(before, bytes(root))
        assertTrue(File(root, ".restore-recovery/PREPARED").exists())
    }
    @Test fun badCommitMarkerCannotBeTreatedAsSuccess() = fixture { root ->
        RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root) }
        File(root, ".restore-recovery/COMMITTED").writeText("broken")
        val before = bytes(root)
        RestoreRecoveryJournal.open(root, host).use { assertTrue(runCatching { it.recover() }.isFailure) }
        assertEquals(before, bytes(root))
    }
    @Test fun incompletePrepareDoesNotChangeLiveState() = fixture { root ->
        File(root, ".restore-recovery/old/0").mkdirs()
        File(root, ".restore-recovery/old/0/data").writeText("incomplete")
        val before = bytes(root)
        RestoreRecoveryJournal.open(root, host).use { it.recover() }
        assertEquals(before, bytes(root))
        assertFalse(File(root, ".restore-recovery").exists())
    }
    @Test fun symlinkInLateTargetRejectedBeforeAnyRollback() = fixture { root ->
        val outside = Files.createTempFile("outside-restore", ".txt").toFile()
        outside.writeText("outside")
        try {
            RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root) }
            Files.createSymbolicLink(File(root, "files/configs/link").toPath(), outside.toPath())
            RestoreRecoveryJournal.open(root, host).use { assertTrue(runCatching { it.recover() }.isFailure) }
            assertEquals("new-settings", File(root, "databases/settings.db").readText())
            assertEquals("outside", outside.readText())
            assertTrue(File(root, ".restore-recovery/PREPARED").exists())
        } finally { outside.delete() }
    }
    @Test fun syncFailureBeforePreparedDoesNotAuthorizeMutation() = fixture { root ->
        val before = bytes(root)
        val fails = object : RestoreRecoveryJournal.Durability {
            override fun isSymbolicLink(file: File) = host.isSymbolicLink(file)
            override fun syncDirectory(directory: File) { throw java.io.IOException("injected fsync") }
        }
        RestoreRecoveryJournal.open(root, fails).use { assertTrue(runCatching { it.prepare() }.isFailure) }
        assertFalse(File(root, ".restore-recovery/PREPARED").exists())
        RestoreRecoveryJournal.open(root, host).use { it.recover() }
        assertEquals(before, bytes(root))
    }
    @Test fun interruptedRollbackCanReplayEveryTarget() {
        RestoreRecoveryJournal.targets.indices.forEach { failureIndex -> fixture { root ->
            val original = bytes(root)
            RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root) }
            RestoreRecoveryJournal.open(root, host).use { journal ->
                assertTrue(runCatching { journal.recover { index ->
                    if (index == failureIndex) throw java.io.IOException("injected rollback interruption")
                } }.isFailure)
            }
            RestoreRecoveryJournal.open(root, host).use { it.recover() }
            assertEquals("rollback target $failureIndex", original, bytes(root))
        } }
    }
    @Test fun cleanupFailureAfterCommitRetainsCommittedState() = fixture { root ->
        RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root); it.commit() }
        val expected = bytes(root)
        val fails = object : RestoreRecoveryJournal.Durability {
            override fun isSymbolicLink(file: File) = host.isSymbolicLink(file)
            override fun syncDirectory(directory: File) { throw java.io.IOException("cleanup fsync") }
        }
        RestoreRecoveryJournal.open(root, fails).use { assertTrue(runCatching { it.cleanupCommitted() }.isFailure) }
        RestoreRecoveryJournal.open(root, host).use { it.recover() }
        assertEquals(expected, bytes(root))
    }
    @Test fun newParentEntriesSyncedBeforeCommitMarker() {
        val root = Files.createTempDirectory("restore-parent-sync").toFile().canonicalFile
        try {
            val calls = mutableListOf<String>()
            val trace = object : RestoreRecoveryJournal.Durability {
                override fun isSymbolicLink(file: File) = host.isSymbolicLink(file)
                override fun syncDirectory(directory: File) {
                    if (File(root, ".restore-recovery/COMMITTED").exists()) calls.add("marker")
                    else calls.add(directory.relativeTo(root).path.ifEmpty { "ROOT" })
                    host.syncDirectory(directory)
                }
            }
            RestoreRecoveryJournal.open(root, trace).use {
                it.prepare(); calls.clear()
                File(root, "databases").mkdirs(); File(root, "files/configs").mkdirs(); mutate(root)
                it.commit()
            }
            assertTrue(calls.indexOf("ROOT") >= 0)
            assertTrue(calls.indexOf("ROOT") < calls.indexOf("marker"))
            RestoreRecoveryJournal.open(root, host).use { it.recover() }
        } finally { root.deleteRecursively() }
    }
    @Test fun reopenedSessionCannotCommitInterruptedMutation() = fixture { root ->
        val original = bytes(root)
        RestoreRecoveryJournal.open(root, host).use { it.prepare(); mutate(root) }
        RestoreRecoveryJournal.open(root, host).use {
            assertTrue(runCatching { it.commit() }.isFailure)
            it.recover()
        }
        assertEquals(original, bytes(root))
    }
    @Test fun closesAndRejectsReuse() = fixture { root ->
        val journal = RestoreRecoveryJournal.open(root, host)
        journal.close(); journal.close()
        assertTrue(runCatching { journal.prepare() }.isFailure)
        RestoreRecoveryJournal.open(root, host).use { it.recover() }
    }
}
