package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.inspection.InspectionAi
import io.nekohasekai.sfa.inspection.InspectionPolicy
import io.nekohasekai.sfa.inspection.InspectionRunner
import io.nekohasekai.sfa.inspection.InspectionStore
import io.nekohasekai.sfa.utils.CoreIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class InspectionReview(val snapshot: InspectionPolicy.Snapshot, val config: InspectionStore.AiConfig, val payload: String, val stamp: InspectionRunner.ContextStamp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by InspectionStore.changes.collectAsState()
    var snapshot by remember { mutableStateOf<InspectionPolicy.Snapshot?>(null) }
    var stamp by remember { mutableStateOf(InspectionRunner.stamp(context)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var enabled by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf<InspectionStore.AiConfig?>(null) }
    var showConfig by remember { mutableStateOf(false) }
    var showProbeConsent by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<InspectionReview?>(null) }
    var running by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var answerStamp by remember { mutableStateOf<InspectionRunner.ContextStamp?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(changes) {
        withContext(Dispatchers.IO) {
            Triple(InspectionStore.latest(context), InspectionStore.enabled(context), InspectionStore.config(context))
        }.let { (result, auto, ai) -> if (snapshot?.collectedAt != result?.collectedAt) answer = null; snapshot = result; enabled = auto; config = ai }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            stamp = InspectionRunner.stamp(context)
            now = System.currentTimeMillis()
            if (answerStamp != stamp || snapshot?.let { !InspectionPolicy.fresh(it, now, stamp.profile, stamp.tunnelUp, stamp.network, stamp.token) } != false) answer = null
        }
    }
    val fresh = snapshot?.let { InspectionPolicy.fresh(it, now, stamp.profile, stamp.tunnelUp, stamp.network, stamp.token) } == true
    OverrideTopBar {
        TopAppBar(title = { Text(stringResource(R.string.inspection_title)) }, navigationIcon = {
            IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close)) }
        })
    }
    Column(
        Modifier.padding(LocalScaffoldPadding.current).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.inspection_intro))
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text(stringResource(R.string.inspection_auto), Modifier.weight(1f))
                    Switch(checked = enabled, enabled = !running, onCheckedChange = { value ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    InspectionRunner.schedule(context, value)
                                    InspectionStore.setEnabled(context, value)
                                }
                            } catch (_: Exception) { message = R.string.inspection_save_failed }
                        }
                    })
                }
                Text(stringResource(R.string.inspection_auto_desc), style = MaterialTheme.typography.bodySmall)
            }
        }
        snapshot?.let { result ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.inspection_observed_at, DateFormat.getDateTimeInstance().format(Date(result.collectedAt))))
                    Text(stringResource(if (fresh) R.string.inspection_point_in_time else R.string.inspection_stale), color = MaterialTheme.colorScheme.primary)
                    result.facts.forEach { fact ->
                        Text("${fact.id}: ${fact.state.name} / ${fact.code}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.inspection_evidence_limit), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { task?.cancel(); message = R.string.inspection_cancelled }) { Text(stringResource(R.string.cancel)) }
        }
        Button(enabled = !running, onClick = { showProbeConsent = true }) { Text(stringResource(R.string.inspection_run)) }
        OutlinedButton(enabled = !running, onClick = { showConfig = true }) { Text(stringResource(R.string.inspection_model_settings)) }
        Button(enabled = !running && fresh && config != null, onClick = {
            val result = snapshot ?: return@Button
            val ai = config ?: return@Button
            review = InspectionReview(result, ai, InspectionPolicy.payload(result, BuildConfig.VERSION_NAME, CoreIdentity.libboxOrPin()), InspectionRunner.stamp(context))
        }) { Text(stringResource(R.string.inspection_ai_review)) }
        if (config == null) Text(stringResource(R.string.inspection_no_model), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !running, onClick = {
            scope.launch { withContext(Dispatchers.IO) { InspectionStore.clearResult(context) }; answer = null }
        }) { Text(stringResource(R.string.inspection_clear)) }
        message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        answer?.let {
            Text(stringResource(R.string.inspection_ai_untrusted), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(it) }
        }
    }
    if (showProbeConsent) AlertDialog(
        onDismissRequest = { showProbeConsent = false },
        title = { Text(stringResource(R.string.inspection_run)) },
        text = { Text(stringResource(R.string.inspection_probe_consent)) },
        confirmButton = { TextButton(onClick = {
            showProbeConsent = false
            running = true; answer = null; message = null
            task = scope.launch {
                try { snapshot = InspectionRunner.manual(context) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = R.string.inspection_failed }
                finally { running = false }
            }
        }) { Text(stringResource(R.string.inspection_run)) } },
        dismissButton = { TextButton(onClick = { showProbeConsent = false }) { Text(stringResource(R.string.cancel)) } },
    )
    review?.let { approved ->
        AlertDialog(
            onDismissRequest = { review = null },
            title = { Text(stringResource(R.string.inspection_send_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.inspection_send_desc, approved.config.endpoint, approved.config.model))
                    Text(approved.payload, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.inspection_prompt_disclosure), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                review = null; running = true; answer = null; message = null
                task = scope.launch {
                    fun current() = approved.stamp == InspectionRunner.stamp(context) &&
                        InspectionPolicy.fresh(approved.snapshot, System.currentTimeMillis(), approved.stamp.profile, approved.stamp.tunnelUp, approved.stamp.network, approved.stamp.token)
                    try {
                        val output = InspectionAi.analyze(approved.config, approved.payload, ::current)
                        if (current()) { answer = output; answerStamp = approved.stamp }
                        else message = R.string.inspection_failed
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = R.string.inspection_ai_failed }
                    finally { running = false }
                }
            }) { Text(stringResource(R.string.inspection_send)) } },
            dismissButton = { TextButton(onClick = { review = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showConfig) InspectionConfigDialog(config, onDismiss = { showConfig = false }, onSave = { value ->
        scope.launch {
            try {
                withContext(Dispatchers.IO) { if (value == null) InspectionStore.clearConfig(context) else InspectionStore.saveConfig(context, value) }
                showConfig = false; message = null
            } catch (_: Exception) { message = R.string.inspection_save_failed; showConfig = false }
        }
    })
}

@Composable
private fun InspectionConfigDialog(existing: InspectionStore.AiConfig?, onDismiss: () -> Unit, onSave: (InspectionStore.AiConfig?) -> Unit) {
    var endpoint by remember { mutableStateOf(existing?.endpoint.orEmpty()) }
    var model by remember { mutableStateOf(existing?.model.orEmpty()) }
    // Deliberately not saveable: never put credentials in saved instance state or an export.
    var key by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.inspection_model_settings)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.inspection_config_desc))
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.inspection_endpoint)) }, singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.inspection_model)) }, singleLine = true)
            OutlinedTextField(key, { key = it }, label = { Text(stringResource(if (existing == null) R.string.inspection_key else R.string.inspection_key_keep)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.inspection_remove_model)) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(InspectionStore.AiConfig(endpoint.trim(), model.trim(), key.ifBlank { existing?.key?.takeIf { existing.endpoint == endpoint.trim() }.orEmpty() })) }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
