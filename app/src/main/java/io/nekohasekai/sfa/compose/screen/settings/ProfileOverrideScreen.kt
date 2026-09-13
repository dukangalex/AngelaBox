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
import androidx.compose.material3.Scaffold
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
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置覆盖") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = { Text("自动重定向") },
                    supportingContent = { Text("需要 ROOT 权限") },
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
                text = "以下为运行时强制覆盖，不修改订阅文件。开启后无论订阅有没有对应字段都会写入。点 ⓘ 查看说明。",
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
                            "在所有路由（含中国直连）之前拒绝 STUN/TURN：UDP 3478-3481 / 5349-5351 / 19302-19310，TCP 3478/5349，以及主机名含 stun./turn. 的请求。国内 STUN（如 bilibili、小米）同样拦截，不会因为中国直连而放过。开启后「工具 → STUN 测试」失败是预期。",
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
                            "开启后在运行时注入官方 sing-geosite 的 geosite-category-ads-all 规则集，并对匹配流量执行 reject。若当前配置的脚本已经把广告分到「广告拦截」分组，本开关不会再盖一层拒绝，避免和脚本打架。不改订阅文件。首次开启会下载规则集。",
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
                                "避免 sing-box 因 detour 指向空 direct 而无法启动。",
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
                    subtitle = "强制开启 TUN strict_route",
                    checked = strictRoute,
                    onHelp = {
                        help = SwitchHelp(
                            "严格路由",
                            "无论订阅是否已写 strict_route，开启后都强制写成 true。没有 TUN 入站时该开关无法生效，其它开关不受影响。",
                        )
                    },
                ) {
                    strictRoute = it
                    scope.launch(Dispatchers.IO) {
                        Settings.strictRoute = it
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
                OverrideSwitch(
                    title = "DNS 防泄漏倾向",
                    subtitle = "强制 DNS 走代理栈",
                    checked = dnsProtect,
                    onHelp = {
                        help = SwitchHelp(
                            "DNS",
                            "强制写入 independent_cache 与 auto_detect_interface，覆盖订阅原值。",
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
                            "强制 DNS strategy=ipv4_only，拦截 IPv6，并清空 TUN 的 IPv6 地址。",
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
                    subtitle = "拦截 UDP 443",
                    checked = disableQuic,
                    onHelp = {
                        help = SwitchHelp("禁用 QUIC", "强制在路由最前插入 UDP 443 拒绝规则，覆盖订阅原值。")
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
                    subtitle = "放行中国大陆 QUIC",
                    checked = excludeCnQuic,
                    enabled = disableQuic,
                    onHelp = {
                        help = SwitchHelp("排除国内 QUIC", "国内域名 UDP 443 强制直连，其余仍拦。")
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
