package io.nekohasekai.sfa.compose.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.compose.base.GlobalEventBus
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.OverlayScripts

@Composable
fun ProfileScriptBinderCard(profileId: Long, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.overlay_scripts),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ProfileScriptBinderBody(profileId = profileId)
        }
    }
}

@Composable
fun ProfileScriptBinderDialog(
    profileId: Long,
    profileName: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.overlay_scripts)} · $profileName") },
        text = { ProfileScriptBinderBody(profileId = profileId) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}

@Composable
private fun ProfileScriptBinderBody(profileId: Long) {
    var tick by remember { mutableIntStateOf(0) }
    val scripts = remember(tick) { OverlayScripts.list() }
    val bound = remember(tick, profileId) { OverlayScripts.selectedIds(profileId) }
    val selected = bound ?: emptyList()
    val masterOn = selected.isNotEmpty()

    fun persist(ids: List<String>) {
        OverlayScripts.setBinding(profileId, ids)
        if (ids.isNotEmpty()) ChainBindings.remove(profileId)
        tick++
        if (profileId == Settings.selectedProfile) {
            GlobalEventBus.tryEmit(UiEvent.ApplyServiceChange(UiEvent.ApplyServiceChange.Mode.Reload))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.overlay_scripts_profile_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.overlay_scripts_profile_enable),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(
                checked = masterOn,
                onCheckedChange = { on ->
                    if (!on) {
                        persist(emptyList())
                    } else if (scripts.isEmpty()) {
                        persist(emptyList())
                    } else {
                        val ids = if (scripts.size == 1) {
                            listOf(scripts.first().id)
                        } else {
                            selected.ifEmpty { scripts.map { it.id } }
                        }
                        persist(ids)
                    }
                },
            )
        }
        if (scripts.isEmpty()) {
            Text(
                text = stringResource(R.string.overlay_scripts_profile_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (masterOn && scripts.size == 1) {
            Text(
                text = scripts.first().name,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (masterOn) {
            scripts.forEach { script ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = script.id in selected,
                        onCheckedChange = { checked ->
                            val next = if (checked) {
                                selected + script.id
                            } else {
                                selected.filterNot { it == script.id }
                            }
                            persist(next)
                        },
                    )
                    Text(
                        text = script.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}
