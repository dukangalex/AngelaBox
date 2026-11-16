package io.nekohasekai.sfa.utils

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class SubscriptionRouteLeaseTest {
    private class Connection : HttpURLConnection(URL("https://example.com")) {
        var disconnects = 0
        override fun connect() = Unit
        override fun disconnect() {
            disconnects++
        }
        override fun usingProxy() = false
    }

    @After
    fun reset() {
        TunnelGate.setUp(false)
    }

    @Test
    fun upToDownRejectsBeforeOpeningOrResolving() {
        TunnelGate.setUp(true)
        SubscriptionRouteLease().use { lease ->
            TunnelGate.setUp(false)
            assertThrows(IllegalStateException::class.java) {
                HTTPClient.openFor("https://example.com", RemoteUrlGuard.Kind.SUBSCRIPTION, emptyMap(), HTTPClient.Route.PROXY, lease)
            }
        }
    }

    @Test
    fun downToUpRejectsBeforeDirectDialOrProtection() {
        TunnelGate.setUp(false)
        SubscriptionRouteLease().use { lease ->
            var protects = 0
            DirectDial.protectTcp = {
                protects++
                true
            }
            try {
                TunnelGate.setUp(true)
                assertThrows(IllegalStateException::class.java) {
                    HTTPClient.openFor("https://example.com", RemoteUrlGuard.Kind.SUBSCRIPTION, emptyMap(), HTTPClient.Route.DIRECT, lease)
                }
                assertEquals(0, protects)
            } finally {
                DirectDial.protectTcp = null
            }
        }
    }

    @Test
    fun restartedTunnelDoesNotReviveOldLease() {
        TunnelGate.setUp(true)
        SubscriptionRouteLease().use { lease ->
            TunnelGate.setUp(false)
            TunnelGate.setUp(true)
            assertThrows(IllegalStateException::class.java) { lease.check() }
        }
    }

    @Test
    fun transitionDisconnectsTrackedRequestAndRejectsLateRegistration() {
        TunnelGate.setUp(true)
        SubscriptionRouteLease().use { lease ->
            val active = Connection()
            lease.track(active)
            TunnelGate.setUp(false)
            assertEquals(1, active.disconnects)
            val late = Connection()
            assertThrows(IllegalStateException::class.java) { lease.track(late) }
            assertEquals(1, late.disconnects)
        }
    }

    @Test
    fun unchangedStateDoesNotInvalidateLeaseAndCloseDisconnects() {
        TunnelGate.setUp(false)
        val connection = Connection()
        SubscriptionRouteLease().use { lease ->
            lease.track(connection)
            TunnelGate.setUp(false)
            lease.check()
            assertEquals(0, connection.disconnects)
        }
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun untrackedConnectionNotDisconnectedByFutureStateChange() {
        TunnelGate.setUp(false)
        SubscriptionRouteLease().use { lease ->
            val connection = Connection()
            lease.track(connection)
            lease.untrack(connection)
            TunnelGate.setUp(true)
            assertEquals(0, connection.disconnects)
        }
    }

    @Test
    fun staleLeasePreventsDirectResolverFallback() {
        TunnelGate.setUp(false)
        SubscriptionRouteLease().use { lease ->
            TunnelGate.setUp(true)
            assertThrows(IllegalStateException::class.java) {
                RemoteUrlGuard.resolveSubscriptionDirect("example.com", lease::check)
            }
            assertThrows(IllegalStateException::class.java) {
                RemoteUrlGuard.resolveOutsideTunnel("example.com", false, lease::check)
            }
        }
    }

    @Test
    fun subscriptionCannotOpenWithoutLease() {
        assertThrows(IllegalStateException::class.java) {
            HTTPClient.openFor("https://example.com", RemoteUrlGuard.Kind.SUBSCRIPTION, emptyMap(), HTTPClient.Route.PROXY)
        }
    }

    @Test
    fun downToUpDisconnectsAnActiveDirectRequest() {
        TunnelGate.setUp(false)
        SubscriptionRouteLease().use { lease ->
            val direct = Connection()
            lease.track(direct)
            TunnelGate.setUp(true)
            assertEquals(1, direct.disconnects)
            assertThrows(IllegalStateException::class.java) { lease.check() }
        }
    }

    @Test
    fun readOverlappingEitherTransitionRejectsReturnedBytes() {
        for (initial in listOf(false, true)) {
            TunnelGate.setUp(initial)
            SubscriptionRouteLease().use { lease ->
                val input = object : java.io.InputStream() {
                    override fun read(): Int {
                        TunnelGate.setUp(!initial)
                        return 42
                    }
                }
                assertThrows(IllegalStateException::class.java) { lease.checkedStream(input).read() }
            }
        }
    }

    @Test
    fun bufferReadOverlappingEitherTransitionRejectsReturnedBytes() {
        for (initial in listOf(false, true)) {
            TunnelGate.setUp(initial)
            SubscriptionRouteLease().use { lease ->
                val input = object : java.io.InputStream() {
                    override fun read() = -1
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        bytes[offset] = 42
                        TunnelGate.setUp(!initial)
                        return 1
                    }
                }
                assertThrows(IllegalStateException::class.java) { lease.checkedStream(input).read(ByteArray(10)) }
            }
        }
    }

    @Test
    fun directFailureAfterTransitionCannotReachPublicDnsRetry() {
        TunnelGate.setUp(false)
        SubscriptionRouteLease().use { lease ->
            val routes = mutableListOf<HTTPClient.Route>()
            assertThrows(IllegalStateException::class.java) {
                HTTPClient().withUpdateRetry(RemoteUrlGuard.Kind.SUBSCRIPTION, lease.snapshot.up) { route ->
                    lease.check()
                    routes.add(route)
                    TunnelGate.setUp(true)
                    throw java.net.SocketTimeoutException("timeout")
                }
            }
            assertEquals(listOf(HTTPClient.Route.DIRECT), routes)
        }
    }
}
