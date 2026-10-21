package io.nekohasekai.sfa.database

import android.os.Build
import androidx.room.Room
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.ProxyService
import io.nekohasekai.sfa.bg.VPNService
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.preference.KeyValueDatabase
import io.nekohasekai.sfa.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sfa.ktx.boolean
import io.nekohasekai.sfa.ktx.int
import io.nekohasekai.sfa.ktx.long
import io.nekohasekai.sfa.ktx.map
import io.nekohasekai.sfa.ktx.string
import io.nekohasekai.sfa.ktx.stringSet

object Settings {
    private val dbLock = Any()

    @Volatile
    private var db: KeyValueDatabase? = null

    private fun database(): KeyValueDatabase {
        db?.takeIf { it.isOpen }?.let { return it }
        synchronized(dbLock) {
            db?.takeIf { it.isOpen }?.let { return it }
            Application.application.getDatabasePath(Path.SETTINGS_DATABASE_PATH).parentFile?.mkdirs()
            // Any schema-version mismatch is backed up before Room opens the
            // file, so neither direction can silently wipe the KV store
            // (audit F-7). Upgrades without a registered Migration now fail
            // loudly instead of falling back to a destructive recreate.
            DatabaseDowngradeGuard.backupIfVersionMismatch(Application.application, Path.SETTINGS_DATABASE_PATH, KeyValueDatabase.VERSION)
            val built = Room.databaseBuilder(
                Application.application,
                KeyValueDatabase::class.java,
                Path.SETTINGS_DATABASE_PATH,
            ).allowMainThreadQueries()
                .fallbackToDestructiveMigrationOnDowngrade()
                .enableMultiInstanceInvalidation()
                .build()
            db = built
            return built
        }
    }

    val dataStore = RoomPreferenceDataStore { database().keyValuePairDao() }
    var selectedProfile by dataStore.long(SettingsKey.SELECTED_PROFILE) { -1L }
    var serviceMode by dataStore.string(SettingsKey.SERVICE_MODE) { ServiceMode.NORMAL }
    var startedByUser by dataStore.boolean(SettingsKey.STARTED_BY_USER)

    var updateSource by dataStore.string(SettingsKey.UPDATE_SOURCE) { "github" }
    var checkUpdateEnabled by dataStore.boolean(SettingsKey.CHECK_UPDATE_ENABLED) { false }
    var updateCheckPrompted by dataStore.boolean(SettingsKey.UPDATE_CHECK_PROMPTED) { false }
    var updateTrack by dataStore.string(SettingsKey.UPDATE_TRACK) {
        val versionName = BuildConfig.VERSION_NAME.lowercase()
        if (versionName.contains("-alpha") || versionName.contains("-beta") || versionName.contains("-rc")) {
            "beta"
        } else {
            "stable"
        }
    }
    var githubToken by dataStore.string(SettingsKey.GITHUB_TOKEN) { "" }
    var silentInstallEnabled by dataStore.boolean(SettingsKey.SILENT_INSTALL_ENABLED) { false }
    var silentInstallMethod by dataStore.string(SettingsKey.SILENT_INSTALL_METHOD) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "PACKAGE_INSTALLER" else "SHIZUKU"
    }
    var fdroidMirrorUrl by dataStore.string(SettingsKey.FDROID_MIRROR_URL) { "https://f-droid.org/repo" }
    var fdroidCustomMirrors by dataStore.stringSet(SettingsKey.FDROID_CUSTOM_MIRRORS) { emptySet() }
    var autoUpdateEnabled by dataStore.boolean(SettingsKey.AUTO_UPDATE_ENABLED) { false }
    var dynamicNotification by dataStore.boolean(SettingsKey.DYNAMIC_NOTIFICATION) { true }
    var disableDeprecatedWarnings by dataStore.boolean(SettingsKey.DISABLE_DEPRECATED_WARNINGS) { false }
    var onboardingGuideDismissed by dataStore.boolean(SettingsKey.ONBOARDING_GUIDE_DISMISSED) { false }
    var themeMode by dataStore.string(SettingsKey.THEME_MODE) { "system" }
    var themeSeed by dataStore.string(SettingsKey.THEME_SEED) { "default" }
    var themePureBlack by dataStore.boolean(SettingsKey.THEME_PURE_BLACK) { false }
    var overlayScriptsJson by dataStore.string(SettingsKey.OVERLAY_SCRIPTS) { "[]" }
    var overlayScriptBindingsJson by dataStore.string(SettingsKey.OVERLAY_SCRIPT_BINDINGS) { "{}" }

    const val PER_APP_PROXY_DISABLED = 0
    const val PER_APP_PROXY_EXCLUDE = 1
    const val PER_APP_PROXY_INCLUDE = 2

    var autoRedirect by dataStore.boolean(SettingsKey.AUTO_REDIRECT) { false }
    var onDemand by dataStore.boolean(SettingsKey.ON_DEMAND) { true }
    var disableQuic by dataStore.boolean(SettingsKey.DISABLE_QUIC) { true }
    var excludeCnQuic by dataStore.boolean(SettingsKey.EXCLUDE_CN_QUIC) { true }
    var strictRoute by dataStore.boolean(SettingsKey.STRICT_ROUTE) { false }
    var dnsProtect by dataStore.boolean(SettingsKey.DNS_PROTECT) { true }
    var disableIpv6 by dataStore.boolean(SettingsKey.DISABLE_IPV6) { true }
    var webrtcProtect by dataStore.boolean(SettingsKey.WEBRTC_PROTECT) { true }
    var chinaDirect by dataStore.boolean(SettingsKey.CHINA_DIRECT) { true }
    var adsBlock by dataStore.boolean(SettingsKey.ADS_BLOCK) { true }
    var configNormalize by dataStore.boolean(SettingsKey.CONFIG_NORMALIZE) { true }
    var chinaDefaultsRev by dataStore.int(SettingsKey.CHINA_DEFAULTS_REV) { 0 }
    var chainEnabled by dataStore.boolean(SettingsKey.CHAIN_ENABLED) { false }
    var chainEntryTag by dataStore.string(SettingsKey.CHAIN_ENTRY_TAG) { "" }
    var chainLandingProfileId by dataStore.long(SettingsKey.CHAIN_LANDING_PROFILE_ID) { -1L }
    var chainLandingTag by dataStore.string(SettingsKey.CHAIN_LANDING_TAG) { "" }
    var chainBoundProfileId by dataStore.long(SettingsKey.CHAIN_BOUND_PROFILE_ID) { -1L }
    var chainBindingsJson by dataStore.string(SettingsKey.CHAIN_BINDINGS) { "{}" }
    var profileStableIdsJson by dataStore.string(SettingsKey.PROFILE_STABLE_IDS) { "{}" }
    var webdavUrl by dataStore.string(SettingsKey.WEBDAV_URL) { "" }
    var webdavUser by dataStore.string(SettingsKey.WEBDAV_USER) { "" }
    var webdavPassword by dataStore.string(SettingsKey.WEBDAV_PASSWORD) { "" }
    var webdavRemoteFile by dataStore.string(SettingsKey.WEBDAV_REMOTE_FILE) { "backup.zip" }
    var webdavProbeOk by dataStore.int(SettingsKey.WEBDAV_PROBE_OK) { -1 }
    var restoreCompat by dataStore.boolean(SettingsKey.RESTORE_COMPAT) { false }
    var perAppProxyEnabled by dataStore.boolean(SettingsKey.PER_APP_PROXY_ENABLED) { true }
    var perAppProxyMode by dataStore.int(SettingsKey.PER_APP_PROXY_MODE) { PER_APP_PROXY_EXCLUDE }
    var perAppProxyList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_LIST) { emptySet() }
    var perAppProxyManagedMode by dataStore.boolean(SettingsKey.PER_APP_PROXY_MANAGED_MODE) { true }
    var perAppProxyManagedList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_MANAGED_LIST) { emptySet() }

    // Set once a China-app scan has filled the managed list, even when it found nothing.
    var perAppProxyManagedScanned by dataStore.boolean(SettingsKey.PER_APP_PROXY_MANAGED_SCANNED) { false }
    const val PACKAGE_QUERY_MODE_SHIZUKU = "SHIZUKU"
    const val PACKAGE_QUERY_MODE_ROOT = "ROOT"
    var perAppProxyPackageQueryMode by dataStore.string(SettingsKey.PER_APP_PROXY_PACKAGE_QUERY_MODE) { PACKAGE_QUERY_MODE_SHIZUKU }
    var perAppProxyHideSystem by dataStore.boolean(SettingsKey.PER_APP_PROXY_HIDE_SYSTEM) { false }
    var perAppProxyHideOffline by dataStore.boolean(SettingsKey.PER_APP_PROXY_HIDE_OFFLINE) { true }
    var perAppProxyHideDisabled by dataStore.boolean(SettingsKey.PER_APP_PROXY_HIDE_DISABLED) { true }

    fun getEffectivePerAppProxyMode(): Int = if (perAppProxyManagedMode) PER_APP_PROXY_EXCLUDE else perAppProxyMode
    fun getEffectivePerAppProxyList(): Set<String> = if (perAppProxyManagedMode) perAppProxyManagedList else perAppProxyList

    /** Switch keys the rev<1 tier turns on for China users. */
    internal val CHINA_SAFEGUARD_KEYS: List<String> = listOf(
        SettingsKey.CHINA_DIRECT,
        SettingsKey.ADS_BLOCK,
        SettingsKey.WEBRTC_PROTECT,
        SettingsKey.DNS_PROTECT,
        SettingsKey.DISABLE_IPV6,
        SettingsKey.DISABLE_QUIC,
        SettingsKey.EXCLUDE_CN_QUIC,
    )

    /**
     * Pure migration planner for [applyChinaUserDefaultsIfNeeded]: returns
     * the (key, value) writes a tier still owes plus the rev to store.
     *
     * A tier only fills in switches that were never set. A key present in
     * the store was written by a user toggle and counts as a deliberate
     * choice — the old unconditional tiers silently re-opened switches
     * users had turned off on purpose (audit F-2/F-4). Absent keys on an
     * upgrade still carry the old off defaults, so filling those in is
     * exactly the tiers' original job. [chinaDefaultsRev] is the migration
     * marker: an install already at rev 3 gets no writes and is never
     * re-migrated.
     */
    internal fun chinaDefaultsMigration(
        chinaDefaultsRev: Int,
        isSet: (String) -> Boolean,
    ): Pair<List<Pair<String, Boolean>>, Int> {
        // Tiers are monotone (each owed tier advances the rev by one), so
        // gating on the incoming rev is the same as gating tier by tier.
        val writes = mutableListOf<Pair<String, Boolean>>()
        if (chinaDefaultsRev < 1) {
            for (key in CHINA_SAFEGUARD_KEYS) {
                if (!isSet(key)) writes += key to true
            }
        }
        if (chinaDefaultsRev < 2) {
            if (!isSet(SettingsKey.CONFIG_NORMALIZE)) {
                writes += SettingsKey.CONFIG_NORMALIZE to true
            }
        }
        if (chinaDefaultsRev < 3) {
            // 1.0.79 forced strict route on. It drops packets while Wi-Fi
            // and cellular swap, which shows up as the whole device going
            // offline. Only the never-set case is filled in; a stored value
            // may be a deliberate post-1.0.79 choice and stays untouched.
            if (!isSet(SettingsKey.STRICT_ROUTE)) {
                writes += SettingsKey.STRICT_ROUTE to false
            }
        }
        val newRev = if (chinaDefaultsRev < 3) 3 else chinaDefaultsRev
        return writes to newRev
    }

    /** One-shot: turn on China-user safeguards for installs that still have the old off defaults. */
    fun applyChinaUserDefaultsIfNeeded() {
        val (writes, newRev) = chinaDefaultsMigration(chinaDefaultsRev) { key ->
            dataStore.getBoolean(key) != null
        }
        writes.forEach { (key, value) -> dataStore.putBoolean(key, value) }
        if (newRev != chinaDefaultsRev) chinaDefaultsRev = newRev
    }

    var allowBypass by dataStore.boolean(SettingsKey.ALLOW_BYPASS) { false }
    var systemProxyEnabled by dataStore.boolean(SettingsKey.SYSTEM_PROXY_ENABLED) { true }
    var privilegeSettingsEnabled by dataStore.boolean(SettingsKey.PRIVILEGE_SETTINGS_ENABLED) { false }
    var privilegeSettingsList by dataStore.stringSet(SettingsKey.PRIVILEGE_SETTINGS_LIST) { emptySet() }
    var privilegeSettingsInterfaceRenameEnabled by dataStore.boolean(SettingsKey.PRIVILEGE_SETTINGS_INTERFACE_RENAME_ENABLED) { false }
    var privilegeSettingsInterfacePrefix by dataStore.string(SettingsKey.PRIVILEGE_SETTINGS_INTERFACE_PREFIX) { "wlan" }
    var oomKillerEnabled by dataStore.boolean(SettingsKey.OOM_KILLER_ENABLED) { false }
    var oomKillerDisabled by dataStore.boolean(SettingsKey.OOM_KILLER_DISABLED) { true }
    var oomMemoryLimitMB by dataStore.int(SettingsKey.OOM_MEMORY_LIMIT_MB) { 50 }
    var powerReportEnabled by dataStore.boolean(SettingsKey.POWER_REPORT_ENABLED) { false }
    var dashboardItemOrder by dataStore.string(SettingsKey.DASHBOARD_ITEM_ORDER) { "" }
    var dashboardDisabledItems by dataStore.stringSet(SettingsKey.DASHBOARD_DISABLED_ITEMS) { emptySet() }
    var dashboardStyleVersion by dataStore.int(SettingsKey.DASHBOARD_STYLE_VERSION) { 0 }
    var activeRemoteServerId by dataStore.long(SettingsKey.ACTIVE_REMOTE_SERVER_ID) { 0L }
    var tailscaleSSHRememberedUsernames by dataStore.map(SettingsKey.TAILSCALE_SSH_REMEMBERED_USERNAMES)
    var tailscaleSSHRememberedTerminalTypes by dataStore.map(SettingsKey.TAILSCALE_SSH_REMEMBERED_TERMINAL_TYPES)
    var tailscaleSSHQuickConnectPeers by dataStore.stringSet(SettingsKey.TAILSCALE_SSH_QUICK_CONNECT_PEERS)
    var tailscaleSSHLightTheme by dataStore.string(SettingsKey.TAILSCALE_SSH_LIGHT_THEME) { "Alabaster" }
    var tailscaleSSHDarkTheme by dataStore.string(SettingsKey.TAILSCALE_SSH_DARK_THEME) { "Afterglow" }
    var tailscaleSSHFontFamily by dataStore.string(SettingsKey.TAILSCALE_SSH_FONT_FAMILY)
    var tailscaleSSHFontSize by dataStore.int(SettingsKey.TAILSCALE_SSH_FONT_SIZE) { 14 }
    var tailscaleSSHCustomFontPath by dataStore.string(SettingsKey.TAILSCALE_SSH_CUSTOM_FONT_PATH)
    var tailscaleSSHLightConfig by dataStore.string(SettingsKey.TAILSCALE_SSH_LIGHT_CONFIG)
    var tailscaleSSHDarkConfig by dataStore.string(SettingsKey.TAILSCALE_SSH_DARK_CONFIG)
    var tailscaleSSHFontFollowTheme by dataStore.boolean(SettingsKey.TAILSCALE_SSH_FONT_FOLLOW_THEME) { true }
    var cachedUpdateInfo by dataStore.string(SettingsKey.CACHED_UPDATE_INFO) { "" }
    var cachedApkPath by dataStore.string(SettingsKey.CACHED_APK_PATH) { "" }
    var lastShownUpdateVersion by dataStore.int(SettingsKey.LAST_SHOWN_UPDATE_VERSION) { 0 }

    fun serviceClass(): Class<*> = when (serviceMode) {
        ServiceMode.VPN -> VPNService::class.java
        else -> ProxyService::class.java
    }

    suspend fun rebuildServiceMode(): Boolean {
        var newMode = ServiceMode.NORMAL
        try {
            if (needVPNService()) newMode = ServiceMode.VPN
        } catch (_: Exception) {
        }
        if (serviceMode == newMode) return false
        serviceMode = newMode
        return true
    }

    private suspend fun needVPNService(): Boolean {
        // Runtime always inserts a TUN inbound. The saved subscription often
        // has none, so parsing the file used to pick ProxyService, whose
        // openTun is a stub and surfaces as "invalid argument".
        val selectedProfileId = selectedProfile
        if (selectedProfileId == -1L) return false
        return ProfileManager.get(selectedProfileId) != null
    }

    fun closeDatabase() {
        synchronized(dbLock) {
            runCatching { db?.close() }
            db = null
        }
    }
}
