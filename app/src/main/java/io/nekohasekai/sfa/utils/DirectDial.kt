package io.nekohasekai.sfa.utils

import java.net.DatagramSocket
import java.net.Socket

/**
 * Lets subscription and update downloads leave the VPN when the current
 * node resets the connection. [VPNService] installs the protect hooks
 * while the tunnel exists. Missing hooks mean there is nothing to bypass.
 */
object DirectDial {
    @Volatile
    var protectTcp: ((Socket) -> Boolean)? = null

    @Volatile
    var protectUdp: ((DatagramSocket) -> Boolean)? = null

    fun protect(socket: Socket): Boolean = protectTcp?.invoke(socket) == true

    fun protect(socket: DatagramSocket): Boolean = protectUdp?.invoke(socket) == true
}
