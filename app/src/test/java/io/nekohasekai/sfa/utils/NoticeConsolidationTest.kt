package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.136 提示整合：规则集家族分组、detour 跨启动去重、订阅更新提示
 * 的计数汇总与相同文本合并。夹具文案取 1.0.135 实机截图里的原话形态。
 */
class NoticeConsolidationTest {

    private val cnSkip = OverrideNotice(
        title = "系统显式",
        reason = "中国规则集（geosite-cn、geoip-cn、geosite-geolocation-!cn）已在本次启动的规则集恢复中跳过，中国直连仅按内置域名/IP 名单生效",
        hint = "订阅原文未改；规则集能正常下载后，下次启动会自动恢复规则集直连。",
    )
    private val adsSkip = OverrideNotice(
        title = "系统显式",
        reason = "广告拦截规则集已在本次启动的规则集恢复中跳过",
        hint = "订阅原文未改；规则集能正常下载后，下次启动会自动恢复广告拦截。",
    )
    private val ladderSkip = OverrideNotice(
        title = "规则集已跳过",
        reason = "远程规则集下载失败，已跳过这些规则集后启动。内核原话：start or reload service: " +
            "start router: (initialize rule-set[6]: initial rule-set: geosite-nvidia@cn: Get " +
            "\"https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-nvidia-cn.srs\": EOF",
        hint = "节点和分组还在，其余分流还在。没有改成直连。",
    )
    private val substituted = OverrideNotice(
        title = "配置规范化",
        reason = "远程规则集暂时下不下来，已用本地缓存的同名规则集替代后启动。",
        hint = "节点、分组和分流没动。网络恢复、规则集能下载后会自动换回远程版。",
    )
    private val detour = OverrideNotice(
        title = "系统显式",
        reason = "DNS 服务器「dns-remote」的 detour 由「默认代理」改为当前出站「漏网之鱼」",
        hint = "DNS 防泄漏要求解析出站与流量出站一致，否则查询仍从原 detour 泄露。",
    )
    private val dnsFinal = OverrideNotice(
        title = "系统显式",
        reason = "DNS 最终解析由「dns-remote」覆盖为「dns-remote」",
        hint = "DNS 防泄漏开启时，未匹配 dns.rules 的查询走受保护的远程服务器。",
    )
    private val chainError = OverrideNotice(
        title = "链式代理未生效，已停止启动",
        reason = "链式入口已失效，该配置启动时将停止并报错。",
        hint = "",
        error = true,
    )

    // ---------- 分类 ----------

    @Test
    fun ruleSetFamilyClassification() {
        assertTrue(NoticeClassifier.isRuleSetAvailability(cnSkip))
        assertTrue(NoticeClassifier.isRuleSetAvailability(adsSkip))
        assertTrue(NoticeClassifier.isRuleSetAvailability(ladderSkip))
        assertEquals(
            NoticeClassifier.RuleSetDegrade.SUBSTITUTED,
            NoticeClassifier.ruleSetDegradeOf(substituted),
        )
        // 实义状态不进家族：链失败、DNS final 变更、detour 改写各自独立。
        assertFalse(NoticeClassifier.isRuleSetAvailability(chainError))
        assertFalse(NoticeClassifier.isRuleSetAvailability(dnsFinal))
        assertFalse(NoticeClassifier.isRuleSetAvailability(detour))
    }

    @Test
    fun detourClassification() {
        assertTrue(NoticeClassifier.isDnsDetourRewrite(detour))
        assertFalse(NoticeClassifier.isDnsDetourRewrite(dnsFinal))
        assertFalse(NoticeClassifier.isDnsDetourRewrite(cnSkip))
    }

    // ---------- 分组 ----------

    @Test
    fun degradedStartWallCollapsesIntoOneBundle() {
        val result = NoticeGrouper.group(listOf(cnSkip, detour, adsSkip, ladderSkip, chainError))
        // 家族 3 条并成 1 张汇总卡（位于家族第一条的位置），detour 与链错误独立。
        assertEquals(3, result.groups.size)
        val bundle = result.groups[0] as NoticeGroup.RuleSetBundle
        assertEquals(listOf(cnSkip, adsSkip, ladderSkip), bundle.items)
        assertEquals(NoticeGroup.Single(detour), result.groups[1])
        assertEquals(NoticeGroup.Single(chainError), result.groups[2])
        // 点名计数：CN 三个 + 内核原话里的 geosite-nvidia-cn，广告集未点名不虚增。
        assertEquals(4, bundle.distinctSets)
        assertTrue(bundle.summaryLine().contains("已跳过 4 个"))
    }

    @Test
    fun singleFamilyNoticeStaysSingle() {
        val result = NoticeGrouper.group(listOf(ladderSkip, detour))
        assertEquals(
            listOf(NoticeGroup.Single(ladderSkip), NoticeGroup.Single(detour)),
            result.groups,
        )
    }

    @Test
    fun substitutedBundleSummaryMentionsCache() {
        val otherSub = OverrideNotice(
            title = "配置规范化",
            reason = "远程规则集暂时下不下来，已用本地缓存的同名规则集替代后启动。",
            hint = "",
        )
        val result = NoticeGrouper.group(listOf(substituted, otherSub))
        val bundle = result.groups.single() as NoticeGroup.RuleSetBundle
        assertTrue(bundle.summaryLine().contains("本地缓存替代"))
    }

    // ---------- detour 跨启动去重 ----------

    @Test
    fun identicalDetourNoticeShownOnceAcrossStarts() {
        val first = NoticeGrouper.group(listOf(detour))
        assertEquals(listOf(NoticeGroup.Single(detour)), first.groups)
        assertEquals(
            setOf(NoticeClassifier.detourFingerprint(detour)),
            first.shownDetourFingerprints,
        )
        val second = NoticeGrouper.group(listOf(detour), first.shownDetourFingerprints)
        assertTrue(second.groups.isEmpty())
        // 事实变了（换出站）必须重新展示。
        val changed = detour.copy(reason = "DNS 服务器「dns-remote」的 detour 由「默认代理」改为当前出站「另一个出口」")
        val third = NoticeGrouper.group(listOf(changed), first.shownDetourFingerprints)
        assertEquals(listOf(NoticeGroup.Single(changed)), third.groups)
    }

    // ---------- 更新提示汇总与合并 ----------

    private fun swapLines(n: Int): String =
        (1..n).joinToString("\n") { "规则集「geosite-$it」的原地址不是公共 HTTPS 地址，未被采用，已换成官方版" }

    @Test
    fun allSwapWarningsSummarizeAsOneLine() {
        val detail = swapLines(10)
        val counts = SubscriptionNoticeSummary.count(detail)
        assertEquals(10, counts.total)
        assertEquals(10, counts.ruleSetSwaps)
        assertEquals("10 个规则集地址已换成官方版", SubscriptionNoticeSummary.summaryLine(detail))
    }

    @Test
    fun mixedWarningsSummarizeByKind() {
        val detail = listOf(
            "规则集「geosite-cn」的原地址不是公共 HTTPS 地址，未被采用，已换成官方版",
            "规则集「geoip-cn」的原地址不是公共 HTTPS 地址，未被采用，已换成官方版",
            "规则集「geosite-x」的原地址不是公共 HTTPS 地址，未被采用，已去掉",
            "已跳过 2 个不支持的节点",
            "某个其它提示",
        ).joinToString("\n")
        assertEquals(
            "共 5 条：规则集换官方版 2 项 · 规则集去掉 1 项 · 节点跳过 1 项 · 其他 1 项",
            SubscriptionNoticeSummary.summaryLine(detail),
        )
        val groups = SubscriptionNoticeSummary.groupLines(detail)
        assertEquals(
            listOf(
                SubscriptionNoticeSummary.BUCKET_NODES,
                SubscriptionNoticeSummary.BUCKET_RULESETS,
                SubscriptionNoticeSummary.BUCKET_OTHER,
            ),
            groups.map { it.first },
        )
    }

    @Test
    fun identicalUpdateWarningsMergeAcrossProfiles() {
        val detail = swapLines(3)
        fun issue(name: String, id: Long, text: String) = SubscriptionUpdateHealth.HealthIssue(
            profileName = name,
            kind = SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS,
            detail = text,
            profileId = id,
        )
        val cards = SubscriptionNoticeSummary.mergeImportWarningCards(
            listOf(
                issue("Mycfzgo", 11L, detail),
                issue("knzgo", 12L, detail),
                issue("other", 13L, swapLines(1)),
            ),
        )
        assertEquals(2, cards.size)
        assertEquals(listOf(11L, 12L), cards[0].profileIds)
        assertEquals(listOf("Mycfzgo", "knzgo"), cards[0].profileNames)
        assertEquals(listOf(13L), cards[1].profileIds)
    }
}
