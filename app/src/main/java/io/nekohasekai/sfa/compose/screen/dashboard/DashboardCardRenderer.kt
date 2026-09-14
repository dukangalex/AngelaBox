package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.CommandClient

@Composable
fun DashboardCardRenderer(
    cardGroup: CardGroup,
    cardWidth: CardWidth,
    uiState: DashboardUiState,
    serviceStatus: Status = Status.Stopped,
    onClashModeSelected: (String) -> Unit,
    onSystemProxyToggle: (Boolean) -> Unit,
    // Profile card specific props
    profiles: List<Profile> = emptyList(),
    selectedProfileId: Long = -1L,
    isLoading: Boolean = false,
    showAddProfileSheet: Boolean = false,
    showProfilePickerSheet: Boolean = false,
    updatingProfileId: Long? = null,
    updatedProfileId: Long? = null,
    onProfileSelected: (Long) -> Unit = {},
    onProfileEdit: (Profile) -> Unit = {},
    onProfileDelete: (Profile) -> Unit = {},
    onProfileShare: (Profile) -> Unit = {},
    onProfileShareURL: (Profile) -> Unit = {},
    onProfileUpdate: (Profile) -> Unit = {},
    onProfileMove: (Int, Int) -> Unit = { _, _ -> },
    onShowAddProfileSheet: () -> Unit = {},
    onHideAddProfileSheet: () -> Unit = {},
    onShowProfilePickerSheet: () -> Unit = {},
    onHideProfilePickerSheet: () -> Unit = {},
    onOpenNewProfile: (NewProfileArgs) -> Unit = {},
    onOpenChainBuilder: () -> Unit = {},
    onToggleService: () -> Unit = {},
    onRequestDelayTest: () -> Unit = {},
    commandClient: CommandClient? = null,
    modifier: Modifier = Modifier,
) {
    when (cardGroup) {
        CardGroup.ChainPath -> {
            val selected = profiles.find { it.id == selectedProfileId }
            ChainPathCard(
                topology = uiState.topology,
                onOpenChainBuilder = onOpenChainBuilder,
                downlink = uiState.downlink,
                uplink = uiState.uplink,
                downlinkTotal = uiState.downlinkTotal,
                uplinkTotal = uiState.uplinkTotal,
                downlinkHistory = uiState.downlinkHistory,
                profileName = uiState.selectedProfileName.orEmpty(),
                clashModes = uiState.clashModes,
                selectedClashMode = uiState.selectedClashMode,
                onClashModeSelected = onClashModeSelected,
                onShowProfilePicker = onShowProfilePickerSheet,
                onShowAddProfile = onShowAddProfileSheet,
                onToggleService = onToggleService,
                onRequestDelayTest = onRequestDelayTest,
                onUpdateCurrentProfile = {
                    selected?.let(onProfileUpdate)
                },
                canUpdateCurrentProfile = selected?.typed?.type == TypedProfile.Type.Remote,
                updatingCurrentProfile = updatingProfileId != null && updatingProfileId == selectedProfileId,
                updatedCurrentProfile = updatedProfileId != null && updatedProfileId == selectedProfileId,
                systemProxyVisible = uiState.systemProxyVisible,
                systemProxyEnabled = uiState.systemProxyEnabled,
                onSystemProxyToggle = onSystemProxyToggle,
                memory = uiState.memory,
                goroutines = uiState.goroutines,
                serviceStatus = serviceStatus,
                modifier = modifier,
            )
        }

        CardGroup.ClashMode -> {
            if (uiState.clashModeVisible) {
                ClashModeCard(
                    modes = uiState.clashModes,
                    selectedMode = uiState.selectedClashMode,
                    onModeSelected = onClashModeSelected,
                    modifier = modifier,
                )
            }
        }

        CardGroup.UploadTraffic -> {
            if (uiState.trafficVisible) {
                UploadTrafficCard(
                    uplink = uiState.uplink,
                    uplinkTotal = uiState.uplinkTotal,
                    uplinkHistory = uiState.uplinkHistory,
                    modifier = modifier,
                )
            }
        }

        CardGroup.DownloadTraffic -> {
            if (uiState.trafficVisible) {
                DownloadTrafficCard(
                    downlink = uiState.downlink,
                    downlinkTotal = uiState.downlinkTotal,
                    downlinkHistory = uiState.downlinkHistory,
                    modifier = modifier,
                )
            }
        }

        CardGroup.Debug -> {
            if (uiState.isStatusVisible) {
                DebugCard(
                    memory = uiState.memory,
                    goroutines = uiState.goroutines,
                    modifier = modifier,
                )
            }
        }

        CardGroup.Connections -> {
            if (uiState.trafficVisible) {
                ConnectionsCard(
                    connectionsIn = uiState.connectionsIn,
                    connectionsOut = uiState.connectionsOut,
                    modifier = modifier,
                )
            }
        }

        CardGroup.SystemProxy -> {
            if (uiState.systemProxyVisible) {
                SystemProxyCard(
                    enabled = uiState.systemProxyEnabled,
                    isSwitching = uiState.systemProxySwitching,
                    onToggle = onSystemProxyToggle,
                    modifier = modifier,
                )
            }
        }

        CardGroup.Profiles -> {
            ProfilesCard(
                profiles = profiles,
                selectedProfileId = selectedProfileId,
                isLoading = isLoading,
                showAddProfileSheet = showAddProfileSheet,
                showProfilePickerSheet = showProfilePickerSheet,
                updatingProfileId = updatingProfileId,
                updatedProfileId = updatedProfileId,
                onProfileSelected = onProfileSelected,
                onProfileEdit = onProfileEdit,
                onProfileDelete = onProfileDelete,
                onProfileShare = onProfileShare,
                onProfileShareURL = onProfileShareURL,
                onProfileUpdate = onProfileUpdate,
                onProfileMove = onProfileMove,
                onShowAddProfileSheet = onShowAddProfileSheet,
                onHideAddProfileSheet = onHideAddProfileSheet,
                onShowProfilePickerSheet = onShowProfilePickerSheet,
                onHideProfilePickerSheet = onHideProfilePickerSheet,
                onOpenNewProfile = onOpenNewProfile,
            )
        }
    }
}
