package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared settings switch row (B11): medium title with an optional ⓘ
 * help button, a small subtitle, and a trailing switch on a transparent
 * ListItem. Extracted from the 系统显式 page's former private
 * OverrideSwitch so every settings page renders identical rows.
 * [enabled] greys the switch only; the row text stays readable, which
 * is how sub-item rows show "parent off" without hiding the setting.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onHelp: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (onHelp != null) {
                    IconButton(onClick = onHelp) {
                        Icon(Icons.Outlined.Info, contentDescription = "说明", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
