package io.nekohasekai.sfa.compose.screen.profile

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compat.ProfileCodeEditor
import io.nekohasekai.sfa.compat.ProfileEditorColors
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.PullToPopContainer
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.ktx.clipboardText
import io.nekohasekai.sfa.utils.ConfigCompat
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProxyProviderItem
import io.nekohasekai.sfa.utils.ProxyProviders
import io.nekohasekai.sfa.utils.RemoteUrlGuard
import io.nekohasekai.sfa.utils.RuleSetProvider
import io.nekohasekai.sfa.utils.RuleSetProviders
import io.nekohasekai.sfa.utils.SubscriptionInfo
import io.nekohasekai.sfa.utils.SubscriptionInfoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

data class RuleProviderRow(
    val item: RuleSetProvider,
    val updatedAt: Long? = null,
    val entries: Int? = null,
    val busy: Boolean = false,
)

data class ProxyProviderRow(
    val item: ProxyProviderItem,
    val updatedAt: Long? = null,
    val info: SubscriptionInfo? = null,
    val busy: Boolean = false,
)

enum class ProviderViewKind { Entries, Config }

data class ViewPayload(
    val title: String,
    val lines: List<String>,
    val kind: ProviderViewKind = ProviderViewKind.Entries,
    val loading: Boolean = false,
)

data class RuleProvidersUiState(
    val profileName: String = "",
    val rows: List<RuleProviderRow> = emptyList(),
    val proxies: List<ProxyProviderRow> = emptyList(),
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
            val firstLoad = _ui.value.rows.isEmpty() && _ui.value.proxies.isEmpty()
            if (firstLoad) {
                _ui.value = _ui.value.copy(loading = true, error = null)
            }
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
            val info = SubscriptionInfoStore.get(Application.application, loaded.id)
            val rows = RuleSetProviders.parse(content).map { item ->
                val file = resolveRuleFile(working, item)
                val entries = RuleSetProviders.listEntries(file, item.raw)
                val count = entries.size.takeIf { it > 0 }
                    ?: file?.let { RuleSetProviders.sourceRuleCount(it) }
                RuleProviderRow(
                    item = item,
                    updatedAt = file?.lastModified()?.takeIf { it > 0L },
                    entries = count,
                )
            }
            val proxies = ProxyProviders.parse(
                content,
                loaded.name,
                loaded.typed.remoteURL,
            ).map { item ->
                ProxyProviderRow(
                    item = item,
                    updatedAt = loaded.typed.lastUpdated.time.takeIf { it > 0L },
                    info = info,
                )
            }
            _ui.value = RuleProvidersUiState(
                profileName = loaded.name,
                rows = rows,
                proxies = proxies,
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

    fun syncProxy(tag: String, onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            markProxyBusy(tag, true)
            val message = runCatching { syncProxyOne() }.fold(
                onSuccess = { Application.application.getString(R.string.rule_providers_synced) },
                onFailure = {
                    Application.application.getString(R.string.rule_providers_sync_failed) +
                        "：" + (it.message ?: it.toString())
                },
            )
            markProxyBusy(tag, false)
            withContext(Dispatchers.Main) { onDone(message) }
            reload()
        }
    }

    fun syncAll(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            if (_ui.value.proxies.any { it.item.remote && it.item.url.isNotBlank() }) {
                val success = runCatching { syncProxyOne() }.isSuccess
                if (success) ok++ else fail++
            }
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
                if (dest.name.endsWith(".json", true)) {
                    dest.copyTo(RuleSetProviders.sourceCacheFile(workingDir(), tag), overwrite = true)
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

    fun viewRule(tag: String, onReady: (ViewPayload) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val lines = loadRuleEntries(tag)
            withContext(Dispatchers.Main) {
                onReady(
                    ViewPayload(
                        title = tag,
                        lines = lines,
                        kind = ProviderViewKind.Entries,
                    ),
                )
            }
        }
    }

    fun viewProxy(tag: String, onReady: (ViewPayload) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val lines = prettyProfileLines()
            withContext(Dispatchers.Main) {
                onReady(
                    ViewPayload(
                        title = tag,
                        lines = lines,
                        kind = ProviderViewKind.Config,
                    ),
                )
            }
        }
    }

    private fun prettyProfileLines(): List<String> {
        val loaded = profile ?: return emptyList()
        val text = runCatching { File(loaded.typed.path).readText() }.getOrDefault("")
        val pretty = try {
            val trimmed = text.trim()
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> text
            }
        } catch (_: Exception) {
            text
        }
        return pretty.replace("\r\n", "\n").lines()
    }

    private fun loadRuleEntries(tag: String): List<String> {
        val row = _ui.value.rows.firstOrNull { it.item.tag == tag } ?: return emptyList()
        val file = resolveRuleFile(workingDir(), row.item)
        return RuleSetProviders.listEntries(file, row.item.raw)
    }

    private fun resolveRuleFile(working: File, item: RuleSetProvider): File? {
        val cache = RuleSetProviders.cacheFile(working, item.tag, item.format, item.url)
        val json = RuleSetProviders.sourceCacheFile(working, item.tag)
        val local = item.path.takeIf { it.isNotBlank() }?.let { File(it) }
        return listOfNotNull(
            cache.takeIf { it.isFile && it.length() > 0L },
            local?.takeIf { it.isFile && it.length() > 0L },
            json.takeIf { it.isFile && it.length() > 0L },
        ).firstOrNull()
    }

    private suspend fun syncOne(tag: String) {
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

    private suspend fun syncProxyOne() {
        val loaded = profile ?: ProfileManager.get(profileId) ?: error("配置不存在")
        if (loaded.typed.type != TypedProfile.Type.Remote || loaded.typed.remoteURL.isBlank()) {
            error("这个提供者没有远程地址")
        }
        val content = ConfigCompat.sanitize(
            SubscriptionInfoStore.fetchRemote(
                loaded.typed.remoteURL,
                loaded.id,
                Application.application,
            ),
        )
        Libbox.checkConfig(content)
        File(loaded.typed.path).writeText(content)
        loaded.typed.lastUpdated = Date()
        ProfileManager.update(loaded)
        profile = loaded
    }

    private fun markBusy(tag: String, busy: Boolean) {
        _ui.value = _ui.value.copy(
            rows = _ui.value.rows.map { if (it.item.tag == tag) it.copy(busy = busy) else it },
        )
    }

    private fun markProxyBusy(tag: String, busy: Boolean) {
        _ui.value = _ui.value.copy(
            proxies = _ui.value.proxies.map { if (it.item.tag == tag) it.copy(busy = busy) else it },
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
    var viewPayload by remember { mutableStateOf<ViewPayload?>(null) }
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

    val viewing = viewPayload
    if (viewing != null) {
        ProviderViewScreen(
            viewing = viewing,
            onClose = { viewPayload = null },
        )
        return
    }

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
                if (ui.proxies.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.proxy_providers_section),
                            onSync = {
                                viewModel.syncAll { message ->
                                    scope.launch { snackbar.showSnackbar(message) }
                                    notifyApply(io.nekohasekai.sfa.compose.base.UiEvent.ApplyServiceChange.Mode.Reload)
                                }
                            },
                        )
                    }
                    items(ui.proxies, key = { "proxy-${it.item.tag}" }) { row ->
                        ProxyProviderCard(
                            row = row,
                            onView = {
                                viewPayload = ViewPayload(
                                    title = row.item.tag,
                                    lines = emptyList(),
                                    kind = ProviderViewKind.Config,
                                    loading = true,
                                )
                                viewModel.viewProxy(row.item.tag) { viewPayload = it }
                            },
                            onSync = {
                                viewModel.syncProxy(row.item.tag) { message ->
                                    scope.launch { snackbar.showSnackbar(message) }
                                    notifyApply(io.nekohasekai.sfa.compose.base.UiEvent.ApplyServiceChange.Mode.Reload)
                                }
                            },
                        )
                    }
                }
                item {
                    SectionHeader(
                        title = stringResource(R.string.rule_providers_section),
                        onSync = {
                            viewModel.syncAll { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                                notifyApply(io.nekohasekai.sfa.compose.base.UiEvent.ApplyServiceChange.Mode.Reload)
                            }
                        },
                    )
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
                items(ui.rows, key = { "rule-${it.item.tag}" }) { row ->
                    RuleProviderCard(
                        row = row,
                        onUpload = {
                            uploadTag = row.item.tag
                            picker.launch("*/*")
                        },
                        onView = {
                            viewPayload = ViewPayload(
                                title = row.item.tag,
                                lines = emptyList(),
                                kind = ProviderViewKind.Entries,
                                loading = true,
                            )
                            viewModel.viewRule(row.item.tag) { viewPayload = it }
                        },
                        onSync = {
                            viewModel.sync(row.item.tag) { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                                notifyApply(io.nekohasekai.sfa.compose.base.UiEvent.ApplyServiceChange.Mode.Reload)
                            }
                        },
                    )
                }
            }
            if (ui.loading && ui.rows.isEmpty() && ui.proxies.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderViewScreen(
    viewing: ViewPayload,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.rule_providers_view_title, viewing.title)) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                if (!viewing.loading && viewing.lines.isNotEmpty()) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.per_app_proxy_action_copy)) },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            },
                            onClick = {
                                clipboardText = viewing.lines.joinToString("\n")
                                Toast.makeText(
                                    context,
                                    R.string.copied_to_clipboard,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                menu = false
                            },
                        )
                    }
                }
            },
        )
    }
    PullToPopContainer(
        enabled = true,
        releaseHint = stringResource(R.string.pull_release_up),
        onPop = onClose,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(LocalScaffoldPadding.current),
        ) {
            when {
                viewing.loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                viewing.lines.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.rule_providers_no_entries),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                viewing.kind == ProviderViewKind.Config -> {
                    ConfigFileView(text = viewing.lines.joinToString("\n"))
                }
                else -> {
                    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        items(viewing.lines.size) { index ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    text = (index + 1).toString(),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = gutterColor,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(44.dp).padding(end = 10.dp),
                                )
                                Text(
                                    text = viewing.lines[index],
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigFileView(text: String) {
    val colorScheme = MaterialTheme.colorScheme
    val editorColors = remember(colorScheme) {
        ProfileEditorColors(
            background = colorScheme.background.toArgb(),
            foreground = colorScheme.onSurface.toArgb(),
            lineNumber = colorScheme.onSurfaceVariant.toArgb(),
            lineNumberBackground = colorScheme.background.toArgb(),
            selectionBackground = colorScheme.primary.copy(alpha = 0.35f).toArgb(),
            currentLineBackground = colorScheme.surfaceContainer.toArgb(),
            cursor = colorScheme.primary.toArgb(),
            matchedTextBackground = colorScheme.tertiary.copy(alpha = 0.35f).toArgb(),
            comment = colorScheme.onSurfaceVariant.toArgb(),
            key = colorScheme.primary.toArgb(),
            string = colorScheme.tertiary.toArgb(),
            number = colorScheme.secondary.toArgb(),
            literal = colorScheme.primary.toArgb(),
        )
    }
    val holder = remember { arrayOfNulls<ProfileCodeEditor>(1) }
    DisposableEffect(Unit) {
        onDispose { holder[0]?.release() }
    }
    AndroidView(
        factory = { context ->
            ProfileCodeEditor(context).also { created ->
                created.setReadOnly(true)
                created.applyColors(editorColors)
                created.setText(text)
                holder[0] = created
            }.view
        },
        update = {
            val current = holder[0] ?: return@AndroidView
            current.applyColors(editorColors)
            if (current.getText() != text) current.setText(text)
        },
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
    )
}

@Composable
private fun SectionHeader(title: String, onSync: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onSync) {
            Icon(
                Icons.Default.CloudSync,
                contentDescription = stringResource(R.string.overlay_scripts_sync),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleProviderCard(
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
    ProviderCardFrame(
        title = row.item.tag,
        subtitle = listOfNotNull(time, count).joinToString(" · "),
        busy = row.busy,
        showUpload = true,
        canSync = row.item.url.isNotBlank(),
        onUpload = onUpload,
        onView = onView,
        onSync = onSync,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProxyProviderCard(
    row: ProxyProviderRow,
    onView: () -> Unit,
    onSync: () -> Unit,
) {
    val context = LocalContext.current
    val info = row.info
    val time = row.updatedAt?.let { RelativeTimeFormatter.format(context, Date(it)) }
    val count = context.getString(R.string.rule_providers_entries, row.item.entries.size)
    val traffic = when {
        info == null -> null
        info.hasQuota -> context.getString(
            R.string.profile_traffic_used,
            info.usedLabel(),
            info.totalLabel(),
        )
        else -> context.getString(R.string.profile_traffic_unlimited)
    }
    val expire = info?.expireLabel()
    val title = if (expire.isNullOrBlank()) row.item.tag else "${row.item.tag}  ·  $expire"
    ProviderCardFrame(
        title = title,
        subtitle = listOfNotNull(traffic, time, count).joinToString(" · "),
        progress = info?.takeIf { it.hasQuota }?.progress,
        busy = row.busy,
        showUpload = false,
        canSync = row.item.remote && row.item.url.isNotBlank(),
        onUpload = {},
        onView = onView,
        onSync = onSync,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCardFrame(
    title: String,
    subtitle: String,
    progress: Float? = null,
    busy: Boolean,
    showUpload: Boolean,
    canSync: Boolean,
    onUpload: () -> Unit,
    onView: () -> Unit,
    onSync: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showUpload) {
                    FilledTonalButton(onClick = onUpload, enabled = !busy) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.rule_providers_upload))
                    }
                }
                FilledTonalButton(onClick = onView, enabled = !busy) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.rule_providers_view))
                }
                FilledTonalButton(
                    onClick = onSync,
                    enabled = !busy && canSync,
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.overlay_scripts_sync))
                }
            }
        }
    }
}
