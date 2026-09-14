package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.chain.ChainBinding
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.compat.menuAnchorCompat
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.OverlayScripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

private data class HopRef(
    val profileId: Long,
    val profileName: String,
    val tag: String,
    val type: String,
) {
    val typeLabel: String
        get() = when (type) {
            "urltest" -> "自动优选分组"
            "selector" -> "手动选择分组"
            else -> "节点"
        }
    val displayLine: String get() = "$profileName / $tag · $typeLabel"
}

private data class ProfileChoice(val id: Long, val name: String, val hops: List<HopRef>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainBuilderScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    var currentProfileId by remember { mutableStateOf(-1L) }
    var currentProfileName by remember { mutableStateOf("") }
    var currentProfilePath by remember { mutableStateOf<String?>(null) }
    var currentHops by remember { mutableStateOf<List<HopRef>>(emptyList()) }
    var allProfiles by remember { mutableStateOf<List<ProfileChoice>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf<HopRef?>(null) }
    var exit by remember { mutableStateOf<HopRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    var chainActive by remember { mutableStateOf(false) }
    var otherBound by remember { mutableStateOf(0) }
    var otherBoundLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var showOtherBound by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var pickerQuery by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    var barProfileMenu by remember { mutableStateOf(false) }

    fun reload(targetId: Long = -1L) {
        val fallbackId = if (targetId > 0L) targetId else currentProfileId
        scope.launch(Dispatchers.IO) {
            try {
                val profiles = ProfileManager.list()
                val wantId = if (fallbackId > 0L) fallbackId else Settings.selectedProfile
                val current = profiles.find { it.id == wantId } ?: profiles.find { it.id == Settings.selectedProfile }
                if (current == null) {
                    withContext(Dispatchers.Main) { loadError = "未选择配置" }
                    return@launch
                }
                val content = File(current.typed.path).readText()
                val root = ChainRuntimeCompiler.parseConfig(content)
                val routeFinal = root.optJSONObject("route")?.optString("final")?.trim().orEmpty()
                val hops = ChainRuntimeCompiler.listSelectableHops(content, current.id, current.name).map {
                    HopRef(it.profileId, it.profileName, it.tag, it.type)
                }
                val suggested = ChainRuntimeCompiler.resolveMainTag(
                    root.optJSONArray("outbounds") ?: JSONArray(),
                    routeFinal,
                )
                val others = profiles.mapNotNull { p ->
                    val parsed = parseHopsFromProfile(p)
                    if (parsed.isEmpty()) null else ProfileChoice(p.id, p.name, parsed)
                }
                val binding = ChainBindings.get(current.id)
                val othersBound = ChainBindings.all().filter { it.profileId != current.id }
                val othersBoundLines = othersBound.map { b ->
                    val src = profiles.find { it.id == b.profileId }?.name
                        ?.ifBlank { null } ?: "配置 ${b.profileId}"
                    val dstName = profiles.find { it.id == b.landingProfileId }?.name
                        ?.ifBlank { null }
                    val land = if (dstName != null) "$dstName / ${b.landingTag}" else b.landingTag
                    "$src  →  $land"
                }
                withContext(Dispatchers.Main) {
                    currentProfileId = current.id
                    currentProfileName = current.name
                    currentProfilePath = current.typed.path
                    currentHops = hops
                    allProfiles = others
                    loadError = null
                    otherBound = othersBound.size
                    otherBoundLines = othersBoundLines
                    chainActive = binding != null
                    val savedEntry = binding?.entryTag.orEmpty()
                    entry = hops.find { it.tag == savedEntry }
                        ?: hops.find { it.tag == suggested }
                        ?: hops.firstOrNull { !ChainRuntimeCompiler.isFinalLike(it.tag) }
                    if (binding != null) {
                        val found = others.flatMap { it.hops }.find {
                            it.profileId == binding.landingProfileId && it.tag == binding.landingTag
                        }
                        exit = found ?: HopRef(
                            binding.landingProfileId,
                            profiles.find { it.id == binding.landingProfileId }?.name ?: "落地",
                            binding.landingTag,
                            "selector",
                        )
                        savedHint = "仅当前配置：${entry?.tag ?: "?"} → ${exit?.displayLine}"
                    } else {
                        exit = null
                        savedHint = null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loadError = e.message ?: "配置读取失败" }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun save() {
        val landing = exit ?: run {
            scope.launch { snackbar.showSnackbar("请先选择落地代理") }
            return
        }
        val main = entry ?: run {
            scope.launch { snackbar.showSnackbar("请先选择入口分组或节点") }
            return
        }
        if (landing.profileId == currentProfileId && landing.tag == main.tag) {
            scope.launch { snackbar.showSnackbar("入口与落地不能是同一个 outbound") }
            return
        }
        val path = currentProfilePath ?: return
        val boundId = currentProfileId
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    val raw = file.readText()
                    val originalFinal = ChainRuntimeCompiler.parseConfig(raw).optJSONObject("route")
                        ?.optString("final")
                        ?.takeIf { it.isNotBlank() && !it.startsWith(ChainRuntimeCompiler.GENERATED_PREFIX) }
                    val landingContent = if (landing.profileId == boundId) {
                        null
                    } else {
                        val landingProfile = ProfileManager.get(landing.profileId)
                            ?: error("落地配置不存在")
                        File(landingProfile.typed.path).readText()
                    }
                    // apply() is validation-only here: it throws if entry/landing
                    // is illegal. The profile JSON stays clean (clear() writes it
                    // back). Runtime chaining is deferred to ConfigChainReapply.
                    ChainRuntimeCompiler.apply(
                        ChainRuntimeCompiler.ApplyRequest(
                            content = raw,
                            currentProfileId = boundId,
                            entryTag = main.tag,
                            landingProfileId = landing.profileId,
                            landingTag = landing.tag,
                            landingContent = landingContent,
                        ),
                    )
                    file.writeText(ChainRuntimeCompiler.clear(raw, originalFinal))
                    ChainBindings.put(
                        ChainBinding(
                            profileId = boundId,
                            entryTag = main.tag,
                            landingProfileId = landing.profileId,
                            landingTag = landing.tag,
                        ),
                    )
                    OverlayScripts.setBinding(boundId, emptyList())
                }
            }
            busy = false
            if (result.isSuccess) {
                chainActive = true
                savedHint = "仅当前配置：${main.tag} → ${landing.displayLine}"
                if (boundId == Settings.selectedProfile) {
                    notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                }
                if (!navController.popBackStack("dashboard", false)) {
                    navController.popBackStack()
                }
            } else {
                snackbar.showSnackbar("保存失败：${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun clearChain() {
        val path = currentProfilePath ?: return
        val boundId = currentProfileId
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    val root = ChainRuntimeCompiler.parseConfig(file.readText())
                    val final = ChainRuntimeCompiler.resolveMainTag(root.optJSONArray("outbounds") ?: JSONArray(), "")
                    file.writeText(ChainRuntimeCompiler.clear(root.toString(), final))
                    ChainBindings.remove(boundId)
                }
            }
            busy = false
            if (result.isSuccess) {
                exit = null
                chainActive = false
                savedHint = "已取消「$currentProfileName」的链式，其他配置保留各自绑定"
                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                snackbar.showSnackbar("已取消当前配置的链式代理")
            } else {
                snackbar.showSnackbar("取消失败：${result.exceptionOrNull()?.message}")
            }
        }
    }

    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chain_builder)) },
                navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (allProfiles.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { barProfileMenu = true }) {
                                Text(
                                    currentProfileName.ifBlank { stringResource(R.string.title_configuration) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 140.dp),
                                )
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = barProfileMenu,
                                onDismissRequest = { barProfileMenu = false },
                            ) {
                                allProfiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(profile.name) },
                                        onClick = {
                                            barProfileMenu = false
                                            if (profile.id != currentProfileId) {
                                                currentProfileId = profile.id
                                                entry = null
                                                exit = null
                                                reload(profile.id)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Default.Info, stringResource(R.string.read_more)) }
                    IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_reload)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (loadError != null) {
                Text("加载失败: $loadError", color = MaterialTheme.colorScheme.error)
            }
            Card(colors = cardColors, shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("绑定到哪一份配置", fontWeight = FontWeight.Medium)
                    ExposedDropdownMenuBox(
                        expanded = profileMenu,
                        onExpandedChange = { profileMenu = it },
                    ) {
                        OutlinedTextField(
                            value = currentProfileName.ifBlank { "选择配置" },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileMenu) },
                            modifier = Modifier
                                .then(menuAnchorCompat(true))
                                .fillMaxWidth(),
                            label = { Text("配置") },
                        )
                        ExposedDropdownMenu(
                            expanded = profileMenu,
                            onDismissRequest = { profileMenu = false },
                        ) {
                            allProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = {
                                        profileMenu = false
                                        if (profile.id != currentProfileId) {
                                            currentProfileId = profile.id
                                            entry = null
                                            exit = null
                                            reload(profile.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    PathPreview(entry = entry, landing = exit)
                    savedHint?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (otherBound > 0) {
                OutlinedButton(
                    onClick = { showOtherBound = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("另有 $otherBound 个配置已绑定落地，点此查看是哪几个")
                }
            }
            Text("入口（当前配置）", fontWeight = FontWeight.Medium)
            HopPickCard(
                title = entry?.let { "${it.tag} · ${it.typeLabel}" } ?: "选择入口",
                type = entry?.type,
                enabled = !busy,
                onClick = { picker = "entry"; pickerQuery = "" },
            )
            Text("落地（当前或其他配置）", fontWeight = FontWeight.Medium)
            HopPickCard(
                title = exit?.displayLine ?: "选择落地",
                type = exit?.type,
                enabled = !busy,
                onClick = { picker = "landing"; pickerQuery = "" },
            )
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && entry != null && exit != null,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("保存并绑定到「$currentProfileName」")
            }
            OutlinedButton(
                onClick = { clearChain() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && chainActive,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("取消当前配置的链式")
            }
        }
    }

    if (picker != null) {
        val source = if (picker == "entry") {
            currentHops
        } else {
            allProfiles.flatMap { it.hops }.filter { hop ->
                !(hop.profileId == currentProfileId && hop.tag == entry?.tag)
            }
        }
        val q = pickerQuery.trim().lowercase()
        val filtered = if (q.isEmpty()) source else source.filter {
            it.tag.lowercase().contains(q) || it.profileName.lowercase().contains(q)
        }
        val grouped = if (picker == "landing") {
            val currentFirst = filtered.filter { it.profileId == currentProfileId }
            val rest = filtered.filter { it.profileId != currentProfileId }.groupBy { it.profileId to it.profileName }
            buildList {
                if (currentFirst.isNotEmpty()) add(currentProfileName to currentFirst)
                allProfiles.filter { it.id != currentProfileId }.forEach { p ->
                    rest[p.id to p.name]?.let { add(p.name to it) }
                }
            }
        } else {
            listOf("" to filtered)
        }
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (picker == "entry") "选择入口" else "选择落地") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        label = { Text("搜索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (pickerQuery.isNotEmpty()) IconButton(onClick = { pickerQuery = "" }) { Icon(Icons.Default.Clear, null) }
                        },
                    )
                    if (filtered.isEmpty()) {
                        Text("没有可选项。请先导入含节点的配置。", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        grouped.forEach { (groupName, hops) ->
                            if (picker == "landing" && groupName.isNotEmpty()) {
                                item(key = "hdr-$groupName") {
                                    Text(
                                        groupName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                            }
                            items(hops, key = { "${it.profileId}:${it.tag}" }) { hop ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        if (picker == "entry") entry = hop else exit = hop
                                        picker = null
                                    }.padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        if (picker == "entry") "${hop.tag} · ${hop.typeLabel}" else "${hop.tag} · ${hop.typeLabel}",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("关闭") } },
        )
    }

    if (showOtherBound) {
        AlertDialog(
            onDismissRequest = { showOtherBound = false },
            title = { Text("其他配置的链式绑定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "每份配置独立保存落地，互不影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    otherBoundLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOtherBound = false }) { Text("关闭") }
            },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("链式代理说明") },
            text = {
                Text(
                    "1. 入口：当前配置里流量先走的分组或节点（前置）。不要依赖「漏网之鱼」。前置只作为链式第一跳，不会单独成为出口。\n" +
                        "2. 落地：下一跳，出口 IP 应该是落地节点，不是前置。可来自当前或其他配置。\n" +
                        "3. 保存后只绑定当前配置。每个配置可以各绑不同落地。切换配置时各自使用自己保存的落地，互不影响。\n" +
                        "4. 绑定存在本地，不写进订阅 JSON。远程订阅更新只换节点列表，不会清掉绑定；入口改名会自动改用主分组。\n" +
                        "5. 使用 sing-box 原生 Chain outbound，按你选的顺序串联现有 outbound：入口 → 落地 → 目标。不绑定机场或协议。\n" +
                        "6. Fail Closed：链路失败会明确报错并停止启动，不会偷偷改走 DIRECT。\n" +
                        "7. 链式代理模式下，所有非中国流量不可直连，必须经链式代理后从落地节点出口。中国直连开关仍可让国内与局域网走 DIRECT。\n" +
                        "8. 链式开启后，当前配置的脚本会自动关闭，两者不同时生效。取消链式后如需脚本，到编辑配置页再打开。\n" +
                        "9. 保存后会回到仪表。指向前置的路由规则会被改写到 Chain，避免前置泄漏。DNS detour 保持一跳。",
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun PathPreview(entry: HopRef?, landing: HopRef?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PathNode(
            modifier = Modifier.weight(1f),
            caption = "入口",
            title = entry?.tag ?: "未选择",
            subtitle = entry?.typeLabel.orEmpty(),
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        PathNode(
            modifier = Modifier.weight(1f),
            caption = "落地",
            title = landing?.tag ?: "未选择",
            subtitle = landing?.let { "${it.profileName} · ${it.typeLabel}" }.orEmpty(),
        )
    }
}

@Composable
private fun PathNode(
    modifier: Modifier,
    caption: String,
    title: String,
    subtitle: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HopPickCard(
    title: String,
    type: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (type == "urltest") Icons.Outlined.Bolt else Icons.Outlined.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                title,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun parseHopsFromProfile(profile: Profile): List<HopRef> = try {
    ChainRuntimeCompiler.listSelectableHops(
        File(profile.typed.path).readText(),
        profile.id,
        profile.name,
    ).map { HopRef(it.profileId, it.profileName, it.tag, it.type) }
} catch (_: Exception) {
    emptyList()
}
