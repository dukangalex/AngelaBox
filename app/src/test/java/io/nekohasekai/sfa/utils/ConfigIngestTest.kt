package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigIngestTest {
    @Test
    fun clashYamlKeepsGeoipCnDirectAndDoesNotInjectExtraChinaRules() {
        val yaml = """
            proxies:
              - name: "hk-1"
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-256-gcm
                password: secret
            proxy-groups:
              - name: PROXY
                type: select
                proxies:
                  - hk-1
                  - DIRECT
            rules:
              - GEOIP,CN,DIRECT
              - DOMAIN-SUFFIX,google.com,PROXY
              - MATCH,PROXY
        """.trimIndent()
        val result = ConfigIngest.adapt(yaml)
        assertEquals(ConfigIngest.Format.Clash, result.format)
        val root = JSONObject(result.content)
        val outs = root.getJSONArray("outbounds")
        val tags = (0 until outs.length()).map { outs.getJSONObject(it).getString("tag") }
        assertTrue(tags.contains("hk-1"))
        assertTrue(tags.contains("PROXY"))
        assertTrue(tags.contains("direct"))
        val route = root.getJSONObject("route")
        assertEquals("PROXY", route.getString("final"))
        val rules = route.getJSONArray("rules")
        val text = rules.toString()
        assertTrue(text.contains("geoip-cn"))
        assertTrue(text.contains("google.com"))
        assertFalse(text.contains("geosite-cn"))
        assertFalse(text.contains("geolocation-cn"))
        val hk = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.getString("tag") == "hk-1" }
        assertEquals("shadowsocks", hk.getString("type"))
        assertEquals("1.2.3.4", hk.getString("server"))
        assertEquals(443, hk.getInt("server_port"))
    }

    @Test
    fun clashFlowStyleProxiesConvert() {
        val yaml = """
            proxies:
              - {name: jp-1, type: vmess, server: jp.example.com, port: 443, uuid: 11111111-1111-1111-1111-111111111111, alterId: 0, cipher: auto, tls: true, network: ws, ws-opts: {path: /v, headers: {Host: jp.example.com}}}
            proxy-groups:
              - {name: PROXY, type: select, proxies: [jp-1]}
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val root = JSONObject(ConfigIngest.adapt(yaml).content)
        val jp = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == "jp-1" }
        assertEquals("vmess", jp.getString("type"))
        assertTrue(jp.getJSONObject("tls").getBoolean("enabled"))
        assertEquals("ws", jp.getJSONObject("transport").getString("type"))
        assertEquals("/v", jp.getJSONObject("transport").getString("path"))
    }

    @Test
    fun shareLinkSsBecomesSelector() {
        val ss = "ss://YWVzLTI1Ni1nY206cGFzcw@example.com:8388#home"
        val result = ConfigIngest.adapt(ss)
        assertEquals(ConfigIngest.Format.ShareLinks, result.format)
        val root = JSONObject(result.content)
        val tags = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        assertTrue(tags.contains("home"))
        assertTrue(tags.contains("节点选择"))
        assertEquals("节点选择", root.getJSONObject("route").getString("final"))
    }

    @Test
    fun singBoxJsonPassthroughKeepsUserFinal() {
        val json = """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}"""
        val result = ConfigIngest.adapt(json)
        assertEquals(ConfigIngest.Format.SingBox, result.format)
        assertEquals("direct", JSONObject(result.content).getJSONObject("route").getString("final"))
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun healDoesNotThrowOnGarbage() {
        assertEquals("not-json", ConfigNormalize.healString("not-json"))
    }

    @Test
    fun sanitizeConvertsClashBeforeKernelCheck() {
        val yaml = """
            proxies:
              - name: n1
                type: ss
                server: 10.0.0.1
                port: 80
                cipher: aes-256-gcm
                password: x
            rules:
              - MATCH,n1
        """.trimIndent()
        val out = ConfigCompat.sanitize(yaml)
        val root = JSONObject(out)
        assertTrue(root.getJSONArray("outbounds").length() >= 1)
        assertEquals("n1", root.getJSONObject("route").getString("final"))
    }

    @Test
    fun healConvertsClashYamlToJson() {
        val yaml = """
            ---
            proxies:
              - name: n1
                type: ss
                server: 10.0.0.1
                port: 80
                cipher: aes-256-gcm
                password: x
            rules:
              - GEOIP,CN,DIRECT
              - MATCH,n1
        """.trimIndent()
        val healed = ConfigNormalize.heal(yaml)
        val root = JSONObject(healed.content)
        assertEquals("n1", root.getJSONObject("route").getString("final"))
        val rules = root.getJSONObject("route").getJSONArray("rules").toString()
        assertTrue(rules.contains("geoip-cn"))
        assertFalse(healed.content.contains("\"http-direct\""))
        assertTrue(healed.content.contains("angela-http-direct"))
        assertFalse(rules.contains("geosite-cn"))
    }

    @Test
    fun clashRejectMembersAreDroppedFromGroups() {
        val yaml = """
            proxies:
              - name: n1
                type: ss
                server: 10.0.0.1
                port: 80
                cipher: aes-256-gcm
                password: x
            proxy-groups:
              - name: PROXY
                type: select
                proxies: [n1, REJECT, DIRECT]
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val root = JSONObject(ConfigIngest.adapt(yaml).content)
        val proxy = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == "PROXY" }
        val members = (0 until proxy.getJSONArray("outbounds").length())
            .map { proxy.getJSONArray("outbounds").getString(it) }
        assertTrue(members.contains("n1"))
        assertTrue(members.contains("direct"))
        assertFalse(members.contains("REJECT"))
    }

    @Test
    fun v2rayNVmessJsonBecomesSelector() {
        val json = """{"v":"2","ps":"home","add":"example.com","port":"443","id":"11111111-1111-1111-1111-111111111111","aid":"0","scy":"auto","net":"tcp","tls":"tls"}"""
        val result = ConfigIngest.adapt(json)
        assertEquals(ConfigIngest.Format.ShareLinks, result.format)
        val tags = (0 until JSONObject(result.content).getJSONArray("outbounds").length())
            .map { JSONObject(result.content).getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        assertTrue(tags.contains("home"))
    }
}
