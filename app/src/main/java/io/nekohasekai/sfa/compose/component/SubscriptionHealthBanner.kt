package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.utils.SubscriptionUpdateHealth

/**
 * 订阅健康横幅：只承载**状态类**信号——订阅过期 / 流量耗尽或将尽 /
 * 连续更新失败达到阈值。这些描述的是订阅当前处境，需要常驻直到状态
 * 改变。数据由 SubscriptionUpdateHealth.evaluate() 产出。
 *
 * 「上次更新提示」（IMPORT_WARNINGS）是信息类事件，v1.0.136 起不在
 * 这里常驻：全文在更新时已写入应用提示记录（NoticeLog）留档，界面
 * 由 NoticeFlashHost 闪一行计数汇总。
 *
 * 多个订阅同时更新失败时合并为一条，避免刷屏。
 */
@Composable
fun SubscriptionHealthBanner(
    issues: List<SubscriptionUpdateHealth.HealthIssue>,
    modifier: Modifier = Modifier,
) {
    val relevant = issues.filter {
        it.kind != SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS
    }
    if (relevant.isEmpty()) return
    // 更新失败的合并展示，其他类型（过期/流量）逐条展示
    val updateFailed = relevant.filter { it.kind == SubscriptionUpdateHealth.IssueKind.UPDATE_FAILED }
    val others = relevant.filter { it.kind != SubscriptionUpdateHealth.IssueKind.UPDATE_FAILED }
    Column(modifier = modifier.fillMaxWidth()) {
        if (updateFailed.size == 1) {
            HealthCard(updateFailed[0])
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
            HealthCard(issue)
        }
    }
}

@Composable
private fun HealthCard(issue: SubscriptionUpdateHealth.HealthIssue) {
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
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (issue.detail.isNotBlank()) {
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
