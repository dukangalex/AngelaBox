package io.nekohasekai.sfa.compose.screen.tools

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compat.menuAnchorCompat
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.PullToPopContainer
import io.nekohasekai.sfa.compose.navigation.popToDashboard
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.OverlayScript
import io.nekohasekai.sfa.utils.OverlayScripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptListScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    var scripts by remember {
        val sample = runCatching {
            context.assets.open(OverlayScripts.SAMPLE_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (!sample.isNullOrBlank()) OverlayScripts.refreshStaleSample(sample)
        val airport = runCatching {
            context.assets.open(OverlayScripts.AIRPORT_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (!airport.isNullOrBlank()) OverlayScripts.refreshStaleAirport(airport)
        mutableStateOf(OverlayScripts.list())
    }
    var showImport by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var urlDraft by remember { mutableStateOf<Pair<String, String>?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<OverlayScript?>(null) }
    var syncingId by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var bindProfileId by remember { mutableStateOf(-1L) }
    var bindMenu by remember { mutableStateOf(false) }
    var bindTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { ProfileManager.list() }
        profiles = list
        bindProfileId = Settings.selectedProfile.takeIf { id -> list.any { it.id == id } }
            ?: list.firstOrNull()?.id
            ?: -1L
    }

    fun reloadService() {
        scope.launch { notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload) }
    }

    fun upsert(script: OverlayScript, reload: Boolean = true) {
        try {
            OverlayScripts.upsert(script)
            scripts = OverlayScripts.list()
            if (reload) reloadService()
        } catch (e: Exception) {
            scope.launch {
                snackbar.showSnackbar(e.message ?: context.getString(R.string.overlay_scripts_failed))
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            if (text.isNullOrBlank()) {
                snackbar.showSnackbar(context.getString(R.string.overlay_scripts_empty_file))
                return@launch
            }
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.removeSuffix(".js")
                ?.removeSuffix(".json")
                ?: context.getString(R.string.overlay_scripts_imported)
            upsert(
                OverlayScript(
                    id = OverlayScripts.newId(),
                    name = name,
                    enabled = true,
                    source = OverlayScripts.SOURCE_FILE,
                    code = text,
                ),
            )
        }
    }

    val edit = editor
    if (edit != null) {
        ScriptEditorPane(
            state = edit,
            onChange = { editor = it },
            onSave = {
                val current = editor
                if (current != null) {
                    upsert(
                        OverlayScript(
                            id = current.id,
                            name = current.name.ifBlank { context.getString(R.string.overlay_scripts_imported) },
                            enabled = scripts.firstOrNull { it.id == current.id }?.enabled ?: true,
                            source = current.source,
                            url = current.url,
                            code = current.code,
                        ),
                    )
                    editor = null
                }
            },
            onCancel = { editor = null },
        )
        return
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.overlay_scripts)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = { navController.popToDashboard() }) {
                    Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.action_home))
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalScaffoldPadding.current),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.overlay_scripts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            if (profiles.isNotEmpty() && scripts.isNotEmpty()) {
                item {
                    val bindProfile = profiles.find { it.id == bindProfileId } ?: profiles.first()
                    val boundIds = remember(bindProfile.id, bindTick, scripts) {
                        OverlayScripts.selectedIds(bindProfile.id).orEmpty()
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.overlay_scripts_profile_enable),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            ExposedDropdownMenuBox(
                                expanded = bindMenu,
                                onExpandedChange = { bindMenu = it },
                            ) {
                                OutlinedTextField(
                                    value = bindProfile.name,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bindMenu) },
                                    modifier = Modifier
                                        .then(menuAnchorCompat(true))
                                        .fillMaxWidth(),
                                    label = { Text(stringResource(R.string.title_configuration)) },
                                )
                                ExposedDropdownMenu(
                                    expanded = bindMenu,
                                    onDismissRequest = { bindMenu = false },
                                ) {
                                    profiles.forEach { profile ->
                                        DropdownMenuItem(
                                            text = { Text(profile.name) },
                                            onClick = {
                                                bindMenu = false
                                                bindProfileId = profile.id
                                            },
                                        )
                                    }
                                }
                            }
                            scripts.forEach { script ->
                                ListItem(
                                    headlineContent = { Text(script.name) },
                                    supportingContent = {
                                        Text(
                                            if (script.id in boundIds && script.enabled) {
                                                stringResource(R.string.overlay_scripts_bound_on)
                                            } else {
                                                stringResource(R.string.overlay_scripts_bound_off)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = script.enabled && script.id in boundIds,
                                            onCheckedChange = { on ->
                                                val current = OverlayScripts.selectedIds(bindProfile.id)
                                                    .orEmpty()
                                                    .toMutableList()
                                                if (on) {
                                                    if (script.id !in current) current += script.id
                                                    if (!script.enabled) OverlayScripts.toggle(script.id, true)
                                                } else {
                                                    current.remove(script.id)
                                                }
                                                OverlayScripts.setBinding(bindProfile.id, current)
                                                scripts = OverlayScripts.list()
                                                bindTick++
                                                if (bindProfile.id == Settings.selectedProfile) reloadService()
                                            },
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                        }
                    }
                }
            }
            if (scripts.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.overlay_scripts_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            } else {
                items(scripts, key = { it.id }) { script ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(script.name, style = MaterialTheme.typography.bodyLarge)
                            },
                            supportingContent = {
                                Text(
                                    text = when (script.source) {
                                        OverlayScripts.SOURCE_URL -> script.url.ifBlank {
                                            stringResource(R.string.overlay_scripts_from_url)
                                        }
                                        OverlayScripts.SOURCE_FILE -> stringResource(R.string.overlay_scripts_from_file)
                                        OverlayScripts.SOURCE_SAMPLE -> stringResource(R.string.overlay_scripts_from_sample)
                                        OverlayScripts.SOURCE_AIRPORT -> stringResource(R.string.overlay_scripts_from_airport)
                                        else -> stringResource(R.string.overlay_scripts_from_code)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                Switch(
                                    checked = script.enabled,
                                    onCheckedChange = { on ->
                                        OverlayScripts.toggle(script.id, on)
                                        scripts = OverlayScripts.list()
                                        reloadService()
                                    },
                                )
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menuFor = script.id }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                                    }
                                    DropdownMenu(
                                        expanded = menuFor == script.id,
                                        onDismissRequest = { menuFor = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.edit)) },
                                            onClick = {
                                                menuFor = null
                                                editor = EditorState(
                                                    script.id,
                                                    script.name,
                                                    script.code,
                                                    script.source,
                                                    script.url,
                                                )
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.overlay_scripts_duplicate)) },
                                            onClick = {
                                                menuFor = null
                                                upsert(
                                                    script.copy(
                                                        id = OverlayScripts.newId(),
                                                        name = script.name + " 副本",
                                                        enabled = false,
                                                        source = OverlayScripts.SOURCE_CODE,
                                                    ),
                                                    reload = false,
                                                )
                                            },
                                        )
                                        if (script.source == OverlayScripts.SOURCE_URL && script.url.isNotBlank()) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.overlay_scripts_sync)) },
                                                enabled = syncingId != script.id,
                                                onClick = {
                                                    menuFor = null
                                                    syncingId = script.id
                                                    scope.launch {
                                                        try {
                                                            val body = withContext(Dispatchers.IO) {
                                                                HTTPClient().use { it.getString(script.url, io.nekohasekai.sfa.utils.RemoteUrlGuard.Kind.SCRIPT) }
                                                            }
                                                            if (body.isBlank()) {
                                                                snackbar.showSnackbar(
                                                                    context.getString(R.string.overlay_scripts_empty_file),
                                                                )
                                                            } else {
                                                                upsert(
                                                                    script.copy(
                                                                        code = body,
                                                                        updatedAt = System.currentTimeMillis(),
                                                                    ),
                                                                )
                                                                snackbar.showSnackbar(
                                                                    context.getString(R.string.overlay_scripts_synced),
                                                                )
                                                            }
                                                        } catch (e: Exception) {
                                                            snackbar.showSnackbar(
                                                                e.message
                                                                    ?: context.getString(R.string.overlay_scripts_sync_failed),
                                                            )
                                                        } finally {
                                                            syncingId = null
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_delete)) },
                                            onClick = {
                                                menuFor = null
                                                pendingDelete = script
                                            },
                                        )
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showImport = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.overlay_scripts_import))
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showImport) {
        ModalBottomSheet(
            onDismissRequest = { showImport = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(bottom = 28.dp)) {
                Text(
                    text = stringResource(R.string.overlay_scripts_import),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                ImportRow(Icons.Outlined.Code, stringResource(R.string.overlay_scripts_import_code)) {
                    showImport = false
                    editor = EditorState(
                        id = OverlayScripts.newId(),
                        name = "",
                        code = "function main(config) {\n  return config;\n}\n",
                        source = OverlayScripts.SOURCE_CODE,
                    )
                }
                ImportRow(Icons.Outlined.Link, stringResource(R.string.overlay_scripts_import_url)) {
                    showImport = false
                    urlDraft = "" to ""
                }
                ImportRow(Icons.Outlined.Description, stringResource(R.string.overlay_scripts_import_file)) {
                    showImport = false
                    pickFile.launch(arrayOf("text/*", "application/javascript", "application/json", "*/*"))
                }
                ImportRow(Icons.Outlined.Science, stringResource(R.string.overlay_scripts_import_sample)) {
                    showImport = false
                    scope.launch {
                        val sample = withContext(Dispatchers.IO) {
                            runCatching {
                                context.assets.open(OverlayScripts.SAMPLE_ASSET).bufferedReader().use { it.readText() }
                            }.getOrNull()
                        }
                        if (sample.isNullOrBlank()) {
                            snackbar.showSnackbar(context.getString(R.string.overlay_scripts_empty_file))
                            return@launch
                        }
                        try {
                            OverlayScripts.upsertSample(sample)
                            scripts = OverlayScripts.list()
                            reloadService()
                        } catch (e: Exception) {
                            snackbar.showSnackbar(
                                e.message ?: context.getString(R.string.overlay_scripts_failed),
                            )
                        }
                    }
                }
                ImportRow(Icons.Outlined.Code, stringResource(R.string.overlay_scripts_import_airport)) {
                    showImport = false
                    scope.launch {
                        val code = withContext(Dispatchers.IO) {
                            runCatching {
                                context.assets.open(OverlayScripts.AIRPORT_ASSET).bufferedReader().use { it.readText() }
                            }.getOrNull()
                        }
                        if (code.isNullOrBlank()) {
                            snackbar.showSnackbar(context.getString(R.string.overlay_scripts_empty_file))
                            return@launch
                        }
                        try {
                            OverlayScripts.upsertAirport(code)
                            scripts = OverlayScripts.list()
                            reloadService()
                            snackbar.showSnackbar(context.getString(R.string.overlay_scripts_airport_hint))
                        } catch (e: Exception) {
                            snackbar.showSnackbar(
                                e.message ?: context.getString(R.string.overlay_scripts_failed),
                            )
                        }
                    }
                }
            }
        }
    }

    val urlState = urlDraft
    if (urlState != null) {
        AlertDialog(
            onDismissRequest = { urlDraft = null },
            title = { Text(stringResource(R.string.overlay_scripts_import_url)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = urlState.first,
                        onValueChange = { urlDraft = it to urlState.second },
                        label = { Text(stringResource(R.string.profile_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = urlState.second,
                        onValueChange = { urlDraft = urlState.first to it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = urlState.first
                        val url = urlState.second.trim()
                        urlDraft = null
                        scope.launch {
                            try {
                                val body = withContext(Dispatchers.IO) {
                                    HTTPClient().use { it.getString(url, io.nekohasekai.sfa.utils.RemoteUrlGuard.Kind.SCRIPT) }
                                }
                                if (body.isBlank()) {
                                    snackbar.showSnackbar(context.getString(R.string.overlay_scripts_empty_file))
                                    return@launch
                                }
                                val fallback = url.substringAfterLast('/').substringBefore('?')
                                    .ifBlank { context.getString(R.string.overlay_scripts_imported) }
                                upsert(
                                    OverlayScript(
                                        id = OverlayScripts.newId(),
                                        name = name.ifBlank { fallback },
                                        enabled = true,
                                        source = OverlayScripts.SOURCE_URL,
                                        url = url,
                                        code = body,
                                    ),
                                )
                            } catch (e: Exception) {
                                snackbar.showSnackbar(
                                    e.message ?: context.getString(R.string.overlay_scripts_failed),
                                )
                            }
                        }
                    },
                    enabled = urlState.second.startsWith("https://"),
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { urlDraft = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.menu_delete)) },
            text = { Text(deleteTarget.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        OverlayScripts.remove(deleteTarget.id)
                        scripts = OverlayScripts.list()
                        pendingDelete = null
                        reloadService()
                    },
                ) { Text(stringResource(R.string.menu_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptEditorPane(
    state: EditorState,
    onChange: (EditorState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.overlay_scripts_edit)) },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                TextButton(onClick = onSave) { Text(stringResource(R.string.save)) }
            },
        )
    }
    PullToPopContainer(
        enabled = true,
        releaseHint = stringResource(R.string.pull_release_up),
        onPop = onCancel,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(LocalScaffoldPadding.current)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = { onChange(state.copy(name = it)) },
                label = { Text(stringResource(R.string.profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.code,
                onValueChange = { onChange(state.copy(code = it)) },
                label = { Text(stringResource(R.string.overlay_scripts_code)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun ImportRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private data class EditorState(
    val id: String,
    val name: String,
    val code: String,
    val source: String,
    val url: String = "",
)
