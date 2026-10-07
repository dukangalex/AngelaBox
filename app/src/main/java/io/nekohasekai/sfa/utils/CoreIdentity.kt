package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig

/**
 * Kernel version shown in 设置 → 核心. libbox reads `git describe --tags` at
 * compile time; a depth-1 checkout without tags stamps `unknown`. The
 * release job now tags HEAD from [BuildConfig.KERNEL_TAG] before gomobile,
 * and this fallback still uses the pin if libbox reports unknown.
 */
object CoreIdentity {
    fun libboxOrPin(): String {
        val lib = runCatching { Libbox.version() }.getOrDefault("").trim()
        val raw = if (lib.isNotEmpty() && !lib.equals("unknown", ignoreCase = true)) {
            lib
        } else {
            BuildConfig.KERNEL_TAG
        }
        return raw.removePrefix("v").ifBlank { "unknown" }
    }

    fun display(): String {
        val core = libboxOrPin()
        val upstream = BuildConfig.KERNEL_UPSTREAM.trim()
        return if (upstream.isEmpty()) core else "$core（官方 $upstream）"
    }
}
