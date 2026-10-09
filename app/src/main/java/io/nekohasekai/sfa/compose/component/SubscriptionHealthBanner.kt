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
 * 订阅健康横幅：订阅过期 / 流量耗尽或将尽 / 连续更新失败时在仪表盘顶部提醒。
 * 数据由 SubscriptionUpdateHealth.evaluate() 产出。
 */
@Composable
fun SubscriptionHealthBanner(
    issues: List<SubscriptionUpdateHealth.HealthIssue>,
    modifier: Modifier = Modifier,
) {
    if (issues.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        issues.forEach { issue ->
            val title = when (issue.kind) {
                SubscriptionUpdateHealth.IssueKind.EXPIRED ->
                    stringResource(R.string.subscription_health_expired_title, issue.profileName)
                SubscriptionUpdateHealth.IssueKind.QUOTA_EXHAUSTED ->
                    stringResource(R.string.subscription_health_quota_exhausted_title, issue.profileName)
                SubscriptionUpdateHealth.IssueKind.QUOTA_LOW ->
                    stringResource(R.string.subscription_health_quota_low_title, issue.profileName)
                SubscriptionUpdateHealth.IssueKind.UPDATE_FAILED ->
                    stringResource(R.string.subscription_health_update_failed_title, issue.profileName)
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
    }
}
