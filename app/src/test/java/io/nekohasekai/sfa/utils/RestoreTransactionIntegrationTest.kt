package io.nekohasekai.sfa.utils

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/**
 * Host-only composition test for the admission gate and recovery journal.
 * Synthetic files stand in for app data; this is not BackupManager, Room, Android,
 * native-service, filesystem-durability, or APK acceptance evidence.
 */
class RestoreTransactionIntegrationTest {
    private val durability = object : RestoreRecoveryJournal.Durability {
        override fun isSymbolicLink(file: File) = Files.isSymbolicLink(file.toPath())
        override fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), READ).use { it.force(true) }
        }
    }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("restore-transaction-integration").toFile().canonicalFile
        try {
            listOf(
                "databases/settings.db" to "old-settings",
                "databases/profiles.db" to "old-profiles",
                "files/configs/profile.json" to "old-profile",
            ).forEach { (path, content) ->
                File(root, path).apply { parentFile.mkdirs(); writeText(content) }
            }
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun admittedAfterStartup(root: File): RestoreAccessGate = RestoreAccessGate().apply {
        initialize { RestoreRecoveryJournal.open(root, durability).use { it.recover() } }
    }

    private fun replaceData(root: File) {
        File(root, "databases/settings.db").writeText("new-settings")
        File(root, "databases/profiles.db").writeText("new-profiles")
        File(root, "databases/profiles.db-wal").writeText("new-wal")
        File(root, "databases/profiles.db-shm").writeText("new-shm")
        File(root, "databases/profiles.db-journal").writeText("new-rollback-journal")
        File(root, "files/configs/profile.json").writeText("new-profile")
        File(root, "files/configs/extra.json").writeText("new-extra")
    }

    private fun originalData(root: File) = listOf(
        "databases/settings.db" to "old-settings",
        "databases/profiles.db" to "old-profiles",
        "files/configs/profile.json" to "old-profile",
    )

    @Test fun gateRemainsClosedToOtherThreadsAcrossJournalCommit() = fixture { root ->
        val gate = admittedAfterStartup(root)
        val externalAccess = AtomicReference<Throwable?>()
        gate.exclusive(2_000) {
            RestoreRecoveryJournal.open(root, durability).use { journal ->
                journal.prepare()
                replaceData(root)
                val attempted = CountDownLatch(1)
                val reader = thread(name = "restore-integration-reader") {
                    try {
                        gate.access { fail("External reader entered during exclusive replacement") }
                    } catch (error: Throwable) {
                        externalAccess.set(error)
                    } finally {
                        attempted.countDown()
                    }
                }
                assertTrue("Reader did not finish", attempted.await(5, TimeUnit.SECONDS))
                reader.join(5_000)
                assertFalse(reader.isAlive)
                assertTrue(externalAccess.get() is IllegalStateException)
                journal.commit()
                journal.cleanupCommitted()
            }
        }
        assertEquals("new-settings", File(root, "databases/settings.db").readText())
        assertEquals("new-extra", File(root, "files/configs/extra.json").readText())
        assertEquals("admission reopened after committed transaction", "ok", gate.access { "ok" })
    }

    @Test fun failedExclusiveTransactionStaysPoisonedUntilStartupReplay() = fixture { root ->
        val gate = admittedAfterStartup(root)
        try {
            gate.exclusive(2_000) {
                RestoreRecoveryJournal.open(root, durability).use { journal ->
                    journal.prepare()
                    replaceData(root)
                    throw java.io.IOException("injected failure after replacement before commit")
                }
            }
            fail("Injected transaction failure must escape")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.cause is java.io.IOException)
        } catch (expected: java.io.IOException) {
            // The journal's use{} close leaves PREPARED for startup replay.
        }
        try {
            gate.access { fail("Failed transaction reopened ordinary access") }
        } catch (_: IllegalStateException) {
            // Expected fail-closed behavior for this process.
        }

        // A new gate models a process restart: replay must complete before admission opens.
        val restarted = RestoreAccessGate()
        restarted.initialize { RestoreRecoveryJournal.open(root, durability).use { it.recover() } }
        originalData(root).forEach { (path, expected) -> assertEquals(path, expected, File(root, path).readText()) }
        listOf(
            "databases/profiles.db-wal",
            "databases/profiles.db-shm",
            "databases/profiles.db-journal",
            "files/configs/extra.json",
        ).forEach { path -> assertFalse("$path should be absent after replay", File(root, path).exists()) }
        assertEquals("ok", restarted.access { "ok" })
    }
}
