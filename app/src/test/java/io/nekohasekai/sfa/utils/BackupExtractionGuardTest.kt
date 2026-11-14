package io.nekohasekai.sfa.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupExtractionGuardTest {
    private fun archive(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in listOf("profiles.json" to "profiles".toByteArray(), "config.json" to ByteArray(8192).also { Random(1).nextBytes(it) })) {
                val entry = ZipEntry(name)
                if (name == "config.json") {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun extract(staging: File, src: File) = extractBackupOrDiscard(src, staging, 512) { checkEntry ->
        ZipInputStream(src.inputStream()).use { zip ->
            val limit = BackupExpansionLimit(100_000, 200_000) { error("limit") }
            while (true) {
                val entry = zip.nextEntry ?: break
                val verifyEntry = checkEntry(entry)
                File(staging, entry.name).outputStream().use { limit.consume(zip, it) }
                zip.closeEntry()
                verifyEntry()
            }
        }
    }

    @Test
    fun completeArchiveKeepsStagedData() {
        val staging = java.nio.file.Files.createTempDirectory("backup-stage-").toFile()
        val src = File.createTempFile("backup-", ".zip")
        try {
            src.writeBytes(archive())
            extract(staging, src)
            assertArrayEquals("profiles".toByteArray(), File(staging, "profiles.json").readBytes())
            assertTrue(File(staging, "config.json").length() == 8192L)
        } finally { staging.deleteRecursively(); src.delete() }
    }

    @Test
    fun truncatedArchiveIsRejectedBeforeMerge() {
        val staging = java.nio.file.Files.createTempDirectory("backup-stage-").toFile()
        val src = File.createTempFile("backup-", ".zip")
        val bytes = archive()
        src.writeBytes(bytes.copyOf(bytes.size / 2))
        var reachedMerge = false
        try {
            val result = runCatching {
                extract(staging, src)
                reachedMerge = true
            }
            assertTrue(result.isFailure)
            assertFalse(reachedMerge)
            assertFalse(staging.exists())
        } finally { staging.deleteRecursively(); src.delete() }
    }

    @Test
    fun badEntryCrcDiscardsAlreadyStagedProfiles() {
        val staging = java.nio.file.Files.createTempDirectory("backup-stage-").toFile()
        val src = File.createTempFile("backup-", ".zip")
        try {
            val bytes = archive()
            val marker = ByteArray(8192).also { Random(1).nextBytes(it) }
            val dataStart = bytes.indices.first { i ->
                i + marker.size <= bytes.size && marker.indices.all { bytes[i + it] == marker[it] }
            }
            bytes[dataStart + 123] = (bytes[dataStart + 123].toInt() xor 1).toByte()
            src.writeBytes(bytes)
            assertTrue(runCatching { extract(staging, src) }.isFailure)
            assertFalse(staging.exists())
        } finally { staging.deleteRecursively(); src.delete() }
    }

    @Test
    fun archiveWithoutEndRecordIsRejected() {
        val staging = java.nio.file.Files.createTempDirectory("backup-stage-").toFile()
        val src = File.createTempFile("backup-", ".zip")
        try {
            val bytes = archive()
            src.writeBytes(bytes.copyOf(bytes.size - 22))
            assertTrue(runCatching { extract(staging, src) }.isFailure)
            assertFalse(staging.exists())
        } finally { staging.deleteRecursively(); src.delete() }
    }

    @Test
    fun missingLocalHeaderIsRejectedEvenIfCentralDirectoryExists() {
        val staging = java.nio.file.Files.createTempDirectory("backup-stage-").toFile()
        val src = File.createTempFile("backup-", ".zip")
        try {
            val bytes = archive()
            val signatures = (0 until bytes.size - 3).filter { i ->
                bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                    bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte()
            }
            assertTrue(signatures.size >= 2)
            bytes[signatures[1]] = 0
            src.writeBytes(bytes)
            assertTrue(runCatching { extract(staging, src) }.isFailure)
            assertFalse(staging.exists())
        } finally { staging.deleteRecursively(); src.delete() }
    }

    @Test
    fun mismatchedCentralDirectoryCrcDiscardsStagedData() {
        val staging = java.nio.file.Files.createTempDirectory("backup-stage-").toFile()
        val src = File.createTempFile("backup-", ".zip")
        try {
            val bytes = archive()
            val central = (0 until bytes.size - 20).first { i ->
                bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                    bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()
            }
            bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
            src.writeBytes(bytes)
            assertTrue(runCatching { extract(staging, src) }.isFailure)
            assertFalse(staging.exists())
        } finally { staging.deleteRecursively(); src.delete() }
    }

    @Test
    fun exceptionAfterStagingIsRethrownAndCleaned() {
        val staging = java.nio.file.Files.createTempDirectory("backup-stage-").toFile()
        val src = File.createTempFile("backup-", ".zip")
        try {
            src.writeBytes(archive())
            val result = runCatching {
                extractBackupOrDiscard(src, staging, 512) {
                    File(staging, "partial").writeText("incomplete")
                    throw IllegalStateException("read failure")
                }
            }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertFalse(staging.exists())
        } finally { staging.deleteRecursively(); src.delete() }
    }
}
