package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.dashboard.NetworkCheckupUiState
import io.nekohasekai.sfa.utils.NetworkCheckup

/**
 * 一键网络体检卡片（审计"我的用户视角"想法 1）。
 * Idle → 一个按钮；Running → 进度；Done → 四项结果 + 一句人话结论。
 * 放在仪表盘顶部（SubscriptionHealthBanner 之后），与横幅/引导卡片同一视觉语言。
 */
@Composable
fun NetworkCheckupCard(
    state: NetworkCheckupUiState,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.network_checkup_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            when (state) {
                is NetworkCheckupUiState.Idle -> IdleContent(onRun)
                is NetworkCheckupUiState.Running -> RunningContent(state.step)
                is NetworkCheckupUiState.Done -> DoneContent(state.result, onRun, onDismiss)
            }
        }
    }
}

@Composable
private fun IdleContent(onRun: () -> Unit) {
    Text(
        stringResource(R.string.network_checkup_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = onRun) {
        Text(stringResource(R.string.network_checkup_run))
    }
}

@Composable
private fun RunningContent(step: NetworkCheckup.ItemId) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.network_checkup_running, checkupItemTitle(step)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DoneContent(
    result: NetworkCheckup.Result,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    VerdictBanner(result)
    Spacer(Modifier.height(8.dp))
    result.items.forEach { item ->
        CheckupItemRow(item)
    }
    Spacer(Modifier.height(12.dp))
    Row {
        OutlinedButton(onClick = onRun) {
            Text(stringResource(R.string.network_checkup_rerun))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.network_checkup_dismiss))
        }
    }
}

@Composable
private fun VerdictBanner(result: NetworkCheckup.Result) {
    val (container, content) = when (result.verdict) {
        NetworkCheckup.VerdictKind.ALL_OK ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        NetworkCheckup.VerdictKind.NO_NETWORK, NetworkCheckup.VerdictKind.SERVICE_STOPPED ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        NetworkCheckup.VerdictKind.NODE_SLOW, NetworkCheckup.VerdictKind.SUBSCRIPTION_UNHEALTHY ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val text = when (result.verdict) {
        NetworkCheckup.VerdictKind.ALL_OK ->
            stringResource(R.string.checkup_verdict_all_ok)
        NetworkCheckup.VerdictKind.NO_NETWORK ->
            stringResource(R.string.checkup_verdict_no_network)
        NetworkCheckup.VerdictKind.SERVICE_STOPPED ->
            stringResource(R.string.checkup_verdict_service_stopped)
        NetworkCheckup.VerdictKind.SUBSCRIPTION_BAD ->
            stringResource(R.string.checkup_verdict_subscription_bad, result.verdictDetail)
        NetworkCheckup.VerdictKind.TUNNEL_BROKEN ->
            stringResource(R.string.checkup_verdict_tunnel_broken)
        NetworkCheckup.VerdictKind.DNS_HIJACKED ->
            stringResource(R.string.checkup_verdict_dns_hijacked)
        NetworkCheckup.VerdictKind.NODE_SLOW ->
            stringResource(R.string.checkup_verdict_node_slow, result.verdictDetail)
        NetworkCheckup.VerdictKind.SUBSCRIPTION_UNHEALTHY ->
            stringResource(R.string.checkup_verdict_subscription_unhealthy, result.verdictDetail)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun CheckupItemRow(item: NetworkCheckup.Item) {
    val (icon, tint) = when (item.status) {
        NetworkCheckup.ItemStatus.OK ->
            Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        NetworkCheckup.ItemStatus.WARN ->
            Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
        NetworkCheckup.ItemStatus.FAIL ->
            Icons.Filled.Error to MaterialTheme.colorScheme.error
        NetworkCheckup.ItemStatus.SKIP ->
            Icons.Filled.RemoveCircle to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                checkupItemTitle(item.id),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.detail.isNotBlank()) {
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun checkupItemTitle(id: NetworkCheckup.ItemId): String = when (id) {
    NetworkCheckup.ItemId.TUNNEL -> stringResource(R.string.checkup_item_tunnel)
    NetworkCheckup.ItemId.DNS -> stringResource(R.string.checkup_item_dns)
    NetworkCheckup.ItemId.LATENCY -> stringResource(R.string.checkup_item_latency)
    NetworkCheckup.ItemId.SUBSCRIPTION -> stringResource(R.string.checkup_item_subscription)
}
