package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreJournalTest {
    private class Killed : Error("simulated process death")
    private class Fixture {
        val root = Files.createTempDirectory("restore-journal-").toFile().canonicalFile
        val settings = File(root, "settings.db").apply { writeText("old-settings") }
        val profiles = File(root, "profiles.db").apply { writeText("old-profiles") }
        val configs = File(root, "configs").apply {
            mkdir()
            File(this, "old.json").writeText("old-config")
        }
        val wal = File(root, "profiles.db-wal") // Absent before restore.
        val journalDir = File(root, "journal")
        val targets = linkedMapOf("settings" to settings, "profiles" to profiles, "configs" to configs, "wal" to wal)
        fun journal(cut: (String) -> Unit = {}) = RestoreJournal(journalDir, targets, {}, cut)
        fun change() {
            settings.writeText("new-settings")
            profiles.writeText("new-profiles")
            configs.deleteRecursively()
            configs.mkdir()
            File(configs, "new.json").writeText("new-config")
            wal.writeText("new-wal")
        }
        fun assertOld() {
            assertEquals("old-settings", settings.readText())
            assertEquals("old-profiles", profiles.readText())
            assertEquals(listOf("old.json"), configs.listFiles()!!.map { it.name })
            assertEquals("old-config", File(configs, "old.json").readText())
            assertFalse(wal.exists())
        }
        fun close() {
            root.deleteRecursively()
        }
    }

    @Test fun copyFailureBeforeReadyLeavesOldDatasetUntouched() {
        val f = Fixture()
        try {
            val j = f.journal { if (it == "snapshot_profiles") throw java.io.IOException("no space") }
            assertTrue(runCatching { j.begin() }.isFailure)
            f.assertOld()
            assertFalse(f.journal().recover())
            f.assertOld()
        } finally {
            f.close()
        }
    }

    @Test fun processDeathAtEveryApplyBoundaryRestoresAllRoots() {
        for (boundary in 0..4) {
            val f = Fixture()
            try {
                f.journal().begin()
                if (boundary >= 1) f.settings.writeText("new-settings")
                if (boundary >= 2) f.profiles.writeText("new-profiles")
                if (boundary >= 3) f.configs.deleteRecursively()
                if (boundary >= 4) f.wal.writeText("new-wal")
                assertTrue(f.journal().recover())
                f.assertOld()
                assertFalse(f.journal().recover())
            } finally {
                f.close()
            }
        }
    }

    @Test fun processDeathDuringEveryRollbackBoundaryCanBeRecoveredAgain() {
        val cuts = listOf("configs", "profiles", "settings", "wal").flatMap { name ->
            listOf("prepared_$name", "displaced_$name", "recovered_$name")
        } + "rolled_back"
        for (stage in cuts) {
            val f = Fixture()
            try {
                f.journal().begin()
                f.change()
                var hit = false
                try {
                    f.journal {
                        if (it == stage) {
                            hit = true
                            throw Killed()
                        }
                    }.recover()
                } catch (_: Killed) {}
                assertTrue("missing cut $stage", hit)
                assertTrue(f.journal().needsRecovery())
                assertTrue(f.journal().recover())
                f.assertOld()
            } finally {
                f.close()
            }
        }
    }

    @Test fun committedDatasetIsNotRolledBackAfterDeathOrCleanup() {
        val f = Fixture()
        try {
            val j = f.journal { if (it == "committed") throw Killed() }
            j.begin()
            f.change()
            try {
                j.commit()
                fail()
            } catch (_: Killed) {}
            assertTrue(f.journal().needsRecovery())
            assertFalse(f.journal().recover())
            assertFalse(f.journal().needsRecovery())
            assertEquals("new-settings", f.settings.readText())
            assertEquals("new-profiles", f.profiles.readText())
            assertTrue(File(f.configs, "new.json").isFile)
            assertEquals("new-wal", f.wal.readText())
        } finally {
            f.close()
        }
    }

    @Test fun committedRenameSyncFailureRemainsFencedUntilBarrierSucceeds() {
        val f = Fixture()
        try {
            var fail = true
            val journal = RestoreJournal(f.journalDir, f.targets, { dir ->
                if (fail && dir == f.journalDir && File(dir, "COMMITTED").exists()) {
                    throw java.io.IOException("injected directory sync failure")
                }
            })
            journal.begin()
            f.change()
            assertTrue(runCatching { journal.commit() }.isFailure)
            assertTrue(journal.needsRecovery())
            assertTrue(runCatching { journal.recover() }.isFailure)
            assertTrue(journal.needsRecovery())
            assertTrue(File(f.journalDir, "originals").isDirectory)
            assertEquals("new-settings", f.settings.readText())
            fail = false
            assertFalse(journal.recover())
            assertFalse(journal.needsRecovery())
            assertEquals("new-settings", f.settings.readText())
        } finally {
            f.close()
        }
    }

    @Test fun cleanupRenameSyncFailureStaysFencedUntilParentSync() {
        val f = Fixture()
        try {
            var fail = false
            val journal = RestoreJournal(f.journalDir, f.targets, { dir ->
                if (fail && dir == f.root && dir.listFiles()!!.any { it.name.startsWith("journal-finished-") }) {
                    throw java.io.IOException("injected parent sync failure")
                }
            })
            journal.begin()
            f.change()
            fail = true
            assertTrue(runCatching { journal.commit() }.isFailure)
            assertTrue(journal.needsRecovery())
            assertTrue(runCatching { journal.recover() }.isFailure)
            assertTrue(journal.needsRecovery())
            assertEquals("new-settings", f.settings.readText())
            fail = false
            assertFalse(journal.recover())
            assertFalse(journal.needsRecovery())
            assertEquals("new-settings", f.settings.readText())
        } finally {
            f.close()
        }
    }

    @Test fun preCommitDeathRollsBackEvenAfterSuccessfulApply() {
        val f = Fixture()
        try {
            val j = f.journal { if (it == "before_commit") throw Killed() }
            j.begin()
            f.change()
            try {
                j.commit()
                fail()
            } catch (_: Killed) {}
            assertTrue(f.journal().recover())
            f.assertOld()
        } finally {
            f.close()
        }
    }

    @Test fun corruptedSnapshotBlocksRecoveryBeforeAnyLiveRootChanges() {
        val f = Fixture()
        try {
            f.journal().begin()
            f.change()
            File(f.journalDir, "originals/settings").writeText("corrupt")
            assertTrue(runCatching { f.journal().recover() }.isFailure)
            assertTrue(f.journal().needsRecovery())
            assertEquals("new-settings", f.settings.readText())
            assertEquals("new-profiles", f.profiles.readText())
            assertTrue(File(f.configs, "new.json").isFile)
        } finally {
            f.close()
        }
    }

    @Test fun corruptIndexAndMarkersAreNotTreatedAsSuccessfulRecovery() {
        val f = Fixture()
        try {
            f.journal().begin()
            f.change()
            File(f.journalDir, "index").writeBytes(byteArrayOf(1))
            assertTrue(runCatching { f.journal().recover() }.isFailure)
            assertTrue(f.journal().needsRecovery())
            assertEquals("new-profiles", f.profiles.readText())
        } finally {
            f.close()
        }
    }

    @Test fun symlinkSnapshotCannotRedirectRollbackOutsideTargets() {
        val f = Fixture()
        try {
            val outside = File(f.root, "outside").apply { writeText("keep") }
            Files.createSymbolicLink(File(f.configs, "link").toPath(), outside.toPath())
            assertTrue(runCatching { f.journal().begin() }.isFailure)
            assertEquals("keep", outside.readText())
            assertFalse(f.journal().needsRecovery())
        } finally {
            f.close()
        }
    }

    @Test fun redirectedRootIsRejectedButCanonicalParentAliasIsAccepted() {
        val f = Fixture()
        try {
            val alias = File(f.root.parentFile, f.root.name + "-alias")
            Files.createSymbolicLink(alias.toPath(), f.root.toPath())
            try {
                RestoreJournal(File(f.root, "alias-journal"), mapOf("settings" to File(alias, "settings.db")), {}).begin()
                val redirect = File(f.root, "redirect")
                Files.createSymbolicLink(redirect.toPath(), f.configs.toPath())
                assertTrue(runCatching { RestoreJournal(File(f.root, "other-journal"), mapOf("configs" to redirect), {}) }.isFailure)
            } finally {
                Files.deleteIfExists(alias.toPath())
            }
        } finally {
            f.close()
        }
    }
}
