package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [RestoreDirectoryOsShadow::class])
class BackupRestoreCopyFaultRegressionTest {
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
        assertTrue("test must reach the injected file copy failure: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is kotlin.io.FileAlreadyExistsException)
        assertTrue("prior configuration must not be deleted on failed restore", old.exists())
    }
    @Test fun successfulConfigurationRestoreCommitsAndStartupKeepsNewData() {
        val context = RuntimeEnvironment.getApplication()
        val live = File(context.filesDir, "configs").apply { mkdirs() }
        File(live, "old-profile.json").writeText("original")
        val backup = File(context.cacheDir, "restore-success.zip")
        ZipOutputStream(backup.outputStream()).use {
            it.putNextEntry(ZipEntry("configs/incoming.json"))
            it.write("backup-content".toByteArray()); it.closeEntry()
        }
        val result = BackupManager.restoreBackupFile(context, backup, compat = false)
        assertTrue("successful copy should commit: ${result.exceptionOrNull()}", result.isSuccess)
        BackupRestoreTransaction.recoverAtStartup(context)
        assertEquals("backup-content", File(live, "incoming.json").readText())
        assertFalse(File(live, "old-profile.json").exists())
    }

}
