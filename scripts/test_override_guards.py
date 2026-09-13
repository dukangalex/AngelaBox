#!/usr/bin/env python3
"""Source guards for ChainBox overlay modules. Run from repo root."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def main() -> int:
    errors: list[str] = []
    normalize = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigNormalize.kt")
    if "fun apply(" in normalize:
        errors.append("ConfigNormalize rewriter was removed; do not add apply()")
    if "webrtcRejectRules" not in normalize:
        errors.append("WebRTC STUN reject helper missing")
    if "STUN_UDP_PORTS" not in normalize or "domain_keyword" not in normalize:
        errors.append("WebRTC overlay must cover extra STUN ports and stun./turn. hostnames")
    if "cnDomainSuffixArray" not in normalize:
        errors.append("CN domain helper missing")

    override = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigQuicOverride.kt")
    if "Settings.configNormalize" in override:
        errors.append("ConfigQuicOverride must not call config normalize")
    if "ChainBindings.get" not in override:
        errors.append("runtime chain must look up the current profile binding")

    ui_override = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ProfileOverrideScreen.kt")
    if "配置规范化" in ui_override or "configNormalize" in ui_override:
        errors.append("Profile override UI must not expose config normalize")

    compat = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigCompat.kt")
    if "plugin_opts" not in compat or "objectToPluginOpts" not in compat:
        errors.append("ConfigCompat must coerce Clash plugin_opts objects to strings")

    chain = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainRuntimeCompiler.kt")
    if "fail_closed" in chain:
        errors.append("Chain compiler must not emit fail_closed; kernel ChainOutboundOptions only has outbounds")
    if "不可作为前置代理" in chain:
        errors.append("Chain compiler must not fail closed just because a selector contains DIRECT")
    if "fun resolveMainTag" not in chain:
        errors.append("resolveMainTag should be reusable by the UI")
    if "isFinalLike" not in chain:
        errors.append("isFinalLike missing; 漏网之鱼 would be locked as entry again")
    if "landing/exit" not in chain and "public IP" not in chain:
        errors.append("chain compiler should document packet path: entry first, landing last")
    if "if (sameProfile) add(req.landingTag)" not in chain:
        errors.append("cross-profile landing tags must not be extraExcluded from the entry hop")
    if "rewriteDnsDetours" in chain:
        errors.append("compiler must not rewrite DNS detours onto the chain")
    if "DNS detours stay" not in chain and "DNS detours are left" not in chain:
        errors.append("compiler must leave DNS detours on the original outbound (one hop)")
    if "inPlace = true" not in chain and "inPlace=true" not in chain:
        errors.append("same-profile group hops must mutate in place to avoid double urltest")
    if "跨配置落地内容缺失" not in chain:
        errors.append("empty cross-profile landing content must fail closed")

    bindings = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainBindings.kt")
    if "per-profile" not in bindings.lower() and "Per-profile" not in bindings:
        errors.append("ChainBindings must document per-profile isolation")
    if "chainBindingsJson" not in read("app/src/main/java/io/nekohasekai/sfa/database/Settings.kt"):
        errors.append("Settings.chainBindingsJson missing")

    reapply = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigChainReapply.kt")
    if "ChainBindings.get(currentProfileId)" not in reapply:
        errors.append("runtime reapply must only chain the selected profile")

    ui = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ChainBuilderScreen.kt")
    if 'picker == "entry"' not in ui:
        errors.append("Chain builder must let the user pick the entry hop")
    if "ChainBindings.put" not in ui:
        errors.append("Chain builder must save a per-profile binding")
    if "仅绑定当前" not in ui and "只绑定当前" not in ui:
        errors.append("Chain builder UI must say the binding is current-profile only")
    if "订阅更新" not in ui:
        errors.append("Chain builder should tell users bindings survive subscription refresh")
    for leak in ("Kitty", "MYCF", "edgetunne", "edgtgt", "longteng"):
        if leak in ui:
            errors.append(f"Chain builder UI must not hardcode airport name {leak}")
    if "validation-only" not in ui and "仅用于提前校验" not in ui and "validation-only here" not in ui:
        errors.append("save() must comment that apply() is validation-only")
    if 'popBackStack("dashboard"' not in ui:
        errors.append("saving a chain should return to the dashboard")
    if "showOtherBound" not in ui or "otherBoundLines" not in ui:
        errors.append("chain builder must let the user tap to see which other profiles are bound")
    if "点此查看" not in ui and "点这里查看" not in ui:
        errors.append("other-binding hint should be tappable")

    locales = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/AppSettingsScreen.kt")
    if "locales_config" not in locales:
        errors.append("Language picker must read res/xml/locales_config.xml")

    cn = read("app/src/main/res/values-zh-rCN/strings.xml")
    for key in ("core", "service", "network_quality", "silent_install", "remote_control", "chain_builder"):
        if f'name="{key}"' not in cn:
            errors.append(f"zh-rCN missing {key}")

    settings = read("app/src/main/java/io/nekohasekai/sfa/database/Settings.kt")
    if "webrtcProtect" not in settings:
        errors.append("Settings.webrtcProtect missing")
    if 'WEBRTC_PROTECT) { false }' in settings or 'WEBRTC_PROTECT) {false}' in settings:
        errors.append("WebRTC protect should default on so Chinese STUN cannot leak by default")
    if "configNormalize" in settings:
        errors.append("Settings.configNormalize must stay removed")
    if "adsBlock" not in settings:
        errors.append("Settings.adsBlock missing")
    if "ADS_BLOCK" not in read("app/src/main/java/io/nekohasekai/sfa/constant/SettingsKey.kt"):
        errors.append("SettingsKey.ADS_BLOCK missing")
    if "echDns" in settings or "ECH_DNS" in settings:
        errors.append("ECH overlay was removed; Settings.echDns must not return")
    if "fun closeDatabase" not in settings:
        errors.append("Settings.closeDatabase missing; restore would hit open WAL")
    if "db?.close()" not in settings and "db = null" not in settings:
        errors.append("Settings.closeDatabase must drop the Room instance so restore can reopen")
    if "setQueryExecutor { GlobalScope.launch" in settings:
        errors.append("Settings must not queue Room queries on GlobalScope after close")
    if "restoreCompat" not in settings:
        errors.append("Settings.restoreCompat missing")

    profiles = read("app/src/main/java/io/nekohasekai/sfa/database/ProfileManager.kt")
    if "db = null" not in profiles:
        errors.append("ProfileManager.closeDatabase must drop the Room instance so restore can reopen")
    if "setQueryExecutor { GlobalScope.launch" in profiles:
        errors.append("ProfileManager must not queue Room queries on GlobalScope after close")

    china = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigChinaDirect.kt")
    for needle in ("ip_is_private", "CHINA_DNS_IPS", "CHINA_DNS_DOMAINS", "LAN_DOMAIN_SUFFIXES", "cnDomainSuffixArray"):
        if needle not in china:
            errors.append(f"China direct overlay missing {needle}")
    if "applyEchDns" in china or "ECH_DNS_TAG" in china or "unblockHttpsQueries" in china:
        errors.append("ECH DNS overlay must stay removed from ConfigChinaDirect")
    if "applyCnDns" in china or '.put("server", "223.5.5.5")' in china:
        errors.append("China direct must not inject a DNS server (empty-direct detour crash)")
    if '.put("detour", directTag)' in china or 'put("detour", direct' in china:
        errors.append("China DNS must not set detour to empty direct (sing-box 1.12 rejects it)")
    if "dropLegacyChinaDns" not in china:
        errors.append("China direct must drop leftover chainbox-cn-dns from older overlays")
    if "DIRECT_FALLBACK_TAG" not in china:
        errors.append("findOrCreateDirect must not reuse a non-direct tag named direct")
    if "stripBrokenDnsDetours" not in china:
        errors.append("China direct should strip leftover empty-direct DNS detours")

    compat = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigCompat.kt")
    if "stripBrokenDnsDetours" not in compat:
        errors.append("ConfigCompat must strip DNS detours to empty/missing direct")
    if "isEmptyDirect" not in compat:
        errors.append("ConfigCompat must detect empty direct outbounds")
    if "migrateLegacyDns" not in compat:
        errors.append("ConfigCompat must migrate dns.fakeip / legacy address servers")
    if 'put("type", "fakeip")' not in compat:
        errors.append("legacy fakeip object must become type=fakeip server")
    if "migrateRcodeServers" not in compat:
        errors.append("ConfigCompat must convert type:rcode DNS servers to rule actions")
    if "unknown transport type: rcode" not in compat:
        errors.append("ConfigCompat must document rcode transport removal")
    if "MAX_CONFIG_CHARS" not in compat:
        errors.append("ConfigCompat.sanitize must cap JSON size")
    inbound = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigInboundCompat.kt")
    overlay = compat + inbound
    if "ConfigInboundCompat.apply" not in compat:
        errors.append("ConfigCompat.sanitize must call ConfigInboundCompat.apply")
    if "migrateLegacyInbounds" not in inbound:
        errors.append("ConfigInboundCompat must migrate inbound sniff/domain_strategy to route actions")
    if "rewriteGithubRawUrl" not in inbound or "testingcf.jsdelivr.net" not in inbound:
        errors.append("ConfigInboundCompat must rewrite GitHub raw rule-set URLs to testingcf jsDelivr")
    if "migrateSpecialOutbounds" not in inbound:
        errors.append("ConfigInboundCompat must convert type:dns / type:block outbounds")
    if "legacy inbound fields" not in overlay:
        errors.append("compat overlay must document 1.13 inbound field removal")

    override = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigQuicOverride.kt")
    if "Settings.chinaDirect" not in override:
        errors.append("ConfigQuicOverride must apply china direct")
    if "echDns" in override or "applyEchDns" in override:
        errors.append("ECH overlay must stay removed from ConfigQuicOverride")
    if "applyLogLevel" not in override or '"info"' not in override:
        errors.append("runtime overlay must force log.level=info")
    if "applyOne" not in override:
        errors.append("each overlay switch must apply in isolation so one failure cannot skip the rest")
    if "entryMissing" not in override:
        errors.append("subscription update should fall back when entry tag is gone")
    if "independent_cache\", true)" not in override and "independent_cache\", true" not in override:
        if 'dns.put("independent_cache", true)' not in override:
            errors.append("DNS protect must force-overwrite independent_cache")
    if "stripBrokenDnsDetours" not in override:
        errors.append("runtime overlay must strip empty-direct DNS detours after other switches")
    webrtc_call = override.find('applyOne(warnings, "防 WebRTC 泄露")')
    china_call = override.find('applyOne(warnings, "中国直连")')
    if webrtc_call < 0 or china_call < 0:
        errors.append("WebRTC and China Direct overlays must both apply")
    elif webrtc_call < china_call:
        errors.append("WebRTC reject must apply after China Direct so STUN ports win over CN bypass")
    installer = read("app/src/github/java/io/nekohasekai/sfa/vendor/SystemPackageInstaller.kt")
    if "launchVisibleInstaller" not in installer:
        errors.append("in-app update must show the system package installer UI")
    if 'throw IllegalStateException("请先允许' in installer:
        errors.append("unknown-app-sources prompt must not crash the UI thread")
    if "Toast.makeText" not in installer:
        errors.append("unknown-app-sources should toast instead of throwing")

    ui_override = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ProfileOverrideScreen.kt")
    if "ConfigAdBlock.apply" not in override:
        errors.append("ConfigQuicOverride must apply ad block")
    if "广告拦截" not in ui_override:
        errors.append("Profile override UI must expose 广告拦截")
    if "中国直连" not in ui_override:
        errors.append("Profile override UI must expose 中国直连")
    if "ECH" in ui_override or "echDns" in ui_override:
        errors.append("Profile override UI must not expose ECH")
    if "强制" not in ui_override:
        errors.append("Profile override UI should say overlays are forced")

    compiler = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainRuntimeCompiler.kt")
    if "保存的入口" in compiler and "已不存在" in compiler:
        errors.append("compiler must not fail closed when a saved entry tag disappeared after subscription update")
    if "MAX_CONFIG_CHARS" not in compiler:
        errors.append("compiler must cap JSON size before JSONObject(content)")
    if "pinTrafficToChain" not in compiler:
        errors.append("compiler must rewrite proxy routes so the entry cannot become the public exit")
    if "ENTRY_PREFIX" not in compiler:
        errors.append("compiler must keep generated entry hops from becoming the public exit")

    dav = read("app/src/main/java/io/nekohasekai/sfa/utils/BackupManager.kt")
    if "pickNonVpnNetwork" in dav:
        errors.append("WebDAV must follow split routing, not bypass VPN with pickNonVpnNetwork")
    if "Proxy.NO_PROXY" in dav:
        errors.append("WebDAV must not force Proxy.NO_PROXY; traffic should follow TUN/split routing")
    if "绕过 VPN" in dav:
        errors.append("WebDAV error copy must not say traffic bypasses the VPN")
    if "TrustManagerFactory" not in dav:
        errors.append("WebDAV should use the system TrustManager explicitly")
    if "AndroidCAStore" not in dav:
        errors.append("WebDAV should load AndroidCAStore")
    if "deleteSidecars" not in dav:
        errors.append("restore must delete sqlite WAL/SHM sidecars")
    if "isZipFile" not in dav:
        errors.append("restore must reject non-zip downloads")
    if "closeDatabase" not in dav:
        errors.append("restore must close Room before overwriting db files")
    if "classifyProbe" not in dav:
        errors.append("WebDAV probe must classify 401 as auth failure, not success")
    if "if (code == 401 || code == 403) return@runCatching true" in dav:
        errors.append("WebDAV probe must not treat HTTP 401 as success")
    if "authFailedMessage" not in dav:
        errors.append("WebDAV 401 must produce a dedicated auth error")
    if "compat: Boolean" not in dav:
        errors.append("restore must support compatibility mode")
    if "mergeProfilesFromBackup" not in dav:
        errors.append("compat restore must merge profiles so backup data coexists with current data")
    if "andSelect = false" not in dav:
        errors.append("compat restore must not replace the currently selected profile")
    if "keepUrl" in dav and "putSettingString(liveSettings, SettingsKey.WEBDAV_URL, keepUrl)" in dav:
        errors.append("overwrite restore should take WebDAV URL from the backup, not keep the live URL")
    main = read("app/src/main/java/io/nekohasekai/sfa/compose/MainActivity.kt")
    rec = main[main.find("RequestReconnectService") :]
    if "restartServiceForApplyChange" not in rec[:500]:
        errors.append("RequestReconnectService must stop/start the running service, not only rebind")
    box = read("app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt")
    reload = box[box.find("suspend fun serviceReload0") : box.find("fun getSystemProxyStatus")]
    if "notification.show" not in reload:
        errors.append("serviceReload must refresh the notification title to the new profile")
    backup_ui = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/BackupRestoreScreen.kt")
    if "共存" not in backup_ui:
        errors.append("compat restore copy must say backup data coexists with current data")
    if "完全替换" not in backup_ui:
        errors.append("overwrite restore copy must say backup fully replaces current data")
    if 'listOf("PROPFIND"' in dav or '"PROPFIND", "OPTIONS"' in dav:
        errors.append("WebDAV probe must not use PROPFIND; Android HttpURLConnection rejects it")
    if "friendlyProbeDetail" not in dav:
        errors.append("probe must hide ProtocolException / PROPFIND internals")
    if "HEAD" not in dav:
        errors.append("WebDAV probe should use HEAD/GET like the real backup path")

    dash = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardViewModel.kt")
    if "if (currentState.isLoading) return" in dash:
        errors.append("profile switch must not block on isLoading")
    if "selectedProfileId = profileId" not in dash:
        errors.append("profile switch must update UI immediately")
    dash_sel = dash[dash.find("fun selectProfile") : dash.find("fun editProfile")]
    if "RequestReconnectService" not in dash_sel:
        errors.append("switching profile while running must restart the service")
    if "serviceReload()" in dash_sel:
        errors.append("profile switch must restart the service so the notification follows the new profile")

    boot = read("app/src/main/java/io/nekohasekai/sfa/bg/BootReceiver.kt")
    if "ACTION_MY_PACKAGE_REPLACED" not in boot or "launchApp" not in boot:
        errors.append("update install must relaunch the app")

    icon_bg = read("app/src/main/res/values/ic_launcher_background.xml")
    if "#000000" in icon_bg or "#000" in icon_bg:
        errors.append("launcher background must be white, not black")
    if "#FFFFFF" not in icon_bg and "#ffffff" not in icon_bg:
        errors.append("launcher background should be #FFFFFF")

    icon_fg = read("app/src/main/res/drawable/ic_launcher_foreground.xml")
    gen = read("scripts/gen_cube_icon.py")
    if "#FBBF24" not in icon_fg and "#F59E0B" not in icon_fg:
        errors.append("launcher foreground must be a Rubik cube (orange-yellow top missing)")
    if "#0EA5E9" not in icon_fg:
        errors.append("launcher foreground 正面 must be saturated sky-blue #0EA5E9")
    if "They meet at (3, 3, 3)" not in gen and "meet at (3, 3, 3)" not in gen:
        errors.append("cube faces must meet at (3,3,3) so the silhouette is a solid hexagon")
    if "z=3" not in gen or "x=3" not in gen:
        errors.append("cube 正面 is z=3 (left) and 侧面 is x=3 (right); x=0+z=0 is a chevron")
    if "x=0 + z=0" not in gen and "chevron" not in gen:
        errors.append("cube generator must document that x=0+z=0 is a chevron, not a cube")
    if "two opposite" not in gen:
        errors.append("cube generator must document that x=0 and x=3 are opposite faces")
    if "#F43F5E" not in icon_fg and "#E11D48" not in icon_fg:
        errors.append("launcher foreground must be a Rubik cube (rose face missing)")
    if "gift" in icon_fg.lower() and "cube" not in icon_fg.lower():
        errors.append("launcher foreground should be a cube, not a gift box")
    if "assert_solid_cube" not in gen:
        errors.append("cube generator must assert the silhouette is filled, not a chevron hole")

    logs = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/log/LogModels.kt")
    if "filterLogLevel: LogLevel = LogLevel.INFO" not in logs:
        errors.append("log viewer default filter must be INFO")

    leaks = []
    for rel in (
        "app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ChainBuilderScreen.kt",
        "app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ProfileOverrideScreen.kt",
        "docs/USER_GUIDE.md",
        "README.md",
    ):
        text = read(rel)
        for leak in ("Kitty", "MYCF", "edgetunne", "longteng.de5"):
            if leak in text:
                leaks.append(f"{rel} contains private name {leak}")
    errors.extend(leaks)

    manifest = read("app/src/main/AndroidManifest.xml")
    if 'android:icon="@drawable/ic_menu"' in manifest:
        errors.append("QS tile must not use the upstream sing-box Z icon")
    if 'android:icon="@drawable/ic_qs_brand"' not in manifest:
        errors.append("QS tile should use the colored cube ic_qs_brand")
    tile_chunk = manifest[manifest.find('android:name=".bg.TileService"') : manifest.find('android:name=".bg.TileService"') + 700]
    if 'android:label="@string/app_name"' not in tile_chunk:
        errors.append("QS tile service must label itself with app_name")
    tile = read("app/src/main/java/io/nekohasekai/sfa/bg/TileService.kt")
    if "R.string.app_name" not in tile:
        errors.append("QS tile must set label to app_name at runtime so OEM caches refresh")
    if "ic_qs_brand" not in tile:
        errors.append("QS tile must set icon to ic_qs_brand at runtime")
    if "createWithResource" not in tile:
        errors.append("QS tile must push Icon.createWithResource so Samsung drops the cached Z")
    notif = read("app/src/main/java/io/nekohasekai/sfa/bg/ServiceNotification.kt")
    if 'setContentTitle("sing-box")' in notif or '?: "sing-box"' in notif:
        errors.append("service notification must not title itself sing-box")
    if "ic_qs_tile" not in notif:
        errors.append("service notification small icon should be ic_qs_tile")
    vpn = read("app/src/main/java/io/nekohasekai/sfa/bg/VPNService.kt")
    if '.setSession("sing-box")' in vpn:
        errors.append("VPN session name must match the app, not sing-box")
    importer = read(
        "app/src/main/java/io/nekohasekai/sfa/compose/screen/configuration/ProfileImportHandler.kt",
    )
    if "ConfigCompat.sanitize" not in importer:
        errors.append("JSON import must sanitize (legacy fakeip) before checkConfig")

    readme = read("README.md")
    if "1.14.0" not in readme:
        errors.append("README must state the synced upstream kernel version (1.14.0)")
    if "同步更新策略" not in readme:
        errors.append("README must include the kernel/app sync strategy section")
    if "chain-dev" not in readme:
        errors.append("README must name the kernel branch chain-dev")
    if "外挂" in readme:
        errors.append("README must stay professional; do not use 外挂")
    if "不必为跟版而跟版" in readme:
        errors.append("README must use formal sync-policy wording")
    if "KERNEL_UPSTREAM" not in read("docs/MAINTENANCE.md") and "check_upstream_features" not in read("docs/MAINTENANCE.md"):
        errors.append("MAINTENANCE must document the official feature check")
    if "外挂" in read("docs/MAINTENANCE.md"):
        errors.append("MAINTENANCE must stay professional; do not use 外挂")
    if "3205" not in read("docs/USER_GUIDE.md"):
        errors.append("USER_GUIDE must document official detour TLS limitation #3205")
    if "DNS" not in read("docs/USER_GUIDE.md") or "一跳" not in read("docs/USER_GUIDE.md"):
        errors.append("USER_GUIDE must say DNS stays one hop")
    props = read("version.properties")
    if "KERNEL_UPSTREAM=1.14.0" not in props:
        errors.append("version.properties must record KERNEL_UPSTREAM")
    if "KERNEL_BRANCH=chain-dev" not in props:
        errors.append("version.properties must record KERNEL_BRANCH")
    dash = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardViewModel.kt")
    if "ChainPath" not in dash:
        errors.append("dashboard must expose a ChainPath card")
    if "LiveTopology" not in dash:
        errors.append("dashboard must expose live topology")
    if "ConnectionType.Connections" not in dash:
        errors.append("dashboard must subscribe to live connections for topology")
    if "ConnectionType.Outbounds" not in dash:
        errors.append("dashboard must subscribe to outbounds for latency")
    if "updateOutbounds" not in dash:
        errors.append("dashboard must apply outbound urltest delays")
    if "testSelectedDelay" not in dash:
        errors.append("dashboard must be able to urltest the selected node")
    if "TrafficFlowBuilder" not in read("app/src/main/java/io/nekohasekai/sfa/chain/TrafficFlow.kt"):
        errors.append("live topology must build a radiating traffic flow")
    path_card = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/ChainPathCard.kt")
    if "LiveTopology" not in path_card:
        errors.append("chain path card must render LiveTopology, not a static PPT")
    if "TrafficSankey" not in path_card and "SankeyLayout" not in path_card:
        errors.append("chain path card must render a live sankey topology")
    if "phase" not in path_card:
        errors.append("live topology must animate traffic flow")
    if "node.w + 5f" not in path_card:
        errors.append("sankey labels must sit beside thin bars, not inside wide pills")
    if "0xFF22D3EE" not in path_card:
        errors.append("sankey colors should follow the radiating source-rule-hop-dest palette")
    if "0xFF64748B" not in path_card:
        errors.append("DIRECT hops and ribbons must use a distinct slate color")
    if "verticalScroll" not in path_card:
        errors.append("sankey must scroll instead of crushing overlapping labels")
    if "maxLines = 1" not in path_card and "maxLines = 2" not in path_card:
        errors.append("sankey labels must not wrap into overlapping stacks")
    if "headlineSmall" not in path_card and "displayMedium" not in path_card:
        errors.append("path card should show live down/up rates like the home topology")
    if "displayMedium" not in path_card:
        errors.append("home path should use a large downlink number")
    if "112.dp" in path_card:
        errors.append("do not reserve a floating dest gutter; use equal columns")
    flow = read("app/src/main/java/io/nekohasekai/sfa/chain/TrafficFlow.kt")
    if "prettyHop" not in flow:
        errors.append("generated chain tags must be stripped before they become hop labels")
    if "val direct: Boolean" not in flow:
        errors.append("flow nodes/links must flag DIRECT traffic")
    if "MAX_COLUMN" not in flow:
        errors.append("live path must stay within four columns so labels fit")
    if "BrandMark" in path_card:
        errors.append("decorative cube must be a start/stop control, not BrandMark")
    if "onToggleService" not in path_card:
        errors.append("home icon must start/stop the service")
    if "ic_launcher" not in path_card and "ic_qs_brand" not in path_card:
        errors.append("home power control must use the AngelaBox icon")
    if "painterResource(R.mipmap" in path_card or "R.mipmap.ic_launcher" in path_card:
        errors.append("Compose must not load adaptive mipmap icons; that crashes on launch")
    if "ic_launcher_foreground" not in path_card and "ic_qs_brand" not in path_card:
        errors.append("home power control must use a vector drawable, not an adaptive icon")
    if "PowerMark" not in path_card:
        errors.append("home hero should include a power mark that toggles the service")
    if "ModeChip" not in path_card:
        errors.append("clash mode must stay reachable from the home chips")
    if "onShowProfilePicker" not in path_card:
        errors.append("profile picker must stay reachable from the home chips")
    if "onUpdateCurrentProfile" not in path_card:
        errors.append("home must expose 更新当前配置")
    if "title_configuration" not in path_card:
        errors.append("home must expose a 配置 button that opens all profiles")
    if "chain_path_exit" not in path_card:
        errors.append("chained node row must show 出口 together with 入口")
    if "topology.chained && entryName.isNotBlank() && entryName != nodeName" in path_card:
        errors.append("chained 入口 must stay visible even when the title matches 出口")
    if "homeHidden" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardScreen.kt"):
        errors.append("home must hide the duplicate debug/mode/profile cards")
    dash = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardScreen.kt")
    if "localHomeCards" not in dash:
        errors.append("local dashboard must only render the new path UI")
    if "if (!isRemote)" not in dash and "if (!isRemote) {" not in dash:
        errors.append("local home must force ChainPath even when dashboard_items hid it")
    if "dashboard_items" in dash:
        errors.append("local dashboard must not expose the official card picker")
    if "systemProxyVisible" not in path_card:
        errors.append("system proxy must stay reachable from the new home")
    if "R.string.memory" not in path_card:
        errors.append("debug memory/goroutines must stay visible on the new home")
    if "shortenNodeName" not in flow:
        errors.append("landing hop labels must be shortened for display")
    if "chainedHopPair" not in flow:
        errors.append("chained sankey must keep entry and landing as two hops")
    if "FlyCat" in readme or "FlyCat" in read("docs/MAINTENANCE.md") or "FlyCat" in read("docs/USER_GUIDE.md"):
        errors.append("docs must not mention FlyCat; this Sankey is original")
    guide = read("docs/USER_GUIDE.md")
    if "按分流" not in guide and "按当前分流" not in guide:
        errors.append("USER_GUIDE must say WebDAV follows split routing")
    if "t.me/AngelaBox" not in readme:
        errors.append("README must link the Telegram channel")
    if "t.me/AngelaBox" not in read("docs/MAINTENANCE.md"):
        errors.append("MAINTENANCE must link the Telegram channel")
    if "topology.destinations.joinToString" in path_card:
        errors.append("do not show unused destination caption on home")
    if "dukangalex/AngelaBox" not in readme:
        errors.append("README must point at dukangalex/AngelaBox")
    if "dukangalex/AngelaBox" not in read("docs/MAINTENANCE.md"):
        errors.append("MAINTENANCE must point at dukangalex/AngelaBox")
    if "defaultDisabledCards" not in dash and "localHomeCards" not in dash:
        errors.append("dashboard should hide duplicate upload/download cards by default")
    if "chartHeight = 18.dp" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/UploadTrafficCard.kt"):
        errors.append("traffic cards should use a compact sparkline")
    if "AngelaBox" not in read("app/src/main/res/values/strings.xml"):
        errors.append("app_name must be AngelaBox")
    downloader = read("app/src/github/java/io/nekohasekai/sfa/vendor/ApkDownloader.kt")
    if "expectedSha256" not in downloader or "SHA-256" not in downloader:
        errors.append("in-app update must verify APK SHA-256 when the release sidecar exists")
    checker = read("app/src/github/java/io/nekohasekai/sfa/vendor/GitHubUpdateChecker.kt")
    if "pickSha256" not in checker:
        errors.append("GitHub update checker must fetch the APK SHA-256 asset")
    if "dukangalex/AngelaBox/releases" not in checker:
        errors.append("GitHub update checker must query dukangalex/AngelaBox")
    workflow = read(".github/workflows/build-chainbox.yml")
    if "check_upstream_features.py" not in workflow:
        errors.append("release workflow must check official type constants")
    if "AngelaBox-android.apk" not in workflow:
        errors.append("release must publish AngelaBox-android.apk")
    if "ChainBox-android.apk.sha256" not in workflow:
        errors.append("release workflow must attach APK SHA-256")
    if "KERNEL_COMMIT" not in workflow:
        errors.append("release workflow must record the kernel commit SHA")
    if "TG_BOT_TOKEN" not in workflow:
        errors.append("build-chainbox.yml must notify Telegram after publish")
    if "api.telegram.org" not in workflow and "telegram_send.py" not in workflow:
        errors.append("build-chainbox.yml must notify Telegram after publish")
    if "sendDocument" not in workflow and "telegram_send.py" not in workflow:
        errors.append("build-chainbox.yml must upload the APK to Telegram")
    if "telegram_announce.py" not in workflow:
        errors.append("build-chainbox.yml must build Telegram notes from telegram_announce.py")
    if "telegram_send.py" not in workflow:
        errors.append("build-chainbox.yml must send Telegram posts via telegram_send.py (curl -F hits secret masking)")
    if "reply_markup" not in workflow and "telegram_send.py" not in workflow:
        errors.append("Telegram notify must include a download button")
    if "disable_web_page_preview" not in workflow and "telegram_send.py" not in workflow:
        errors.append("Telegram notify must disable GitHub link preview so the channel shows notes+APK")
    if "telegram-caption.txt" not in read("scripts/telegram_send.py") and "telegram-caption.txt" not in workflow:
        errors.append("Telegram APK caption must come from telegram_announce.py")
    if "filename=AngelaBox-android.apk" not in read("scripts/telegram_send.py") and '"AngelaBox-android.apk"' not in read("scripts/telegram_send.py"):
        errors.append("Telegram sendDocument must set filename so the APK is installable")
    send_py = read("scripts/telegram_send.py")
    if "timeout=180" in send_py:
        errors.append("Telegram upload timeout 180s is too short for a 40MB APK")
    if "sendDocument runs first" in send_py or "changelog first" not in send_py.lower():
        errors.append("telegram_send.py must post sendMessage before sendDocument so a timeout cannot hide the channel notes")
    if "RETRIES" not in send_py:
        errors.append("telegram_send.py must retry sendDocument after write timeout")
    if "CHUNK" not in send_py:
        errors.append("telegram_send.py must stream the APK in chunks instead of one ssl.sendall")
    if "PYTHONUNBUFFERED" not in workflow:
        errors.append("build-chainbox.yml Telegram step must be unbuffered so upload progress is visible")
    if "body_path: release-body.md" not in workflow:
        errors.append("GitHub release body must come from generated notes")
    if "same-bytes alias" not in workflow:
        errors.append("release notes must say ChainBox-android.apk is the same file")
    telegram = read(".github/workflows/telegram.yml")
    if "TG_BOT_TOKEN" not in telegram or "TG_CHANNEL_ID" not in telegram:
        errors.append("telegram.yml must use TG_BOT_TOKEN and TG_CHANNEL_ID")
    if "sendDocument" not in telegram and "telegram_send.py" not in telegram:
        errors.append("telegram.yml should upload AngelaBox-android.apk")
    if "configured=false" not in telegram:
        errors.append("telegram.yml must skip when secrets are missing")
    if "name: Telegram Release" not in telegram:
        errors.append("telegram.yml must be named Telegram Release so it is findable in Actions")
    if "telegram_announce.py" not in telegram:
        errors.append("telegram.yml must use telegram_announce.py so notes and download button match")
    if "telegram_send.py" not in telegram:
        errors.append("telegram.yml must send via telegram_send.py")
    if "disable_web_page_preview" not in telegram and "telegram_send.py" not in telegram:
        errors.append("telegram.yml must disable GitHub link preview")
    if "timeout-minutes: 25" not in telegram and "timeout-minutes: 20" not in telegram:
        errors.append("telegram.yml job timeout must cover APK upload retries")
    if "PYTHONUNBUFFERED" not in telegram:
        errors.append("telegram.yml must be unbuffered so upload progress is visible")
    if "docs/brand/AngelaBox-icon-512.png" not in readme:
        errors.append("README must show the cube icon on the repository homepage")
    brand512 = ROOT / "docs/brand/AngelaBox-icon-512.png"
    brand1024 = ROOT / "docs/brand/AngelaBox-icon-1024.png"
    brand_og = ROOT / "docs/brand/AngelaBox-og.png"
    if not brand512.is_file() or brand512.stat().st_size < 1000:
        errors.append("docs/brand/AngelaBox-icon-512.png missing")
    if not brand1024.is_file() or brand1024.stat().st_size < 1000:
        errors.append("docs/brand/AngelaBox-icon-1024.png missing")
    if not brand_og.is_file() or brand_og.stat().st_size < 1000:
        errors.append("docs/brand/AngelaBox-og.png missing (GitHub social preview)")
    if "t.me/AngelaBox" not in read("docs/MAINTENANCE.md"):
        errors.append("MAINTENANCE must document the Telegram channel")
    if "TG_BOT_TOKEN" not in read("docs/MAINTENANCE.md"):
        errors.append("MAINTENANCE must document Telegram bot secrets")

    live = read("app/src/main/java/io/nekohasekai/sfa/chain/ChainPath.kt")
    if "leaves.size == 1" in live:
        errors.append("single live-chain leaf must not be assigned as landing")
    if "hop !in landingMembers" not in live:
        errors.append("entry live hop must not be taken from the landing group")
    if "hop !in entryMembers" not in live:
        errors.append("landing live hop must not be taken from the entry group")
    if "Role.Landing" not in path_card or "firstOrNull { it.role == ChainPathHop.Role.Landing }" not in path_card:
        errors.append("header 出口 must prefer Role.Landing over an entry leaf")
    if "real.size >= 2" not in flow:
        errors.append("sankey must use logged chain hops when the sample has two or more")
    if "THEME_MODE" not in read("app/src/main/java/io/nekohasekai/sfa/constant/SettingsKey.kt"):
        errors.append("SettingsKey.THEME_MODE missing")
    if "themeMode" not in settings:
        errors.append("Settings.themeMode missing")
    if "settings/theme" not in read("app/src/main/java/io/nekohasekai/sfa/compose/navigation/Navigation.kt"):
        errors.append("theme settings route missing")
    theme_ui = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/ThemeSettingsScreen.kt")
    if "Appearance.applyMode" not in theme_ui or "theme_pure_black" not in theme_ui:
        errors.append("theme page must expose mode and pure black")
    appearance = read("app/src/main/java/io/nekohasekai/sfa/compose/theme/Appearance.kt")
    if "fun setMode" in appearance or "fun setSeed" in appearance or "fun setPureBlack" in appearance:
        errors.append("Appearance apply* methods must not be named setMode/setSeed/setPureBlack (JVM setter clash)")
    if "字体修复" in theme_ui or "深色图标" in theme_ui:
        errors.append("theme page must not copy ROM-specific font/icon toggles")
    if 'name="theme_settings"' not in cn:
        errors.append("zh-rCN missing theme_settings")
    if "PathPreview" not in ui:
        errors.append("chain builder should show an entry → landing path preview")
    if "surfaceContainer" not in ui:
        errors.append("chain builder polish should use surface cards")

    if "looksLikeGroupTag" not in flow:
        errors.append("sankey must expand group tags like 自动选择 to the selected leaf")
    if "expandHopLabel" not in flow:
        errors.append("logged hops that are group tags must expand to the live leaf")
    if "findGroup" not in live:
        errors.append("live topology must match group tags with leading emoji stripped")
    if "looksLikeGroupTag" not in live:
        errors.append("displayNonDirect must not keep a group tag as 出口")
    if ".height(if (running) 200.dp" in path_card or ".height(if (running) 220.dp" in path_card:
        errors.append("running sankey must size to requiredHeight, not a clipped 200.dp box")
    if "requiredHeight" not in path_card:
        errors.append("running sankey must use SankeyLayout.requiredHeight so 5+ rule nodes are fully visible")
    if "480.dp" not in path_card:
        errors.append("sankey must allow a tall requiredHeight so the path diagram is complete")
    if "contentAlignment = Alignment.Center" not in path_card:
        errors.append("sankey must be vertically centered in its slot")
    if "覆写脚本" not in override:
        errors.append("ConfigQuicOverride must apply overlay scripts")
    if "ConfigScriptOverride.apply" not in override:
        errors.append("runtime overlay must run user scripts")
    script_idx = override.find("ConfigScriptOverride.apply")
    chain_idx = override.find("ConfigChainReapply.apply")
    if script_idx < 0 or chain_idx < 0 or chain_idx < script_idx:
        errors.append("scripts must run on the entry profile before chain merge")
    if override.count("ConfigChainReapply.apply") != 1:
        errors.append("chain must be applied once after scripts, not before and after")
    if "enabledFor" not in read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigScriptOverride.kt"):
        errors.append("scripts must apply per selected profile via enabledFor")
    if "fun enabledFor" not in read("app/src/main/java/io/nekohasekai/sfa/utils/OverlayScripts.kt"):
        errors.append("OverlayScripts.enabledFor missing")
    if "overlayScriptBindingsJson" not in settings:
        errors.append("Settings.overlayScriptBindingsJson missing")
    if "OVERLAY_SCRIPT_BINDINGS" not in read("app/src/main/java/io/nekohasekai/sfa/constant/SettingsKey.kt"):
        errors.append("SettingsKey.OVERLAY_SCRIPT_BINDINGS missing")
    if "ProfileScriptBinderCard" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/profile/EditProfileScreen.kt"):
        errors.append("edit profile must expose a script binder")
    if "ProfileScriptBinderDialog" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/ProfilePickerSheet.kt"):
        errors.append("profile picker must let each profile choose scripts")
    if "overlay_scripts_sync" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ScriptListScreen.kt"):
        errors.append("script list must offer URL sync")
    inbound = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigInboundCompat.kt")
    if 'rule.put("override_destination"' in inbound or "override_destination\", true" in inbound:
        errors.append("ConfigInboundCompat must not emit sniff override_destination (sing-box 1.14 rejects it)")
    if "stripSniffOverrideDestination" not in inbound:
        errors.append("leftover sniff override_destination must be stripped")
    if "sniff_override_destination" in read("app/src/main/assets/scripts/airport-region.js"):
        errors.append("sample script must not set sniff_override_destination")
    sample = read("app/src/main/assets/scripts/airport-region.js")
    if "Clash Meta" in sample or "clash:" in sample or "由 Clash" in sample:
        errors.append("default script must not mention third-party clients")
    if "geoip-fastly" in sample or "geosite-apple-cn" in sample or "geosite-biliintl" in sample:
        errors.append("default script must not inject rule-sets that 404 on testingcf jsDelivr")
    if "geoip-private" in sample or "geoip-telegram" in sample:
        errors.append("default script must not inject geoip files missing from sing-geoip rule-set")
    if "geosite-geolocation-!cn" not in sample:
        errors.append("default script must keep non-CN geolocation routing from the original sample")
    if "geosite-spotify" not in sample or "geosite-steam" not in sample:
        errors.append("default script must keep media/game rule-sets that exist on testingcf")
    if "REPLACE_GROUP_TYPES" not in sample or "isInfraRule" not in sample:
        errors.append("default script must replace original groups and routing, not merge a second set")
    if "for (var o = 0; o < oldRules.length; o++) merged.push(oldRules[o])" in sample:
        errors.append("default script must not keep the original route strategy alongside the overlay")
    if "overlay-revision: 5" not in sample:
        errors.append("default script must stamp overlay-revision: 5 so stale copies refresh")
    overlay_kt = read("app/src/main/java/io/nekohasekai/sfa/utils/OverlayScripts.kt")
    if 'SAMPLE_REVISION = "overlay-revision: 5"' not in overlay_kt:
        errors.append("OverlayScripts.SAMPLE_REVISION must match the bundled script stamp")
    if "override_address" in sample:
        errors.append("default script must not emit removed direct override_address")
    if 'type: "socks"' not in sample or "server_port: 9" not in sample:
        errors.append("REJECT/REJECT-DROP must be local socks blackholes, not direct override")
    if "healDirectDestinationOverride" not in inbound:
        errors.append("startup must strip removed direct override fields so old scripts still start")
    if '"override_address"' not in overlay_kt:
        errors.append("stale sample detector must refresh copies that still emit override_address")
    if "inherits catalog-enabled" in overlay_kt:
        errors.append("unbound profiles must not inherit catalog scripts")
    if "?: return emptyList()" not in overlay_kt:
        errors.append("enabledFor must return empty when the profile has no binding")
    if "bound ?: emptyList()" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/profile/ProfileScriptBinder.kt"):
        errors.append("profile script binder must not treat unbound as catalog-on")
    if "继承列表里的默认开关" in read("docs/USER_GUIDE.md"):
        errors.append("user guide must not say unbound profiles inherit catalog scripts")
    if 'SAMPLE_NAME = "默认脚本"' not in read("app/src/main/java/io/nekohasekai/sfa/utils/OverlayScripts.kt"):
        errors.append("bundled script must be named 默认脚本")
    if "dropMissingRemoteRuleSets" not in inbound:
        errors.append("startup must drop remote rule-sets that 404")
    ads_idx = override.find("ConfigAdBlock.apply")
    drop_idx = max(override.rfind("ConfigInboundCompat.apply"), override.rfind("dropMissingRemoteRuleSets"))
    if ads_idx < 0 or drop_idx < ads_idx:
        errors.append("404 rule-sets must be dropped after chain merge and later overlays")
    if "fun refreshStaleSample" not in overlay_kt or "fun sampleLooksStale" not in overlay_kt:
        errors.append("stale bundled sample must be replaced in place on start")
    if "refreshStaleSample" not in read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigScriptOverride.kt"):
        errors.append("script apply must refresh a previously imported default script")
    if "refreshStaleSample" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ScriptListScreen.kt"):
        errors.append("script list must refresh a stale default script so the editor is not the old copy")
    if "ScriptEditorPane" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ScriptListScreen.kt"):
        errors.append("script editor must be a fullscreen page")
    if "weight(1f)" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ScriptListScreen.kt"):
        errors.append("script editor code field must fill the screen")
    theme = read("app/src/main/java/io/nekohasekai/sfa/compose/theme/Theme.kt")
    if "onSurfaceVariant = Color(0xFFD6D6D6)" not in theme:
        errors.append("dark/pure-black theme must use high-contrast onSurfaceVariant")
    update_dlg = read("app/src/main/java/io/nekohasekai/sfa/compose/component/UpdateDialog.kt")
    if "onSurfaceVariant" in update_dlg:
        errors.append("update notes must use onSurface so dark theme caption stays readable")
    if "syntaxHighlightColor" not in update_dlg or "syntaxHighlightTextColor" not in update_dlg:
        errors.append("update markdown code chips must follow the theme, not LightGray")
    if "fun userFacingReleaseNotes" not in update_dlg:
        errors.append("in-app update notes must hide install/kernel dump from ordinary users")
    sankey = read("app/src/main/java/io/nekohasekai/sfa/chain/TrafficFlow.kt")
    if "val proxy = colNodes.filter { !it.direct }" not in sankey:
        errors.append("sankey layout must put proxy nodes above DIRECT")
    if "laneGap" not in sankey:
        errors.append("sankey must separate DIRECT and proxy lanes")
    path_card = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/ChainPathCard.kt")
    if "PathEffect.dashPathEffect" not in path_card:
        errors.append("path diagram must draw a lane divider between proxy and DIRECT")
    if "Color(0xFF22D3EE)" not in path_card:
        errors.append("proxy path must use a brighter tech palette")
    if "color = MaterialTheme.colorScheme.onSurface" not in read(
        "app/src/main/java/io/nekohasekai/sfa/compose/MainActivity.kt"
    ):
        errors.append("check-update prompt must use onSurface in dark theme")
    zh_cn = read("app/src/main/res/values-zh-rCN/strings.xml")
    zh_tw = read("app/src/main/res/values-zh-rTW/strings.xml")
    if "示例脚本" in zh_cn or "机场地区分组" in zh_cn:
        errors.append("zh-CN copy must not keep the old sample name")
    if "示例匯入" in zh_tw:
        errors.append("zh-TW empty-state must say 預設腳本, not 示例")
    if "Clash 字段" in zh_cn or "Clash 欄位" in zh_tw:
        errors.append("script catalog hint must not mention Clash keys")
    builder = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ChainBuilderScreen.kt")
    if "所有非中国流量不可直连" not in builder:
        errors.append("chain builder info must say non-China traffic cannot DIRECT")
    if "链式开启后，当前配置的脚本会自动关闭" not in builder:
        errors.append("chain builder info must say chain mutes scripts on the current profile")
    if "落地配置上的脚本不会执行" in builder or "脚本只对前置生效" in builder or "落地配置开启了脚本" in builder:
        errors.append("chain mode must no longer run scripts on the entry profile")
    if "port: \"3478:3480\"" in sample or "port: \"5349:5355\"" in sample:
        errors.append("STUN port ranges must use port_range, not port")
    if "port_range: \"3478:3480\"" not in sample:
        errors.append("default script must use sing-box port_range for STUN")
    if "isAnnouncement" not in sample:
        errors.append("default script must skip announcement/fake leaf nodes")
    if "链路只绑定当前这一份配置。切换到其他配置时" in builder:
        errors.append("chain builder on-page copy must move into the info dialog")
    if "isBypassDirectRule" not in compiler:
        errors.append("chain compiler must keep China/LAN DIRECT while pinning other DIRECT to chain")
    if "isDirectLike" not in compiler:
        errors.append("chain compiler must recognize DIRECT tags when pinning non-China traffic")
    if "🛑 广告拦截" not in sample or "REJECT-DROP" not in sample:
        errors.append("default script must expose 广告拦截 and REJECT-DROP like the original overlay")
    if "makeSelector(adsTag, [dropTag, rejectTag, directTag], dropTag)" not in sample:
        errors.append("广告拦截 must default to REJECT-DROP with REJECT and DIRECT")
    if "makeSelector(remoteTag, [dropTag, globalTag, directTag], dropTag)" not in sample:
        errors.append("远控工具 must default to REJECT-DROP with 国外服务 and DIRECT")
    if "ordered.push(remoteGroup)" not in sample:
        errors.append("region groups must be appended after 远控工具 so they sit last")
    if "ChainBindings.get(profileId) != null" not in read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigScriptOverride.kt"):
        errors.append("scripts must not apply when the profile has a chain binding")
    if "OverlayScripts.setBinding(boundId, emptyList())" not in builder:
        errors.append("saving a chain must clear scripts on that profile")
    if "ChainBindings.remove(profileId)" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/profile/ProfileScriptBinder.kt"):
        errors.append("enabling scripts must clear the profile chain binding")
    if "function main" not in read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigScriptOverride.kt"):
        errors.append("script engine must require function main(config)")
    if "initSafeStandardObjects" not in read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigScriptOverride.kt"):
        errors.append("Rhino must use initSafeStandardObjects")
    script_kt = read("app/src/main/java/io/nekohasekai/sfa/utils/ConfigScriptOverride.kt")
    if "setClassShutter" not in script_kt:
        errors.append("Rhino ClassShutter must be set via setClassShutter (the field is private)")
    if "cx.classShutter" in script_kt or ".classShutter =" in script_kt:
        errors.append("do not assign Context.classShutter; the field is private and fails release compile")
    if "org.mozilla:rhino" not in read("app/build.gradle.kts"):
        errors.append("app must depend on Mozilla Rhino to run overlay scripts")
    if "tools/scripts" not in read("app/src/main/java/io/nekohasekai/sfa/compose/navigation/Navigation.kt"):
        errors.append("scripts route missing")
    tools = read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ToolsScreen.kt")
    if "overlay_scripts" not in tools:
        errors.append("Tools must expose 脚本")
    if "function main(config)" not in read("app/src/main/assets/scripts/airport-region.js"):
        errors.append("bundled sample must be a sing-box function main(config) script")
    if "proxy-groups" in read("app/src/main/assets/scripts/airport-region.js"):
        errors.append("bundled sample must not emit Clash proxy-groups")
    if "type: \"urltest\"" not in read("app/src/main/assets/scripts/airport-region.js") and 'type: "urltest"' not in read("app/src/main/assets/scripts/airport-region.js"):
        errors.append("bundled sample must create sing-box urltest outbounds")
    if "load-balance" in read("app/src/main/assets/scripts/airport-region.js") and "GROUP_TYPES" not in read("app/src/main/assets/scripts/airport-region.js"):
        errors.append("bundled sample must not create Clash load-balance outbounds")
    sample = read("app/src/main/assets/scripts/airport-region.js")
    if '"type": "load-balance"' in sample or "type: \"load-balance\"" in sample:
        errors.append("bundled sample must not create load-balance outbounds (sing-box has no such type)")
    if "testingcf.jsdelivr.net" not in sample:
        errors.append("bundled sample rule-set URLs must use testingcf jsDelivr")
    if "Package: io.chainbox.app" in read("scripts/telegram_announce.py"):
        errors.append("Telegram copy must not show Package: to ordinary users")
    if "SHA256:" in read("scripts/telegram_announce.py") and "核对" not in read("scripts/telegram_announce.py"):
        errors.append("Telegram SHA-256 line must explain it is a file checksum")
    if "核对文件是否完整" not in read("scripts/telegram_announce.py"):
        errors.append("Telegram SHA-256 must be explained in plain language")
    if "overlayScriptsJson" not in settings:
        errors.append("Settings.overlayScriptsJson missing")
    if "OVERLAY_SCRIPTS" not in read("app/src/main/java/io/nekohasekai/sfa/constant/SettingsKey.kt"):
        errors.append("SettingsKey.OVERLAY_SCRIPTS missing")
    if 'name="overlay_scripts"' not in cn:
        errors.append("zh-rCN missing overlay_scripts")
    if 'name="overlay_scripts_sync"' not in cn:
        errors.append("zh-rCN missing overlay_scripts_sync")
    if 'name="overlay_scripts_profile_enable"' not in cn:
        errors.append("zh-rCN missing overlay_scripts_profile_enable")
    if "ScriptListScreen" not in read("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ScriptListScreen.kt"):
        errors.append("ScriptListScreen missing")
    if "painterResource(R.mipmap" in path_card:
        errors.append("Compose must not load adaptive mipmap icons")

    if errors:
        print("FAIL")
        for e in errors:
            print(" -", e)
        return 1
    print("override guards ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
