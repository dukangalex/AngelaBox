package io.nekohasekai.sfa.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainPathTest {

    @Test
    fun regularPathUsesDefaultOutbound() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = null,
            landingProfileName = null,
        )
        assertFalse(path.chained)
        assertEquals(3, path.hops.size)
        assertEquals(ChainPathHop.Role.Device, path.hops[0].role)
        assertEquals(ChainPathHop.Role.Exit, path.hops[1].role)
        assertEquals("节点选择", path.hops[1].label)
        assertEquals("UOT", path.hops[1].detail)
        assertEquals(ChainPathHop.Role.Destination, path.hops[2].role)
        assertTrue(path.hops.none { it.highlighted })
    }

    @Test
    fun regularPathFallsBackToProfileName() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = null,
            binding = null,
            landingProfileName = null,
        )
        assertEquals("UOT", path.hops[1].label)
        assertFalse(path.chained)
    }

    @Test
    fun chainedPathHighlightsEntryAndLanding() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "proxy-select", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        assertTrue(path.chained)
        assertEquals(4, path.hops.size)
        assertEquals(ChainPathHop.Role.Entry, path.hops[1].role)
        assertEquals("proxy-select", path.hops[1].label)
        assertTrue(path.hops[1].highlighted)
        assertEquals(ChainPathHop.Role.Landing, path.hops[2].role)
        assertEquals("zgo", path.hops[2].label)
        assertEquals("VPS", path.hops[2].detail)
        assertTrue(path.hops[2].highlighted)
        assertFalse(path.hops[0].highlighted)
        assertFalse(path.hops.last().highlighted)
    }

    @Test
    fun sameProfileLandingKeepsCurrentProfileAsDetail() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 1L, "jp-1"),
            landingProfileName = "UOT",
        )
        assertEquals("UOT", path.hops[2].detail)
        assertEquals("jp-1", path.landingTag)
    }

    @Test
    fun blankEntryFallsBackToDefaultOutbound() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "自动选择",
            binding = ChainBinding(1L, "", 2L, "land"),
            landingProfileName = "other",
        )
        assertEquals("自动选择", path.hops[1].label)
        assertTrue(path.chained)
    }

    @Test
    fun idleTopologyUsesPlannedHops() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 1L, "jp-1"),
            landingProfileName = "UOT",
        )
        val topology = LiveTopologyBuilder.fromPath(path, running = false)
        assertFalse(topology.running)
        assertTrue(topology.chained)
        assertEquals("节点选择", topology.hops[1].title)
        assertEquals("jp-1", topology.hops[2].title)
        assertFalse(topology.flowing)
    }

    @Test
    fun liveTopologyPrefersSelectedNodeOverGroupTag() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 1L, "自动选择"),
            landingProfileName = "UOT",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(
                GroupHint("节点选择", selected = "hk-1", delays = mapOf("hk-1" to 42)),
                GroupHint("自动选择", selected = "jp-1", delays = mapOf("jp-1" to 88)),
            ),
        )
        assertTrue(topology.running)
        assertEquals("hk-1", topology.hops[1].title)
        assertEquals(42, topology.hops[1].delayMs)
        assertEquals("jp-1", topology.hops[2].title)
        assertEquals(88, topology.hops[2].delayMs)
    }

    @Test
    fun liveTopologyOverlaysConnectionChainAndDestinations() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(
                GroupHint("节点选择", selected = "hk-1"),
                GroupHint("zgo", selected = "us-1"),
            ),
            liveChain = listOf("chainbox-chain-1-2", "chainbox-entry-节点选择", "hk-2", "chainbox-landing-2-zgo", "us-9"),
            destinations = listOf("api.example.com:443", "1.1.1.1:443"),
            activeConnections = 4,
            flowing = true,
        )
        assertEquals("hk-2", topology.hops[1].title)
        assertEquals("us-9", topology.hops[2].title)
        assertEquals("api.example.com:443", topology.hops[3].title)
        assertEquals(4, topology.activeConnections)
        assertTrue(topology.flowing)
        assertEquals(listOf("api.example.com:443", "1.1.1.1:443"), topology.destinations)
        assertTrue(topology.flowNodes.isNotEmpty())
        assertTrue(topology.flowLinks.isNotEmpty())
    }

    @Test
    fun trafficFlowUsesRulesAndSources() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val (nodes, links) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:43210",
                    rule = "geosite-google",
                    outbound = "hk-1",
                    chain = listOf("hk-1", "us-9"),
                    dest = "hbjsjpcl.cloudflareaccess.com:443",
                ),
                FlowSample(
                    source = "172.19.0.1:43211",
                    rule = "geosite-telegram",
                    outbound = "hk-1",
                    chain = listOf("hk-1", "us-9"),
                    dest = "api.telegram.org:443",
                ),
            ),
            path = path,
            chained = true,
        )
        val labels = nodes.map { it.label }
        assertTrue(labels.any { it.contains("172.19.0.1") })
        assertTrue(labels.contains("google"))
        assertTrue(labels.contains("telegram"))
        assertTrue(labels.contains("hk-1"))
        assertTrue(labels.contains("us-9"))
        assertTrue(nodes.none { it.column > 3 })
        assertTrue(labels.none { it.contains("cloudflareaccess") })
        assertTrue(links.isNotEmpty())
        val (placed, ribbons) = SankeyLayout.layout(nodes, links, 400f, 200f, 64f, 4f)
        assertEquals(nodes.size, placed.size)
        assertTrue(ribbons.isNotEmpty())
    }

    @Test
    fun prettyRuleParsesRuleSetAssignment() {
        assertEquals("google", TrafficFlowBuilder.prettyRule("""rule_set=["geosite-google"]"""))
        assertEquals("github", TrafficFlowBuilder.prettyRule("rule_set=geosite-github"))
        assertEquals("cn", TrafficFlowBuilder.prettyRule("geoip-cn"))
        assertEquals("<final>", TrafficFlowBuilder.prettyRule(""))
        assertEquals(
            "tracy",
            TrafficFlowBuilder.prettyHop("chainbox-landing-6-tracy-VLESS_TCP/TLS_WS-example.com"),
        )
        assertEquals("ofo.033388.xyz", TrafficFlowBuilder.prettyHop("ofo.033388.xyz"))
        assertEquals("自动选择", TrafficFlowBuilder.prettyHop("chainbox-entry-自动选择"))
    }

    @Test
    fun generatedChainTagIsHidden() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val (nodes, _) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:1",
                    rule = """rule_set=["geosite-google"]""",
                    outbound = "chainbox-chain-7-6",
                    chain = listOf("chainbox-chain-7-6"),
                    dest = "www.google.com:443",
                ),
            ),
            path = path,
            chained = true,
        )
        assertTrue(nodes.none { it.label.contains("chainbox", ignoreCase = true) })
        assertTrue(nodes.any { it.label == "节点选择" })
        assertTrue(nodes.any { it.label == "zgo" })
        assertTrue(nodes.any { it.label == "google" })
        assertTrue(nodes.maxOf { it.column } <= 3)
        assertTrue(nodes.none { it.label.contains("www.google.com") })
    }

    @Test
    fun directHopsUseDistinctFlag() {
        val path = ChainPath.regular(profileName = "UOT", exitTag = "节点选择")
        val (nodes, links) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:2",
                    rule = "geoip-cn",
                    outbound = "DIRECT",
                    chain = listOf("DIRECT"),
                    dest = "www.baidu.com:443",
                ),
            ),
            path = path,
            chained = false,
        )
        assertTrue(nodes.any { it.label == "DIRECT" && it.direct })
        assertTrue(links.any { it.direct })
        assertTrue(nodes.any { it.label == "cn" })
    }

    @Test
    fun angelaDirectIsPinnedWhenProxyColumnOverflows() {
        val path = ChainPath.regular(profileName = "UOT", exitTag = "节点选择")
        val samples = (0 until 12).map { i ->
            FlowSample(
                source = "172.19.0.1:50$i",
                rule = "geosite-site-$i",
                outbound = "hk-$i",
                chain = listOf("hk-$i"),
                dest = "host$i.example.com:443",
            )
        } + FlowSample(
            source = "172.19.0.1:9",
            rule = "geoip-cn",
            outbound = "angela-direct",
            chain = listOf("angela-direct"),
            dest = "www.baidu.com:443",
        )
        val (nodes, links) = TrafficFlowBuilder.build(samples, path, chained = false)
        assertTrue(nodes.any { it.label == "DIRECT" && it.direct })
        assertTrue(links.any { it.direct })
        assertTrue(TrafficFlowBuilder.isDirectTag("angela-direct"))
    }

    @Test
    fun chainedDirectSamplesStayOnDirectLane() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "proxy-select", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val (nodes, links) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:2",
                    rule = "geosite-cn",
                    outbound = "angela-direct",
                    chain = listOf("angela-direct"),
                    dest = "www.baidu.com:443",
                ),
                FlowSample(
                    source = "172.19.0.1:3",
                    rule = "geosite-google",
                    outbound = "韩国节点",
                    chain = listOf("韩国节点", "hk-1"),
                    dest = "www.google.com:443",
                ),
            ),
            path = path,
            chained = true,
        )
        assertTrue(nodes.any { it.label == "DIRECT" && it.direct })
        assertTrue(nodes.any { it.label == "cn" })
        assertTrue(links.any { it.direct })
        val directNode = nodes.first { it.label == "DIRECT" && it.direct }
        assertTrue(directNode.column >= 2)
        assertFalse(
            links.any { link ->
                val from = nodes.find { it.id == link.fromId }?.label
                val to = nodes.find { it.id == link.toId }?.label
                from == "cn" && to != null && to != "DIRECT" && !to.startsWith("+")
            },
        )
    }

    @Test
    fun mixedColumnPutsDirectBelowProxy() {
        val nodes = listOf(
            FlowNode("0|app", "app", 0, 1, false),
            FlowNode("0|DIRECT", "DIRECT", 0, 1, true),
            FlowNode("1|hk", "hk", 1, 1, false),
            FlowNode("1|d", "DIRECT", 1, 1, true),
        )
        val (placed, _) = SankeyLayout.layout(nodes, emptyList(), 400f, 240f, 12f, 8f, gapY = 8f)
        placed.groupBy { it.node.column }.forEach { (_, col) ->
            val proxy = col.filter { !it.node.direct }
            val direct = col.filter { it.node.direct }
            assertTrue(proxy.isNotEmpty() && direct.isNotEmpty())
            assertTrue(direct.minOf { it.y } > proxy.maxOf { it.y + it.h })
        }
    }

    @Test
    fun trafficFlowOverflowKeepsLinks() {
        val path = ChainPath.regular(profileName = "UOT", exitTag = "节点选择")
        val samples = (0 until 12).map { i ->
            FlowSample(
                source = "172.19.0.1:40$i",
                rule = "geosite-site-$i",
                outbound = "hk-1",
                chain = listOf("hk-1"),
                dest = "host$i.example.com:443",
            )
        }
        val (nodes, links) = TrafficFlowBuilder.build(samples, path, chained = false)
        assertTrue(nodes.any { it.label.startsWith("+") })
        assertTrue(links.isNotEmpty())
        val ids = nodes.map { it.id }.toSet()
        assertTrue(links.all { it.fromId in ids && it.toId in ids })
    }

    @Test
    fun directModeShortCircuitsToDirectExit() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 1L, "jp-1"),
            landingProfileName = "UOT",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            mode = "direct",
            destinations = listOf("www.baidu.com"),
        )
        assertFalse(topology.chained)
        assertEquals("DIRECT", topology.hops[1].title)
        assertEquals("www.baidu.com", topology.hops[2].title)
    }

    @Test
    fun regularLivePathUsesSelectedExit() {
        val path = ChainPath.regular(profileName = "UOT", exitTag = "节点选择")
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(GroupHint("节点选择", selected = "sg-1", delays = mapOf("sg-1" to 12))),
        )
        assertFalse(topology.chained)
        assertEquals("sg-1", topology.hops[1].title)
        assertEquals(12, topology.hops[1].delayMs)
    }

    @Test
    fun chainedSkipsDirectOverlayHops() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val (nodes, _) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:2",
                    rule = "geoip-cn",
                    outbound = "DIRECT",
                    chain = listOf("DIRECT"),
                    dest = "www.baidu.com:443",
                ),
                FlowSample(
                    source = "172.19.0.1:3",
                    rule = "geosite-google",
                    outbound = "us-9",
                    chain = listOf("hk-1", "us-9"),
                    dest = "www.google.com:443",
                ),
            ),
            path = path,
            chained = true,
        )
        assertTrue(nodes.none { it.label == "DIRECT" })
        assertTrue(nodes.any { it.label == "hk-1" })
        assertTrue(nodes.any { it.label == "us-9" })
        assertTrue(nodes.any { it.label == "google" })
        assertTrue(nodes.none { it.label == "cn" && nodes.any { n -> n.label == "DIRECT" } })
    }

    @Test
    fun chainedDirectOnlyFallsBackToSavedHops() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val (nodes, _) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:2",
                    rule = "geoip-cn",
                    outbound = "DIRECT",
                    chain = listOf("DIRECT"),
                    dest = "www.baidu.com:443",
                ),
            ),
            path = path,
            chained = true,
        )
        assertTrue(nodes.none { it.label == "DIRECT" })
        assertTrue(nodes.any { it.label == "节点选择" })
        assertTrue(nodes.any { it.label == "zgo" })
    }

    @Test
    fun chainedLiveHopsIgnoreDirectLeaves() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(
                GroupHint("节点选择", selected = "hk-1", delays = mapOf("hk-1" to 42)),
                GroupHint("zgo", selected = "us-9", delays = mapOf("us-9" to 88)),
            ),
            liveChain = listOf("DIRECT"),
        )
        assertTrue(topology.chained)
        assertEquals("hk-1", topology.hops[1].title)
        assertEquals("us-9", topology.hops[2].title)
        assertEquals(42, topology.hops[1].delayMs)
        assertEquals(88, topology.hops[2].delayMs)
        assertTrue(topology.hops.none { it.role == ChainPathHop.Role.Landing && it.title == "DIRECT" })
        assertTrue(topology.flowNodes.none { it.label == "DIRECT" })
    }

    @Test
    fun chainedSingleTagSampleKeepsEntryAndLanding() {
        val path = ChainPathBuilder.build(
            profileName = "MYCF",
            defaultOutboundTag = "自动选择",
            binding = ChainBinding(1L, "自动选择", 2L, "tracy"),
            landingProfileName = "Kitty",
        )
        val hops = listOf(
            LiveHop(ChainPathHop.Role.Device, title = ""),
            LiveHop(
                role = ChainPathHop.Role.Entry,
                title = "ofo.033388.xyz",
                subtitle = "自动选择",
            ),
            LiveHop(
                role = ChainPathHop.Role.Landing,
                title = "hk-exit",
                subtitle = "Kitty",
            ),
            LiveHop(ChainPathHop.Role.Destination, title = ""),
        )
        val (nodes, links) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:1",
                    rule = "geosite-google",
                    outbound = "自动选择",
                    chain = listOf("自动选择"),
                    dest = "www.google.com:443",
                ),
            ),
            path = path,
            hops = hops,
            chained = true,
        )
        val pair = TrafficFlowBuilder.chainedHopPair(path, hops)
        assertEquals("ofo.033388.xyz", pair[0])
        assertEquals("hk-exit", pair[1])
        assertTrue(nodes.any { it.column == 2 && it.label == "ofo.033388.xyz" })
        assertTrue(nodes.any { it.column == 3 && it.label == "hk-exit" })
        assertTrue(nodes.any { it.label == "google" })
        assertTrue(links.isNotEmpty())
    }

    @Test
    fun chainedLiveMustNotUseEntryLeafAsLanding() {
        val path = ChainPathBuilder.build(
            profileName = "MYCF",
            defaultOutboundTag = "自动选择",
            binding = ChainBinding(1L, "自动选择", 2L, "tracy"),
            landingProfileName = "Kitty",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(
                GroupHint(
                    "自动选择",
                    selected = "ofo.033388.xyz",
                    delays = mapOf("ofo.033388.xyz" to 108),
                ),
                GroupHint("tracy", selected = "hk-exit", delays = mapOf("hk-exit" to 40)),
            ),
            liveChain = listOf("ofo.033388.xyz"),
        )
        assertEquals("ofo.033388.xyz", topology.hops[1].title)
        assertEquals(ChainPathHop.Role.Entry, topology.hops[1].role)
        assertEquals("hk-exit", topology.hops[2].title)
        assertEquals(ChainPathHop.Role.Landing, topology.hops[2].role)
        assertTrue(topology.hops.none { it.role == ChainPathHop.Role.Landing && it.title == "ofo.033388.xyz" })
    }

    @Test
    fun trafficFlowUsesLoggedChainHopsWhenPresent() {
        val path = ChainPathBuilder.build(
            profileName = "Kitty Network",
            defaultOutboundTag = "proxy-select",
            binding = ChainBinding(1L, "proxy-select", 2L, "自动选择"),
            landingProfileName = "MyZgo",
        )
        val (nodes, _) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:1",
                    rule = "geosite-google",
                    outbound = "tracy",
                    chain = listOf("Hong Kong 03", "tracy"),
                    dest = "www.google.com:443",
                ),
                FlowSample(
                    source = "172.19.0.1:2",
                    rule = "",
                    outbound = "tracy",
                    chain = listOf("Hong Kong 03", "tracy"),
                    dest = "api.example.com:443",
                ),
            ),
            path = path,
            chained = true,
        )
        val labels = nodes.map { it.label }
        assertTrue(labels.any { it.contains("172.19.0.1") })
        assertTrue(labels.contains("google"))
        assertTrue(labels.contains("Hong Kong 03") || labels.any { it.startsWith("Hong Kong") })
        assertTrue(labels.contains("tracy"))
    }

    @Test
    fun chainedOneHopSampleFallsBackToSavedTags() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val (nodes, _) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:9",
                    rule = "geosite-google",
                    outbound = "hk-1",
                    chain = listOf("hk-1"),
                    dest = "www.google.com:443",
                ),
            ),
            path = path,
            chained = true,
        )
        assertTrue(nodes.any { it.column == 2 && it.label == "节点选择" })
        assertTrue(nodes.any { it.column == 3 && it.label == "hk-1" })
    }

    @Test
    fun landingGroupTagExpandsToSelectedLeaf() {
        val path = ChainPathBuilder.build(
            profileName = "MYCF",
            defaultOutboundTag = "自动选择",
            binding = ChainBinding(1L, "自动选择", 1L, "⚡ 自动选择"),
            landingProfileName = "MYCF",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(
                GroupHint(
                    "自动选择",
                    selected = "cf.553314.xyz",
                    delays = mapOf("cf.553314.xyz" to 80),
                ),
                GroupHint(
                    "⚡ 自动选择",
                    selected = "jp-tokyo-1",
                    delays = mapOf("jp-tokyo-1" to 120),
                ),
            ),
            liveChain = listOf("cf.553314.xyz", "⚡ 自动选择"),
        )
        assertEquals("cf.553314.xyz", topology.hops[1].title)
        assertEquals(ChainPathHop.Role.Entry, topology.hops[1].role)
        assertEquals("jp-tokyo-1", topology.hops[2].title)
        assertEquals(ChainPathHop.Role.Landing, topology.hops[2].role)
        assertTrue(topology.hops.none { it.role == ChainPathHop.Role.Landing && it.title.contains("自动选择") })
    }

    @Test
    fun trafficFlowExpandsGroupTagToLeaf() {
        val path = ChainPathBuilder.build(
            profileName = "MYCF",
            defaultOutboundTag = "自动选择",
            binding = ChainBinding(1L, "自动选择", 1L, "⚡ 自动选择"),
            landingProfileName = "MYCF",
        )
        val hops = listOf(
            LiveHop(ChainPathHop.Role.Device, title = ""),
            LiveHop(ChainPathHop.Role.Entry, title = "cf.553314.xyz", subtitle = "自动选择"),
            LiveHop(ChainPathHop.Role.Landing, title = "jp-tokyo-1", subtitle = "⚡ 自动选择"),
            LiveHop(ChainPathHop.Role.Destination, title = ""),
        )
        val (nodes, _) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:1",
                    rule = "geosite-google",
                    outbound = "⚡ 自动选择",
                    chain = listOf("cf.553314.xyz", "⚡ 自动选择"),
                    dest = "www.google.com:443",
                ),
            ),
            path = path,
            hops = hops,
            chained = true,
        )
        val labels = nodes.map { it.label }
        assertTrue(labels.contains("cf.553314.xyz") || labels.any { it.startsWith("cf.553314") })
        assertTrue(labels.contains("jp-tokyo-1"))
        assertTrue(labels.none { it.contains("自动选择") })
    }
}
