package io.nekohasekai.sfa.utils

/** Automatic checks must not silently switch to direct after observing a VPN. */
internal class WebDavAutoProbePolicy(
    private val initialGeneration: Long,
) {
    private var requiresTunnel = false

    fun requiredGeneration(
        upGeneration: Long?,
        currentGeneration: Long,
    ): Long? {
        // A whole start/stop cycle may occur between the 20-second polls.
        // Generation changes latch this screen session even when already down.
        if (upGeneration != null || currentGeneration != initialGeneration) requiresTunnel = true
        return if (requiresTunnel) upGeneration ?: currentGeneration else null
    }
}
