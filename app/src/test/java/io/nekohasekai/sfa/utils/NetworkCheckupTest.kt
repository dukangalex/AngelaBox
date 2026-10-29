package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一键网络体检结论优先级单测。
 * decideVerdict 是纯函数（无 android 依赖），在宿主机 JVM 上跑。
 */
class NetworkCheckupTest {

    private fun item(
        id: NetworkCheckup.ItemId,
        status: NetworkCheckup.ItemStatus,
        code: String,
    ) = NetworkCheckup.Item(id, status, code, "detail")

    private fun allOk() = listOf(
        item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.OK, "ok"),
        item(NetworkCheckup.ItemId.DNS, NetworkCheckup.ItemStatus.OK, "ok"),
        item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.OK, "ok"),
        item(NetworkCheckup.ItemId.SUBSCRIPTION, NetworkCheckup.ItemStatus.OK, "ok"),
    )

    private fun withItem(
        id: NetworkCheckup.ItemId,
        status: NetworkCheckup.ItemStatus,
        code: String,
    ) = allOk().map { if (it.id == id) it.copy(status = status, code = code) else it }

    private fun itemsOf(vararg overrides: NetworkCheckup.Item) = allOk().map { base -> overrides.firstOrNull { it.id == base.id } ?: base }

    @Test
    fun verdict_allOk() {
        assertEquals(
            NetworkCheckup.VerdictKind.ALL_OK,
            NetworkCheckup.decideVerdict(true, true, allOk()),
        )
    }

    @Test
    fun verdict_noNetworkBeatsEverything() {
        val items = listOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "timeout"),
            item(NetworkCheckup.ItemId.SUBSCRIPTION, NetworkCheckup.ItemStatus.FAIL, "expired"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.NO_NETWORK,
            NetworkCheckup.decideVerdict(false, true, items),
        )
    }

    @Test
    fun verdict_serviceStopped() {
        assertEquals(
            NetworkCheckup.VerdictKind.SERVICE_STOPPED,
            NetworkCheckup.decideVerdict(true, false, emptyList()),
        )
    }

    @Test
    fun verdict_subscriptionBadBeatsTunnelBroken() {
        val items = listOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "timeout"),
            item(NetworkCheckup.ItemId.DNS, NetworkCheckup.ItemStatus.OK, "ok"),
            item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.OK, "ok"),
            item(NetworkCheckup.ItemId.SUBSCRIPTION, NetworkCheckup.ItemStatus.FAIL, "expired"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.SUBSCRIPTION_BAD,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_tunnelBroken() {
        // 探测超时且没有任何旁证能证明节点可用（延迟测不出）→ 判坏。
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "timeout"),
            item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.WARN, "no_data"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.TUNNEL_BROKEN,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_tunnelTimeoutExcusedByFreshLatencyOk() {
        // 审计 A-1 的截图场景：探测超时，但同轮新鲜测得延迟正常 →
        // 只能判"不确定"，不能判"节点连不上"。
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "timeout"),
            item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.OK, "ok"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.TUNNEL_UNCERTAIN,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_tunnelTimeoutExcusedByFreshSlowLatencyGivesNodeSlow() {
        // 延迟是新鲜测得的且偏慢：节点路径在回包，不判坏，判慢。
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "timeout"),
            item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.WARN, "slow"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.NODE_SLOW,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_tunnelTimeoutStaysBrokenWithStaleLatency() {
        // 延迟只是接受的旧值（ok_stale）：证明不了节点现在可用，不豁免。
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "timeout"),
            item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.OK, "ok_stale"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.TUNNEL_BROKEN,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_tunnelHttpErrorStaysBrokenWithFreshLatency() {
        // 豁免只限超时：HTTP 错误是测到了明确坏结果，延迟正常也不翻案。
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "http_error"),
            item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.OK, "ok"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.TUNNEL_BROKEN,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_tunnelDnsFailureStaysBrokenWithFreshLatency() {
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "dns_failure"),
            item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.OK, "ok"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.TUNNEL_BROKEN,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_downgradedTunnelItemIsUncertain() {
        // run() 降级后的形态（WARN + timeout_downgraded）→ 不确定。
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.WARN, "timeout_downgraded"),
            item(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.OK, "ok"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.TUNNEL_UNCERTAIN,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_dnsHijackedBeatsTunnelUncertain() {
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.WARN, "timeout_downgraded"),
            item(NetworkCheckup.ItemId.DNS, NetworkCheckup.ItemStatus.FAIL, "hijacked"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.DNS_HIJACKED,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_tunnelUncertainBeatsSubscriptionUnhealthy() {
        val items = itemsOf(
            item(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.WARN, "timeout_downgraded"),
            item(NetworkCheckup.ItemId.SUBSCRIPTION, NetworkCheckup.ItemStatus.WARN, "update_failed"),
        )
        assertEquals(
            NetworkCheckup.VerdictKind.TUNNEL_UNCERTAIN,
            NetworkCheckup.decideVerdict(true, true, items),
        )
    }

    @Test
    fun verdict_dnsHijacked() {
        assertEquals(
            NetworkCheckup.VerdictKind.DNS_HIJACKED,
            NetworkCheckup.decideVerdict(
                true,
                true,
                withItem(NetworkCheckup.ItemId.DNS, NetworkCheckup.ItemStatus.FAIL, "hijacked_local"),
            ),
        )
    }

    @Test
    fun verdict_nodeSlow() {
        assertEquals(
            NetworkCheckup.VerdictKind.NODE_SLOW,
            NetworkCheckup.decideVerdict(
                true,
                true,
                withItem(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.WARN, "slow"),
            ),
        )
    }

    @Test
    fun verdict_latencyNoDataIsNotSlow() {
        // "测不出延迟" 也是 WARN，但 code 是 no_data，不能判成节点慢。
        assertEquals(
            NetworkCheckup.VerdictKind.ALL_OK,
            NetworkCheckup.decideVerdict(
                true,
                true,
                withItem(NetworkCheckup.ItemId.LATENCY, NetworkCheckup.ItemStatus.WARN, "no_data"),
            ),
        )
    }

    @Test
    fun verdict_subscriptionUnhealthy() {
        assertEquals(
            NetworkCheckup.VerdictKind.SUBSCRIPTION_UNHEALTHY,
            NetworkCheckup.decideVerdict(
                true,
                true,
                withItem(NetworkCheckup.ItemId.SUBSCRIPTION, NetworkCheckup.ItemStatus.WARN, "update_failed"),
            ),
        )
    }

    @Test
    fun verdict_quotaLowIsWarnNotBad() {
        assertEquals(
            NetworkCheckup.VerdictKind.SUBSCRIPTION_UNHEALTHY,
            NetworkCheckup.decideVerdict(
                true,
                true,
                withItem(NetworkCheckup.ItemId.SUBSCRIPTION, NetworkCheckup.ItemStatus.WARN, "quota_low"),
            ),
        )
    }

    @Test
    fun fakeIp_range198_18_slash15() {
        // 198.18.0.0/15：198.18.x.x 与 198.19.x.x 都是 FakeIP。
        assertEquals(true, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("198.18.0.1")))
        assertEquals(true, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("198.18.255.254")))
        assertEquals(true, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("198.19.0.1")))
        assertEquals(true, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("198.19.255.255")))
    }

    @Test
    fun fakeIp_outsideRangeIsNotFakeIp() {
        // 边界外：198.17.x.x、198.20.x.x、公网 IP 都不是 FakeIP。
        assertEquals(false, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("198.17.255.255")))
        assertEquals(false, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("198.20.0.1")))
        assertEquals(false, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("8.8.8.8")))
        assertEquals(false, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("223.5.5.5")))
    }

    @Test
    fun fakeIp_ipv6IsNotFakeIp() {
        assertEquals(false, NetworkCheckup.isFakeIp(java.net.InetAddress.getByName("::1")))
    }

    @Test
    fun probeReduce_anyOkWins() {
        assertEquals(
            NetworkCheckup.TunnelProbe.Ok,
            NetworkCheckup.reduceProbeAttempts(
                listOf(NetworkCheckup.TunnelProbe.Timeout, NetworkCheckup.TunnelProbe.Ok),
            ),
        )
    }

    @Test
    fun probeReduce_httpErrorBeatsTimeout() {
        // 有 HTTP 回应说明路径能到服务器，比超时更有信息量。
        assertEquals(
            NetworkCheckup.TunnelProbe.HttpError(503),
            NetworkCheckup.reduceProbeAttempts(
                listOf(
                    NetworkCheckup.TunnelProbe.Timeout,
                    NetworkCheckup.TunnelProbe.HttpError(503),
                ),
            ),
        )
    }

    @Test
    fun probeReduce_allDnsFailuresIsDnsFailure() {
        assertEquals(
            NetworkCheckup.TunnelProbe.DnsFailure,
            NetworkCheckup.reduceProbeAttempts(
                listOf(
                    NetworkCheckup.TunnelProbe.DnsFailure,
                    NetworkCheckup.TunnelProbe.DnsFailure,
                ),
            ),
        )
    }

    @Test
    fun probeReduce_mixedDnsAndTimeoutIsTimeout() {
        assertEquals(
            NetworkCheckup.TunnelProbe.Timeout,
            NetworkCheckup.reduceProbeAttempts(
                listOf(
                    NetworkCheckup.TunnelProbe.DnsFailure,
                    NetworkCheckup.TunnelProbe.Timeout,
                ),
            ),
        )
    }

    @Test
    fun probeReduce_allTimeoutsIsTimeout() {
        assertEquals(
            NetworkCheckup.TunnelProbe.Timeout,
            NetworkCheckup.reduceProbeAttempts(
                listOf(
                    NetworkCheckup.TunnelProbe.Timeout,
                    NetworkCheckup.TunnelProbe.Timeout,
                ),
            ),
        )
    }

    @Test
    fun probeReduce_ioOnlyIsIoError() {
        assertEquals(
            NetworkCheckup.TunnelProbe.IoError,
            NetworkCheckup.reduceProbeAttempts(
                listOf(NetworkCheckup.TunnelProbe.IoError, NetworkCheckup.TunnelProbe.IoError),
            ),
        )
    }

    @Test
    fun verdict_fakeipCodeIsOkNotHijacked() {
        // code "fakeip" 配 OK 状态：不能触发 DNS_HIJACKED。
        assertEquals(
            NetworkCheckup.VerdictKind.ALL_OK,
            NetworkCheckup.decideVerdict(
                true,
                true,
                withItem(NetworkCheckup.ItemId.DNS, NetworkCheckup.ItemStatus.OK, "fakeip"),
            ),
        )
    }
}
