package io.nekohasekai.sfa.compose.screen.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.SettingsSwitchRow
import io.nekohasekai.sfa.compose.navigation.popToDashboard
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SwitchHelp(val title: String, val body: String)

/** B8: bump when the DNS-protect notice copy changes so it re-shows once. */
private const val DNS_PROTECT_NOTICE_CURRENT_REV = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileOverrideScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifyApplyChange = rememberApplyServiceChangeNotifier()

    var autoRedirect by remember { mutableStateOf(Settings.autoRedirect) }
    var onDemand by remember { mutableStateOf(Settings.onDemand) }
    var perAppProxyEnabled by remember { mutableStateOf(Settings.perAppProxyEnabled) }
    var managedModeEnabled by remember { mutableStateOf(Settings.perAppProxyManagedMode) }
    var isScanning by remember { mutableStateOf(false) }
    var webrtcProtect by remember { mutableStateOf(Settings.webrtcProtect) }
    var privacyProtect by remember { mutableStateOf(Settings.privacyProtect) }
    var adsBlock by remember { mutableStateOf(Settings.adsBlock) }
    var dnsProtect by remember { mutableStateOf(Settings.dnsProtect) }
    var dnsProtectLegacyFillOnly by remember { mutableStateOf(Settings.dnsProtectLegacyFillOnly) }
    // B8: dismissed when the stored rev reaches the current notice
    // revision, or the legacy boolean was set by an older version.
    var dnsNoticeDismissed by remember {
        mutableStateOf(
            Settings.dnsProtectNoticeDismissedRev >= DNS_PROTECT_NOTICE_CURRENT_REV ||
                Settings.dnsProtectNoticeDismissed,
        )
    }
    var vpnLeakProtect by remember { mutableStateOf(Settings.vpnLeakProtect) }
    var strictRoute by remember { mutableStateOf(Settings.strictRoute) }
    var chinaIpDomainDirect by remember { mutableStateOf(Settings.chinaIpDomainDirect) }
    var chinaPublicDnsDirect by remember { mutableStateOf(Settings.chinaPublicDnsDirect) }
    var lanDirect by remember { mutableStateOf(Settings.lanDirect) }
    var disableQuic by remember { mutableStateOf(Settings.disableQuic) }
    var excludeCnQuic by remember { mutableStateOf(Settings.excludeCnQuic) }
    var disableIpv6 by remember { mutableStateOf(Settings.disableIpv6) }
    var configNormalize by remember { mutableStateOf(Settings.configNormalize) }
    var help by remember { mutableStateOf<SwitchHelp?>(null) }
    // B6: whether the currently selected profile has a valid chain
    // binding (its chain-pinned final then outranks 防 VPN 泄露, D6).
    val selectedChainPinned = remember {
        ChainBindings.get(Settings.selectedProfile)?.takeIf { !it.invalidated } != null
    }

    // Resolved in composition so the click-time help lambdas can use them.
    val perAppManagedHint = stringResource(R.string.per_app_proxy_manage_managed_hint)
    val perAppManagedPrecedence = stringResource(R.string.per_app_proxy_managed_precedence)
    val excludeCnQuicRulesetNote = stringResource(R.string.exclude_cn_quic_ruleset_note)

    fun reload() {
        scope.launch(Dispatchers.Main) {
            notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
        }
    }

    fun scanAndSaveManagedList(shouldNotify: Boolean = false) {
        isScanning = true
        scope.launch {
            // A failed package query (root/Shizuku gone) must not crash the app.
            val scanned =
                try {
                    val chinaApps = PerAppProxyScanner.scanAllChinaApps()
                    withContext(Dispatchers.IO) {
                        Settings.perAppProxyManagedList = chinaApps
                        Settings.perAppProxyManagedScanned = true
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_SHORT).show()
                    false
                } finally {
                    isScanning = false
                }
            if (scanned && shouldNotify) {
                withContext(Dispatchers.Main) { reload() }
            }
        }
    }

    if (help != null) {
        val h = help!!
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(h.title) },
            text = { Text(h.body) },
            confirmButton = { TextButton(onClick = { help = null }) { Text("知道了") } },
        )
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text("系统显式") },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { navController.popToDashboard() }) {
                    Icon(Icons.Filled.Home, contentDescription = "返回主页")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalScaffoldPadding.current)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "以下为运行时强制覆盖，不修改订阅文件。开启后无论订阅有没有对应字段都会写入。这些开关在脚本之后生效，会覆盖脚本里的同质设置。点 ⓘ 查看说明。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            text = "隐私防护",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            SettingsSwitchRow(
                title = "防 WebRTC 泄露",
                subtitle = "优先拦截 STUN/TURN（含国内 STUN），避免真实 IP 漏出",
                checked = webrtcProtect,
                onHelp = {
                    help = SwitchHelp(
                        "防 WebRTC 泄露",
                        "在所有路由（含中国直连）之前拒绝 STUN/TURN：UDP 3478-3481 / 5349-5351 / 19302-19310，TCP 3478/5349，以及主机名含 stun./turn. 的请求。国内 STUN（如 bilibili、小米）同样拦截，不会因为中国直连而放过。开启后「工具 → STUN 测试」失败是预期。\n\n脚本在前先运行，系统显式分段在后覆盖：脚本里写了同一套 STUN/TURN 拦截时，以这里为准，不叠两套。\n\n边界：TURN over 443 与浏览器内部生成的 ICE 候选无法在配置层拦截，本开关覆盖常见 STUN/TURN 端口与协议特征。",
                    )
                },
                onCheckedChange = {
                    webrtcProtect = it
                    scope.launch(Dispatchers.IO) {
                        Settings.webrtcProtect = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                },
            )
            SettingsSwitchRow(
                title = "防隐私",
                // A7: say out loud that discovery-protocol rejects
                // still win while 局域网直连 is on (the segment
                // orders them that way; the help text said it, the
                // subtitle did not).
                subtitle = if (lanDirect) {
                    "通知不显示配置名，拦截局域网发现协议（局域网直连开着时发现协议仍拒绝优先）"
                } else {
                    "通知不显示配置名，拦截局域网发现协议"
                },
                checked = privacyProtect,
                onHelp = {
                    help = SwitchHelp(
                        "防隐私",
                        "开启后：前台通知只显示应用名，不显示配置名；局域网发现协议 mDNS（UDP 5353）、LLMNR（UDP 5355）、SSDP（UDP 1900）直接拒绝，避免设备与服务信息被局域网发现泄漏。\n\n与「局域网直连」不同：局域网直连是让局域网目标走直连放行，本项是把发现协议直接拒绝。两者可以同时开，发现协议的拒绝优先。",
                    )
                },
                onCheckedChange = {
                    privacyProtect = it
                    scope.launch(Dispatchers.IO) {
                        Settings.privacyProtect = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                },
            )
            SettingsSwitchRow(
                title = "广告拦截",
                subtitle = "拒绝 geosite 广告规则集匹配的域名",
                checked = adsBlock,
                onHelp = {
                    help = SwitchHelp(
                        "广告拦截",
                        "开启后在运行时注入官方 sing-geosite 的 geosite-category-ads-all 规则集，并对匹配流量执行 reject。脚本在前先运行，系统显式分段在后覆盖：脚本里写了广告规则时，以这里为准，不叠两套。不改订阅文件。首次开启会下载规则集。",
                    )
                },
                onCheckedChange = {
                    adsBlock = it
                    scope.launch(Dispatchers.IO) {
                        Settings.adsBlock = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                },
            )
        }
        Text(
            text = "防泄漏",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (!dnsNoticeDismissed) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DNS 防泄漏已改为强制覆写模式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        text = "现在开启后会强制指定最终 DNS 服务器并重排 DNS 规则，订阅写死的明文/直连 DNS 也会被覆盖，不再只补空缺。如需旧行为，可开下方「DNS 防泄漏使用旧版仅填空模式」（仅本版保留）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            dnsNoticeDismissed = true
                            scope.launch(Dispatchers.IO) {
                                // B8: record the notice revision
                                // dismissed, not just a boolean, so a
                                // future copy change can re-show it.
                                Settings.dnsProtectNoticeDismissedRev =
                                    DNS_PROTECT_NOTICE_CURRENT_REV
                                Settings.dnsProtectNoticeDismissed = true
                            }
                        },
                    ) {
                        Text("知道了")
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            SettingsSwitchRow(
                title = "防 DNS 泄露",
                subtitle = "FakeIP + 53 端口劫持，最终 DNS 强制走受保护服务器",
                checked = dnsProtect,
                onHelp = {
                    help = SwitchHelp(
                        "防 DNS 泄露",
                        "开启后：A/AAAA 查询走 FakeIP（应用只能拿到 198.18.0.0/15 假地址），53 端口与 DNS 协议流量劫持到内部 DNS，HTTPS/SVCB 查询直接拒绝，最终 DNS 服务器强制指向受保护服务器（覆写模式，会覆盖订阅写死的明文/直连 DNS）。\n\n边界：DoH over 443 与普通 HTTPS 无法区分，只能拦截已知端点；DoT/DoQ 的 853 端口会被拒绝。\n\n脚本开着时本开关仍在脚本之后强制生效。",
                    )
                },
            ) {
                dnsProtect = it
                scope.launch(Dispatchers.IO) {
                    Settings.dnsProtect = it
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            SettingsSwitchRow(
                title = "DNS 防泄漏使用旧版仅填空模式",
                // B7/B8: parent-off shows the uniform 需先开启 style;
                // when the fallback is on, the subtitle states the
                // mode actually in effect instead of only the plan.
                subtitle = when {
                    !dnsProtect -> "需先开启「防 DNS 泄露」"
                    dnsProtectLegacyFillOnly ->
                        "防 DNS 泄露的子项 · 仅本版保留，下个版本移除 · 当前实际生效：旧版仅填空模式"
                    else -> "防 DNS 泄露的子项 · 仅本版保留，下个版本移除"
                },
                checked = dnsProtectLegacyFillOnly,
                enabled = dnsProtect,
                onHelp = {
                    help = SwitchHelp(
                        "旧版仅填空模式",
                        "打开后 DNS 防泄漏回到旧语义：只补空缺，不覆盖订阅已写的最终服务器与策略。此开关仅在本版本保留，下个版本移除。",
                    )
                },
            ) {
                dnsProtectLegacyFillOnly = it
                scope.launch(Dispatchers.IO) {
                    Settings.dnsProtectLegacyFillOnly = it
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            SettingsSwitchRow(
                title = "防 VPN 泄露",
                // B6: when the selected profile has a valid chain
                // binding, D6 precedence becomes visible right here
                // instead of living only in the ⓘ text.
                subtitle = if (selectedChainPinned) {
                    "最终出口强制走代理；当前配置已绑链，链固定的 final 优先"
                } else {
                    "最终出口强制走代理；IPv6 靠「禁用 IPv6」兜底"
                },
                checked = vpnLeakProtect,
                onHelp = {
                    help = SwitchHelp(
                        "防 VPN 泄露",
                        "开启后，路由的最终出口（final）在订阅写成 direct 或留空时被强制改指代理/链式出口，避免流量绕过隧道直出。链式代理已固定的 final 优先，本开关不覆盖它。\n\nIPv6：当前隧道只捕获 IPv4。开着「禁用 IPv6」时 IPv6 在 DNS/路由层被拒绝，姿态成立；关掉它时 IPv6 流量可能不经隧道。\n\n系统级的「始终开启的 VPN / 阻断未使用 VPN 的连接」应用无法代设、也读不到开关状态，请在系统设置里确认（下方有入口）。",
                    )
                },
            ) {
                vpnLeakProtect = it
                scope.launch(Dispatchers.IO) {
                    Settings.vpnLeakProtect = it
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            // A6: live warning — with the parent on but IPv6 not
            // blocked, the leak posture does not hold (help text said
            // so; nothing on the page did).
            if (vpnLeakProtect && !disableIpv6) {
                Text(
                    text = "IPv6 未拦：当前隧道只捕获 IPv4，IPv6 可能绕过隧道。建议开启「禁用 IPv6」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ListItem(
                headlineContent = { Text("系统 VPN 设置") },
                supportingContent = {
                    Text("「始终开启的 VPN / 阻断未使用 VPN 的连接」在系统里开，应用读不到其状态")
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            SettingsSwitchRow(
                title = "严格路由",
                // B7: true sub-item of 防 VPN 泄露 — greyed with the
                // uniform 需先开启 subtitle while the parent is off;
                // the pipeline stage is gated the same way and the
                // stored value is never rewritten by the parent.
                subtitle = if (vpnLeakProtect) {
                    "防 VPN 泄露的子项；Android 上强制力有限，不要单独依赖它防泄漏"
                } else {
                    "需先开启「防 VPN 泄露」"
                },
                checked = strictRoute,
                enabled = vpnLeakProtect,
                onHelp = {
                    help = SwitchHelp(
                        "严格路由",
                        "打开后把 TUN strict_route 写成 true，关掉写成 false。\n\n" +
                            "切 Wi-Fi 和移动数据时，还没改绑到新网卡的包会被丢掉，这是防泄漏，不是把代理关掉。新网卡由系统回调交给内核。脚本在前先运行，系统显式分段在后覆盖：脚本里写了 strict_route 时，以这里为准。\n\n" +
                            "注：strict_route 的完整强制语义只在 Linux/Windows 实现，Android 上仅写入字段——本项已并入「防 VPN 泄露」作为子项，真正的防线在那里。父开关关闭时本项不生效，开关值本身保留。",
                    )
                },
            ) {
                strictRoute = it
                scope.launch(Dispatchers.IO) {
                    Settings.strictRoute = it
                    withContext(Dispatchers.Main) { reload() }
                }
            }
        }
        Text(
            text = "直连",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            SettingsSwitchRow(
                title = "中国 IP/域名直连",
                subtitle = "中国 IP 与域名走直连（规则集 + DNS 镜像）",
                checked = chinaIpDomainDirect,
                onHelp = {
                    help = SwitchHelp(
                        "中国 IP/域名直连",
                        "原「中国直连」开关拆分而来，本项管中国 IP 与域名：运行时注入 geosite-cn / geoip-cn 规则集并置于订阅规则之前直连，DNS 侧同步把中国域名导向国内 DNS 解析，避免境外 CDN 误路由。不改订阅文件。\n\n规则集下载失败时用内置清单兜底。公共 DNS 与局域网已拆为下面两项，可分别开关。脚本在前先运行，系统显式分段在后覆盖：脚本里写了同名直连规则时，以这里为准。",
                    )
                },
                onCheckedChange = {
                    chinaIpDomainDirect = it
                    scope.launch(Dispatchers.IO) {
                        Settings.chinaIpDomainDirect = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                },
            )
            SettingsSwitchRow(
                title = "中国公共 DNS 直连",
                subtitle = "阿里 / 114 / DNSPod 等公共 DNS 的 IP 与域名直连",
                checked = chinaPublicDnsDirect,
                onHelp = {
                    help = SwitchHelp(
                        "中国公共 DNS 直连",
                        "中国公共 DNS 的 IP（223.5.5.5、114.114.114.114、119.29.29.29 等，含 IPv6）与域名（dns.alidns.com、doh.pub 等）走直连，保证国内 DNS 的引导解析不绕代理、不成环。清单为内置静态名单，随版本更新。原「中国直连」拆分而来。脚本在前先运行，系统显式分段在后覆盖：脚本里写了同名处理时，以这里为准。",
                    )
                },
                onCheckedChange = {
                    chinaPublicDnsDirect = it
                    scope.launch(Dispatchers.IO) {
                        Settings.chinaPublicDnsDirect = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                },
            )
            SettingsSwitchRow(
                title = "局域网直连",
                subtitle = "局域网 IP 与 .local / .lan 等域名直连",
                checked = lanDirect,
                onHelp = {
                    help = SwitchHelp(
                        "局域网直连",
                        "私网地址（ip_is_private，含 CGNAT 兜底段）与局域网域名后缀（.local / .lan / .home 等）走直连。原「中国直连」拆分而来。\n\n与「防隐私」不同：本项是放行直连，防隐私是拒绝发现协议；同时开时发现协议的拒绝优先。脚本在前先运行，系统显式分段在后覆盖：脚本里写了同名规则时，以这里为准。",
                    )
                },
                onCheckedChange = {
                    lanDirect = it
                    scope.launch(Dispatchers.IO) {
                        Settings.lanDirect = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                },
            )
        }
        Text(
            // A4: was 「既有」 — a bucket name that said nothing. The
            // section holds QUIC / IPv6 / on-demand tunnel behaviour.
            text = "网络与隧道",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            SettingsSwitchRow(
                title = "禁用 QUIC",
                subtitle = "拒绝 UDP 443，并用复位让应用改走 TCP",
                checked = disableQuic,
                onHelp = {
                    help = SwitchHelp(
                        "禁用 QUIC",
                        "打开后拒绝 UDP 443。用内核默认复位，相当于 mihomo 的 REJECT，不是静默丢包。只走 QUIC 的应用会马上改走 TCP，而不是一直转圈。\n\n" +
                            "脚本在前先运行，系统显式分段在后覆盖：脚本里写了同一处理时，以这里为准，不写第二套。",
                    )
                },
            ) {
                // B7: turning the parent off no longer writes
                // excludeCnQuic=false (neither UI state nor storage).
                // The sub-item just greys out; its stored value is
                // kept, and the segment's QUIC read side already only
                // consults excludeCnQuic while disableQuic is on.
                disableQuic = it
                scope.launch(Dispatchers.IO) {
                    Settings.disableQuic = it
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            SettingsSwitchRow(
                title = "排除国内 QUIC",
                // B7: uniform parent-off subtitle, like the other
                // sub-items (was indistinguishable from the on text).
                subtitle = if (disableQuic) {
                    "国内 UDP 443 先直连，其余拒绝"
                } else {
                    "需先开启「禁用 QUIC」"
                },
                checked = excludeCnQuic,
                enabled = disableQuic,
                onHelp = {
                    help = SwitchHelp(
                        "排除国内 QUIC",
                        "和「禁用 QUIC」一起用。国内 IP、国内域名的 UDP 443 先走直连，其余 UDP 443 拒绝。关掉则 UDP 443 全部拒绝。\n\n" +
                            excludeCnQuicRulesetNote + "\n\n" +
                            "脚本在前先运行，系统显式分段在后覆盖：脚本里写了同一处理时，以这里为准。",
                    )
                },
            ) {
                excludeCnQuic = it
                scope.launch(Dispatchers.IO) {
                    Settings.excludeCnQuic = it
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            SettingsSwitchRow(
                title = "禁用 IPv6",
                subtitle = "仅 IPv4，避免 IPv6 旁路",
                checked = disableIpv6,
                onHelp = {
                    help = SwitchHelp(
                        "禁用 IPv6",
                        "强制 DNS strategy=ipv4_only，拦截 IPv6，并清空 TUN 的 IPv6 地址。关闭后走 IPv4/IPv6 双栈（prefer_ipv4，国内直连含 IPv6）。脚本在前先运行，系统显式分段在后覆盖：脚本里写了同一处理时，以这里为准。",
                    )
                },
            ) {
                disableIpv6 = it
                scope.launch(Dispatchers.IO) {
                    Settings.disableIpv6 = it
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            if (BuildConfig.KERNEL_UPSTREAM.startsWith("1.15")) {
                SettingsSwitchRow(
                    title = "按需连接",
                    subtitle = "空闲时断开 WireGuard / Tailscale / OpenVPN / OpenConnect 端点",
                    checked = onDemand,
                    onHelp = {
                        help = SwitchHelp(
                            "按需连接",
                            "对应官方 sing-box 1.15 的 on_demand。开启后，上述端点在空闲时断开，有流量时再连上，有利于省电。\n\n" +
                                "默认开启。只改运行时配置，不改订阅文件。不是分流规则，脚本开着时应用仍会写入。没有这类端点时开关不生效，其它功能不受影响。",
                        )
                    },
                ) {
                    onDemand = it
                    scope.launch(Dispatchers.IO) {
                        Settings.onDemand = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ListItem(
                headlineContent = { Text("自动重定向") },
                supportingContent = {
                    Text(
                        "需要 ROOT，1.15 起才能转发热点。当前内核开着时，发往路由器自己地址的 UDP DNS 可能绕过劫持。没开 ROOT 就不要开。",
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Route, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = autoRedirect,
                        onCheckedChange = { checked ->
                            scope.launch(Dispatchers.IO) {
                                if (checked) {
                                    val hasRoot = RootClient.checkRootAvailable()
                                    if (!hasRoot) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "需要 ROOT 权限", Toast.LENGTH_SHORT).show()
                                        }
                                        return@launch
                                    }
                                }
                                Settings.autoRedirect = checked
                                withContext(Dispatchers.Main) {
                                    autoRedirect = checked
                                    reload()
                                }
                            }
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Text(
            // A4: 配置规范化 gets its own functional section
            // instead of trailing the page headerless.
            text = "配置处理",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            SettingsSwitchRow(
                title = "配置规范化",
                subtitle = "Clash / V2Ray / sing-box 导入即可用。没有错误不提示。真正改写时才显示已修正",
                checked = configNormalize,
                onHelp = {
                    help = SwitchHelp(
                        "配置规范化",
                        "平时不打扰。导入时若是 Clash YAML 或 V2Ray/SS 节点列表，会先转成 sing-box，原来的分流规则保留，不会改成中国直连那一套（开了脚本才会覆写）。\n\n" +
                            "只有启动因语法、规则集、格式失败时，才改写除节点、分组、分流以外的错误字段，然后再试一次。\n\n" +
                            "无效规则集不会丢掉，会换成官方 sing-geosite / sing-geoip 地址，原来的名字保留，APP 分流还能对上。\n" +
                            "修正成功会提示改了什么。不改订阅文件。链式落地同样会修，链式本身不受影响。\n" +
                            "若该配置开了脚本，绑定会保留；脚本导致启动失败时这次先跳过脚本。\n\n" +
                            "默认开启。只要节点本身可用，不必再为配置格式发愁。",
                    )
                },
                onCheckedChange = {
                    configNormalize = it
                    scope.launch(Dispatchers.IO) {
                        Settings.configNormalize = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                },
            )
        }
        Text(
            text = "分应用代理",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            // L15/A12: this section does NOT go through the
            // rule-assembly path above — say so at the section head
            // instead of three clicks deep in a help dialog.
            text = "本节走系统级通道（OverrideOptions），与上方规则类开关相互独立。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ListItem(
                headlineContent = { Text("启用") },
                leadingContent = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = perAppProxyEnabled,
                        onCheckedChange = { checked ->
                            perAppProxyEnabled = checked
                            scope.launch(Dispatchers.IO) {
                                Settings.perAppProxyEnabled = checked
                                withContext(Dispatchers.Main) {
                                    if (checked && managedModeEnabled) {
                                        scanAndSaveManagedList(shouldNotify = true)
                                    } else {
                                        reload()
                                    }
                                }
                            }
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (perAppProxyEnabled) {
                ListItem(
                    headlineContent = { Text("管理") },
                    supportingContent = if (managedModeEnabled) {
                        {
                            Text(
                                perAppManagedHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        null
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        navController.navigate("settings/profile_override/manage")
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    headlineContent = { Text("托管模式") },
                    supportingContent = {
                        Column {
                            Text("自动排除中国应用")
                            if (managedModeEnabled) {
                                Text(
                                    perAppManagedPrecedence,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
                    trailingContent = {
                        if (isScanning) {
                            CircularProgressIndicator()
                        } else {
                            Switch(
                                checked = managedModeEnabled,
                                onCheckedChange = { checked ->
                                    managedModeEnabled = checked
                                    scope.launch(Dispatchers.IO) {
                                        Settings.perAppProxyManagedMode = checked
                                        // Reload only after the new mode is stored.
                                        withContext(Dispatchers.Main) {
                                            if (checked) {
                                                scanAndSaveManagedList(shouldNotify = true)
                                            } else {
                                                reload()
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
