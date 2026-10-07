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

    fun setUp(value: Boolean) {
        up = value
    }
}
