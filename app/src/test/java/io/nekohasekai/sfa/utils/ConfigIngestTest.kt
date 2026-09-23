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

    @Test
    fun clashJsonWithDnsIsNotSingBoxPassthrough() {
        val json = """
            {"mixed-port":"7890","dns":{"enable":true},"proxies":[{"name":"n1","type":"ss","server":"1.1.1.1","port":80,"cipher":"aes-256-gcm","password":"x"}],"rules":["MATCH,n1"]}
        """.trimIndent()
        val result = ConfigIngest.adapt(json)
        assertEquals(ConfigIngest.Format.Clash, result.format)
        val root = JSONObject(result.content)
        assertFalse(root.has("mixed-port"))
        assertFalse(root.has("proxies"))
        assertEquals("n1", root.getJSONObject("route").getString("final"))
    }

    @Test
    fun clashEchOptsBecomeTlsEch() {
        val yaml = """
            proxies:
              - name: e1
                type: vless
                server: ex.com
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
                tls: true
                ech-opts:
                  enable: true
                  config: AEn+DQ
                  query-server-name: cloudflare-ech.com
            rules:
              - MATCH,e1
        """.trimIndent()
        val root = JSONObject(ConfigIngest.adapt(yaml).content)
        val node = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == "e1" }
        val ech = node.getJSONObject("tls").getJSONObject("ech")
        assertTrue(ech.getBoolean("enabled"))
        val pem = ech.getJSONArray("config").getString(0)
        assertTrue(pem.contains("BEGIN ECH CONFIGS"))
        assertTrue(pem.contains("END ECH CONFIGS"))
        assertTrue(pem.contains("AEn+DQ") || pem.replace("\\s".toRegex(), "").contains("AEn+DQ"))
        assertEquals("cloudflare-ech.com", ech.getString("query_server_name"))
    }

    @Test
    fun httpupgradeIsMappedAndXhttpIsSkipped() {
        val yaml = """
            proxies:
              - name: up
                type: vless
                server: up.example
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
                network: httpupgrade
                httpupgrade-opts:
                  path: /up
                  host: up.example
              - name: bad
                type: vless
                server: bad.example
                port: 443
                uuid: 22222222-2222-2222-2222-222222222222
                network: xhttp
            rules:
              - MATCH,up
        """.trimIndent()
        val result = ConfigIngest.adapt(yaml)
        assertTrue(result.fatal == null)
        assertTrue(result.notes.any { it.contains("xhttp") })
        val root = JSONObject(result.content)
        val tags = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        assertTrue(tags.contains("up"))
        assertFalse(tags.contains("bad"))
        val up = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == "up" }
        assertEquals("httpupgrade", up.getJSONObject("transport").getString("type"))
        assertEquals("/up", up.getJSONObject("transport").getString("path"))
        assertEquals("up.example", up.getJSONObject("transport").getString("host"))
    }

    @Test
    fun allXhttpDoesNotBecomeDirect() {
        val yaml = """
            proxies:
              - name: bad
                type: vless
                server: bad.example
                port: 443
                uuid: 22222222-2222-2222-2222-222222222222
                network: xhttp
        """.trimIndent()
        val result = ConfigIngest.adapt(yaml)
        assertTrue(result.fatal.orEmpty().contains("直连"))
        assertTrue(result.fatal.orEmpty().contains("xhttp"))
        try {
            ConfigCompat.sanitize(yaml)
            throw AssertionError("expected fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("直连"))
        }
    }

    @Test
    fun masqueClashJsonIsNotPassthrough() {
        val json = """
            {"mixed-port":"7890","proxies":[{"name":"wp","type":"masque","server":"a.example","port":443}]}
        """.trimIndent()
        val result = ConfigIngest.adapt(json)
        assertTrue(result.fatal.orEmpty().contains("MASQUE"))
        assertFalse(result.content.trimStart().startsWith("p"))
    }

    @Test
    fun stringKeepAliveAndXhttpLeafAreCoerced() {
        val json = """
            {"outbounds":[
              {"type":"vless","tag":"bad","server":"a","server_port":1,"uuid":"11111111-1111-1111-1111-111111111111","transport":{"type":"xhttp"}},
              {"type":"shadowsocks","tag":"ok","server":"1.1.1.1","server_port":1,"method":"aes-256-gcm","password":"x","tcp_keep_alive":"true"}
            ]}
        """.trimIndent()
        val root = JSONObject(ConfigCompat.sanitize(json))
        assertFalse(root.has("mixed-port"))
        val tags = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        assertFalse(tags.contains("bad"))
        val ok = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == "ok" }
        assertEquals("60s", ok.getString("tcp_keep_alive"))
    }

    @Test
    fun clashProxiesPastTheOldHeaderWindowStillConvert() {
        val pad = buildString {
            append("port: 7890\n")
            append("socks-port: 7891\n")
            repeat(120) { append("# pad-$it ${"x".repeat(40)}\n") }
        }
        val yaml = pad + """
            proxies:
              - name: n1
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-256-gcm
                password: x
            rules:
              - MATCH,n1
        """.trimIndent()
        assertTrue(yaml.indexOf("proxies:") > 4000)
        val root = JSONObject(ConfigCompat.sanitize(yaml))
        assertEquals("n1", root.getJSONObject("route").getString("final"))
    }

    @Test
    fun plainTextIsRejectedInsteadOfReachingTheJsonDecoder() {
        try {
            ConfigCompat.sanitize("port: 1\n")
            throw AssertionError("expected reject")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("直连"))
            assertFalse(e.message.orEmpty().contains("invalid character"))
        }
    }

    @Test
    fun wireguardShareBecomesEndpoint() {
        val line = "wireguard://qJPq9qRY3EeIxa1mwRiB0DmXBDWJR4rzsoBG%2BLDqxHk%3D@162.159.197.109:443?address=172.16.0.2%2F32&reserved=0%2C0%2C0&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&mtu=1420#WG-CF-1"
        val result = ConfigIngest.adapt(line)
        assertEquals(ConfigIngest.Format.ShareLinks, result.format)
        assertTrue(result.fatal == null)
        val root = JSONObject(result.content)
        val ep = root.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", ep.getString("type"))
        assertEquals("WG-CF-1", ep.getString("tag"))
        assertEquals("qJPq9qRY3EeIxa1mwRiB0DmXBDWJR4rzsoBG+LDqxHk=", ep.getString("private_key"))
        assertEquals("172.16.0.2/32", ep.getJSONArray("address").getString(0))
        assertEquals(1420, ep.getInt("mtu"))
        val peer = ep.getJSONArray("peers").getJSONObject(0)
        assertEquals("162.159.197.109", peer.getString("address"))
        assertEquals(443, peer.getInt("port"))
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", peer.getString("public_key"))
        assertEquals(0, peer.getJSONArray("reserved").getInt(0))
        assertEquals(0, peer.getJSONArray("reserved").getInt(2))
        assertEquals("节点选择", root.getJSONObject("route").getString("final"))
        val select = (0 until root.getJSONArray("outbounds").length())
            .map { root.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == "节点选择" }
        assertEquals("WG-CF-1", select.getJSONArray("outbounds").getString(0))
        assertTrue(result.notes.any { it.contains("默认脚本") })
    }

    @Test
    fun badEchWithoutQueryNameIsDropped() {
        val json = """
            {"outbounds":[{"type":"vless","tag":"n","server":"a","server_port":443,"uuid":"11111111-1111-1111-1111-111111111111","tls":{"enabled":true,"ech":{"enabled":true,"config":["!!!"]}}}]}
        """.trimIndent()
        val root = JSONObject(ConfigCompat.sanitize(json))
        val tls = root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")
        assertFalse(tls.has("ech"))
    }

    @Test
    fun clashKeepsRuleSetPortLogicalAndPrivate() {
        val yaml = """
            proxies:
              - name: n1
                type: ss
                server: 1.2.3.4
                port: 443
                cipher: aes-256-gcm
                password: x
            proxy-groups:
              - name: PROXY
                type: select
                proxies: [n1]
            rule-providers:
              reject:
                type: http
                behavior: domain
                url: https://example.com/reject.yaml
                path: ./ruleset/reject.yaml
            rules:
              - RULE-SET,reject,REJECT
              - DOMAIN,example.com,PROXY
              - PORT,8443,PROXY
              - AND,((DOMAIN-SUFFIX,youtube.com),(NETWORK,tcp)),PROXY
              - GEOIP,private,DIRECT
              - GEOIP,CN,DIRECT
              - MATCH,PROXY
        """.trimIndent()
        val result = ConfigIngest.adapt(yaml)
        assertEquals(ConfigIngest.Format.Clash, result.format)
        val root = JSONObject(result.content)
        val rules = root.getJSONObject("route").getJSONArray("rules")
        val text = rules.toString()
        assertTrue(text.contains("geosite-category-ads-all"))
        assertTrue(text.contains("example.com"))
        assertTrue(text.contains("8443"))
        assertTrue(text.contains("logical"))
        assertTrue(text.contains("youtube.com"))
        assertTrue(text.contains("ip_is_private"))
        assertTrue(text.contains("geoip-cn"))
        assertFalse(text.contains("geoip-private"))
        assertEquals("PROXY", root.getJSONObject("route").getString("final"))
        val sets = root.getJSONObject("route").getJSONArray("rule_set").toString()
        assertTrue(sets.contains("geosite-category-ads-all"))
        assertTrue(sets.contains("geoip-cn"))
        assertFalse(sets.contains("geoip-private"))
    }
}
