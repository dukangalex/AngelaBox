package io.nekohasekai.sfa.utils

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.Socket
import java.net.SocketException

class HTTPClientProtectionTest {
    @After
    fun resetHook() {
        DirectDial.protectTcp = null
    }

    @Test
    fun missingProtectionRejectsRequiredBypass() {
        Socket().use { socket ->
            DirectDial.protectTcp = null
            assertThrows(SocketException::class.java) { DirectDial.requireTcpProtection(socket) }
        }
    }

    @Test
    fun rejectedProtectionRejectsRequiredBypass() {
        Socket().use { socket ->
            DirectDial.protectTcp = { false }
            assertThrows(SocketException::class.java) { DirectDial.requireTcpProtection(socket) }
        }
    }

    @Test
    fun acceptedProtectionKeepsTheOriginalSocket() {
        Socket().use { socket ->
            DirectDial.protectTcp = { protected ->
                assertSame(socket, protected)
                true
            }
            DirectDial.requireTcpProtection(socket)
        }
    }
}
