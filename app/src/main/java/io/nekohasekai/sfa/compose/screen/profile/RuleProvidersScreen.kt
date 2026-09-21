package io.nekohasekai.sfa.compose.screen.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.PullToPopContainer
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteUrlGuard
import io.nekohasekai.sfa.utils.RuleSetProvider
import io.nekohasekai.sfa.utils.RuleSetProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Date

data class RuleProviderRow(
    val item: RuleSetProvider,
    val updatedAt: Long? = null,
    val entries: Int? = null,
    val busy: Boolean = false,
)

data class RuleProvidersUiState(
    val profileName: String = "",
    val rows: List<RuleProviderRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class RuleProvidersViewModel(private val profileId: Long) : ViewModel() {
    private val _ui = MutableStateFlow(RuleProvidersUiState())
    val ui: StateFlow<RuleProvidersUiState> = _ui.asStateFlow()
    private var profile: Profile? = null

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val loaded = ProfileManager.get(profileId)
            if (loaded == null) {
                _ui.value = RuleProvidersUiState(loading = false, error = "配置不存在")
                return@launch
            }
            profile = loaded
            val content = runCatching { File(loaded.typed.path).readText() }.getOrElse {
                _ui.value = RuleProvidersUiState(
                    profileName = loaded.name,
                    loading = false,
                    error = it.message,
                )
                return@launch
            }
            val working = workingDir()
            val rows = RuleSetProviders.parse(content).map { item ->
                val cache = RuleSetProviders.cacheFile(working, item.tag, item.format, item.url)
                val local = item.path.takeIf { it.isNotBlank() }?.let { File(it) }
                val file = when {
                    local?.isFile == true -> local
                    cache.isFile -> cache
                    else -> null
                }
                RuleProviderRow(
                    item = item,
                    updatedAt = file?.lastModified()?.takeIf { it > 0L },
                    entries = file?.let { RuleSetProviders.sourceRuleCount(it) },
                )
            }
            _ui.value = RuleProvidersUiState(
                profileName = loaded.name,
                rows = rows,
                loading = false,
            )
        }
    }

    fun sync(tag: String, onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            markBusy(tag, true)
            val message = runCatching { syncOne(tag) }.fold(
                onSuccess = { Application.application.getString(R.string.rule_providers_synced) },
                onFailure = {
                    Application.application.getString(R.string.rule_providers_sync_failed) +
                        "：" + (it.message ?: it.toString())
                },
            )
            markBusy(tag, false)
            withContext(Dispatchers.Main) { onDone(message) }
            reload()
        }
    }

    fun syncAll(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            _ui.value.rows.filter { it.item.remote && it.item.url.isNotBlank() }.forEach { row ->
                markBusy(row.item.tag, true)
                val success = runCatching { syncOne(row.item.tag) }.isSuccess
                if (success) ok++ else fail++
                markBusy(row.item.tag, false)
            }
            withContext(Dispatchers.Main) {
                onDone(
                    if (fail == 0) {
                        Application.application.getString(R.string.rule_providers_synced)
                    } else {
                        Application.application.getString(R.string.rule_providers_sync_failed) +
                            "（$ok / ${ok + fail}）"
                    },
                )
            }
            reload()
        }
    }

    fun upload(tag: String, uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            markBusy(tag, true)
            val message = runCatching {
                val loaded = profile ?: ProfileManager.get(profileId) ?: error("配置不存在")
                val content = File(loaded.typed.path).readText()
                val root = JSONObject(content)
                val item = _ui.value.rows.firstOrNull { it.item.tag == tag }?.item
                    ?: error("找不到规则集")
                val dest = RuleSetProviders.cacheFile(workingDir(), tag, item.format, item.url)
                dest.parentFile?.mkdirs()
                Application.application.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取文件")
                if (!dest.isFile || dest.length() == 0L) {
                    dest.delete()
                    error("空文件")
                }
                RuleSetProviders.updateItem(root, tag) { current ->
                    current.put("type", "local")
                    current.put("path", dest.absolutePath)
                    current.remove("url")
                    current.remove("download_url")
                    current.remove("initial_path")
                    if (current.optString("format").isBlank()) {
                        current.put(
                            "format",
                            if (dest.name.endsWith(".json", true)) "source" else "binary",
                        )
                    }
                }
                File(loaded.typed.path).writeText(root.toString())
            }.fold(
                onSuccess = { Application.application.getString(R.string.rule_providers_uploaded) },
                onFailure = {
                    Application.application.getString(R.string.rule_providers_sync_failed) +
                        "：" + (it.message ?: it.toString())
                },
            )
            markBusy(tag, false)
            withContext(Dispatchers.Main) { onDone(message) }
            reload()
        }
    }

    fun viewJson(tag: String): String {
        val row = _ui.value.rows.firstOrNull { it.item.tag == tag } ?: return ""
        return RuleSetProviders.pretty(row.item.raw)
    }

    private fun syncOne(tag: String) {
        val loaded = profile ?: ProfileManager.get(profileId) ?: error("配置不存在")
        val content = File(loaded.typed.path).readText()
        val root = JSONObject(content)
        val item = RuleSetProviders.parse(content).firstOrNull { it.tag == tag }
            ?: error("找不到规则集")
        if (item.url.isBlank()) error("这个规则集没有远程地址")
        val dest = RuleSetProviders.cacheFile(workingDir(), tag, item.format, item.url)
        HTTPClient().use { client ->
            client.downloadToFile(item.url, RemoteUrlGuard.Kind.SUBSCRIPTION, dest)
        }
        RuleSetProviders.updateItem(root, tag) { current ->
            current.put("initial_path", dest.absolutePath)
        }
        File(loaded.typed.path).writeText(root.toString())
    }

    private fun markBusy(tag: String, busy: Boolean) {
        _ui.value = _ui.value.copy(
            rows = _ui.value.rows.map { if (it.item.tag == tag) it.copy(busy = busy) else it },
        )
    }

    private fun workingDir(): File =
        Application.application.getExternalFilesDir(null) ?: Application.application.filesDir

    class Factory(private val profileId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RuleProvidersViewModel(profileId) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleProvidersScreen(
    profileId: Long,
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    val viewModel: RuleProvidersViewModel = viewModel(factory = RuleProvidersViewModel.Factory(profileId))
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notifyApply = rememberApplyServiceChangeNotifier(serviceStatus)
    var viewJson by remember { mutableStateOf<String?>(null) }
    var uploadTag by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val tag = uploadTag
        uploadTag = null
        if (uri != null && tag != null) {
            viewModel.upload(tag, uri) { message ->
                scope.launch { snackbar.showSnackbar(message) }
                notifyApply(io.nekohasekai.sfa.compose.base.UiEvent.ApplyServiceChange.Mode.Reload)
            }
        }
    }

    LaunchedEffect(profileId) { viewModel.reload() }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.rule_providers)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = {
                    viewModel.syncAll { message ->
                        scope.launch { snackbar.showSnackbar(message) }
                        notifyApply(io.nekohasekai.sfa.compose.base.UiEvent.ApplyServiceChange.Mode.Reload)
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.overlay_scripts_sync))
                }
            },
        )
    }

    PullToPopContainer(
        enabled = true,
        releaseHint = stringResource(R.string.pull_release_up),
        onPop = { navController.navigateUp() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(LocalScaffoldPadding.current),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.rule_providers_section),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = {
                            viewModel.syncAll { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                                notifyApply(io.nekohasekai.sfa.compose.base.UiEvent.ApplyServiceChange.Mode.Reload)
                            }
                        }) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = stringResource(R.string.overlay_scripts_sync),
                            )
                        }
                    }
                }
                if (ui.rows.isEmpty() && !ui.loading) {
                    item {
                        Text(
                            text = ui.error ?: stringResource(R.string.rule_providers_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(ui.rows, key = { it.item.tag }) { row ->
                    ProviderCard(
                        row = row,
                        onUpload = {
                            uploadTag = row.item.tag
                            picker.launch("*/*")
                        },
                        onView = { viewJson = viewModel.viewJson(row.item.tag) },
                        onSync = {
                            viewModel.sync(row.item.tag) { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                                notifyApply(io.nekohasekai.sfa.compose.base.UiEvent.ApplyServiceChange.Mode.Reload)
                            }
                        },
                    )
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }

    val json = viewJson
    if (json != null) {
        AlertDialog(
            onDismissRequest = { viewJson = null },
            title = { Text(stringResource(R.string.rule_providers_view)) },
            text = {
                SelectionContainer {
                    Text(
                        text = json,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewJson = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    row: RuleProviderRow,
    onUpload: () -> Unit,
    onView: () -> Unit,
    onSync: () -> Unit,
) {
    val context = LocalContext.current
    val time = row.updatedAt?.let { RelativeTimeFormatter.format(context, Date(it)) }
        ?: stringResource(R.string.rule_providers_never)
    val count = row.entries?.let { context.getString(R.string.rule_providers_entries, it) }
        ?: if (row.item.format.equals("binary", true) || row.item.url.endsWith(".srs")) {
            stringResource(R.string.rule_providers_binary)
        } else {
            null
        }
    val subtitle = listOfNotNull(time, count).joinToString(" · ")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(row.item.tag, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onUpload, enabled = !row.busy) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.rule_providers_upload))
                }
                FilledTonalButton(onClick = onView, enabled = !row.busy) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.rule_providers_view))
                }
                FilledTonalButton(
                    onClick = onSync,
                    enabled = !row.busy && row.item.url.isNotBlank(),
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.overlay_scripts_sync))
                }
            }
        }
    }
}