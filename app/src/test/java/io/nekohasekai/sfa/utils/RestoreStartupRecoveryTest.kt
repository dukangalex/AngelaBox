package io.nekohasekai.sfa.utils

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import org.junit.Assert.*
import org.junit.Test

/** Host filesystem replay + actual admission gate. Not an Android lifecycle test. */
class RestoreStartupRecoveryTest {
    private val host = object : RestoreRecoveryJournal.Durability {
        override fun isSymbolicLink(file: File) = Files.isSymbolicLink(file.toPath())
        override fun syncDirectory(directory: File) { FileChannel.open(directory.toPath(), READ).use { it.force(true) } }
    }
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("restore-startup-test").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun write(root: File, name: String, text: String) {
        File(root, name).apply { parentFile.mkdirs(); writeText(text) }
    }
    private fun initialize(gate: RestoreAccessGate, root: File) {
        gate.initialize { RestoreRecoveryJournal.open(root, host).use { it.recover() } }
    }
    @Test fun startupWithoutJournalAllowsAccess() = fixture { root ->
        val gate = RestoreAccessGate()
        initialize(gate, root)
        gate.access { assertFalse(File(root, ".restore-recovery").exists()) }
    }
    @Test fun recoveryRestoresBeforeAnyGuardedReaderCanRun() = fixture { root ->
        write(root, "databases/settings.db", "old-settings")
        write(root, "files/configs/a", "old-config")
        RestoreRecoveryJournal.open(root, host).use { it.prepare() }
        write(root, "databases/settings.db", "mixed-settings")
        write(root, "files/configs/a", "mixed-config")
        val gate = RestoreAccessGate()
        initialize(gate, root)
        gate.access {
            assertEquals("old-settings", File(root, "databases/settings.db").readText())
            assertEquals("old-config", File(root, "files/configs/a").readText())
        }
        initialize(RestoreAccessGate(), root) // Replay/cleanup remains idempotent after restart.
    }
    @Test fun corruptJournalPreventsOpeningData() = fixture { root ->
        write(root, "databases/settings.db", "old-settings")
        RestoreRecoveryJournal.open(root, host).use { it.prepare() }
        write(root, "databases/settings.db", "mixed-settings")
        File(root, ".restore-recovery/old/0/data").writeText("corrupt")
        val gate = RestoreAccessGate()
        try { initialize(gate, root); fail() } catch (_: IllegalStateException) {}
        try { gate.access { fail("Reader admitted after failure") }; fail() } catch (_: IllegalStateException) {}
        assertEquals("mixed-settings", File(root, "databases/settings.db").readText())
        assertTrue(File(root, ".restore-recovery").isDirectory)
    }
    @Test fun committedJournalKeepsNewData() = fixture { root ->
        write(root, "databases/settings.db", "old-settings")
        RestoreRecoveryJournal.open(root, host).use {
            it.prepare(); write(root, "databases/settings.db", "new-settings"); it.commit()
        }
        val gate = RestoreAccessGate()
        initialize(gate, root)
        gate.access { assertEquals("new-settings", File(root, "databases/settings.db").readText()) }
    }
}
