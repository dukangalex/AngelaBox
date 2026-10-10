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
        assertEquals(
            NetworkCheckup.VerdictKind.TUNNEL_BROKEN,
            NetworkCheckup.decideVerdict(true, true, withItem(NetworkCheckup.ItemId.TUNNEL, NetworkCheckup.ItemStatus.FAIL, "timeout")),
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
