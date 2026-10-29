package io.nekohasekai.sfa.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 国产手机厂商后台保活引导。
 *
 * 华为/荣耀/小米/OPPO/vivo 等国产 ROM 有激进的后台清理策略，
 * 仅靠标准 Android 电池优化白名单不够，必须引导用户做厂商特有的设置：
 * 1. 应用启动管理 → 手动管理 → 自启动/关联启动/后台活动全开
 * 2. 多任务界面加锁（锁屏清理时不杀）
 */
object VendorKeepAlive {

    /** 是否为国产 OEM（需要厂商特殊保活引导） */
    fun isChineseOem(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("huawei") ||
            manufacturer.contains("honor") ||
            manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("oppo") ||
            manufacturer.contains("realme") ||
            manufacturer.contains("oneplus") ||
            manufacturer.contains("vivo") ||
            manufacturer.contains("iqoo") ||
            manufacturer.contains("meizu") ||
            manufacturer.contains("lenovo") ||
            manufacturer.contains("nubia") ||
            manufacturer.contains("zte")
    }

    /** 厂商中文名，用于 UI 展示 */
    fun vendorName(): String = when {
        Build.MANUFACTURER.contains("huawei", ignoreCase = true) -> "华为"
        Build.MANUFACTURER.contains("honor", ignoreCase = true) -> "荣耀"
        Build.MANUFACTURER.contains("xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.contains("redmi", ignoreCase = true) -> "小米"
        Build.MANUFACTURER.contains("oppo", ignoreCase = true) ||
            Build.MANUFACTURER.contains("realme", ignoreCase = true) ||
            Build.MANUFACTURER.contains("oneplus", ignoreCase = true) -> "OPPO"
        Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
            Build.MANUFACTURER.contains("iqoo", ignoreCase = true) -> "vivo"
        else -> "本机"
    }

    /**
     * 跳转厂商自启动/应用启动管理页面。
     * 失败时降级到应用信息页，再降级到系统设置。
     * @return true 表示成功打开了厂商页面
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = autoStartCandidates()
        for ((pkg, cls) in candidates) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // 先验证能解析，避免 ActivityNotFoundException
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
                // 尝试下一个候选
            }
        }
        // 降级：应用信息页
        return try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            false
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun autoStartCandidates(): List<Pair<String, String>> {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                // EMUI 8+ / MagicOS
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                // 老机型
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.bootstart.BootStartActivity",
            )
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") ||
                manufacturer.contains("oneplus") -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                "com.iqoo.secure" to "com.iqoo.secure.MainActivity",
            )
            manufacturer.contains("meizu") -> listOf(
                "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
            )
            else -> emptyList()
        }
    }
}
