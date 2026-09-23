package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDomainResolverTest {
    @Test
    fun missingLocalTagGetsSystemDnsWithoutTouchingDetour() {
        val root = JSONObject(
            """
            {"dns":{"servers":[{"type":"udp","tag":"remote","server":"8.8.8.8"}]},
             "outbounds":[{"type":"vless","tag":"node","server":"example.com","detour":"relay","domain_resolver":"local"}]}
            """.trimIndent(),
        )
        assertTrue(ConfigInboundCompat.healDanglingDomainResolvers(root))
        val node = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("local", node.getString("domain_resolver"))
        assertEquals("relay", node.getString("detour"))
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        var local = false
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            if (server.getString("tag") == "local" && server.getString("type") == "local") local = true
        }
        assertTrue(local)
        assertFalse(ConfigInboundCompat.healDanglingDomainResolvers(root))
    }

    @Test
    fun objectFormAndDefaultResolverPointAtExistingLocal() {
        val root = JSONObject(
            """
            {"dns":{"servers":[{"type":"local","tag":"dns-local"}]},
             "outbounds":[{"type":"vmess","tag":"a","domain_resolver":{"server":"google","strategy":"ipv4_only"}}],
             "route":{"default_domain_resolver":"missing"}}
            """.trimIndent(),
        )
        assertTrue(ConfigInboundCompat.healDanglingDomainResolvers(root))
        val resolver = root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("domain_resolver")
        assertEquals("dns-local", resolver.getString("server"))
        assertEquals("ipv4_only", resolver.getString("strategy"))
        assertEquals("dns-local", root.getJSONObject("route").getString("default_domain_resolver"))
        assertEquals(1, root.getJSONObject("dns").getJSONArray("servers").length())
    }

    @Test
    fun presentResolverIsLeftAlone() {
        val root = JSONObject(
            """
            {"dns":{"servers":[{"type":"local","tag":"local"}]},
             "outbounds":[{"type":"shadowsocks","tag":"a","domain_resolver":"local"}]}
            """.trimIndent(),
        )
        assertFalse(ConfigInboundCompat.healDanglingDomainResolvers(root))
    }

    @Test
    fun diagnoseDoesNotEchoKernelLine() {
        val text = ConfigDiagnose.explain(
            "start or reload service: initialize outbound[0]: domain resolver not found: local",
        )
        assertTrue(text.contains("local"))
        assertTrue(text.contains("本机"))
        assertFalse(text.contains("initialize outbound"))
        assertTrue(ConfigDiagnose.looksLikeDomainResolver(text).not())
        assertTrue(
            ConfigDiagnose.looksLikeDomainResolver(
                "initialize outbound[0]: domain resolver not found: local",
            ),
        )
    }
}
