package io.nekohasekai.sfa.utils

/**
 * 订阅「上次更新提示」的展示整合（v1.0.136）：更新留下的提示文本按行
 * 分类计数，卡片默认只显示一行汇总（「10 个规则集地址已换成官方版」），
 * 全文收进展开区；多个订阅留下逐字相同的提示时并成一张卡，不再一户一墙。
 *
 * 纯逻辑，无 Android 依赖，供横幅渲染与单测共用。此前的同类分组逻辑在
 * SubscriptionHealthBanner 里是私有实现（只显示前 3 行），现统一到这里。
 */
object SubscriptionNoticeSummary {

    const val BUCKET_NODES = 0
    const val BUCKET_RULESETS = 1
    const val BUCKET_OTHER = 2

    fun bucketOf(line: String): Int = when {
        line.startsWith("规则集「") || line.startsWith("无效规则集") -> BUCKET_RULESETS
        line.contains("节点") && line.contains("跳过") -> BUCKET_NODES
        else -> BUCKET_OTHER
    }

    fun linesOf(detail: String): List<String> =
        detail.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** 按桶分组（保持 节点 / 规则集 / 其他 的展示顺序），展开区用。 */
    fun groupLines(detail: String): List<Pair<Int, List<String>>> {
        val byBucket = linkedMapOf<Int, MutableList<String>>()
        linesOf(detail).forEach { line ->
            byBucket.getOrPut(bucketOf(line)) { mutableListOf() }.add(line)
        }
        return listOf(BUCKET_NODES, BUCKET_RULESETS, BUCKET_OTHER)
            .mapNotNull { bucket -> byBucket[bucket]?.let { bucket to it } }
    }

    data class Counts(
        val total: Int,
        /** 规则集「已换成官方版」行数（本类提示的绝对主力）。 */
        val ruleSetSwaps: Int,
        /** 规则集「已去掉」行数。 */
        val ruleSetDrops: Int,
        /** 规则集桶里既非换版也非去掉的行数。 */
        val ruleSetOther: Int,
        val nodeSkips: Int,
        val others: Int,
    )

    fun count(detail: String): Counts {
        var swaps = 0
        var drops = 0
        var ruleSetOther = 0
        var nodeSkips = 0
        var others = 0
        val lines = linesOf(detail)
        for (line in lines) {
            when (bucketOf(line)) {
                BUCKET_RULESETS -> when {
                    // 覆盖逐集行「已换成官方版」与聚合行「已换成官方地址」两种措辞。
                    line.contains("已换成官方") -> swaps++
                    line.contains("已去掉") -> drops++
                    else -> ruleSetOther++
                }
                BUCKET_NODES -> nodeSkips++
                else -> others++
            }
        }
        return Counts(lines.size, swaps, drops, ruleSetOther, nodeSkips, others)
    }

    /** 卡片折叠态的一行汇总。全部是换版行时用最直白的说法（现场原话形态）。 */
    fun summaryLine(detail: String): String {
        val c = count(detail)
        if (c.total == 0) return ""
        if (c.ruleSetSwaps == c.total) return "${c.total} 个规则集地址已换成官方版"
        val parts = mutableListOf<String>()
        if (c.ruleSetSwaps > 0) parts += "规则集换官方版 ${c.ruleSetSwaps} 项"
        if (c.ruleSetDrops > 0) parts += "规则集去掉 ${c.ruleSetDrops} 项"
        if (c.ruleSetOther > 0) parts += "规则集 ${c.ruleSetOther} 项"
        if (c.nodeSkips > 0) parts += "节点跳过 ${c.nodeSkips} 项"
        if (c.others > 0) parts += "其他 ${c.others} 项"
        return "共 ${c.total} 条：" + parts.joinToString(" · ")
    }

    /** 合并后的一张更新提示卡：可能来自单个订阅，也可能来自多个订阅的相同批次。 */
    data class ImportWarningCard(
        val profileIds: List<Long>,
        val profileNames: List<String>,
        val detail: String,
    )

    /**
     * 把 IMPORT_WARNINGS 类 HealthIssue 按提示文本逐字分组：同文的并成
     * 一张卡（关闭时一并关掉组内所有订阅的这一批），不同文的各自成卡，
     * 顺序取首次出现。重复更新留下相同文本时，卡片数量不随订阅数膨胀。
     */
    fun mergeImportWarningCards(
        issues: List<SubscriptionUpdateHealth.HealthIssue>,
    ): List<ImportWarningCard> {
        val byDetail = linkedMapOf<String, MutableList<SubscriptionUpdateHealth.HealthIssue>>()
        for (issue in issues) {
            if (issue.kind != SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS) continue
            byDetail.getOrPut(issue.detail) { mutableListOf() }.add(issue)
        }
        return byDetail.values.map { group ->
            ImportWarningCard(
                profileIds = group.map { it.profileId },
                profileNames = group.map { it.profileName },
                detail = group.first().detail,
            )
        }
    }
}
