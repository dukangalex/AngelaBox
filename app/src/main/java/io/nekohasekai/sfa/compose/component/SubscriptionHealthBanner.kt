package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.utils.SubscriptionUpdateHealth

/**
 * 订阅健康横幅：订阅过期 / 流量耗尽或将尽 / 连续更新失败时在仪表盘顶部提醒。
 * 数据由 SubscriptionUpdateHealth.evaluate() 产出。
 *
 * 多个订阅同时更新失败时合并为一条，避免刷屏。
 */
@Composable
fun SubscriptionHealthBanner(
    issues: List<SubscriptionUpdateHealth.HealthIssue>,
    modifier: Modifier = Modifier,
    onDismissImportWarnings: (Long) -> Unit = {},
) {
    if (issues.isEmpty()) return
    // 更新失败的合并展示，其他类型（过期/流量）逐条展示
    val updateFailed = issues.filter { it.kind == SubscriptionUpdateHealth.IssueKind.UPDATE_FAILED }
    val others = issues.filter { it.kind != SubscriptionUpdateHealth.IssueKind.UPDATE_FAILED }
    Column(modifier = modifier.fillMaxWidth()) {
        if (updateFailed.size == 1) {
            HealthCard(updateFailed[0], onDismissImportWarnings)
        } else if (updateFailed.size > 1) {
            // 合并：标题显示数量，详情列出订阅名（最多 5 个）
            val names = updateFailed.take(5).joinToString("、") { it.profileName }
            val suffix = if (updateFailed.size > 5) "等" else ""
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.subscription_health_update_failed_title_multi, updateFailed.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$names$suffix",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        others.forEach { issue ->
            HealthCard(issue, onDismissImportWarnings)
        }
    }
}

@Composable
private fun HealthCard(
    issue: SubscriptionUpdateHealth.HealthIssue,
    onDismissImportWarnings: (Long) -> Unit = {},
) {
    val isWarnings = issue.kind == SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS
    val title = when (issue.kind) {
        SubscriptionUpdateHealth.IssueKind.EXPIRED ->
            stringResource(R.string.subscription_health_expired_title, issue.profileName)
        SubscriptionUpdateHealth.IssueKind.QUOTA_EXHAUSTED ->
            stringResource(R.string.subscription_health_quota_exhausted_title, issue.profileName)
        SubscriptionUpdateHealth.IssueKind.QUOTA_LOW ->
            stringResource(R.string.subscription_health_quota_low_title, issue.profileName)
        SubscriptionUpdateHealth.IssueKind.UPDATE_FAILED ->
            stringResource(R.string.subscription_health_update_failed_title, issue.profileName)
        SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS ->
            stringResource(R.string.subscription_health_update_notes_title, issue.profileName)
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                if (isWarnings && issue.profileId >= 0L) {
                    IconButton(onClick = { onDismissImportWarnings(issue.profileId) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            if (isWarnings) {
                WarningNotes(detail = issue.detail)
            } else if (issue.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    issue.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

// AB-025: one update can leave many note lines (a wall of near-
// identical rule-set swaps drowned the dashboard in 1.0.126). The
// notes card now groups lines by what they are about — node skips
// vs rule-set URL swaps vs the rest — shows at most
// MAX_SHOWN_NOTE_LINES item lines in total, and folds the remainder
// into a single "and N more" line.
private const val NOTE_BUCKET_NODES = 0
private const val NOTE_BUCKET_RULESETS = 1
private const val NOTE_BUCKET_OTHER = 2
private const val MAX_SHOWN_NOTE_LINES = 3

private fun noteBucketOf(line: String): Int = when {
    line.startsWith("规则集「") || line.startsWith("无效规则集") -> NOTE_BUCKET_RULESETS
    line.contains("节点") && line.contains("跳过") -> NOTE_BUCKET_NODES
    else -> NOTE_BUCKET_OTHER
}

private fun groupWarningNotes(detail: String): List<Pair<Int, List<String>>> {
    val byBucket = linkedMapOf<Int, MutableList<String>>()
    detail.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        byBucket.getOrPut(noteBucketOf(line)) { mutableListOf() }.add(line)
    }
    return listOf(NOTE_BUCKET_NODES, NOTE_BUCKET_RULESETS, NOTE_BUCKET_OTHER)
        .mapNotNull { bucket -> byBucket[bucket]?.let { bucket to it } }
}

@Composable
private fun WarningNotes(detail: String) {
    val groups = remember(detail) { groupWarningNotes(detail) }
    if (groups.isEmpty()) return
    val total = groups.sumOf { it.second.size }
    var shown = 0
    Column {
        Spacer(Modifier.height(4.dp))
        groups.forEach { (bucket, lines) ->
            if (shown >= MAX_SHOWN_NOTE_LINES) return@forEach
            if (groups.size > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        when (bucket) {
                            NOTE_BUCKET_NODES -> R.string.subscription_health_notes_nodes
                            NOTE_BUCKET_RULESETS -> R.string.subscription_health_notes_rulesets
                            else -> R.string.subscription_health_notes_other
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            lines.forEach { line ->
                if (shown >= MAX_SHOWN_NOTE_LINES) return@forEach
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                shown++
            }
        }
        val hidden = total - shown
        if (hidden > 0) {
            Text(
                stringResource(R.string.subscription_health_notes_more, hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
