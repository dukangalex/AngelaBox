package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [RestoreDirectoryOsShadow::class])
class BackupRestoreCopyFaultRegressionTest {
    @Test fun unacknowledgedRuntimeCannotChangeLiveFiles() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val live = File(context.filesDir, "configs").apply { mkdirs() }
        val old = File(live, "old-profile.json").apply { writeText("original") }
        val incoming = File(live, "incoming.json")
        val backup = File(context.cacheDir, "restore-runtime-stop-fault.zip")
        ZipOutputStream(backup.outputStream()).use {
            it.putNextEntry(ZipEntry("configs/incoming.json"))
            it.write("backup-content".toByteArray())
            it.closeEntry()
        }
        val gate = io.nekohasekai.sfa.bg.BoxService.restoreGate
        val key = Any()
        gate.start(key, gate.startTicket()) { error("injected native stop failure") }!!.close()
        try {
            val result = BackupManager.restoreBackupFile(context, backup, compat = false)
            assertTrue("unacknowledged stop must fail restore: $result", result.isFailure)
            assertEquals("injected native stop failure", result.exceptionOrNull()?.message)
            assertEquals("original", old.readText())
            assertFalse(incoming.exists())
        } finally {
            gate.stopped(key)
        }
    }

    @Test fun copyFailureMustNotBeReportedAsSuccessfulRestore() {
        val context = RuntimeEnvironment.getApplication()
        val live = File(context.filesDir, "configs").apply { mkdirs() }
        val old = File(live, "old-profile.json").apply { writeText("original") }
        val blocking = File(live, "incoming.json").apply { mkdirs() }
        File(blocking, "block-delete").writeText("occupied")
        val backup = File(context.cacheDir, "restore-copy-fault.zip")
        ZipOutputStream(backup.outputStream()).use {
            it.putNextEntry(ZipEntry("configs/incoming.json"))
            it.write("backup-content".toByteArray())
            it.closeEntry()
        }
        val result = BackupManager.restoreBackupFile(context, backup, compat = false)
        println("FAULT_TEST resultSuccess=${result.isSuccess} exception=${result.exceptionOrNull()} oldExists=${old.exists()} newIsFile=${File(live, "incoming.json").isFile}")
        assertTrue("copy failure should fail restore", result.isFailure)
        assertTrue(
            "test must reach the injected file copy failure: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is kotlin.io.FileAlreadyExistsException,
        )
        assertTrue("prior configuration must not be deleted on failed restore", old.exists())
    }

    @Test fun successfulConfigurationRestoreCommitsAndStartupKeepsNewData() {
        val context = RuntimeEnvironment.getApplication()
        val live = File(context.filesDir, "configs").apply { mkdirs() }
        File(live, "old-profile.json").writeText("original")
        val backup = File(context.cacheDir, "restore-success.zip")
        ZipOutputStream(backup.outputStream()).use {
            it.putNextEntry(ZipEntry("configs/incoming.json"))
            it.write("backup-content".toByteArray())
            it.closeEntry()
        }
        val result = BackupManager.restoreBackupFile(context, backup, compat = false)
        assertTrue("successful copy should commit: ${result.exceptionOrNull()}", result.isSuccess)
        BackupRestoreTransaction.recoverAtStartup(context)
        assertEquals("backup-content", File(live, "incoming.json").readText())
        assertFalse(File(live, "old-profile.json").exists())
    }

    @Test fun portableCopyFailureRollsBackTheProfileSet() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        io.nekohasekai.sfa.database.Settings.closeDatabase()
        io.nekohasekai.sfa.database.ProfileManager.closeDatabase()
        val context = RuntimeEnvironment.getApplication()
        val live = File(context.filesDir, "configs").apply { mkdirs() }
        val old = File(live, "old.json").apply { writeText("old") }
        io.nekohasekai.sfa.database.ProfileManager.create(io.nekohasekai.sfa.database.Profile(name = "old", typed = io.nekohasekai.sfa.database.TypedProfile().apply { path = old.path }))
        val blocked = File(live, "1.json").apply { mkdirs() }
        File(blocked, "occupied").writeText("block")
        val backup = File(context.cacheDir, "portable-copy-fault.zip")
        ZipOutputStream(backup.outputStream()).use { zip ->
            mapOf(
                "configs/incoming.json" to "{}",
                "profiles.json" to """{"profiles":[{"id":"new-uuid","name":"new","type":"local","config":"configs/incoming.json"}]}""",
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val result = BackupManager.restoreBackupFile(context, backup, compat = false)
        println("PORTABLE_FAULT success=${result.isSuccess} exception=${result.exceptionOrNull()} profiles=${io.nekohasekai.sfa.database.ProfileManager.list().map { it.name }}")
        assertTrue("portable copy failure must fail instead of committing an empty profile set", result.isFailure)
        assertEquals(listOf("old"), io.nekohasekai.sfa.database.ProfileManager.list().map { it.name })
        assertEquals("old", old.readText())
    }
}
