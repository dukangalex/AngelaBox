package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.utils.NoticeDetourMemory
import io.nekohasekai.sfa.utils.NoticeFlashMemory
import io.nekohasekai.sfa.utils.NoticeFlashPlanner
import io.nekohasekai.sfa.utils.OverrideNotice
import io.nekohasekai.sfa.utils.OverrideStatus
import io.nekohasekai.sfa.utils.SubscriptionUpdateHealth
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * 仪表盘提示呈现（v1.0.136 按用户口径重做）：
 *
 * - **状态类提示**（OverrideNotice.error = true：链式未生效、已用最小
 *   配置启动、开关未完全生效……）描述的是当前这次运行的实际形态，
 *   继续以卡片常驻，直到状态本身改变；关掉只是本轮不再看。
 * - **信息类提示**（error = false：规则集跳过、detour 改写、订阅更新
 *   提示……）不在这里渲染——由 [NoticeFlashHost] 一闪而过地闪现，
 *   全文已在生产者侧写进应用提示记录（NoticeLog）留档。
 */
@Composable
fun OverrideBanner(modifier: Modifier = Modifier) {
    val notices by OverrideStatus.notices.collectAsState()
    val stateNotices = notices.filter { it.error }
    if (stateNotices.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        stateNotices.forEach { notice ->
            NoticeCard(notice = notice, onDismiss = { OverrideStatus.dismiss(notice) })
        }
    }
}

@Composable
private fun NoticeCard(notice: OverrideNotice, onDismiss: () -> Unit) {
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
                    notice.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.dismiss),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (notice.reason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    notice.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (notice.hint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    notice.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 闪现行在队列里逐条展示的时长：Snackbar 的 Short（约 4 秒）后自动消失，屏幕不留任何东西。 */
@Composable
fun NoticeFlashHost(
    healthIssues: List<SubscriptionUpdateHealth.HealthIssue>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostState = remember { SnackbarHostState() }
    val lines = remember { Channel<String>(Channel.UNLIMITED) }

    // 消费者：逐条闪现，SnackbarDuration.Short 到时自动消失。
    LaunchedEffect(lines) {
        for (line in lines) {
            hostState.showSnackbar(message = line, duration = SnackbarDuration.Short)
        }
    }

    // 启动提示：等这一批稳定下来（阶梯逐级 add，700ms 无变化视为一批）
    // 再规划，避免半批闪现；已闪过的指纹提交记忆，重组/再进仪表盘不重闪。
    val notices by OverrideStatus.notices.collectAsState()
    LaunchedEffect(notices) {
        if (notices.isEmpty()) return@LaunchedEffect
        delay(700)
        val plan = NoticeFlashPlanner.plan(
            notices,
            seenDetourFingerprints = NoticeDetourMemory.seen,
            alreadyFlashed = NoticeFlashMemory.flashedInfoFingerprints,
        )
        NoticeDetourMemory.seen += plan.shownDetourFingerprints
        NoticeFlashMemory.flashedInfoFingerprints += plan.flashedFingerprints
        plan.lines.forEach { lines.trySend(it) }
    }

    // 订阅更新提示：按（订阅， 批次序号）去重——AB-025 的新批次即使文本
    // 相同也再闪一次，同批次反复评估不重闪。
    LaunchedEffect(healthIssues) {
        val warnings = healthIssues.filter {
            it.kind == SubscriptionUpdateHealth.IssueKind.IMPORT_WARNINGS
        }
        if (warnings.isEmpty()) return@LaunchedEffect
        for (flash in NoticeFlashPlanner.planSubscriptionWarnings(warnings)) {
            val key = flash.profileIds.joinToString(",") { profileId ->
                "$profileId:${SubscriptionUpdateHealth.importWarningsBatch(context, profileId)}"
            } + "|" + flash.text
            if (!NoticeFlashMemory.flashedSubscriptionKeys.add(key)) continue
            lines.trySend(flash.text)
        }
    }

    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}
