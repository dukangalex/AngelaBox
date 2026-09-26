package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.chain.ChainPath
import io.nekohasekai.sfa.chain.ChainPathBuilder
import io.nekohasekai.sfa.chain.ChainPathHop
import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.chain.FlowSample
import io.nekohasekai.sfa.chain.GroupHint
import io.nekohasekai.sfa.chain.LiveTopology
import io.nekohasekai.sfa.chain.LiveTopologyBuilder
import io.nekohasekai.sfa.chain.TrafficFlowBuilder
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.model.ConnectionStateFilter
import io.nekohasekai.sfa.compose.model.isNestedRegionAutoSelect
import io.nekohasekai.sfa.compose.navigation.ProfileRoutes
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.ktx.toList
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.ConfigCompat
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.util.Collections
import java.util.Date

enum class CardGroup {
    ChainPath,
    ClashMode,
    UploadTraffic,
    DownloadTraffic,
    Debug,
    Connections,
    SystemProxy,
    Profiles,
}

enum class CardWidth {
    Half,
    Full,
}

data class DashboardUiState(
    val serviceStatus: Status = Status.Stopped,
    val profiles: List<Profile> = emptyList(),
    val selectedProfileId: Long = -1L,
    val selectedProfileName: String? = null,
    val chainPath: ChainPath = ChainPath.regular(),
    val topology: LiveTopology = LiveTopology.idle(),
    val isLoading: Boolean = false,
    val hasGroups: Boolean = false,
    val groupsCount: Int = 0,
    val connectionsCount: Int = 0,
    val serviceStartTime: Long? = null,
    val deprecatedNotes: List<DeprecatedNote> = emptyList(),
    val showDeprecatedDialog: Boolean = false,
    val showAddProfileSheet: Boolean = false,
    val showProfilePickerSheet: Boolean = false,
    val updatingProfileId: Long? = null,
    val updatedProfileId: Long? = null,
    // Status
    val memory: String = "",
    val goroutines: String = "",
    val isStatusVisible: Boolean = false,
    // Traffic
    val trafficVisible: Boolean = false,
    val connectionsIn: String = "0",
    val connectionsOut: String = "0",
    val uplink: String = "0 B/s",
    val downlink: String = "0 B/s",
    val uplinkTotal: String = "0 B",
    val downlinkTotal: String = "0 B",
    val uplinkHistory: List<Float> = List(30) { 0f },
    val downlinkHistory: List<Float> = List(30) { 0f },
    // Clash Mode
    val clashModeVisible: Boolean = false,
    val clashModes: List<String> = emptyList(),
    val selectedClashMode: String = "",
    // System Proxy
    val systemProxyVisible: Boolean = false,
    val systemProxyEnabled: Boolean = false,
    val systemProxySwitching: Boolean = false,
    // Card visibility settings
    val visibleCards: Set<CardGroup> =
        setOf(
            CardGroup.ChainPath,
            CardGroup.ClashMode,
            CardGroup.Debug,
            CardGroup.Connections,
            CardGroup.SystemProxy,
            CardGroup.Profiles,
        ),
    val cardOrder: List<CardGroup> =
        listOf(
            CardGroup.ChainPath,
            CardGroup.UploadTraffic,
            CardGroup.DownloadTraffic,
            CardGroup.Debug,
            CardGroup.Connections,
            CardGroup.SystemProxy,
            CardGroup.ClashMode,
            CardGroup.Profiles,
        ),
    val cardWidths: Map<CardGroup, CardWidth> =
        mapOf(
            CardGroup.ChainPath to CardWidth.Full,
            CardGroup.ClashMode to CardWidth.Full,
            CardGroup.UploadTraffic to CardWidth.Half,
            CardGroup.DownloadTraffic to CardWidth.Half,
            CardGroup.Debug to CardWidth.Half,
            CardGroup.Connections to CardWidth.Half,
            CardGroup.SystemProxy to CardWidth.Full,
            CardGroup.Profiles to CardWidth.Full,
        ),
    val showCardSettingsDialog: Boolean = false,
) {
    data class DeprecatedNote(val message: String, val migrationLink: String?)
}

// DashboardViewModel now only uses UiEvent for all events
// No need for DashboardEvent anymore as all events are handled globally

class DashboardViewModel :
    BaseViewModel<DashboardUiState, UiEvent>(),
    CommandClient.Handler {
    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus: StateFlow<Status> = _serviceStatus.asStateFlow()

    internal val commandClient =
        CommandClient(
            viewModelScope,
            listOf(
                CommandClient.ConnectionType.Status,
                CommandClient.ConnectionType.ClashMode,
                CommandClient.ConnectionType.Groups,
                CommandClient.ConnectionType.Connections,
                CommandClient.ConnectionType.Outbounds,
            ),
            this,
        )

    private var plannedPath: ChainPath = ChainPath.regular()
    @Volatile private var groupHints: List<GroupHint> = emptyList()
    @Volatile private var outboundDelays: Map<String, Int> = emptyMap()
    @Volatile private var liveChain: List<String> = emptyList()
    @Volatile private var liveDestinations: List<String> = emptyList()
    @Volatile private var liveActive: Int = 0
    @Volatile private var liveFlowing: Boolean = false
    @Volatile private var liveSamples: List<FlowSample> = emptyList()
    private var delayPrimed = false
    private var connectionsStore: Connections? = null
    private val connectionsMutex = Mutex()
    private val topologyLock = Any()
    private var lastTopologyPublishAt = 0L
    private var pendingTopology = false

    companion object {
        private const val TOPOLOGY_THROTTLE_MS = 1000L
    }

    private data class LiveSnap(
        val chain: List<String>,
        val destinations: List<String>,
        val active: Int,
        val flowing: Boolean,
        val samples: List<FlowSample> = emptyList(),
    )

    override fun createInitialState(): DashboardUiState {
        val savedOrder = loadItemOrder()
        val disabledItems = loadDisabledItems()

        // Calculate visible items (all items minus disabled)
        val allItems = CardGroup.values().toSet()
        val visibleCards = allItems - disabledItems

        return DashboardUiState(
            cardOrder = savedOrder,
            visibleCards = visibleCards,
        )
    }

    init {
        loadProfiles()
        ProfileManager.registerCallback(::onProfilesChanged)

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                _serviceStatus,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    override fun onCleared() {
        super.onCleared()
        ProfileManager.unregisterCallback(::onProfilesChanged)
        commandClient.disconnect()
    }

    private fun onProfilesChanged() {
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profiles = ProfileManager.list()
                val selectedId = Settings.selectedProfile
                val selected = profiles.find { it.id == selectedId }
                val path = buildChainPath(profiles, selectedId, selected)
                plannedPath = path
                val topology = buildTopology()

                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            profiles = profiles,
                            selectedProfileId = selectedId,
                            selectedProfileName = selected?.name,
                            chainPath = path,
                            topology = topology,
                        )
                    }
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun reloadChainPath() {
        loadProfiles()
    }

    private fun buildChainPath(
        profiles: List<Profile>,
        selectedId: Long,
        selected: Profile?,
    ): ChainPath {
        val binding = ChainBindings.get(selectedId)
        val landingName = binding?.let { b -> profiles.find { it.id == b.landingProfileId }?.name }
        return ChainPathBuilder.build(
            profileName = selected?.name,
            defaultOutboundTag = selected?.let { readDefaultOutboundTag(it) },
            binding = binding,
            landingProfileName = landingName,
        )
    }

    private fun readDefaultOutboundTag(profile: Profile): String? {
        return runCatching {
            val file = File(profile.typed.path)
            if (!file.isFile) return null
            val root = ChainRuntimeCompiler.parseConfig(file.readText())
            val outs = root.optJSONArray("outbounds") ?: return null
            val routeFinal = root.optJSONObject("route")?.optString("final").orEmpty()
            ChainRuntimeCompiler.resolveMainTag(outs, routeFinal)
        }.getOrNull()
    }

    private fun checkDeprecatedNotes() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Check if deprecated warnings are disabled
                if (Settings.disableDeprecatedWarnings) {
                    return@launch
                }

                val notes = Libbox.newStandaloneCommandClient().deprecatedNotes
                if (notes.hasNext()) {
                    val notesList = mutableListOf<DashboardUiState.DeprecatedNote>()
                    while (notes.hasNext()) {
                        val note = notes.next()
                        notesList.add(
                            DashboardUiState.DeprecatedNote(
                                message = note.message(),
                                migrationLink = note.migrationLink,
                            ),
                        )
                    }
                    withContext(Dispatchers.Main) {
                        updateState {
                            copy(
                                deprecatedNotes = notesList,
                                showDeprecatedDialog = notesList.isNotEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun toggleService() {
        when (currentState.serviceStatus) {
            Status.Starting, Status.Started -> stopService()
            Status.Stopped -> sendGlobalEvent(UiEvent.RequestStartService)
            else -> { /* Ignore while transitioning */ }
        }
    }

    private fun stopService() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BoxService.stop()
                // Status will be updated via updateServiceStatus callback
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun dismissDeprecatedNote() {
        val notes = currentState.deprecatedNotes
        if (notes.isNotEmpty()) {
            updateState {
                copy(
                    deprecatedNotes = notes.drop(1),
                    showDeprecatedDialog = notes.size > 1,
                )
            }
        }
    }

    fun selectProfile(profileId: Long) {
        if (profileId == currentState.selectedProfileId) return
        val name = currentState.profiles.find { it.id == profileId }?.name
        updateState {
            copy(
                selectedProfileId = profileId,
                selectedProfileName = name ?: selectedProfileName,
                showProfilePickerSheet = false,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Settings.selectedProfile = profileId
                runCatching { Settings.rebuildServiceMode() }
                sendGlobalEvent(UiEvent.RequestReconnectService)
                loadProfiles()
            } catch (e: Exception) {
                sendError(e)
                loadProfiles()
            }
        }
    }

    fun editProfile(profile: Profile) {
        updateState { copy(showProfilePickerSheet = false) }
        sendGlobalEvent(UiEvent.EditProfile(profile.id))
    }

    fun openProviders(profile: Profile) {
        updateState { copy(showProfilePickerSheet = false) }
        sendGlobalEvent(UiEvent.OpenProviders(profile.id))
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Update UI immediately for responsiveness
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            profiles = profiles.filter { p -> p.id != profile.id },
                        )
                    }
                }
                // Then delete from database
                ProfileManager.delete(profile)
            } catch (e: Exception) {
                // Reload profiles if deletion fails
                loadProfiles()
                sendError(e)
            }
        }
    }

    fun shareProfile(profile: Profile) {
        // Handled directly in ProfilesCard
    }

    fun shareProfileURL(profile: Profile) {
        // Handled directly in ProfilesCard
    }

    fun updateProfile(profile: Profile) {
        if (profile.typed.type != TypedProfile.Type.Remote) return

        viewModelScope.launch(Dispatchers.IO) {
            // Set updating state
            withContext(Dispatchers.Main) {
                updateState { copy(updatingProfileId = profile.id) }
            }

            try {
                val content = ConfigCompat.sanitize(
                    io.nekohasekai.sfa.utils.SubscriptionInfoStore.fetchRemote(
                        profile.typed.remoteURL,
                        profile.id,
                        io.nekohasekai.sfa.Application.application,
                    ),
                )
                Libbox.checkConfig(content)

                // Check if content changed
                val file = File(profile.typed.path)
                var contentChanged = false
                if (!file.exists() || file.readText() != content) {
                    file.writeText(content)
                    contentChanged = true
                }

                // Update last updated time
                profile.typed.lastUpdated = Date()
                ProfileManager.update(profile)

                // Reload profiles
                loadProfiles()

                // Show success state
                withContext(Dispatchers.Main) {
                    updateState { copy(updatingProfileId = null, updatedProfileId = profile.id) }
                }

                // Clear success state after delay
                withContext(Dispatchers.Main) {
                    delay(1500)
                    updateState { copy(updatedProfileId = null) }
                }

                // Restart service if this is the selected profile and content changed
                if (contentChanged && profile.id == Settings.selectedProfile) {
                    withContext(Dispatchers.Main) {
                        sendGlobalEvent(UiEvent.RequestReconnectService)
                    }
                }
            } catch (e: Exception) {
                sendErrorMessage(io.nekohasekai.sfa.utils.HTTPClient.explainProfileUpdate(e))
                // Clear updating state on error
                withContext(Dispatchers.Main) {
                    updateState { copy(updatingProfileId = null) }
                }
            }
        }
    }

    fun updateAllRemoteProfiles() {
        currentState.profiles.filter { it.typed.type == TypedProfile.Type.Remote }.forEach { updateProfile(it) }
    }

    fun moveProfile(from: Int, to: Int) {
        val currentProfiles = currentState.profiles.toMutableList()

        if (from < to) {
            for (i in from until to) {
                Collections.swap(currentProfiles, i, i + 1)
            }
        } else {
            for (i in from downTo to + 1) {
                Collections.swap(currentProfiles, i, i - 1)
            }
        }

        // Update UI immediately
        updateState { copy(profiles = currentProfiles) }

        // Update user order in database
        viewModelScope.launch(Dispatchers.IO) {
            currentProfiles.forEachIndexed { index, profile ->
                profile.userOrder = index.toLong()
            }
            ProfileManager.update(currentProfiles)
        }
    }

    fun showAddProfileSheet() {
        updateState { copy(showAddProfileSheet = true) }
    }

    fun hideAddProfileSheet() {
        updateState { copy(showAddProfileSheet = false) }
    }

    fun showProfilePickerSheet() {
        updateState { copy(showProfilePickerSheet = false) }
        sendGlobalEvent(UiEvent.Navigate(ProfileRoutes.Profiles))
    }

    fun hideProfilePickerSheet() {
        updateState { copy(showProfilePickerSheet = false) }
    }

    fun showAddProfileFromPicker() {
        updateState { copy(showProfilePickerSheet = false, showAddProfileSheet = true) }
    }

    fun updateServiceStatus(status: Status) {
        viewModelScope.launch {
            _serviceStatus.emit(status)
            updateState {
                copy(
                    serviceStatus = status,
                    isStatusVisible =
                    if (RemoteControlManager.remoteServer.value != null) {
                        isStatusVisible
                    } else {
                        status == Status.Starting || status == Status.Started
                    },
                )
            }
            handleServiceStatusChange(status)
        }
    }

    private fun handleServiceStatusChange(status: Status) {
        val isRemote = RemoteControlManager.remoteServer.value != null
        when (status) {
            Status.Started -> {
                checkDeprecatedNotes()
                requestTopologyPublish(force = true)
                if (isRemote) {
                    return
                }
                reloadSystemProxyStatus()
                reloadStartedAt()
            }

            Status.Stopped -> {
                if (isRemote) {
                    return
                }
                resetLiveSnapshot()
                val topology = LiveTopologyBuilder.fromPath(plannedPath, running = false)
                updateState {
                    copy(
                        hasGroups = false,
                        groupsCount = 0,
                        connectionsCount = 0,
                        serviceStartTime = null,
                        clashModeVisible = false,
                        systemProxyVisible = false,
                        trafficVisible = false,
                        memory = "",
                        goroutines = "",
                        connectionsIn = "0",
                        connectionsOut = "0",
                        uplink = "0 B/s",
                        downlink = "0 B/s",
                        uplinkTotal = "0 B",
                        downlinkTotal = "0 B",
                        uplinkHistory = List(30) { 0f },
                        downlinkHistory = List(30) { 0f },
                        topology = topology,
                    )
                }
            }

            else -> {}
        }
    }

    private fun reloadStartedAt() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val startedAt = Libbox.newStandaloneCommandClient().startedAt
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(serviceStartTime = startedAt)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun reloadSystemProxyStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = Libbox.newStandaloneCommandClient().systemProxyStatus
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            systemProxyVisible = status.available,
                            systemProxyEnabled = status.enabled,
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun toggleSystemProxy(enabled: Boolean) {
        if (currentState.systemProxySwitching) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateState { copy(systemProxySwitching = true) }
                Settings.systemProxyEnabled = enabled
                Libbox.newStandaloneCommandClient().setSystemProxyEnabled(enabled)
                delay(1000L)
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            systemProxyEnabled = enabled,
                            systemProxySwitching = false,
                        )
                    }
                }
            } catch (e: Exception) {
                sendError(e)
                updateState { copy(systemProxySwitching = false) }
            }
        }
    }

    fun selectClashMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().setClashMode(mode)
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(selectedClashMode = mode)
                    }
                    requestTopologyPublish(force = true)
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        viewModelScope.launch(Dispatchers.Main) {
            updateState { copy(isStatusVisible = true) }
            // Returning from remote control skipped the local reloads that
            // normally run when the service starts.
            if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value == Status.Started) {
                reloadSystemProxyStatus()
                reloadStartedAt()
            }
        }
    }

    override fun onDisconnected() {
        viewModelScope.launch(Dispatchers.Main) {
            resetLiveSnapshot()
            updateState {
                copy(
                    memory = "",
                    goroutines = "",
                    isStatusVisible = false,
                    topology = LiveTopologyBuilder.fromPath(plannedPath, running = false),
                )
            }
        }
    }

    override fun updateStatus(status: StatusMessage) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                // Update history by adding new values and removing old ones
                val newUplinkHistory = (uplinkHistory.drop(1) + status.uplink.toFloat())
                val newDownlinkHistory = (downlinkHistory.drop(1) + status.downlink.toFloat())

                // Format the total values
                val newUplinkTotal = Libbox.formatBytes(status.uplinkTotal)
                val newDownlinkTotal = Libbox.formatBytes(status.downlinkTotal)

                copy(
                    memory = Libbox.formatBytes(status.memory),
                    goroutines = status.goroutines.toString(),
                    // Only set trafficVisible to true, never back to false from status updates
                    trafficVisible = if (status.trafficAvailable) true else trafficVisible,
                    connectionsCount = status.connectionsIn,
                    connectionsIn = status.connectionsIn.toString(),
                    connectionsOut = status.connectionsOut.toString(),
                    uplink = "${Libbox.formatBytes(status.uplink)}/s",
                    downlink = "${Libbox.formatBytes(status.downlink)}/s",
                    // Only update total values if they've actually changed
                    uplinkTotal = if (newUplinkTotal != uplinkTotal) newUplinkTotal else uplinkTotal,
                    downlinkTotal = if (newDownlinkTotal != downlinkTotal) newDownlinkTotal else downlinkTotal,
                    uplinkHistory = newUplinkHistory,
                    downlinkHistory = newDownlinkHistory,
                )
            }
            val trafficFlowing = status.uplink > 0L || status.downlink > 0L
            if (trafficFlowing != liveFlowing && liveActive == 0) {
                liveFlowing = trafficFlowing
                requestTopologyPublish()
            }
        }
    }

    override fun initializeClashMode(modeList: List<String>, currentMode: String) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(
                    clashModeVisible = modeList.size > 1,
                    clashModes = modeList,
                    selectedClashMode = currentMode,
                )
            }
            requestTopologyPublish(force = true)
        }
    }

    override fun updateClashMode(newMode: String) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(selectedClashMode = newMode)
            }
            requestTopologyPublish(force = true)
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        viewModelScope.launch(Dispatchers.Main) {
            val tags = HashSet<String>(newGroups.size)
            for (group in newGroups) tags.add(group.tag)
            var visible = 0
            for (group in newGroups) {
                if (!isNestedRegionAutoSelect(group.tag, tags)) visible++
            }
            groupHints = newGroups.map { group ->
                GroupHint(tag = group.tag, selected = group.selected)
            }
            updateState {
                copy(hasGroups = visible > 0, groupsCount = visible)
            }
            requestTopologyPublish(force = true)
            maybePrimeDelay()
        }
    }

    override fun updateOutbounds(outbounds: List<OutboundGroupItem>) {
        viewModelScope.launch(Dispatchers.Main) {
            val next = LinkedHashMap<String, Int>()
            outbounds.forEach { item ->
                if (item.urlTestDelay > 0) {
                    next[item.tag] = item.urlTestDelay
                    val shown = ChainRuntimeCompiler.displayHopTag(item.tag)
                    if (shown.isNotBlank()) next[shown] = item.urlTestDelay
                }
            }
            outboundDelays = next
            requestTopologyPublish(force = true)
        }
    }

    override fun writeConnectionEvents(events: ConnectionEvents) {
        viewModelScope.launch(Dispatchers.Default) {
            val snap = connectionsMutex.withLock {
                val store = connectionsStore ?: Connections().also { connectionsStore = it }
                store.applyEvents(events)
                store.filterState(ConnectionStateFilter.Active.libboxValue)
                extractLive(store)
            }
            liveChain = snap.chain
            liveDestinations = snap.destinations
            liveActive = snap.active
            liveFlowing = snap.flowing
            liveSamples = snap.samples
            requestTopologyPublish()
        }
    }

    private fun extractLive(store: Connections): LiveSnap {
        val destCounts = LinkedHashMap<String, Int>()
        var bestChain = emptyList<String>()
        var active = 0
        var flowing = false
        val directSamples = ArrayList<FlowSample>(16)
        val proxySamples = ArrayList<FlowSample>(24)
        val seen = HashSet<String>()
        val iterator = store.iterator()
        var scanned = 0
        while (iterator.hasNext()) {
            if (scanned >= 200) break
            scanned++
            val connection = iterator.next()
            if (connection.outboundType == "dns") continue
            active++
            if (connection.uplink > 0L || connection.downlink > 0L) flowing = true
            val dest = connection.displayDestination().ifBlank {
                connection.domain
            }.ifBlank {
                connection.destination
            }
            if (dest.isNotBlank()) {
                destCounts[dest] = (destCounts[dest] ?: 0) + 1
            }
            val hops = runCatching { connection.chain().toList() }.getOrDefault(emptyList())
            if (hops.size > bestChain.size) bestChain = hops
            val isDirect = connection.outboundType.equals("direct", true) ||
                TrafficFlowBuilder.isDirectTag(connection.outbound) ||
                (hops.isNotEmpty() && hops.all { it.isBlank() || TrafficFlowBuilder.isDirectTag(it) })
            val outbound = if (isDirect) "DIRECT" else connection.outbound
            val chain = if (isDirect) listOf("DIRECT") else hops
            val key = "${connection.rule}|$outbound"
            if (!seen.add(key) && !isDirect) continue
            val sample = FlowSample(
                source = connection.source.ifBlank { connection.inbound },
                rule = connection.rule,
                outbound = outbound,
                chain = chain,
                dest = dest,
            )
            if (isDirect) {
                if (directSamples.size < 16) directSamples += sample
            } else if (proxySamples.size < 24) {
                proxySamples += sample
            }
            if (directSamples.size >= 16 && proxySamples.size >= 24) break
        }
        val samples = proxySamples + directSamples
        val destinations = destCounts.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(3)
        return LiveSnap(bestChain, destinations, active, flowing, samples)
    }

    private fun resetLiveSnapshot() {
        liveChain = emptyList()
        liveDestinations = emptyList()
        liveActive = 0
        liveFlowing = false
        liveSamples = emptyList()
        groupHints = emptyList()
        outboundDelays = emptyMap()
        delayPrimed = false
        viewModelScope.launch(Dispatchers.Default) {
            connectionsMutex.withLock { connectionsStore = null }
        }
    }

    private fun buildTopology(): LiveTopology {
        val running = _serviceStatus.value == Status.Started
        return LiveTopologyBuilder.fromPath(
            path = plannedPath,
            running = running,
            mode = currentState.selectedClashMode,
            groups = hintsWithDelays(),
            liveChain = liveChain,
            destinations = liveDestinations,
            activeConnections = if (liveActive > 0) liveActive else currentState.connectionsCount,
            flowing = liveFlowing,
            samples = liveSamples,
        )
    }

    private fun hintsWithDelays(): List<GroupHint> {
        val delays = outboundDelays
        if (delays.isEmpty()) return groupHints
        if (groupHints.isEmpty()) return listOf(GroupHint(tag = "", delays = delays))
        return groupHints.map { hint -> hint.copy(delays = delays + hint.delays) }
    }

    fun testSelectedDelay() {
        if (_serviceStatus.value != Status.Started) return
        val hop = currentState.topology.hops.lastOrNull { live ->
            (live.role == ChainPathHop.Role.Landing || live.role == ChainPathHop.Role.Exit) &&
                live.title.isNotBlank() &&
                !TrafficFlowBuilder.isDirectTag(live.title)
        }
        val tag = hop?.title?.ifBlank { hop.subtitle }.orEmpty()
        if (tag.isBlank() || TrafficFlowBuilder.isDirectTag(tag)) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { CommandTarget.standaloneClient().urlTest(tag) }
        }
    }

    private fun maybePrimeDelay() {
        if (delayPrimed) return
        if (_serviceStatus.value != Status.Started) return
        delayPrimed = true
        viewModelScope.launch {
            delay(1200)
            testSelectedDelay()
        }
    }

    private fun requestTopologyPublish(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(topologyLock) {
            if (!force && now - lastTopologyPublishAt < TOPOLOGY_THROTTLE_MS) {
                if (pendingTopology) return
                pendingTopology = true
                val wait = TOPOLOGY_THROTTLE_MS - (now - lastTopologyPublishAt)
                viewModelScope.launch {
                    delay(wait.coerceAtLeast(1L))
                    synchronized(topologyLock) { pendingTopology = false }
                    publishTopologyNow()
                }
                return
            }
        }
        publishTopologyNow()
    }

    private fun publishTopologyNow() {
        val topology = buildTopology()
        synchronized(topologyLock) {
            lastTopologyPublishAt = android.os.SystemClock.elapsedRealtime()
        }
        viewModelScope.launch(Dispatchers.Main) {
            updateState { copy(topology = topology) }
        }
    }

    fun toggleCardSettingsDialog() {
        updateState {
            copy(showCardSettingsDialog = !showCardSettingsDialog)
        }
    }

    fun toggleCardVisibility(cardGroup: CardGroup) {
        updateState {
            val newVisibleCards =
                if (visibleCards.contains(cardGroup)) {
                    visibleCards - cardGroup
                } else {
                    visibleCards + cardGroup
                }
            // Save disabled items to settings
            saveDisabledItems(newVisibleCards)
            // Also save the current order if not already saved (indicates user has configured dashboard)
            if (Settings.dashboardItemOrder.isBlank()) {
                saveItemOrder(cardOrder)
            }
            copy(visibleCards = newVisibleCards)
        }
    }

    fun closeCardSettingsDialog() {
        updateState {
            copy(showCardSettingsDialog = false)
        }
    }

    fun reorderCards(newOrder: List<CardGroup>) {
        updateState {
            saveItemOrder(newOrder)
            copy(cardOrder = newOrder)
        }
    }

    fun resetCardOrder() {
        Settings.dashboardItemOrder = ""
        Settings.dashboardStyleVersion = 2
        val visible = defaultVisibleCards()
        saveDisabledItems(visible)
        updateState {
            copy(
                cardOrder = getDefaultItemOrder(),
                visibleCards = visible,
            )
        }
    }

    // Helper functions for serialization
    private fun getDefaultItemOrder() = listOf(
        CardGroup.ChainPath,
        CardGroup.UploadTraffic,
        CardGroup.DownloadTraffic,
        CardGroup.Debug,
        CardGroup.Connections,
        CardGroup.SystemProxy,
        CardGroup.ClashMode,
        CardGroup.Profiles,
    )

    private fun defaultDisabledCards() = setOf(
        CardGroup.UploadTraffic,
        CardGroup.DownloadTraffic,
        CardGroup.Debug,
        CardGroup.Connections,
        CardGroup.ClashMode,
        CardGroup.Profiles,
    )

    private fun defaultVisibleCards() = CardGroup.values().toSet() - defaultDisabledCards()

    private fun loadItemOrder(): List<CardGroup> {
        val savedOrder = Settings.dashboardItemOrder
        if (savedOrder.isBlank()) {
            return getDefaultItemOrder()
        }

        return try {
            val jsonArray = JSONArray(savedOrder)
            val order = mutableListOf<CardGroup>()

            for (i in 0 until jsonArray.length()) {
                val itemName = jsonArray.getString(i)
                stringToCardGroup(itemName)?.let { order.add(it) }
            }

            // Add any new items that aren't in the saved order
            val allItems = CardGroup.values().toSet()
            val savedItems = order.toSet()
            val newItems = (allItems - savedItems).toMutableList()
            if (CardGroup.ChainPath in newItems) {
                newItems.remove(CardGroup.ChainPath)
                val profilesIdx = order.indexOf(CardGroup.Profiles)
                if (profilesIdx >= 0) {
                    order.add(profilesIdx, CardGroup.ChainPath)
                } else {
                    order.add(0, CardGroup.ChainPath)
                }
            }
            order.addAll(newItems)
            order
        } catch (e: JSONException) {
            getDefaultItemOrder()
        }
    }

    private fun saveItemOrder(order: List<CardGroup>) {
        val jsonArray = JSONArray()
        order.forEach { item ->
            jsonArray.put(cardGroupToString(item))
        }
        Settings.dashboardItemOrder = jsonArray.toString()
    }

    private fun loadDisabledItems(): Set<CardGroup> {
        if (Settings.dashboardStyleVersion < 2) {
            Settings.dashboardStyleVersion = 2
            val defaults = defaultDisabledCards()
            Settings.dashboardDisabledItems = defaults.map { cardGroupToString(it) }.toSet()
            return defaults
        }
        val savedDisabled = Settings.dashboardDisabledItems
        if (savedDisabled.isEmpty() && Settings.dashboardItemOrder.isBlank()) {
            val defaults = defaultDisabledCards()
            Settings.dashboardDisabledItems = defaults.map { cardGroupToString(it) }.toSet()
            return defaults
        }
        return savedDisabled.mapNotNull { stringToCardGroup(it) }.toSet()
    }

    private fun saveDisabledItems(visibleCards: Set<CardGroup>) {
        val allItems = CardGroup.values().toSet()
        val disabledItems = allItems - visibleCards
        Settings.dashboardDisabledItems = disabledItems.map { cardGroupToString(it) }.toSet()
    }

    private fun cardGroupToString(card: CardGroup): String = card.name

    private fun stringToCardGroup(name: String): CardGroup? = try {
        CardGroup.valueOf(name)
    } catch (e: IllegalArgumentException) {
        null
    }
}
