package io.nekohasekai.sfa.utils

/** Automatic checks must not silently switch to direct after observing a VPN. */
internal class WebDavAutoProbePolicy {
    private var requiresTunnel = false

    fun requiredGeneration(
        upGeneration: Long?,
        currentGeneration: Long,
    ): Long? {
        if (upGeneration != null) requiresTunnel = true
        return if (requiresTunnel) upGeneration ?: currentGeneration else null
    }
}
