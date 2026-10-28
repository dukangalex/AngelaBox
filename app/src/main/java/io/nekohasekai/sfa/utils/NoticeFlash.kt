package io.nekohasekai.sfa.utils

/**
 * 提示闪现规划（v1.0.136）：用户口径——信息类提示一闪而过、不要常驻，
 * 但每条都要在 NoticeLog 里留档（留档在生产者侧完成，与本规划无关）。
 *
 * 本规划只决定「这一批 OverrideNotice 里，哪些变成一闪而过的闪现行、
 * 哪些是必须常驻的状态」：
 * - error = true 的提示是**状态**（链式未生效、已用最小配置启动、开关
 *   未完全生效……描述的是当前这次运行的实际形态），不在这里闪现，
 *   由调用方继续常驻渲染——它们消失的前提是状态本身改变。
 * - error = false 的是**信息**（规则集跳过、detour 改写、ECH 跳过等
 *   一次性事件事实），收敛成闪现行：规则集家族沿用 NoticeGrouper 并
 *   成一行汇总，其余各自一行（标题 + 正文摘要，全文在日志里）。
 *
 * 闪现去重（进程级）：同一条信息在同一进程里只闪一次（指纹记忆由
 * 调用方持有，见 NoticeFlashMemory）；detour 改写沿用 lane B 的指纹
 * 记忆——同一事实逐字未变时连一次都不再闪，变了才闪。
 */
object NoticeFlashPlanner {

    /** 一批最多闪这么多行，超出的只留档不闪现（末行会说明还有几条）。 */
    const val MAX_LINES = 4

    private const val ONE_LINE_REASON_MAX = 90

    fun fingerprint(notice: OverrideNotice): String =
        notice.title + "\u0001" + notice.reason + "\u0001" + notice.hint + "\u0001" + notice.error

    data class Plan(
        /** 要闪现的行，按原提示顺序（规则集汇总卡占家族第一条的位置）。 */
        val lines: List<String>,
        /** error = true 的状态类提示，顺序不变，调用方常驻渲染。 */
        val stateNotices: List<OverrideNotice>,
        /** 本次实际闪出来的 detour 指纹，调用方据此更新 NoticeDetourMemory。 */
        val shownDetourFingerprints: Set<String>,
        /** 本次闪现覆盖到的信息类提示指纹，调用方据此更新 NoticeFlashMemory。 */
        val flashedFingerprints: Set<String>,
    )

    fun plan(
        notices: List<OverrideNotice>,
        seenDetourFingerprints: Set<String> = emptySet(),
        alreadyFlashed: Set<String> = emptySet(),
    ): Plan {
        val state = notices.filter { it.error }
        val fresh = notices.filter { !it.error && fingerprint(it) !in alreadyFlashed }
        if (fresh.isEmpty()) {
            return Plan(emptyList(), state, emptySet(), emptySet())
        }
        val grouping = NoticeGrouper.group(fresh, seenDetourFingerprints)
        val lines = mutableListOf<String>()
        val flashed = mutableSetOf<String>()
        for (group in grouping.groups) {
            when (group) {
                is NoticeGroup.RuleSetBundle -> {
                    lines += group.summaryLine()
                    group.items.forEach { flashed += fingerprint(it) }
                }
                is NoticeGroup.Single -> {
                    lines += oneLine(group.notice)
                    flashed += fingerprint(group.notice)
                }
            }
        }
        val capped = if (lines.size > MAX_LINES) {
            lines.take(MAX_LINES - 1) + "另有 ${lines.size - (MAX_LINES - 1)} 条提示，已记入日志"
        } else {
            lines
        }
        return Plan(capped, state, grouping.shownDetourFingerprints, flashed)
    }

    /** 单条信息的一行形态：标题 + 正文（压平空白、超长截断；全文在日志里）。 */
    fun oneLine(notice: OverrideNotice): String {
        val reason = notice.reason.replace(Regex("\\s+"), " ").trim()
        if (reason.isEmpty()) return notice.title
        val condensed = if (reason.length > ONE_LINE_REASON_MAX) {
            reason.take(ONE_LINE_REASON_MAX) + "…"
        } else {
            reason
        }
        return notice.title + "：" + condensed
    }

    /** 一条订阅更新提示的闪现：合并同文批次，一行计数汇总（全文在日志里）。 */
    data class SubscriptionFlash(
        val profileIds: List<Long>,
        val text: String,
    )

    fun planSubscriptionWarnings(
        issues: List<SubscriptionUpdateHealth.HealthIssue>,
    ): List<SubscriptionFlash> =
        SubscriptionNoticeSummary.mergeImportWarningCards(issues).map { card ->
            val names = card.profileNames.joinToString("、")
            val summary = SubscriptionNoticeSummary.summaryLine(card.detail)
            SubscriptionFlash(
                profileIds = card.profileIds,
                text = if (summary.isBlank()) "「$names」更新提示" else "「$names」更新提示：$summary",
            )
        }
}

/**
 * 闪现记忆（进程级）：已经闪过的信息类提示指纹。同进程内同一条信息
 * 只闪一次——重复启动产生逐字相同的降级事实时不再刷屏，事实变了
 * （指纹不同）照常闪。进程重启后清空、会再闪一次，与 NoticeDetourMemory
 * 同口径。
 */
object NoticeFlashMemory {
    val flashedInfoFingerprints: MutableSet<String> = mutableSetOf()

    /** 订阅更新提示的闪现键：（订阅 id : AB-025 批次序号）+ 文本。 */
    val flashedSubscriptionKeys: MutableSet<String> = mutableSetOf()
}
