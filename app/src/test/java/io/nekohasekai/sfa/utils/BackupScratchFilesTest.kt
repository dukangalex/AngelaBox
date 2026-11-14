package io.nekohasekai.sfa.utils

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BackupScratchFilesTest {
    @Test fun independentImportsAndExportDoNotShareFiles() {
        val dir = Files.createTempDirectory("backup-scratch-test").toFile()
        try {
            val one = BackupScratchFiles.create(dir, "restore")
            val two = BackupScratchFiles.create(dir, "restore")
            val backup = BackupScratchFiles.create(dir, "backup")
            assertEquals(3, setOf(one.canonicalPath, two.canonicalPath, backup.canonicalPath).size)
            BackupFileIO.copyToFile(ByteArrayInputStream("first".toByteArray()), one)
            BackupFileIO.copyToFile(ByteArrayInputStream("second".toByteArray()), two)
            backup.writeText("export")
            assertEquals("first", one.readText())
            assertEquals("second", two.readText())
            assertEquals("export", backup.readText())
            two.delete()
            assertEquals("first", one.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectedSecondImportCannotDeleteFirst() {
        val dir = Files.createTempDirectory("backup-scratch-test").toFile()
        try {
            val first = BackupScratchFiles.create(dir, "restore")
            val second = BackupScratchFiles.create(dir, "restore")
            first.writeText("first")
            try {
                BackupFileIO.copyToFile(ByteArrayInputStream("x".toByteArray()), second, 0)
                fail("expected rejection")
            } catch (_: IllegalArgumentException) { }
            assertFalse(second.exists())
            assertEquals("first", first.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cleanupOccursOnFailureAndSuccess() {
        val dir = Files.createTempDirectory("backup-scratch-test").toFile()
        try {
            assertEquals(3, BackupScratchFiles.withFile(dir, "backup") { it.writeText("abc"); it.length().toInt() })
            assertTrue(dir.listFiles()!!.isEmpty())
            try {
                BackupScratchFiles.withFile(dir, "backup") { it.writeText("private"); error("injected") }
                fail("expected error")
            } catch (_: IllegalStateException) { }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun overlappingExportsKeepOriginalBytesUntilTheirOwnCleanup() {
        val dir = Files.createTempDirectory("backup-scratch-test").toFile()
        val ready = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var firstRead = ""
        val t = Thread {
            BackupScratchFiles.withFile(dir, "backup") {
                it.writeText("first")
                ready.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                firstRead = it.readText()
            }
        }
        try {
            t.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            BackupScratchFiles.withFile(dir, "backup") { it.writeText("second"); assertEquals("second", it.readText()) }
            finish.countDown(); t.join(5000)
            assertFalse(t.isAlive)
            assertEquals("first", firstRead)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { finish.countDown(); t.join(5000); dir.deleteRecursively() }
    }
    @Test fun dialogDisposalDefersDeleteUntilRestoreFinishes() {
        val dir = Files.createTempDirectory("backup-scratch-test").toFile()
        try {
            val file = BackupScratchFiles.create(dir, "restore")
            file.writeText("secret")
            val retention = BackupScratchRetention()
            retention.begin(file)
            retention.discard(file)
            assertEquals("secret", file.readText())
            retention.finish(file)
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun dialogRetryKeepsInputUntilDiscarded() {
        val dir = Files.createTempDirectory("backup-scratch-test").toFile()
        try {
            val file = BackupScratchFiles.create(dir, "restore")
            val retention = BackupScratchRetention()
            retention.begin(file); retention.finish(file)
            assertTrue(file.exists())
            retention.begin(file); retention.finish(file)
            retention.discard(file)
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }

}
