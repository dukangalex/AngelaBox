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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.utils.OverrideStatus

@Composable
fun OverrideBanner(modifier: Modifier = Modifier) {
    val notices by OverrideStatus.notices.collectAsState()
    if (notices.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        notices.forEach { notice ->
            val container = if (notice.error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
            val on = if (notice.error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = container),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(notice.title, style = MaterialTheme.typography.titleSmall, color = on)
                    Spacer(Modifier.height(4.dp))
                    Text(notice.reason, style = MaterialTheme.typography.bodySmall, color = on)
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
    }
}