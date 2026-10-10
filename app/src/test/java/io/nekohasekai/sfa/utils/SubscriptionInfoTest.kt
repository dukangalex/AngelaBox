package io.nekohasekai.sfa.utils

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubscriptionInfoTest {

    private lateinit var savedTimeZone: TimeZone

    /**
     * expireLabel() 按系统默认时区格式化日期；测试把时区固定为 America/Los_Angeles，
     * 否则在 CI（UTC）等环境下 "2099-12-30" 会变成 "2099-12-31"。
     */
    @Before
    fun pinTimeZone() {
        savedTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(savedTimeZone)
    }

    @Test
    fun parseClashUserinfoHeader() {
        val info = SubscriptionInfo.parse(
            "upload=18; download=0; total=102400; expire=4102358400",
        )
        requireNotNull(info)
        assertEquals(18L, info.upload)
        assertEquals(0L, info.download)
        assertEquals(102400L, info.total)
        assertEquals(4102358400L, info.expireAt)
        assertTrue(info.hasQuota)
        assertEquals("2099-12-30", info.expireLabel())
        assertEquals("18.00 B", info.usedLabel())
        assertEquals("100.00 KB", info.totalLabel())
    }

    @Test
    fun parseTreatsLargeExpireAsMilliseconds() {
        val info = SubscriptionInfo.parse("upload=0; download=0; total=0; expire=4102358400000")
        requireNotNull(info)
        assertEquals(4102358400L, info.expireAt)
        assertEquals("2099-12-30", info.expireLabel())
    }

    @Test
    fun parseUnlimitedWhenNoQuota() {
        val info = SubscriptionInfo.parse("upload=0; download=0; total=0")
        assertNull(info)
        val used = SubscriptionInfo.parse("upload=1; download=2; total=0")
        requireNotNull(used)
        assertTrue(used.unlimited)
        assertFalse(used.hasQuota)
        assertEquals("3.00 B", used.usedLabel())
    }

    @Test
    fun formatBytesMatchesClashStyle() {
        assertEquals("18.00 B", SubscriptionInfo.formatBytes(18))
        assertEquals("23.53 GB", SubscriptionInfo.formatBytes((23.53 * 1024 * 1024 * 1024).toLong()))
        assertEquals("512.00 GB", SubscriptionInfo.formatBytes(512L * 1024 * 1024 * 1024))
    }

    @Test
    fun parseClampsNegativeValuesToZero() {
        // v1.0.112: negative upload/download/total must be clamped to 0, not propagated.
        val info = SubscriptionInfo.parse("upload=-5; download=-10; total=-100; expire=4102358400")
        requireNotNull(info)
        assertEquals(0L, info.upload)
        assertEquals(0L, info.download)
        assertEquals(0L, info.total)
    }
}
