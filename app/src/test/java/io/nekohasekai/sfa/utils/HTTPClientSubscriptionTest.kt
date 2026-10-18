package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Subscription fetch when the tunnel is down: the ladder must retry a
 * poisoned system resolver through public resolvers, and the failure message
 * must say what actually broke instead of blaming the user's network.
 */
class HTTPClientSubscriptionTest {

    private val client = HTTPClient()

    @Test
    fun ladderRetriesDirectDnsAfterTransientDirectFailure() {
        val seen = mutableListOf<HTTPClient.Route>()
        val result = client.tryRoutes(
            RemoteUrlGuard.Kind.SUBSCRIPTION,
            listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS),
        ) { route ->
            seen.add(route)
            if (route == HTTPClient.Route.DIRECT) throw SocketTimeoutException("connect timed out")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(
            listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS),
            seen,
        )
    }

    @Test
    fun ladderStopsImmediatelyOnPermanentFailure() {
        val seen = mutableListOf<HTTPClient.Route>()
        try {
            client.tryRoutes(
                RemoteUrlGuard.Kind.SUBSCRIPTION,
                listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS),
            ) { route ->
                seen.add(route)
                throw IllegalArgumentException("仅允许 HTTPS")
            }
        } catch (e: IllegalStateException) {
            assertEquals(listOf(HTTPClient.Route.DIRECT), seen)
            assertTrue(e.message!!.contains("仅允许 HTTPS"))
            return
        }
        throw AssertionError("expected immediate failure")
    }

    @Test
    fun ladderFailureMessageIsHonestWhenTunnelDown() {
        try {
            client.tryRoutes(
                RemoteUrlGuard.Kind.SUBSCRIPTION,
                listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS),
            ) { throw SocketException("Connection reset") }
        } catch (e: IllegalStateException) {
            val msg = e.message!!
            assertTrue(msg.contains("直连订阅服务器失败"))
            assertFalse(msg.contains("请检查网络后再更新"))
            return
        }
        throw AssertionError("expected failure")
    }

    @Test
    fun dnsFailureExplainsHonestlyWhenTunnelDown() {
        val msg = HTTPClient.explainProfileUpdate(
            UnknownHostException("sub.example.com"),
            tunnelUp = false,
        )
        assertTrue(msg.contains("域名解析失败"))
        assertTrue(msg.contains("公共 DNS"))
        assertFalse(msg.contains("请检查网络"))
    }

    @Test
    fun guardDnsRejectionExplainsHonestlyWhenTunnelDown() {
        val msg = HTTPClient.explainProfileUpdate(
            IllegalArgumentException("无法解析主机，已拒绝"),
            tunnelUp = false,
        )
        assertTrue(msg.contains("域名解析失败"))
    }

    @Test
    fun resetExplainsDirectBlockedWhenTunnelDown() {
        val msg = HTTPClient.explainProfileUpdate(
            SocketException("Connection reset"),
            tunnelUp = false,
        )
        assertTrue(msg.contains("直连订阅服务器失败"))
        assertTrue(msg.contains("可用节点"))
        assertTrue(msg.contains("SocketException"))
        assertFalse(msg.contains("Connection reset"))
        assertFalse(msg.contains("请检查网络后再更新"))
    }

    @Test
    fun tunnelUpMessageUnchanged() {
        val msg = HTTPClient.explainProfileUpdate(
            SocketException("Connection reset"),
            tunnelUp = true,
        )
        assertTrue(msg.contains("当前节点把连接断开了"))
    }

    @Test
    fun userFacingChineseStillWins() {
        val msg = HTTPClient.explainProfileUpdate(
            IllegalStateException("下载失败 HTTP 403"),
            tunnelUp = false,
        )
        assertEquals("下载失败 HTTP 403", msg)
    }

    @Test
    fun ladderRetriesDirectDnsAfterDnsGuardRejection() {
        // 系统 DNS 被污染 + 公共 DNS(UDP) 被运营商拦截时，DIRECT 路由的守卫
        // 会抛"无法解析主机"；ladder 必须继续走 DIRECT_DNS（TCP/53），而不是
        // 当场判永久失败。回归：此前 isTransientUpdateFailure 对该异常返回
        // false，DIRECT_DNS 路由永远走不到。
        val seen = mutableListOf<HTTPClient.Route>()
        val result = client.tryRoutes(
            RemoteUrlGuard.Kind.SUBSCRIPTION,
            listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS),
        ) { route ->
            seen.add(route)
            if (route == HTTPClient.Route.DIRECT) throw IllegalArgumentException("无法解析主机，已拒绝")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(
            listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS),
            seen,
        )
    }

    @Test
    fun dnsGuardRejectionIsTransientButOtherGuardRejectionsStayPermanent() {
        assertTrue(
            HTTPClient.isTransientUpdateFailure(
                IllegalArgumentException("无法解析主机，已拒绝"),
            ),
        )
        assertFalse(
            HTTPClient.isTransientUpdateFailure(
                IllegalArgumentException("仅允许 HTTPS"),
            ),
        )
        assertFalse(
            HTTPClient.isTransientUpdateFailure(
                IllegalArgumentException("URL 不能包含用户名密码"),
            ),
        )
    }
}
