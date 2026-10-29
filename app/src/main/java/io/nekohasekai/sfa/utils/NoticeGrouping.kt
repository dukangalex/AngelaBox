package io.nekohasekai.sfa.utils

/**
 * 仪表盘提示整合（v1.0.136）：把同一次启动产生的「规则集不可用」家族
 * 提示（中国集跳过、广告集跳过、阶梯的「规则集已跳过」、逐集分诊结果）
 * 在展示层并成一张卡，避免降级启动后满屏卡片。
 *
 * 这里只做分类与分组，不改任何生产者：OverrideNotice 本身不带家族
 * 字段，所以按标题/正文的稳定措辞识别。识别口径刻意收紧——链式失败、
 * DNS final 变更等实义状态不含这些措辞，永远独立成卡。
 */
object NoticeClassifier {

    /** 规则集可用性家族里的两种降级方式。 */
    enum class RuleSetDegrade { SKIPPED, SUBSTITUTED }

    private val SKIP_WORDS = listOf("跳过", "不可用", "下载失败", "下不下来", "未能下载", "无法下载")

    /**
     * 该提示是否属于「规则集不可用」家族：关于规则集、且是跳过/替代类
     * 降级事实。逐集分诊（lane A）只要沿用「规则集 + 跳过/不可用」措辞，
     * 就会自动并入本家族。
     */
    fun ruleSetDegradeOf(notice: OverrideNotice): RuleSetDegrade? {
        val text = notice.title + "\n" + notice.reason + "\n" + notice.hint
        if (notice.title == "规则集已跳过") return RuleSetDegrade.SKIPPED
        if (!text.contains("规则集")) return null
        if (text.contains("替代") && text.contains("缓存")) return RuleSetDegrade.SUBSTITUTED
        if (SKIP_WORDS.any { text.contains(it) }) return RuleSetDegrade.SKIPPED
        return null
    }

    fun isRuleSetAvailability(notice: OverrideNotice): Boolean = ruleSetDegradeOf(notice) != null

    /**
     * 防 DNS 的 detour 改写提示（ConfigQuicOverride 的固定措辞）。同一
     * 配置 + 同一设置下它每次启动都逐字重发，是「事实没变、提示常驻」
     * 的典型——展示层按指纹跨启动去重（见 NoticeGrouper）。
     */
    fun isDnsDetourRewrite(notice: OverrideNotice): Boolean = notice.reason.contains("detour 由") && notice.reason.contains("改为当前出站")

    fun detourFingerprint(notice: OverrideNotice): String = notice.title + "|" + notice.reason + "|" + notice.hint

    private val SET_TAG_REGEX = Regex("""(?:geosite|geoip)-[A-Za-z0-9!._\-]+""")

    /** 从提示文本里提取被点名的规则集 tag（去重），供汇总行计数。 */
    fun extractRuleSetTags(notice: OverrideNotice): Set<String> {
        val text = notice.title + "\n" + notice.reason + "\n" + notice.hint
        return SET_TAG_REGEX.findAll(text)
            .map { it.value.removeSuffix(".srs").trimEnd('.', '。', '、', '，') }
            .toSet()
    }
}

/** 展示层分组结果：一张独立卡，或一张规则集家族汇总卡。 */
sealed interface NoticeGroup {
    data class Single(val notice: OverrideNotice) : NoticeGroup

    data class RuleSetBundle(val items: List<OverrideNotice>) : NoticeGroup {
        /** 被点名的不同规则集个数；文本没点名时退回条数计数。 */
        val distinctSets: Int
            get() {
                val tags = items.flatMap { NoticeClassifier.extractRuleSetTags(it) }.toSet()
                if (tags.isEmpty()) return items.size
                // 同一个集在内核原话里常以 tag 与文件名（tag + 后缀）
                // 两种形态各出现一次，按前缀归并，避免重复计数。
                return tags.count { tag -> tags.none { it != tag && it.startsWith(tag) } }
            }

        val error: Boolean get() = items.any { it.error }

        /** 汇总卡的一行摘要。明细（哪个集、降级成什么样、内核原话）在展开区。 */
        fun summaryLine(): String {
            val skipped = items.count {
                NoticeClassifier.ruleSetDegradeOf(it) == NoticeClassifier.RuleSetDegrade.SKIPPED
            }
            val substituted = items.count {
                NoticeClassifier.ruleSetDegradeOf(it) == NoticeClassifier.RuleSetDegrade.SUBSTITUTED
            }
            return when {
                skipped > 0 && substituted > 0 ->
                    "部分规则集不可用，已跳过 $distinctSets 个、另有 $substituted 项用本地缓存替代，其余分流正常"
                substituted > 0 && skipped == 0 ->
                    "远程规则集暂时下不下来，已用本地缓存替代，其余分流正常"
                else ->
                    "部分规则集不可用，已跳过 $distinctSets 个，其余分流正常"
            }
        }
    }
}

object NoticeGrouper {

    data class Result(
        val groups: List<NoticeGroup>,
        /** 本轮实际展示出来的 detour 提示指纹，调用方据此更新跨启动记忆。 */
        val shownDetourFingerprints: Set<String>,
    )

    /**
     * 把一次启动的提示列表分组：
     * - 规则集家族 ≥2 条 → 并成一张汇总卡，位置取家族第一条出现处；
     *   只有 1 条时保持独立卡（全文可见，不降级它的显著性）。
     * - detour 改写提示若指纹已在 [seenDetourFingerprints] 里（上次启动
     *   展示过且事实逐字未变），本轮不再展示；变了（换配置/换出站）照常展示。
     * - 其余提示原样、原序独立成卡。
     */
    fun group(
        notices: List<OverrideNotice>,
        seenDetourFingerprints: Set<String> = emptySet(),
    ): Result {
        val family = notices.filter { NoticeClassifier.isRuleSetAvailability(it) }
        val bundle = if (family.size >= 2) NoticeGroup.RuleSetBundle(family) else null
        val groups = mutableListOf<NoticeGroup>()
        val shownDetour = mutableSetOf<String>()
        var bundlePlaced = false
        for (notice in notices) {
            if (NoticeClassifier.isRuleSetAvailability(notice)) {
                if (bundle != null) {
                    if (!bundlePlaced) {
                        groups.add(bundle)
                        bundlePlaced = true
                    }
                    continue
                }
                groups.add(NoticeGroup.Single(notice))
                continue
            }
            if (NoticeClassifier.isDnsDetourRewrite(notice)) {
                val fingerprint = NoticeClassifier.detourFingerprint(notice)
                if (fingerprint in seenDetourFingerprints) continue
                shownDetour.add(fingerprint)
            }
            groups.add(NoticeGroup.Single(notice))
        }
        return Result(groups, shownDetour)
    }
}

/**
 * detour 提示的跨启动记忆（进程级）：装的只是「已经展示过、且逐字未
 * 变」的指纹。进程重启后清空、会再展示一次——这是有意的：新进程里
 * 用户值得再看一眼这条行为变更，之后同事实不再刷屏。
 */
object NoticeDetourMemory {
    val seen: MutableSet<String> = mutableSetOf()
}
