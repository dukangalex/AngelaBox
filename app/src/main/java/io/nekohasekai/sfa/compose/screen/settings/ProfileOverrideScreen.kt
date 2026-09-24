package io.nekohasekai.sfa.compose.screen.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.navigation.popToDashboard
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SwitchHelp(val title: String, val body: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileOverrideScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)

    var autoRedirect by remember { mutableStateOf(Settings.autoRedirect) }
    var onDemand by remember { mutableStateOf(Settings.onDemand) }
    var perAppProxyEnabled by remember { mutableStateOf(Settings.perAppProxyEnabled) }
    var managedModeEnabled by remember { mutableStateOf(Settings.perAppProxyManagedMode) }
    var isScanning by remember { mutableStateOf(false) }
    var webrtcProtect by remember { mutableStateOf(Settings.webrtcProtect) }
    var chinaDirect by remember { mutableStateOf(Settings.chinaDirect) }
    var adsBlock by remember { mutableStateOf(Settings.adsBlock) }
    var disableQuic by remember { mutableStateOf(Settings.disableQuic) }
    var excludeCnQuic by remember { mutableStateOf(Settings.excludeCnQuic) }
    var strictRoute by remember { mutableStateOf(Settings.strictRoute) }
    var dnsProtect by remember { mutableStateOf(Settings.dnsProtect) }
    var disableIpv6 by remember { mutableStateOf(Settings.disableIpv6) }
    var configNormalize by remember { mutableStateOf(Settings.configNormalize) }
    var help by remember { mutableStateOf<SwitchHelp?>(null) }

    fun reload() {
        scope.launch(Dispatchers.Main) {
            notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
        }
    }

    fun scanAndSaveManagedList(shouldNotify: Boolean = false) {
        isScanning = true
        scope.launch {
            val chinaApps = PerAppProxyScanner.scanAllChinaApps()
            withContext(Dispatchers.IO) {
                Settings.perAppProxyManagedList = chinaApps
            }
            isScanning = false
            if (shouldNotify) {
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
            title = { Text("配置覆盖") },
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
                text = "兼容当前版本",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                OverrideSwitch(
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
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = { Text("自动重定向") },
                    supportingContent = { Text("需要 ROOT；1.15 起支持热点/中继转发") },
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
                text = "分应用代理",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
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
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable(enabled = !managedModeEnabled) {
                            navController.navigate("settings/profile_override/manage")
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    ListItem(
                        headlineContent = { Text("托管模式") },
                        supportingContent = { Text("自动排除中国应用") },
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
                                        }
                                        if (checked) {
                                            scanAndSaveManagedList(shouldNotify = true)
                                        } else {
                                            reload()
                                        }
                                    },
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            Text(
                text = "隐私防护",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "以下为运行时强制覆盖，不修改订阅文件。开启后无论订阅有没有对应字段都会写入。脚本开着时，这些开关控制脚本里的对应功能，不会再额外写一套规则。点 ⓘ 查看说明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                OverrideSwitch(
                    title = "防 WebRTC 泄露",
                    subtitle = "优先拦截 STUN/TURN（含国内 STUN），避免真实 IP 漏出",
                    checked = webrtcProtect,
                    onHelp = {
                        help = SwitchHelp(
                            "防 WebRTC 泄露",
                            "在所有路由（含中国直连）之前拒绝 STUN/TURN：UDP 3478-3481 / 5349-5351 / 19302-19310，TCP 3478/5349，以及主机名含 stun./turn. 的请求。国内 STUN（如 bilibili、小米）同样拦截，不会因为中国直连而放过。开启后「工具 → STUN 测试」失败是预期。\n\n脚本开着时由脚本写入同一套规则，本开关决定开或关，不会叠两套。",
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
                OverrideSwitch(
                    title = "广告拦截",
                    subtitle = "拒绝 geosite 广告规则集匹配的域名",
                    checked = adsBlock,
                    onHelp = {
                        help = SwitchHelp(
                            "广告拦截",
                            "开启后在运行时注入官方 sing-geosite 的 geosite-category-ads-all 规则集，并对匹配流量执行 reject。脚本开着时由脚本写入同一套广告规则，本开关决定开或关，不会叠两套。不改订阅文件。首次开启会下载规则集。",
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
                text = "中国直连",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                OverrideSwitch(
                    title = "中国直连",
                    subtitle = "强制绕过中国 IP/域名、公共 DNS 与局域网",
                    checked = chinaDirect,
                    onHelp = {
                        help = SwitchHelp(
                            "中国直连",
                            "开启后强制写入运行时直连规则，不改订阅文件，也不依赖订阅是否已有 geoip/geosite：\n" +
                                "1. 绕过中国 IP（订阅若已有 geoip-cn 会优先使用）\n" +
                                "2. 绕过中国域名（.cn 及常用国内站点）\n" +
                                "3. 绕过中国公共 DNS IP（阿里/114/DNSPod 等）\n" +
                                "4. 绕过中国公共 DNS 域名\n" +
                                "5. 绕过局域网 IP（ip_is_private）\n" +
                                "6. 绕过局域网域名（.local / .lan 等）\n\n" +
                                "只改路由：匹配到的流量走 direct。不注入 DNS 服务器，" +
                                "避免 sing-box 因 detour 指向空 direct 而无法启动。\n\n" +
                                "脚本开着时由脚本写入同一套国内直连（含 IPv4/IPv6），本开关决定开或关，不会叠两套。",
                        )
                    },
                    onCheckedChange = {
                        chinaDirect = it
                        scope.launch(Dispatchers.IO) {
                            Settings.chinaDirect = it
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
            }
            Text(
                text = "网络增强",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                OverrideSwitch(
                    title = "严格路由",
                    subtitle = "默认关闭。打开后切网可能整机掉线",
                    checked = strictRoute,
                    onHelp = {
                        help = SwitchHelp(
                            "严格路由",
                            "打开后把 TUN strict_route 写成 true，订阅里原来的值也会被盖掉。关掉就写成 false。\n\n" +
                                "切 Wi-Fi 和移动数据时，打开会把还没改道的包丢掉，表现是突然没网。需要防泄漏再打开。已经装过的手机会自动关一次。脚本开着时由脚本按这个开关写，不再只在打开时写 true。",
                        )
                    },
                ) {
                    strictRoute = it
                    scope.launch(Dispatchers.IO) {
                        Settings.strictRoute = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
                if (BuildConfig.KERNEL_UPSTREAM.startsWith("1.15")) {
                    OverrideSwitch(
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
                OverrideSwitch(
                    title = "DNS 防泄漏倾向",
                    subtitle = "强制 DNS 走代理栈",
                    checked = dnsProtect,
                    onHelp = {
                        help = SwitchHelp(
                            "DNS",
                            "强制写入 independent_cache 与 auto_detect_interface，覆盖订阅原值。脚本开着时由脚本写入，本开关决定开或关。",
                        )
                    },
                ) {
                    dnsProtect = it
                    scope.launch(Dispatchers.IO) {
                        Settings.dnsProtect = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
                OverrideSwitch(
                    title = "禁用 IPv6",
                    subtitle = "仅 IPv4，避免 IPv6 旁路",
                    checked = disableIpv6,
                    onHelp = {
                        help = SwitchHelp(
                            "禁用 IPv6",
                            "强制 DNS strategy=ipv4_only，拦截 IPv6，并清空 TUN 的 IPv6 地址。关闭后走 IPv4/IPv6 双栈（prefer_ipv4，国内直连含 IPv6）。脚本开着时由脚本写入，本开关决定开或关。",
                        )
                    },
                ) {
                    disableIpv6 = it
                    scope.launch(Dispatchers.IO) {
                        Settings.disableIpv6 = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
                OverrideSwitch(
                    title = "禁用 QUIC",
                    subtitle = "不再丢 UDP 443，避免只走 QUIC 的应用卡住",
                    checked = disableQuic,
                    onHelp = {
                        help = SwitchHelp(
                            "禁用 QUIC",
                            "以前没绑定脚本时，应用会拒绝全部 UDP 443。YouTube、X 这类应用不会改走 TCP，表现是一直转圈或直接掉网。现在脚本开着和没开都一样，不写这条拒绝。开关还在，打开也不会丢包。",
                        )
                    },
                ) {
                    disableQuic = it
                    if (!it) excludeCnQuic = false
                    scope.launch(Dispatchers.IO) {
                        Settings.disableQuic = it
                        if (!it) Settings.excludeCnQuic = false
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
                OverrideSwitch(
                    title = "排除国内 QUIC",
                    subtitle = "不再单独改 UDP 443",
                    checked = excludeCnQuic,
                    enabled = disableQuic,
                    onHelp = {
                        help = SwitchHelp(
                            "排除国内 QUIC",
                            "以前没开脚本时，国内域名的 UDP 443 走直连，其余拒绝。拒绝其余会让国外 QUIC 超时。现在这条也不写了。",
                        )
                    },
                ) {
                    excludeCnQuic = it
                    scope.launch(Dispatchers.IO) {
                        Settings.excludeCnQuic = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
            }
        }
}

@Composable
private fun OverrideSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onHelp: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = onHelp) {
                    Icon(Icons.Outlined.Info, contentDescription = "说明", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
