package io.nekohasekai.sfa.utils

import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketException

/**
 * Lets app-update fallback downloads leave the VPN when the current
 * node resets the connection. Subscriptions never use this fallback. [VPNService] installs the protect hooks
 * while the tunnel exists. A required bypass must fail when protection is unavailable.
 */
object DirectDial {
    @Volatile
    var protectTcp: ((Socket) -> Boolean)? = null

    @Volatile
    var protectUdp: ((DatagramSocket) -> Boolean)? = null

    fun protect(socket: Socket): Boolean = protectTcp?.invoke(socket) == true

    internal fun requireTcpProtection(socket: Socket) {
        if (!protect(socket)) throw SocketException("无法安全绕过 VPN，已拒绝直连")
    }

    fun protect(socket: DatagramSocket): Boolean = protectUdp?.invoke(socket) == true
}
