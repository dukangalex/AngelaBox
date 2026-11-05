package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** No automatic subscription bypass when the current VPN node fails. */
class HTTPClientVpnSubscriptionTest {
    private val client = HTTPClient()

    @Test
    fun vpnSubscriptionSuccessUsesOnlyProxy() {
        val seen = mutableListOf<HTTPClient.Route>()
        val value = client.withUpdateRetry(RemoteUrlGuard.Kind.SUBSCRIPTION, tunnelUp = true) {
            seen.add(it)
            "subscription"
        }
        assertEquals("subscription", value)
        assertEquals(listOf(HTTPClient.Route.PROXY), seen)
    }

    @Test
    fun vpnSubscriptionFailuresNeverTryDirectOrOutsideDns() {
        val failures = listOf(
            SocketException("Connection reset"),
            SocketTimeoutException("timeout"),
            UnknownHostException("provider.invalid"),
            javax.net.ssl.SSLException("handshake failed"),
            IllegalStateException("下载失败 HTTP 429（服务器限流）"),
            IllegalStateException("下载失败 HTTP 503"),
            IllegalStateException("下载失败 HTTP 403"),
        )
        for (failure in failures) {
            val seen = mutableListOf<HTTPClient.Route>()
            try {
                client.withUpdateRetry(RemoteUrlGuard.Kind.SUBSCRIPTION, tunnelUp = true) {
                    seen.add(it)
                    throw failure
                }
                throw AssertionError("expected subscription failure")
            } catch (error: IllegalStateException) {
                assertEquals(listOf(HTTPClient.Route.PROXY), seen)
                assertTrue(error.cause === failure)
                assertFalse(error.message!!.contains("不经过节点再试也失败"))
            }
        }
    }

    @Test
    fun vpnSubscriptionFailureRetainsStartingRouteContext() {
        TunnelGate.setUp(true)
        try {
            try {
                client.withUpdateRetry(RemoteUrlGuard.Kind.SUBSCRIPTION) {
                    // The VPN stops while the attempt is in flight. Failure
                    // copy must retain the route captured when it started.
                    TunnelGate.setUp(false)
                    throw SocketTimeoutException("timeout")
                }
                throw AssertionError("expected subscription failure")
            } catch (error: IllegalStateException) {
                assertTrue(error.message!!.contains("未自动绕过 VPN"))
            }
        } finally {
            TunnelGate.setUp(false)
        }
    }

    @Test
    fun tunnelDownSubscriptionKeepsDirectDnsRecovery() {
        val seen = mutableListOf<HTTPClient.Route>()
        val value = client.withUpdateRetry(RemoteUrlGuard.Kind.SUBSCRIPTION, tunnelUp = false) {
            seen.add(it)
            if (it == HTTPClient.Route.DIRECT) throw SocketTimeoutException("timeout")
            "subscription"
        }
        assertEquals("subscription", value)
        assertEquals(listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS), seen)
    }

    @Test
    fun appDownloadRetryPolicyIsNotChangedBySubscriptionFix() {
        val seen = mutableListOf<HTTPClient.Route>()
        val value = client.withUpdateRetry(RemoteUrlGuard.Kind.UPDATE, tunnelUp = true) {
            seen.add(it)
            if (it == HTTPClient.Route.PROXY) throw SocketTimeoutException("timeout")
            "apk"
        }
        assertEquals("apk", value)
        assertEquals(listOf(HTTPClient.Route.PROXY, HTTPClient.Route.DIRECT), seen)
    }
}
