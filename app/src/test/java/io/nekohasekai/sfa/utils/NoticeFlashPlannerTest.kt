package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.136 闪现规划：信息类提示收敛成一闪而过的行（规则集家族一行
 * 汇总、其余各一行），error = true 的状态类提示不闪现、留给常驻渲染；
 * 同一条信息同进程只闪一次，detour 沿用指纹去重。
 * 夹具文案取 1.0.135 实机截图里的原话形态。
 */
class NoticeFlashPlannerTest {

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

    @Test
    fun degradedStartWallFlashesSummaryAndKeepsStateResident() {
        val plan = NoticeFlashPlanner.plan(listOf(cnSkip, detour, adsSkip, ladderSkip, chainError, dnsFinal))
        // 状态类（链失败）不闪，原样交回常驻渲染。
        assertEquals(listOf(chainError), plan.stateNotices)
        // 家族 3 条并成一行汇总（在家族第一条的位置），detour 与 DNS final 各一行。
        assertEquals(3, plan.lines.size)
        assertTrue(plan.lines[0].contains("已跳过 4 个"))
        assertTrue(plan.lines[1].startsWith("系统显式：DNS 服务器「dns-remote」的 detour 由"))
        assertTrue(plan.lines[2].startsWith("系统显式：DNS 最终解析由"))
        // 闪现覆盖到的信息指纹齐全（含 detour 的展示指纹回执）。
        assertEquals(
            setOf(cnSkip, adsSkip, ladderSkip, detour, dnsFinal).map { NoticeFlashPlanner.fingerprint(it) }.toSet(),
            plan.flashedFingerprints,
        )
        assertEquals(
            setOf(NoticeClassifier.detourFingerprint(detour)),
            plan.shownDetourFingerprints,
        )
    }

    @Test
    fun alreadyFlashedInfoDoesNotFlashAgain() {
        val first = NoticeFlashPlanner.plan(listOf(cnSkip, adsSkip, ladderSkip))
        assertEquals(1, first.lines.size)
        val second = NoticeFlashPlanner.plan(
            listOf(cnSkip, adsSkip, ladderSkip, chainError),
            alreadyFlashed = first.flashedFingerprints,
        )
        assertTrue(second.lines.isEmpty())
        assertTrue(second.flashedFingerprints.isEmpty())
        // 状态类不受闪现记忆影响，每次都交回。
        assertEquals(listOf(chainError), second.stateNotices)
    }

    @Test
    fun detourDedupStillAppliesUnderFlash() {
        val seen = setOf(NoticeClassifier.detourFingerprint(detour))
        val suppressed = NoticeFlashPlanner.plan(listOf(detour), seenDetourFingerprints = seen)
        assertTrue(suppressed.lines.isEmpty())
        val changed = detour.copy(reason = "DNS 服务器「dns-remote」的 detour 由「默认代理」改为当前出站「另一个出口」")
        val reshown = NoticeFlashPlanner.plan(listOf(changed), seenDetourFingerprints = seen)
        assertEquals(1, reshown.lines.size)
        assertEquals(
            setOf(NoticeClassifier.detourFingerprint(changed)),
            reshown.shownDetourFingerprints,
        )
    }

    @Test
    fun errorRuleSetNoticeIsStateAndNeverBundledIntoFlash() {
        val errSkip = ladderSkip.copy(error = true)
        val plan = NoticeFlashPlanner.plan(listOf(errSkip, cnSkip))
        assertEquals(listOf(errSkip), plan.stateNotices)
        // 家族只剩 1 条信息 → 独立一行，不并汇总。
        assertEquals(listOf(NoticeFlashPlanner.oneLine(cnSkip)), plan.lines)
    }

    @Test
    fun flashLinesAreCappedWithLogPointer() {
        val many = (1..6).map {
            OverrideNotice(title = "提示$it", reason = "第 $it 条一次性事实", hint = "")
        }
        val plan = NoticeFlashPlanner.plan(many)
        assertEquals(NoticeFlashPlanner.MAX_LINES, plan.lines.size)
        assertEquals("另有 3 条提示，已记入日志", plan.lines.last())
    }

    @Test
    fun oneLineCollapsesWhitespaceAndTruncates() {
        val long = OverrideNotice(
            title = "标题",
            reason = "  第一行\n第二行  " + "长".repeat(200),
            hint = "",
        )
        val line = NoticeFlashPlanner.oneLine(long)
        assertFalse(line.contains("\n"))
        assertTrue(line.endsWith("…"))
        assertTrue(line.length <= "标题：".length + 91)
        val noReason = OverrideNotice(title = "只有标题", reason = "  ", hint = "")
        assertEquals("只有标题", NoticeFlashPlanner.oneLine(noReason))
    }

    @Test
    fun subscriptionWarningsFlashAsMergedSummaryLines() {
        fun issue(name: String, id: Long, swaps: Int) = SubscriptionUpdateHealth.HealthIssue(
            profileName = name,
            kind = SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS,
            detail = (1..swaps).joinToString("\n") {
                "规则集「geosite-$it」的原地址不是公共 HTTPS 地址，未被采用，已换成官方版"
            },
            profileId = id,
        )
        val flashes = NoticeFlashPlanner.planSubscriptionWarnings(
            listOf(issue("Mycfzgo", 11L, 3), issue("knzgo", 12L, 3), issue("other", 13L, 1)),
        )
        assertEquals(2, flashes.size)
        assertEquals(listOf(11L, 12L), flashes[0].profileIds)
        assertEquals("「Mycfzgo、knzgo」更新提示：3 个规则集地址已换成官方版", flashes[0].text)
        assertEquals(listOf(13L), flashes[1].profileIds)
    }
}
