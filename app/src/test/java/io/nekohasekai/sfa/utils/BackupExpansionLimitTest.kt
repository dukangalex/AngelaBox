package io.nekohasekai.sfa.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupExpansionLimitTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun limiter(entry: Long, total: Long) =
        BackupExpansionLimit(entry, total) { throw IllegalStateException("too large") }

    @Test
    fun ignoredCompressedEntryIsBounded() {
        val bytes = zip("unrecognized.bin" to ByteArray(256 * 1024))
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.nextEntry
            assertThrows(IllegalStateException::class.java) { limiter(1024, 2048).consume(input, null) }
        }
    }

    @Test
    fun ignoredEntriesCountTowardAggregateLimit() {
        val bytes = zip("ignored" to ByteArray(12), "profiles.json" to ByteArray(12))
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            val limit = limiter(16, 20)
            input.nextEntry
            limit.consume(input, null)
            input.closeEntry()
            input.nextEntry
            assertThrows(IllegalStateException::class.java) { limit.consume(input, ByteArrayOutputStream()) }
        }
    }

    @Test
    fun recognizedEntryAtLimitCopiesExactly() {
        val data = byteArrayOf(1, 2, 3, 4)
        val bytes = zip("profiles.json" to data)
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.nextEntry
            val output = ByteArrayOutputStream()
            limiter(4, 4).consume(input, output)
            assertArrayEquals(data, output.toByteArray())
        }
    }
}
