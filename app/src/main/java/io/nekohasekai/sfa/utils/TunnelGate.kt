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

    @Synchronized
    fun setUp(value: Boolean) {
        if (up != value) generation++
        up = value
    }
}
