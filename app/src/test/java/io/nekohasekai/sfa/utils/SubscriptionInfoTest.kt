package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionInfoTest {

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
}
