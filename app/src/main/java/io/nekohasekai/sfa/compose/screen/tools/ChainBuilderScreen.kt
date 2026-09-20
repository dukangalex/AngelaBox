package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.chain.ChainBinding
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.compat.menuAnchorCompat
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.navigation.popToDashboard
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
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
    var chainActive by remember { mutableStateOf(false) }
    var otherBound by remember { mutableStateOf(0) }
    var otherBoundLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var showOtherBound by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var pickerQuery by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }

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
                    } else {
                        exit = null
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
                }
            }
            busy = false
            if (result.isSuccess) {
                chainActive = true
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
                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                snackbar.showSnackbar("已取消当前配置的链式代理")
            } else {
                snackbar.showSnackbar("取消失败：${result.exceptionOrNull()?.message}")
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    val canSave = !busy && entry != null && exit != null

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.chain_builder)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            },
            actions = {
                IconButton(onClick = { navController.popToDashboard() }) {
                    Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.action_home))
                }
                IconButton(onClick = { showHelp = true }) {
                    Icon(Icons.Default.Info, stringResource(R.string.read_more))
                }
                IconButton(onClick = { reload() }) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.action_reload))
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().padding(LocalScaffoldPadding.current)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loadError != null) {
                Text("加载失败: $loadError", color = scheme.error)
            }

            StatusPill(active = chainActive, bound = chainActive && entry != null && exit != null)

            Text(
                "配置选择",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            ExposedDropdownMenuBox(
                expanded = profileMenu,
                onExpandedChange = { profileMenu = it },
            ) {
                Surface(
                    modifier = Modifier
                        .then(menuAnchorCompat(true))
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            currentProfileName.ifBlank { "选择配置" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileMenu)
                    }
                }
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

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = scheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HopOrb(
                            hop = entry,
                            role = "入口",
                            placeholder = "选择入口",
                            accent = scheme.primary,
                            enabled = !busy,
                            onClick = { picker = "entry"; pickerQuery = "" },
                            modifier = Modifier.weight(1f),
                        )
                        LinkPulse(
                            active = entry != null && exit != null,
                            modifier = Modifier.width(72.dp),
                        )
                        HopOrb(
                            hop = exit,
                            role = "落地",
                            placeholder = "选择落地",
                            accent = scheme.tertiary,
                            enabled = !busy,
                            showFlag = true,
                            onClick = { picker = "landing"; pickerQuery = "" },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (otherBound > 0) {
                Text(
                    "已有 $otherBound 个链式代理配置，点此查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) { showOtherBound = true }
                        .padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(4.dp))

            val ctaShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .alpha(if (canSave) 1f else 0.45f)
                    .clip(ctaShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                scheme.primary,
                                lerp(scheme.primary, scheme.secondary, 0.55f),
                            ),
                        ),
                    )
                    .clickable(enabled = canSave, onClick = { save() }),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "确定并保存",
                        color = scheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "「$currentProfileName」链式，入口 → 落地",
                        color = scheme.onPrimary.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TextButton(
                onClick = { clearChain() },
                enabled = !busy && chainActive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消当前配置的链式")
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
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
                                        "${hop.tag} · ${hop.typeLabel}",
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
                        "每份配置独立保存落地，互不影响。只绑定当前选中的配置。",
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
                        "8. 前置订阅的脚本仍然生效：先跑脚本改分组和分流，再按入口→落地组链。落地配置上的脚本不会套到当前配置。脚本和链式不再互斥。\n" +
                        "9. 保存后会回到仪表。指向前置的路由规则会被改写到 Chain，避免前置泄漏。DNS detour 保持一跳。",
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun StatusPill(active: Boolean, bound: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val label = when {
        bound -> "已绑定链路"
        active -> "已绑定"
        else -> "未绑定"
    }
    val color = if (bound) scheme.primary else scheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun HopOrb(
    hop: HopRef?,
    role: String,
    placeholder: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showFlag: Boolean = false,
) {
    val selected = hop != null
    val ring = if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val pulse = rememberInfiniteTransition(label = "orb-$role")
    val glow by pulse.animateFloat(
        initialValue = 0.28f,
        targetValue = if (selected) 0.7f else 0.28f,
        animationSpec = infiniteRepeatable(
            tween(if (selected) 1600 else 2400, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "glow-$role",
    )
    val flag = if (showFlag && hop != null && hop.type != "urltest" && hop.type != "selector") {
        regionGlyph(hop.tag)
    } else {
        null
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .border(1.dp, ring.copy(alpha = glow), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .border(2.dp, ring.copy(alpha = 0.95f), CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (!flag.isNullOrEmpty()) {
                    Text(flag, fontSize = 28.sp)
                } else {
                    Icon(
                        imageVector = hopIcon(hop),
                        contentDescription = role,
                        tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            hop?.tag ?: placeholder,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            hop?.let { if (showFlag) "${it.profileName} · ${it.typeLabel}" else it.typeLabel } ?: role,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LinkPulse(active: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val dim = color.copy(alpha = 0.22f)
    val t = rememberInfiniteTransition(label = "link")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier.height(40.dp)) {
        val y = size.height / 2f
        val start = 2.dp.toPx()
        val end = size.width - 2.dp.toPx()
        drawLine(dim, Offset(start, y), Offset(end, y), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        val mid = size.width / 2f
        val amp = size.height * 0.38f
        val beat = Path().apply {
            moveTo(mid - 16.dp.toPx(), y)
            lineTo(mid - 7.dp.toPx(), y)
            lineTo(mid - 3.dp.toPx(), y - amp)
            lineTo(mid + 3.dp.toPx(), y + amp)
            lineTo(mid + 7.dp.toPx(), y)
            lineTo(mid + 16.dp.toPx(), y)
        }
        drawPath(
            beat,
            if (active) color else dim,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        if (active) {
            val x = start + (end - start) * phase
            drawCircle(color.copy(alpha = 0.3f), 9.dp.toPx(), Offset(x, y))
            drawCircle(color, 4.dp.toPx(), Offset(x, y))
        }
    }
}

private fun hopIcon(hop: HopRef?) = when (hop?.type) {
    "urltest" -> Icons.Outlined.Bolt
    "selector" -> Icons.Outlined.Hub
    null -> Icons.Outlined.Settings
    else -> Icons.Outlined.Public
}

private val REGION_FLAGS = listOf(
    "HK" to "🇭🇰", "TW" to "🇹🇼", "JP" to "🇯🇵", "KR" to "🇰🇷",
    "SG" to "🇸🇬", "US" to "🇺🇸", "UK" to "🇬🇧", "GB" to "🇬🇧",
    "DE" to "🇩🇪", "FR" to "🇫🇷", "NL" to "🇳🇱", "AU" to "🇦🇺",
    "CA" to "🇨🇦", "TR" to "🇹🇷", "IN" to "🇮🇳", "BR" to "🇧🇷",
    "RU" to "🇷🇺", "MO" to "🇲🇴",
)

private fun regionGlyph(tag: String): String? {
    val raw = tag.trim()
    if (raw.isEmpty()) return null
    when {
        raw.contains("香港") -> return "🇭🇰"
        raw.contains("台湾") || raw.contains("台灣") -> return "🇹🇼"
        raw.contains("日本") -> return "🇯🇵"
        raw.contains("韩国") || raw.contains("韓國") -> return "🇰🇷"
        raw.contains("新加坡") -> return "🇸🇬"
        raw.contains("美国") || raw.contains("美國") || raw.contains("洛杉") -> return "🇺🇸"
        raw.contains("英国") || raw.contains("英國") -> return "🇬🇧"
        raw.contains("德国") || raw.contains("德國") -> return "🇩🇪"
        raw.contains("法国") || raw.contains("法國") -> return "🇫🇷"
        raw.contains("荷兰") || raw.contains("荷蘭") -> return "🇳🇱"
        raw.contains("澳洲") || raw.contains("澳大利亚") -> return "🇦🇺"
        raw.contains("加拿大") -> return "🇨🇦"
    }
    val head = raw.takeWhile { it.isLetter() }.uppercase()
    if (head.length < 2) return null
    val cc = head.take(2)
    return REGION_FLAGS.firstOrNull { it.first == cc }?.second
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
