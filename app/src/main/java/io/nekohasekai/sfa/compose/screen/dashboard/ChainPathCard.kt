package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.chain.ChainPathHop
import io.nekohasekai.sfa.chain.FlowLink
import io.nekohasekai.sfa.chain.FlowNode
import io.nekohasekai.sfa.chain.LiveHop
import io.nekohasekai.sfa.chain.LiveTopology
import io.nekohasekai.sfa.chain.PlacedRibbon
import io.nekohasekai.sfa.chain.SankeyLayout
import io.nekohasekai.sfa.chain.TrafficFlowBuilder
import io.nekohasekai.sfa.compose.base.GlobalEventBus
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.LineChart
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChainPathCard(
    topology: LiveTopology,
    onOpenChainBuilder: () -> Unit,
    modifier: Modifier = Modifier,
    downlink: String = "",
    uplink: String = "",
    downlinkTotal: String = "",
    uplinkTotal: String = "",
    downlinkHistory: List<Float> = emptyList(),
    profileName: String = "",
    clashModes: List<String> = emptyList(),
    selectedClashMode: String = "",
    onClashModeSelected: (String) -> Unit = {},
    onShowProfilePicker: () -> Unit = {},
    onToggleService: () -> Unit = {},
    onRequestDelayTest: () -> Unit = {},
    onUpdateCurrentProfile: () -> Unit = {},
    canUpdateCurrentProfile: Boolean = false,
    updatingCurrentProfile: Boolean = false,
    updatedCurrentProfile: Boolean = false,
    systemProxyVisible: Boolean = false,
    systemProxyEnabled: Boolean = false,
    onSystemProxyToggle: (Boolean) -> Unit = {},
    memory: String = "",
    goroutines: String = "",
    serviceStatus: Status = Status.Stopped,
    connectionsCount: Int = 0,
    groupsCount: Int = 0,
    hasGroups: Boolean = false,
    serviceStartTime: Long? = null,
) {
    val running = topology.running
    val busy = serviceStatus == Status.Starting || serviceStatus == Status.Stopping
    val exitHop = remember(topology.hops) {
        topology.hops.firstOrNull { it.role == ChainPathHop.Role.Landing }
            ?: topology.hops.firstOrNull { it.role == ChainPathHop.Role.Exit }
            ?: pickExitHop(topology.hops)
    }
    val entryHop = remember(topology.hops) {
        topology.hops.firstOrNull { it.role == ChainPathHop.Role.Entry }
    }
    val nodeName = remember(exitHop, profileName) {
        TrafficFlowBuilder.prettyHop(
            exitHop?.title?.ifBlank { exitHop.subtitle }.orEmpty(),
        ).ifBlank { profileName.ifBlank { "—" } }
    }
    val shownEntry = remember(entryHop) {
        TrafficFlowBuilder.prettyHop(entryHop?.title.orEmpty())
            .ifBlank { TrafficFlowBuilder.prettyHop(entryHop?.subtitle.orEmpty()) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(
                stringResource(R.string.title_configuration),
                emphasized = false,
                onClick = onShowProfilePicker,
            )
            if (profileName.isNotBlank()) {
                StatusChip(profileName, emphasized = false, onClick = onShowProfilePicker)
            }
            if (canUpdateCurrentProfile) {
                StatusChip(
                    label = when {
                        updatedCurrentProfile -> stringResource(R.string.success)
                        updatingCurrentProfile -> stringResource(R.string.loading)
                        else -> stringResource(R.string.update_current_profile)
                    },
                    emphasized = true,
                    onClick = {
                        if (!updatingCurrentProfile && !updatedCurrentProfile) {
                            onUpdateCurrentProfile()
                        }
                    },
                )
            }
            if (clashModes.isNotEmpty()) {
                ModeChip(
                    modes = clashModes,
                    selected = selectedClashMode,
                    onSelected = onClashModeSelected,
                )
            }
            IconButton(
                onClick = onOpenChainBuilder,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.chain_builder),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { GlobalEventBus.tryEmit(UiEvent.OpenConnections) },
            ) {
                Text(
                    text = stringResource(R.string.chain_path_downlink),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val (downNum, downUnit) = remember(downlink) { splitRate(downlink.ifEmpty { "0 B/s" }) }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = downNum,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF2E9E7A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = downUnit,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.chain_path_uplink),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uplink.ifEmpty { "0 B/s" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            PowerMark(
                running = running,
                busy = busy,
                onToggle = onToggleService,
                modifier = Modifier.size(88.dp),
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                StatusChip(
                    stringResource(R.string.chain_path_running),
                    emphasized = true,
                    onClick = { GlobalEventBus.tryEmit(UiEvent.OpenLogs) },
                )
                if (topology.chained) {
                    StatusChip(
                        stringResource(R.string.chain_path_chained),
                        emphasized = false,
                        onClick = onOpenChainBuilder,
                    )
                }
                if (topology.mode.equals("direct", ignoreCase = true)) {
                    StatusChip(stringResource(R.string.chain_path_mode_direct), emphasized = false)
                }
                if (systemProxyVisible) {
                    StatusChip(
                        label = stringResource(R.string.system_proxy),
                        emphasized = systemProxyEnabled,
                        onClick = { onSystemProxyToggle(!systemProxyEnabled) },
                    )
                }
                StatusChip(
                    label = "$connectionsCount ${stringResource(R.string.title_connections)}",
                    emphasized = false,
                    onClick = { GlobalEventBus.tryEmit(UiEvent.OpenConnections) },
                )
                if (hasGroups) {
                    StatusChip(
                        label = "$groupsCount ${stringResource(R.string.title_groups)}",
                        emphasized = false,
                        onClick = { GlobalEventBus.tryEmit(UiEvent.OpenGroups) },
                    )
                }
                if (serviceStartTime != null) {
                    StatusChip(
                        label = formatUptime(serviceStartTime),
                        emphasized = true,
                        onClick = { GlobalEventBus.tryEmit(UiEvent.OpenLogs) },
                    )
                }
            } else {
                StatusChip(
                    stringResource(R.string.chain_path_idle),
                    emphasized = false,
                    onClick = onToggleService,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { GlobalEventBus.tryEmit(UiEvent.OpenLogs) },
            horizontalArrangement = Arrangement.End,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (downlinkTotal.isNotEmpty() || uplinkTotal.isNotEmpty()) {
                    Text(
                        text = "↓ ${downlinkTotal.ifEmpty { "0 B" }}  ↑ ${uplinkTotal.ifEmpty { "0 B" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (memory.isNotBlank() || goroutines.isNotBlank()) {
                    Text(
                        text = listOfNotNull(
                            memory.takeIf { it.isNotBlank() }?.let {
                                "${stringResource(R.string.memory)} $it"
                            },
                            goroutines.takeIf { it.isNotBlank() }?.let {
                                "${stringResource(R.string.goroutines)} $it"
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        if (topology.chained) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { GlobalEventBus.tryEmit(UiEvent.OpenGroups) },
                ) {
                    Text(
                        text = stringResource(R.string.chain_path_entry),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = shownEntry.ifBlank { "—" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { GlobalEventBus.tryEmit(UiEvent.OpenGroups) },
                ) {
                    Text(
                        text = stringResource(R.string.chain_path_exit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = nodeName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clickable(onClick = onRequestDelayTest),
                ) {
                    Text(
                        text = stringResource(R.string.chain_path_delay),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val delayMs = exitHop?.delayMs ?: 0
                    Text(
                        text = if (delayMs > 0) {
                            stringResource(R.string.chain_path_ms, delayMs)
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (delayMs in 1..200) {
                            Color(0xFF2E9E7A)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { GlobalEventBus.tryEmit(UiEvent.OpenGroups) },
                ) {
                    Text(
                        text = stringResource(R.string.chain_path_node),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = nodeName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clickable(onClick = onRequestDelayTest),
                ) {
                    Text(
                        text = stringResource(R.string.chain_path_delay),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val delayMs = exitHop?.delayMs ?: 0
                    Text(
                        text = if (delayMs > 0) {
                            stringResource(R.string.chain_path_ms, delayMs)
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (delayMs in 1..200) {
                            Color(0xFF2E9E7A)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        if (running) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { GlobalEventBus.tryEmit(UiEvent.OpenConnections) },
                contentAlignment = Alignment.Center,
            ) {
                if (downlinkHistory.any { it > 0f }) {
                    LineChart(
                        data = downlinkHistory,
                        chartHeight = 28.dp,
                        lineColor = Color(0xFF2E9E7A),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        val device = stringResource(R.string.chain_path_device)
        val dest = stringResource(R.string.chain_path_destination)
        val waiting = stringResource(R.string.chain_path_waiting_dest)
        val unknown = stringResource(R.string.chain_path_unknown)
        val finalRule = stringResource(R.string.chain_path_final)
        val localizedNodes = remember(topology.flowNodes, device, dest, waiting, unknown, finalRule) {
            topology.flowNodes.map { node ->
                node.copy(
                    label = when (node.label) {
                        "Device", "" -> device
                        "Destination" -> dest
                        "waiting" -> waiting
                        "<unknown>" -> unknown
                        "<final>" -> finalRule
                        else -> TrafficFlowBuilder.prettyHop(node.label).ifBlank { node.label }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        TrafficSankey(
            nodes = localizedNodes,
            links = topology.flowLinks,
            flowing = topology.flowing && running,
            compact = !running,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { GlobalEventBus.tryEmit(UiEvent.OpenLogs) },
        )
    }
}

@Composable
private fun ModeChip(
    modes: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        StatusChip(
            label = clashModeLabel(selected.ifBlank { modes.first() }),
            emphasized = true,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(clashModeLabel(mode)) },
                    onClick = {
                        onSelected(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, emphasized: Boolean, onClick: (() -> Unit)? = null) {
    val container = if (emphasized) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text: @Composable () -> Unit = {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (onClick != null) {
        Surface(
            shape = RoundedCornerShape(50),
            color = container,
            contentColor = content,
            onClick = onClick,
            content = text,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(50),
            color = container,
            contentColor = content,
            content = text,
        )
    }
}

@Composable
private fun formatUptime(startTime: Long): String {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startTime) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }
    val elapsedSeconds = ((currentTime - startTime) / 1000).coerceAtLeast(0)
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
private fun PowerMark(
    running: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ringColor = when {
        busy -> MaterialTheme.colorScheme.tertiary
        running -> Color(0xFF2E9E7A)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val description = if (running || busy) {
        stringResource(R.string.stop)
    } else {
        stringResource(R.string.action_start)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = if (running) 6.dp else 2.dp,
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = ringColor,
                        radius = size.minDimension / 2f - 3.dp.toPx(),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = description,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    alpha = if (running || busy) 1f else 0.78f,
                )
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(72.dp),
                        strokeWidth = 2.dp,
                        color = ringColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficSankey(
    nodes: List<FlowNode>,
    links: List<FlowLink>,
    flowing: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = labelColor,
        lineHeight = 13.sp,
    )
    val phase by rememberInfiniteTransition(label = "sankey").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val source = Color(0xFF22D3EE)
    val rule = Color(0xFF4ADE80)
    val hop = Color(0xFFA78BFA)
    val dest = Color(0xFFF472B6)
    val direct = Color(0xFF64748B)
    val scan = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)
    val laneLine = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    BoxWithConstraints(modifier = modifier) {
        if (nodes.isEmpty()) return@BoxWithConstraints
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val padPx = with(density) { 6.dp.toPx() }
        val gapY = with(density) { 8.dp.toPx() }
        val barW = with(density) { 12.dp.toPx() }
        val nSlots = nodes.map { it.column }.distinct().size.coerceAtLeast(1)
        val layerWidth = widthPx / nSlots
        val maxText = (layerWidth - barW - with(density) { 10.dp.toPx() }).coerceAtLeast(
            with(density) { 48.dp.toPx() },
        )
        val measured = remember(nodes, maxText, labelColor) {
            nodes.associate { node ->
                node.id to textMeasurer.measure(
                    text = node.label,
                    style = labelStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = maxText.toInt().coerceAtLeast(24)),
                )
            }
        }
        val minHeights = remember(measured) {
            val padH = with(density) { 6.dp.toPx() }
            measured.mapValues { (_, layout) -> layout.size.height + padH }
        }
        val required = SankeyLayout.requiredHeight(nodes, padPx, gapY, minHeights, 1f)
        val minPx = with(density) { (if (compact) 148.dp else 160.dp).toPx() }
        val maxPx = with(density) { (if (compact) 148.dp else 480.dp).toPx() }
        val fitted = required.coerceIn(minPx, maxPx).coerceAtLeast(1f)
        val canvasH = required.coerceAtLeast(minPx)
        val needScroll = canvasH > maxPx + 1f
        val boxH = if (needScroll) maxPx else fitted
        val canvasDp = with(density) { canvasH.toDp() }
        val boxDp = with(density) { boxH.toDp() }
        val scroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .height(boxDp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (needScroll) Modifier.verticalScroll(scroll) else Modifier),
            ) {
                Canvas(Modifier.fillMaxWidth().height(canvasDp)) {
                val (placed, ribbons) = SankeyLayout.layout(
                    nodes = nodes,
                    links = links,
                    width = size.width,
                    height = size.height,
                    nodeWidth = barW,
                    pad = padPx,
                    minHeights = minHeights,
                    gapY = gapY,
                )
                fun columnColor(col: Int, last: Int): Color = when {
                    col <= 0 -> source
                    col == last -> dest
                    col == 1 && last >= 2 -> rule
                    else -> hop
                }
                fun ink(col: Int, last: Int, isDirect: Boolean): Color =
                    if (isDirect) direct else columnColor(col, last)
                val lastCol = nodes.maxOf { it.column }
                val colXs = placed.groupBy { it.node.column }.mapValues { (_, items) -> items.first().x }
                val sortedCols = colXs.keys.sorted()
                var yScan = 0f
                val scanStep = 7.dp.toPx()
                while (yScan < size.height) {
                    drawLine(scan, Offset(0f, yScan), Offset(size.width, yScan), strokeWidth = 1f)
                    yScan += scanStep
                }
                val proxyBottom = placed.filter { !it.node.direct }.maxOfOrNull { it.y + it.h }
                val directTop = placed.filter { it.node.direct }.minOfOrNull { it.y }
                if (proxyBottom != null && directTop != null && directTop > proxyBottom + 4f) {
                    val mid = (proxyBottom + directTop) / 2f
                    drawLine(
                        color = laneLine,
                        start = Offset(padPx, mid),
                        end = Offset(size.width - padPx, mid),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
                    )
                }
                ribbons.forEach { ribbon ->
                    val fromC = ink(ribbon.columnFrom, lastCol, ribbon.direct)
                    val toC = ink(ribbon.columnTo, lastCol, ribbon.direct)
                    val alpha = if (ribbon.direct) 0.16f else 0.42f
                    drawPath(
                        path = ribbonPath(ribbon),
                        brush = Brush.horizontalGradient(
                            colors = listOf(fromC.copy(alpha = alpha), toC.copy(alpha = alpha)),
                            startX = ribbon.x0,
                            endX = ribbon.x1,
                        ),
                    )
                }
                if (flowing) {
                    ribbons.forEach { ribbon ->
                        val fromC = ink(ribbon.columnFrom, lastCol, ribbon.direct)
                        val toC = ink(ribbon.columnTo, lastCol, ribbon.direct)
                        val dx = (ribbon.x1 - ribbon.x0) * 0.48f
                        val y0 = (ribbon.y0Top + ribbon.y0Bottom) / 2f
                        val y1 = (ribbon.y1Top + ribbon.y1Bottom) / 2f
                        val dots = if (ribbon.direct) 2 else 4
                        for (k in 0 until dots) {
                            val t = (phase + k / dots.toFloat()) % 1f
                            val p = cubicPoint(
                                t,
                                ribbon.x0, y0,
                                ribbon.x0 + dx, y0,
                                ribbon.x1 - dx, y1,
                                ribbon.x1, y1,
                            )
                            val glow = lerp(fromC, toC, t)
                            if (ribbon.direct) {
                                drawCircle(glow.copy(alpha = 0.45f), radius = 1.8.dp.toPx(), center = p)
                            } else {
                                drawCircle(glow.copy(alpha = 0.20f), radius = 7.dp.toPx(), center = p)
                                drawCircle(glow.copy(alpha = 0.85f), radius = 2.6.dp.toPx(), center = p)
                                drawCircle(Color.White.copy(alpha = 0.92f), radius = 1.15.dp.toPx(), center = p)
                            }
                        }
                    }
                }
                placed.forEach { node ->
                    val color = ink(node.node.column, lastCol, node.node.direct)
                    drawRoundRect(
                        color = color.copy(alpha = if (node.node.direct) 0.16f else 0.28f),
                        topLeft = Offset(node.x - 3.dp.toPx(), node.y - 2.dp.toPx()),
                        size = Size(node.w + 6.dp.toPx(), node.h + 4.dp.toPx()),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(node.x, node.y),
                        size = Size(node.w, node.h),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                    val layout = measured[node.node.id] ?: return@forEach
                    val colIndex = sortedCols.indexOf(node.node.column)
                    val nextX = if (colIndex >= 0 && colIndex < sortedCols.lastIndex) {
                        colXs[sortedCols[colIndex + 1]] ?: size.width
                    } else {
                        size.width - padPx
                    }
                    val maxW = (nextX - node.x - node.w - 8.dp.toPx()).coerceAtLeast(24.dp.toPx())
                    val drawn = if (layout.size.width <= maxW.toInt() + 1) {
                        layout
                    } else {
                        textMeasurer.measure(
                            text = node.node.label,
                            style = labelStyle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            constraints = Constraints(maxWidth = maxW.toInt()),
                        )
                    }
                    val tx = node.x + node.w + 5f
                    val ty = node.y + ((node.h - drawn.size.height) / 2f).coerceAtLeast(0f)
                    drawText(drawn, topLeft = Offset(tx, ty))
                }
                }
            }
        }
    }
}

private fun pickExitHop(hops: List<LiveHop>): LiveHop? {
    val useful = hops.filter { hop ->
        hop.role != ChainPathHop.Role.Device &&
            hop.role != ChainPathHop.Role.Destination &&
            !TrafficFlowBuilder.isDirectTag(hop.title)
    }
    return useful.lastOrNull { it.role == ChainPathHop.Role.Landing }
        ?: useful.lastOrNull { it.role == ChainPathHop.Role.Exit }
        ?: useful.lastOrNull()
        ?: hops.lastOrNull { it.role == ChainPathHop.Role.Landing || it.role == ChainPathHop.Role.Exit }
}

internal fun splitRate(raw: String): Pair<String, String> {
    val value = raw.trim().ifBlank { "0 B/s" }
    val slash = value.indexOf('/')
    val core = if (slash > 0) value.take(slash).trim() else value
    val suffix = if (slash > 0) value.substring(slash) else ""
    val sp = core.lastIndexOf(' ')
    return if (sp > 0) {
        core.take(sp) to (core.substring(sp + 1) + suffix)
    } else {
        core to suffix.removePrefix("/")
    }
}

private fun ribbonPath(ribbon: PlacedRibbon): Path {
    val dx = (ribbon.x1 - ribbon.x0) * 0.48f
    return Path().apply {
        moveTo(ribbon.x0, ribbon.y0Top)
        cubicTo(
            ribbon.x0 + dx, ribbon.y0Top,
            ribbon.x1 - dx, ribbon.y1Top,
            ribbon.x1, ribbon.y1Top,
        )
        lineTo(ribbon.x1, ribbon.y1Bottom)
        cubicTo(
            ribbon.x1 - dx, ribbon.y1Bottom,
            ribbon.x0 + dx, ribbon.y0Bottom,
            ribbon.x0, ribbon.y0Bottom,
        )
        close()
    }
}

private fun cubicPoint(
    t: Float,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
): Offset {
    val u = 1f - t
    val uu = u * u
    val uuu = uu * u
    val tt = t * t
    val ttt = tt * t
    return Offset(
        uuu * x0 + 3f * uu * t * x1 + 3f * u * tt * x2 + ttt * x3,
        uuu * y0 + 3f * uu * t * y1 + 3f * u * tt * y2 + ttt * y3,
    )
}
