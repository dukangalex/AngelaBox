package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.136 应用提示留档：JSONL 序列化往返、坏行容错、留存裁剪
 * （300 条 / 30 天）、导出格式与 OverrideNotice 映射。
 */
class NoticeLogTest {

    private val kernelVerbatim = OverrideNotice(
        title = "规则集已跳过",
        reason = "远程规则集下载失败，已跳过这些规则集后启动。内核原话：start router: " +
            "(initialize rule-set[6]: initial rule-set: geosite-nvidia@cn: Get " +
            "\"https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-nvidia-cn.srs\": EOF",
        hint = "节点和分组还在，其余分流还在。没有改成直连。",
    )

    @Test
    fun serializeParseRoundTripPreservesEverything() {
        val record = kernelVerbatim.toNoticeLogRecord(1_730_000_000_000L)
        val parsed = NoticeLog.parse(NoticeLog.serialize(record))
        assertEquals(record, parsed)
        // 含引号、冒号、中文与 URL 的内核原话必须逐字回来。
        assertEquals(kernelVerbatim.reason, parsed!!.reason)
    }

    @Test
    fun parseSkipsBadLines() {
        assertNull(NoticeLog.parse(""))
        assertNull(NoticeLog.parse("not json"))
        assertNull(NoticeLog.parse("{broken"))
        val good = NoticeLog.serialize(kernelVerbatim.toNoticeLogRecord(5L))
        val all = NoticeLog.parseAll("\n$good\n\ngarbage\n")
        assertEquals(1, all.size)
        assertEquals(5L, all[0].timestampMs)
    }

    @Test
    fun trimKeepsNewestWithinCountCap() {
        val now = 100L * 24 * 3600 * 1000
        val records = (1..350).map {
            NoticeLogRecord(now - (350 - it) * 1000L, NoticeLog.SOURCE_START, "t$it", "", "", false)
        }
        val kept = NoticeLog.trim(records, now)
        assertEquals(NoticeLog.MAX_RECORDS, kept.size)
        assertEquals("t51", kept.first().title)
        assertEquals("t350", kept.last().title)
    }

    @Test
    fun trimDropsOldRecordsButKeepsFutureOnes() {
        val now = 100L * 24 * 3600 * 1000
        val old = NoticeLogRecord(now - NoticeLog.MAX_AGE_MS - 1, NoticeLog.SOURCE_START, "old", "", "", false)
        val fresh = NoticeLogRecord(now - 1000L, NoticeLog.SOURCE_START, "fresh", "", "", false)
        val future = NoticeLogRecord(now + 60_000L, NoticeLog.SOURCE_START, "future", "", "", false)
        val kept = NoticeLog.trim(listOf(old, fresh, future), now)
        assertEquals(listOf("fresh", "future"), kept.map { it.title })
    }

    @Test
    fun formatCarriesTimestampSourceAndFullText() {
        val start = kernelVerbatim.toNoticeLogRecord(1_730_000_000_000L)
        val sub = NoticeLogRecord(
            timestampMs = 1_730_000_060_000L,
            source = NoticeLog.SOURCE_SUBSCRIPTION,
            title = "订阅更新提示",
            reason = "规则集「geosite-cn」的原地址不是公共 HTTPS 地址，未被采用，已换成官方版",
            hint = "",
            error = false,
        )
        val err = NoticeLogRecord(1_730_000_120_000L, NoticeLog.SOURCE_START, "链式代理未生效，已停止启动", "入口失效", "", true)
        val text = NoticeLog.format(listOf(start, sub, err))
        assertTrue(text.contains("[启动] 规则集已跳过"))
        assertTrue(text.contains("[订阅更新] 订阅更新提示"))
        assertTrue(text.contains("geosite-nvidia@cn"))
        assertTrue(text.contains("已换成官方版"))
        assertTrue(text.contains("（未完全生效/状态异常）"))
    }

    @Test
    fun overrideNoticeMappingKeepsErrorFlag() {
        val record = kernelVerbatim.copy(error = true).toNoticeLogRecord(42L)
        assertEquals(NoticeLog.SOURCE_START, record.source)
        assertEquals(42L, record.timestampMs)
        assertTrue(record.error)
        assertEquals(kernelVerbatim.title, record.title)
        assertEquals(kernelVerbatim.hint, record.hint)
    }
}
