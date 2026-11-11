package io.nekohasekai.sfa.utils

/**
 * Same-process flag: true only while the VPN/proxy service is Started.
 * Update downloads use it to follow the tunnel instead of dialing a
 * pre-resolved IP that the tunnel cannot route by name.
 */
object TunnelGate {
    @Volatile
    var up: Boolean = false
        private set

    @Volatile
    var generation: Long = 0
        private set

    @Volatile
    var vpnUp: Boolean = false
        private set

    @Synchronized
    fun generationWhenUp(): Long? = if (up) generation else null

    @Synchronized
    fun setUp(
        value: Boolean,
        vpn: Boolean = true,
    ) {
        val nextVpnUp = value && vpn
        if (up != value || vpnUp != nextVpnUp) generation++
        up = value
        vpnUp = nextVpnUp
    }
}
