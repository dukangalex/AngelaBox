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

    @Test
    fun http429And408AreTransientButOther4xxStayPermanent() {
        // E-3 regression: 429 (rate limit) and 408 used to fall into the
        // permanent bucket because the match set only had "HTTP 5" — the
        // ladder stopped at the first hop and the worker never retried,
        // exactly when a provider rate-limits by egress IP.
        assertTrue(
            HTTPClient.isTransientUpdateFailure(
                IllegalStateException("下载失败 HTTP 429（服务器限流）"),
            ),
        )
        assertTrue(
            HTTPClient.isTransientUpdateFailure(
                IllegalStateException("下载失败 HTTP 408（服务器响应超时）"),
            ),
        )
        assertTrue(
            HTTPClient.isTransientUpdateFailure(
                IllegalStateException("下载失败 HTTP 503"),
            ),
        )
        assertFalse(
            HTTPClient.isTransientUpdateFailure(
                IllegalStateException("下载失败 HTTP 404"),
            ),
        )
        assertFalse(
            HTTPClient.isTransientUpdateFailure(
                IllegalStateException("下载失败 HTTP 403"),
            ),
        )
        assertFalse(
            HTTPClient.isTransientUpdateFailure(
                IllegalStateException("下载失败 HTTP 401"),
            ),
        )
    }

    @Test
    fun ladderMovesOnAfterRateLimit() {
        val seen = mutableListOf<HTTPClient.Route>()
        val result = client.tryRoutes(
            RemoteUrlGuard.Kind.SUBSCRIPTION,
            listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS),
        ) { route ->
            seen.add(route)
            if (route == HTTPClient.Route.DIRECT) {
                throw IllegalStateException("下载失败 HTTP 429（服务器限流）")
            }
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(
            listOf(HTTPClient.Route.DIRECT, HTTPClient.Route.DIRECT_DNS),
            seen,
        )
    }

    @Test
    fun fetchDeadlineStopsDripFeed() {
        // E-2: per-read timeouts bound inactivity only; the absolute fetch
        // deadline is what ends a drip-feed download.
        HTTPClient.checkFetchDeadline(System.nanoTime() + 60_000_000_000L)
        try {
            HTTPClient.checkFetchDeadline(System.nanoTime() - 1L)
            throw AssertionError("expected deadline timeout")
        } catch (e: SocketTimeoutException) {
            // The type matters: SocketTimeoutException classifies as
            // transient, so the ladder/worker retry semantics still apply.
            assertTrue(HTTPClient.isTransientUpdateFailure(e))
        }
    }

    @Test
    fun failureBackoffGrowsButNeverExceedsOwnInterval() {
        // E-1: exponential backoff per profile, floored at 15 min, capped
        // at the profile's own interval so a healthy-cadence profile is
        // never starved and manual updates (not gated by this) always pass.
        val daySeconds = 24L * 3600
        assertEquals(0L, SubscriptionUpdateHealth.backoffSeconds(0, daySeconds))
        assertEquals(900L, SubscriptionUpdateHealth.backoffSeconds(1, daySeconds))
        assertEquals(1800L, SubscriptionUpdateHealth.backoffSeconds(2, daySeconds))
        assertEquals(3600L, SubscriptionUpdateHealth.backoffSeconds(3, daySeconds))
        assertEquals(daySeconds, SubscriptionUpdateHealth.backoffSeconds(30, daySeconds))
        // A 15-minute-interval profile: backoff never exceeds 15 minutes.
        assertEquals(900L, SubscriptionUpdateHealth.backoffSeconds(5, 900L))
    }

    @Test
    fun dueCheckCombinesIntervalAndBackoff() {
        // E-1: lastUpdated only moves on success, so due-ness must also
        // respect the attempt backoff or a dead profile is re-fetched in
        // full on every worker run.
        val now = 1_700_000_000_000L
        val hourMs = 3600_000L
        // Never attempted, interval elapsed -> due.
        assertTrue(
            SubscriptionUpdateHealth.isDue(now, now - 2 * hourMs, 0L, 0, 3600L),
        )
        // Interval not elapsed -> not due.
        assertFalse(
            SubscriptionUpdateHealth.isDue(now, now - hourMs / 2, 0L, 0, 3600L),
        )
        // Failed 5 minutes ago, streak 1 (15 min backoff) -> not due yet,
        // even though the interval since lastUpdated long elapsed.
        assertFalse(
            SubscriptionUpdateHealth.isDue(now, now - 48 * hourMs, now - 5 * 60_000L, 1, 24L * 3600),
        )
        // Failed an hour ago, streak 1 -> backoff over -> due again.
        assertTrue(
            SubscriptionUpdateHealth.isDue(now, now - 48 * hourMs, now - hourMs, 1, 24L * 3600),
        )
        // Success resets the streak (recordSuccess clears attempt+count):
        // attempted an hour ago with streak 0 -> due on interval alone.
        assertTrue(
            SubscriptionUpdateHealth.isDue(now, now - 2 * hourMs, now - hourMs, 0, 3600L),
        )
    }
}
