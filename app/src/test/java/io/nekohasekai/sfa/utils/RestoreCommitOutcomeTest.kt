package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RestoreCommitOutcomeTest {
    @Test fun transientPostMarkerFailureRecoversCommitAndRunsEffects() {
        val root = Files.createTempDirectory("restore-outcome").toFile()
        val live = File(root, "live").apply { writeText("old") }
        val dir = File(root, "journal")
        var failOnce = true
        val journal = RestoreJournal(dir, mapOf("live" to live), { d ->
            if (d == dir && File(dir, "COMMITTED").exists() && failOnce) {
                failOnce = false
                throw IOException("injected marker barrier failure")
            }
        })
        var effects = 0
        try {
            RestoreStorageAccess.configure(journal::needsRecovery)
            val result = RestoreStorageAccess.exclusive {
                BackupRestoreTransaction.runJournaled(journal, {}) {
                    live.writeText("new")
                    RestoreStorageAccess.afterCommit("effect") { effects++ }
                    "done"
                }
            }
            assertEquals("done", result)
            assertEquals("new", live.readText())
            assertEquals(1, effects)
            assertFalse(journal.needsRecovery())
        } finally {
            RestoreStorageAccess.configure { false }
            root.deleteRecursively()
        }
    }

    @Test fun persistentPostMarkerFailureKeepsFenceAndReportsUncertain() {
        val root = Files.createTempDirectory("restore-outcome").toFile()
        val live = File(root, "live").apply { writeText("old") }
        val dir = File(root, "journal")
        var fail = true
        val journal = RestoreJournal(dir, mapOf("live" to live), { d ->
            if (d == dir && File(dir, "COMMITTED").exists() && fail) throw IOException("persistent failure")
        })
        var effects = 0
        try {
            RestoreStorageAccess.configure(journal::needsRecovery)
            val failure = runCatching {
                RestoreStorageAccess.exclusive {
                    BackupRestoreTransaction.runJournaled(journal, {}) {
                        live.writeText("new")
                        RestoreStorageAccess.afterCommit("effect") { effects++ }
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("可能已经提交"))
            assertEquals("new", live.readText())
            assertTrue(journal.needsRecovery())
            assertEquals(0, effects)
            assertTrue(runCatching { RestoreStorageAccess.access { } }.isFailure)
            fail = false
            assertFalse(journal.recover())
            assertFalse(journal.needsRecovery())
            assertEquals("new", live.readText())
        } finally {
            RestoreStorageAccess.configure { false }
            root.deleteRecursively()
        }
    }

    @Test fun preCommitFailureRollsBackAndDoesNotRunEffects() {
        val root = Files.createTempDirectory("restore-outcome").toFile()
        val live = File(root, "live").apply { writeText("old") }
        val dir = File(root, "journal")
        val journal = RestoreJournal(dir, mapOf("live" to live), {}, { point ->
            if (point == "before_commit") throw IOException("pre-commit failure")
        })
        var effects = 0
        try {
            RestoreStorageAccess.configure(journal::needsRecovery)
            val failure = runCatching {
                RestoreStorageAccess.exclusive {
                    BackupRestoreTransaction.runJournaled(journal, {}) {
                        live.writeText("new")
                        RestoreStorageAccess.afterCommit("effect") { effects++ }
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("old", live.readText())
            assertEquals(0, effects)
            assertFalse(journal.needsRecovery())
        } finally {
            RestoreStorageAccess.configure { false }
            root.deleteRecursively()
        }
    }

    @Test fun beginMarkerSyncFailureDoesNotRunActionAndFencesUntilRecovery() {
        val root = Files.createTempDirectory("restore-begin").toFile().canonicalFile
        val live = File(root, "live").apply { writeText("old") }
        val dir = File(root, "journal")
        var fail = true
        val journal = RestoreJournal(dir, mapOf("live" to live), { d ->
            if (fail && d == dir && File(dir, "READY").exists()) throw IOException("ready sync")
        })
        var invoked = false
        try {
            RestoreStorageAccess.configure(journal::needsRecovery)
            assertTrue(
                runCatching {
                    RestoreStorageAccess.exclusive {
                        BackupRestoreTransaction.runJournaled(journal, {}) {
                            invoked = true
                            live.writeText("new")
                        }
                    }
                }.isFailure,
            )
            assertFalse(invoked)
            assertEquals("old", live.readText())
            assertTrue(journal.needsRecovery())
            assertTrue(runCatching { RestoreStorageAccess.access {} }.isFailure)
            fail = false
            assertTrue(journal.recover())
            assertEquals("old", live.readText())
            assertFalse(journal.needsRecovery())
        } finally {
            RestoreStorageAccess.configure { false }
            root.deleteRecursively()
        }
    }

    @Test fun cleanupParentSyncFailureRecoversCommittedResultAndEffects() {
        val root = Files.createTempDirectory("restore-cleanup").toFile().canonicalFile
        val live = File(root, "live").apply { writeText("old") }
        val dir = File(root, "journal")
        var failOnce = true
        val journal = RestoreJournal(dir, mapOf("live" to live), { d ->
            if (d == root && root.listFiles()!!.any { it.name.startsWith("journal-finished-") } && failOnce) {
                failOnce = false
                throw IOException("cleanup parent sync")
            }
        })
        var effects = 0
        try {
            RestoreStorageAccess.configure(journal::needsRecovery)
            val result = RestoreStorageAccess.exclusive {
                BackupRestoreTransaction.runJournaled(journal, {}) {
                    live.writeText("new")
                    RestoreStorageAccess.afterCommit("effect") { effects++ }
                    "done"
                }
            }
            assertEquals("done", result)
            assertEquals("new", live.readText())
            assertEquals(1, effects)
            assertFalse(journal.needsRecovery())
        } finally {
            RestoreStorageAccess.configure { false }
            root.deleteRecursively()
        }
    }
}
