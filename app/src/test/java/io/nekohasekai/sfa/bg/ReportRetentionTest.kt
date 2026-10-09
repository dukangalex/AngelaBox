package io.nekohasekai.sfa.bg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportRetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun makeReport(name: String, ageDays: Long, sizeBytes: Int = 16): File {
        val dir = temp.newFolder(name)
        File(dir, "go.log").writeBytes(ByteArray(sizeBytes))
        dir.setLastModified(System.currentTimeMillis() - ageDays * ReportRetention.MILLIS_PER_DAY)
        return dir
    }

    private fun newestFirst(): List<File> =
        temp.root.listFiles { f -> f.isDirectory }!!.sortedByDescending { it.lastModified() }

    @Test
    fun keepsOnlyNewestTen() {
        repeat(12) { i -> makeReport("report-$i", ageDays = i.toLong()) }
        val deleted = ReportRetention.prune(newestFirst())
        assertEquals(2, deleted)
        assertEquals(10, temp.root.listFiles { f -> f.isDirectory }!!.size)
    }

    @Test
    fun dropsReportsOlderThanThirtyDays() {
        makeReport("fresh", ageDays = 1)
        makeReport("old", ageDays = 31)
        val deleted = ReportRetention.prune(newestFirst())
        assertEquals(1, deleted)
        val remaining = temp.root.listFiles { f -> f.isDirectory }!!
        assertEquals(1, remaining.size)
        assertEquals("fresh", remaining[0].name)
    }

    @Test
    fun boundaryDayIsKept() {
        makeReport("almost-old", ageDays = 29)
        val deleted = ReportRetention.prune(newestFirst())
        assertEquals(0, deleted)
        assertEquals(1, temp.root.listFiles { f -> f.isDirectory }!!.size)
    }

    @Test
    fun totalBytesSumsFiles() {
        makeReport("a", ageDays = 0, sizeBytes = 100)
        makeReport("b", ageDays = 0, sizeBytes = 200)
        assertEquals(300L, ReportRetention.totalBytes(temp.root))
    }

    @Test
    fun totalBytesOnMissingDirIsZero() {
        assertEquals(0L, ReportRetention.totalBytes(File(temp.root, "nope")))
    }

    @Test
    fun pruneNeverThrowsOnGarbage() {
        val deleted = ReportRetention.prune(listOf(File(temp.root, "nope")))
        assertEquals(0, deleted)
    }
}
