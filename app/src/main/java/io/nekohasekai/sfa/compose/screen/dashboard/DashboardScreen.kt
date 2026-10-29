package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.NetworkCheckupDialog
import io.nekohasekai.sfa.compose.component.NoticeFlashHost
import io.nekohasekai.sfa.compose.component.OnboardingGuideCard
import io.nekohasekai.sfa.compose.component.OverrideBanner
import io.nekohasekai.sfa.compose.component.RemoteControlMenuItems
import io.nekohasekai.sfa.compose.component.SubscriptionHealthBanner
import io.nekohasekai.sfa.compose.component.rememberRemoteServers
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.RemoteControlManager

data class CardRenderItem(val cards: List<CardGroup>, val isRow: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    serviceStatus: Status = Status.Stopped,
    showStartFab: Boolean = false,
    showStatusBar: Boolean = false,
    onOpenNewProfile: (NewProfileArgs) -> Unit = {},
    onOpenChainBuilder: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()
    val isRemote = remoteServer != null
    val remoteServers by rememberRemoteServers()
    var showOthersMenu by remember { mutableStateOf(false) }
    var showCheckupDialog by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.reloadChainPath()
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_dashboard)) },
            actions = {
                if (remoteServers.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { showOthersMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.title_others))
                        }
                        DropdownMenu(expanded = showOthersMenu, onDismissRequest = { showOthersMenu = false }) {
                            RemoteControlMenuItems(servers = remoteServers, onAction = { showOthersMenu = false })
                        }
                    }
                }
            },
        )
    }

    if (isRemote && !remoteConnected) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val scaffoldPadding = LocalScaffoldPadding.current
    Box(Modifier.fillMaxSize()) {
        val bottomPadding = when {
            showStartFab -> 88.dp
            showStatusBar -> 74.dp
            else -> 0.dp
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item { OverrideBanner() }
            item {
                SubscriptionHealthBanner(uiState.healthIssues)
            }
            if (uiState.showOnboardingGuide) {
                item {
                    OnboardingGuideCard(
                        onImport = viewModel::showAddProfileSheet,
                        onDismiss = viewModel::dismissOnboardingGuide,
                    )
                }
            }
            val serviceRunning = uiState.isStatusVisible
            // Local home is only the new path UI. Official SFA cards stay in
            // the codebase so their functions can be called from chips / the
            // status bar / Settings, but they are not shown as a second dashboard.
            val homeHidden = setOf(
                CardGroup.UploadTraffic,
                CardGroup.DownloadTraffic,
                CardGroup.Debug,
                CardGroup.Connections,
                CardGroup.ClashMode,
                CardGroup.Profiles,
                CardGroup.SystemProxy,
            )
            val localHomeCards = setOf(CardGroup.ChainPath)
            val actuallyVisibleCards = if (!isRemote) {
                localHomeCards
            } else {
                uiState.visibleCards.filter { cardGroup ->
                    cardGroup !in homeHidden &&
                        cardGroup != CardGroup.ChainPath &&
                        serviceRunning &&
                        isCardAvailableWhenServiceRunning(cardGroup, uiState)
                }.toSet()
            }
            val cardOrder = if (!isRemote) {
                listOf(CardGroup.ChainPath)
            } else {
                uiState.cardOrder
            }
            val cardRenderItems = processCardsForRendering(cardOrder, actuallyVisibleCards, uiState.cardWidths)
            items(cardRenderItems) { renderItem ->
                if (renderItem.isRow && renderItem.cards.size >= 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        renderItem.cards.forEach { cardGroup ->
                            DashboardCardRenderer(
                                cardGroup = cardGroup,
                                cardWidth = uiState.cardWidths[cardGroup] ?: CardWidth.Full,
                                uiState = uiState,
                                serviceStatus = serviceStatus,
                                onClashModeSelected = viewModel::selectClashMode,
                                onSystemProxyToggle = viewModel::toggleSystemProxy,
                                profiles = uiState.profiles,
                                selectedProfileId = uiState.selectedProfileId,
                                isLoading = uiState.isLoading,
                                showAddProfileSheet = uiState.showAddProfileSheet,
                                showProfilePickerSheet = uiState.showProfilePickerSheet,
                                updatingProfileId = uiState.updatingProfileId,
                                updatedProfileId = uiState.updatedProfileId,
                                onProfileSelected = viewModel::selectProfile,
                                onProfileEdit = viewModel::editProfile,
                                onProfileProviders = viewModel::openProviders,
                                onProfileDelete = viewModel::requestDeleteProfile,
                                onProfileShare = viewModel::shareProfile,
                                onProfileShareURL = viewModel::shareProfileURL,
                                onProfileUpdate = viewModel::updateProfile,
                                onProfileMove = viewModel::moveProfile,
                                onShowAddProfileSheet = viewModel::showAddProfileSheet,
                                onHideAddProfileSheet = viewModel::hideAddProfileSheet,
                                onShowProfilePickerSheet = viewModel::showProfilePickerSheet,
                                onHideProfilePickerSheet = viewModel::hideProfilePickerSheet,
                                onOpenNewProfile = onOpenNewProfile,
                                onOpenChainBuilder = onOpenChainBuilder,
                                onOpenCheckup = { showCheckupDialog = true },
                                onToggleService = viewModel::toggleService,
                                onRequestDelayTest = viewModel::testSelectedDelay,
                                commandClient = viewModel.commandClient,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    renderItem.cards.forEach { cardGroup ->
                        DashboardCardRenderer(
                            cardGroup = cardGroup,
                            cardWidth = uiState.cardWidths[cardGroup] ?: CardWidth.Full,
                            uiState = uiState,
                            serviceStatus = serviceStatus,
                            onClashModeSelected = viewModel::selectClashMode,
                            onSystemProxyToggle = viewModel::toggleSystemProxy,
                            profiles = uiState.profiles,
                            selectedProfileId = uiState.selectedProfileId,
                            isLoading = uiState.isLoading,
                            showAddProfileSheet = uiState.showAddProfileSheet,
                            showProfilePickerSheet = uiState.showProfilePickerSheet,
                            updatingProfileId = uiState.updatingProfileId,
                            updatedProfileId = uiState.updatedProfileId,
                            onProfileSelected = viewModel::selectProfile,
                            onProfileEdit = viewModel::editProfile,
                            onProfileProviders = viewModel::openProviders,
                            onProfileDelete = viewModel::requestDeleteProfile,
                            onProfileShare = viewModel::shareProfile,
                            onProfileShareURL = viewModel::shareProfileURL,
                            onProfileUpdate = viewModel::updateProfile,
                            onProfileMove = viewModel::moveProfile,
                            onShowAddProfileSheet = viewModel::showAddProfileSheet,
                            onHideAddProfileSheet = viewModel::hideAddProfileSheet,
                            onShowProfilePickerSheet = viewModel::showProfilePickerSheet,
                            onHideProfilePickerSheet = viewModel::hideProfilePickerSheet,
                            onOpenNewProfile = onOpenNewProfile,
                            onOpenChainBuilder = onOpenChainBuilder,
                            onOpenCheckup = { showCheckupDialog = true },
                            onToggleService = viewModel::toggleService,
                            onRequestDelayTest = viewModel::testSelectedDelay,
                            commandClient = viewModel.commandClient,
                        )
                    }
                }
            }
        }
        // 信息类提示的闪现层：悬浮在仪表盘底部，一闪而过、不留常驻。
        NoticeFlashHost(
            healthIssues = uiState.healthIssues,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp),
        )
    }

    if (uiState.showProfilePickerSheet) {
        ProfilePickerSheet(
            profiles = uiState.profiles,
            selectedProfileId = uiState.selectedProfileId,
            onProfileSelected = { profile -> viewModel.selectProfile(profile.id) },
            onProfileEdit = viewModel::editProfile,
            onProfileProviders = viewModel::openProviders,
            onProfileDelete = viewModel::requestDeleteProfile,
            onProfileMove = viewModel::moveProfile,
            onAddProfile = viewModel::showAddProfileFromPicker,
            onDismiss = viewModel::hideProfilePickerSheet,
        )
    }

    if (uiState.showAddProfileSheet) {
        AddProfileSheet(
            onDismiss = viewModel::hideAddProfileSheet,
            onOpenNewProfile = onOpenNewProfile,
            onProfileImported = { profile -> viewModel.editProfile(profile) },
        )
    }

    if (showCheckupDialog) {
        NetworkCheckupDialog(
            state = uiState.checkup,
            onRun = viewModel::runNetworkCheckup,
            onDismiss = {
                viewModel.dismissNetworkCheckup()
                showCheckupDialog = false
            },
            onClose = { showCheckupDialog = false },
        )
    }

    // B1: only shown when the pending delete affects chain bindings;
    // plain deletions never reach this state.
    uiState.pendingChainDelete?.let { pending ->
        ChainDeleteConfirmDialog(
            pending = pending,
            onConfirm = viewModel::confirmPendingChainDelete,
            onDismiss = viewModel::dismissPendingChainDelete,
        )
    }
}

/**
 * B1 delete confirmation, shared by the dashboard (picker sheet entry)
 * and the profiles page: names every binding the deletion would mark
 * 已失效 before anything is deleted.
 */
@Composable
internal fun ChainDeleteConfirmDialog(
    pending: DashboardUiState.PendingChainDelete,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "删除「${pending.profile.name}」会让以下链式绑定的落地失效" +
                        "（绑定会保留并标记为已失效，可在链式代理页重新选择落地）：",
                )
                pending.affectedLines.forEach { line ->
                    Text("• $line")
                }
                Text("仍要删除吗？")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

fun processCardsForRendering(
    cardOrder: List<CardGroup>,
    visibleCards: Set<CardGroup>,
    cardWidths: Map<CardGroup, CardWidth>,
): List<CardRenderItem> {
    val renderItems = mutableListOf<CardRenderItem>()
    val visibleOrderedCards = cardOrder.filter { visibleCards.contains(it) }
    var i = 0
    while (i < visibleOrderedCards.size) {
        val currentCard = visibleOrderedCards[i]
        val currentWidth = cardWidths[currentCard] ?: CardWidth.Full
        if (currentWidth == CardWidth.Half && i + 1 < visibleOrderedCards.size) {
            val nextCard = visibleOrderedCards[i + 1]
            if ((cardWidths[nextCard] ?: CardWidth.Full) == CardWidth.Half) {
                renderItems.add(CardRenderItem(listOf(currentCard, nextCard), true))
                i += 2
                continue
            }
        }
        renderItems.add(CardRenderItem(listOf(currentCard), false))
        i++
    }
    return renderItems
}

fun isCardAvailableWhenServiceRunning(cardGroup: CardGroup, uiState: DashboardUiState): Boolean = when (cardGroup) {
    CardGroup.ChainPath -> true
    CardGroup.ClashMode -> uiState.clashModeVisible
    CardGroup.UploadTraffic -> uiState.trafficVisible
    CardGroup.DownloadTraffic -> uiState.trafficVisible
    CardGroup.Debug -> true
    CardGroup.Connections -> uiState.trafficVisible
    CardGroup.SystemProxy -> uiState.systemProxyVisible
    CardGroup.Profiles -> true
}
