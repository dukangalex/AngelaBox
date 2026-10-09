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
}
