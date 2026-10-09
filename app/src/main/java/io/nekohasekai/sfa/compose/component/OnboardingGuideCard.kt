package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R

/**
 * 首次启动三步引导卡片：配置列表为空时出现在仪表盘顶部。
 * ① 导入订阅（扫码 / 链接 / 剪贴板）→ ② 设为当前配置 → ③ 点启动。
 * 用户点 × 关闭后不再展示（Settings.onboardingGuideDismissed）。
 */
@Composable
fun OnboardingGuideCard(
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.onboarding_guide_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.dismiss),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            GuideStep(
                number = "1",
                text = stringResource(R.string.onboarding_guide_step1),
            )
            Spacer(Modifier.height(6.dp))
            GuideStep(
                number = "2",
                text = stringResource(R.string.onboarding_guide_step2),
            )
            Spacer(Modifier.height(6.dp))
            GuideStep(
                number = "3",
                text = stringResource(R.string.onboarding_guide_step3),
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = onImport) {
                    Text(stringResource(R.string.onboarding_guide_action_import))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.onboarding_guide_action_later))
                }
            }
        }
    }
}

@Composable
private fun GuideStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            number,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(end = 8.dp)
                .width(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
