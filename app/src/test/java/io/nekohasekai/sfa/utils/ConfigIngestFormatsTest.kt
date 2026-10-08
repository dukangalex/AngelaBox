package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Import coverage across source formats (Clash / mihomo YAML, share links, Xray / v2rayN JSON,
 * sing-box JSON) and input shapes (single node, several nodes, mixed paste).
 */
class ConfigIngestFormatsTest {
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    private fun convert(text: String): Pair<ConfigIngest.Result, JSONObject> {
        assertTrue("looksConvertible: $text", ConfigIngest.looksConvertible(text))
        val result = ConfigIngest.adapt(text)
        assertNull("fatal: ${result.fatal}", result.fatal)
        val root = JSONObject(result.content)
        assertStartable(root)
        // The whole import pipeline must accept it too.
        JSONObject(ConfigCompat.sanitize(text))
        return result to root
    }

    private fun nodes(root: JSONObject): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        for (key in listOf("outbounds", "endpoints")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out[o.getString("tag")] = o
            }
        }
        return out
    }

    /** Every reference sing-box resolves at start exists: group members, detours, final, DNS resolvers. */
    private fun assertStartable(root: JSONObject) {
        val all = nodes(root)
        for ((tag, node) in all) {
            assertFalse("helper key leaked in $tag", node.has("__angela_chain"))
            node.optJSONArray("outbounds")?.let { members ->
                assertTrue("empty group $tag", members.length() > 0)
                for (i in 0 until members.length()) {
                    assertTrue("$tag -> ${members.getString(i)}", members.getString(i) in all)
                }
            }
            node.optString("default").takeIf { it.isNotEmpty() }?.let { assertTrue("default $it", it in all) }
            node.optString("detour").takeIf { it.isNotEmpty() }?.let { assertTrue("detour $it", it in all) }
            if (node.optString("type") == "hysteria2" && node.has("server_ports")) {
                assertFalse(node.has("server_port"))
                val ports = node.getJSONArray("server_ports")
                for (i in 0 until ports.length()) assertTrue(ports.getString(i).contains(':'))
            }
        }
        val final = root.optJSONObject("route")?.optString("final").orEmpty()
        if (final.isNotEmpty()) assertTrue("final $final", final in all)
        val dns = root.optJSONObject("dns")?.optJSONArray("servers")
        if (dns != null) {
            val dnsTags = (0 until dns.length()).map { dns.getJSONObject(it).optString("tag") }.toSet()
            for (i in 0 until dns.length()) {
                val resolver = dns.getJSONObject(i).optString("domain_resolver")
                if (resolver.isNotEmpty()) assertTrue("resolver $resolver", resolver in dnsTags)
            }
        }
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    // ---------------------------------------------------------------- YAML reader

    @Test
    fun blockScalarsKeepTheRestOfTheDocument() {
        val yaml = """
            proxies:
              - name: ssh-1
                type: ssh
                server: 10.0.0.1
                port: 22
                username: root
                private-key: |
                  -----BEGIN OPENSSH PRIVATE KEY-----
                  b3BlbnNzaC1rZXktdjEAAAAA
                  -----END OPENSSH PRIVATE KEY-----
                private-key-passphrase: >-
                  folded
                  pass
              - name: hk-1
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-128-gcm
                password: pw
            proxy-groups:
              - name: PROXY
                type: select
                proxies: [ssh-1, hk-1]
            rules:
              - DOMAIN-SUFFIX,google.com,PROXY
              - MATCH,DIRECT
        """.trimIndent()
        val (_, root) = convert(yaml)
        val all = nodes(root)
        val ssh = all.getValue("ssh-1")
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----",
            ssh.getString("private_key"),
        )
        assertEquals("folded pass", ssh.getString("private_key_passphrase"))
        assertTrue("hk-1" in all)
        assertEquals(2, all.getValue("PROXY").getJSONArray("outbounds").length())
        assertTrue(root.getJSONObject("route").getJSONArray("rules").toString().contains("google.com"))
        assertEquals("direct", root.getJSONObject("route").getString("final"))
    }

    @Test
    fun blockScalarChompingAndListItems() {
        val parsed = MiniYaml.parse(
            """
            a: |+
              keep

            b: |-
              strip
            c:
              - |
                item one
              - plain
            d: >
              one
              two

              para
            """.trimIndent(),
        ) as Map<*, *>
        assertEquals("keep\n\n", parsed["a"])
        assertEquals("strip", parsed["b"])
        assertEquals(listOf("item one\n", "plain"), parsed["c"])
        assertEquals("one two\npara\n", parsed["d"])
    }

    @Test
    fun yamlOneOneBooleansStayText() {
        val yaml = """
            proxies:
              - name: NO
                type: trojan
                server: no.example.com
                port: 443
                password: yes
                udp: true
              - name: on
                type: ss
                server: 1.1.1.1
                port: 443
                cipher: aes-256-gcm
                password: off
            proxy-groups:
              - name: PROXY
                type: select
                proxies: [NO, on]
        """.trimIndent()
        val (_, root) = convert(yaml)
        val all = nodes(root)
        assertEquals("yes", all.getValue("NO").getString("password"))
        assertEquals("off", all.getValue("on").getString("password"))
        val members = all.getValue("PROXY").getJSONArray("outbounds")
        assertEquals("NO", members.getString(0))
        assertEquals("on", members.getString(1))
    }

    @Test
    fun flowMapsAnchorsMergeKeysAndQuotes() {
        val yaml = """
            base: &base {type: vmess, port: 443, uuid: $uuid, alterId: 0, cipher: auto, tls: true}
            proxies:
              - {name: "HK, 01", server: hk.example.com, <<: *base}
              - <<: *base
                name: 'it''s JP'
                server: jp.example.com
              - {
                  name: SG multi,
                  type: trojan,
                  server: sg.example.com,
                  port: 443,
                  password: "p\"w\u00e9"
                }
              - name: Bob's node # trailing comment
                type: ss
                server: 2.2.2.2
                port: 8388
                cipher: aes-128-gcm
                password: x
            proxy-groups:
              - &grp {name: PROXY, type: select, proxies: ["HK, 01", "it's JP", SG multi, Bob's node]}
        """.trimIndent()
        val (_, root) = convert(yaml)
        val all = nodes(root)
        assertEquals("vmess", all.getValue("HK, 01").getString("type"))
        assertEquals(uuid, all.getValue("it's JP").getString("uuid"))
        assertEquals("jp.example.com", all.getValue("it's JP").getString("server"))
        assertEquals("p\"wé", all.getValue("SG multi").getString("password"))
        assertTrue("Bob's node" in all)
        assertEquals(4, all.getValue("PROXY").getJSONArray("outbounds").length())
    }

    // ---------------------------------------------------------------- Clash / mihomo nodes

    @Test
    fun clashHysteria2PortsAndDefaults() {
        val yaml = """
            proxies:
              - {name: hy2-hop, type: hysteria2, server: a.example.com, ports: "443,20000-30000", password: pw, up: "50 Mbps", down: 200, hop-interval: 30, sni: a.example.com}
              - {name: hy2-mport, type: hysteria2, server: b.example.com, port: 8443, mport: 30000-40000, password: pw}
              - {name: hy2-noport, type: hysteria2, server: c.example.com, password: pw, obfs: salamander, obfs-password: ob}
        """.trimIndent()
        val (_, root) = convert(yaml)
        val all = nodes(root)
        val hop = all.getValue("hy2-hop")
        assertEquals(JSONArray(listOf("443:443", "20000:30000")).toString(), hop.getJSONArray("server_ports").toString())
        assertEquals(50, hop.getInt("up_mbps"))
        assertEquals(200, hop.getInt("down_mbps"))
        assertEquals("30s", hop.getString("hop_interval"))
        assertEquals("30000:40000", all.getValue("hy2-mport").getJSONArray("server_ports").getString(0))
        assertEquals(443, all.getValue("hy2-noport").getInt("server_port"))
        assertEquals("salamander", all.getValue("hy2-noport").getJSONObject("obfs").getString("type"))
    }

    @Test
    fun clashShadowTlsPluginBecomesDetourAndUnknownPluginsSkipOnlyThatNode() {
        val yaml = """
            proxies:
              - name: ss-stls
                type: ss
                server: 3.3.3.3
                port: 443
                cipher: 2022-blake3-aes-128-gcm
                password: "AAAAAAAAAAAAAAAAAAAAAA=="
                client-fingerprint: chrome
                plugin: shadow-tls
                plugin-opts:
                  host: cloud.tencent.com
                  password: stls-pw
                  version: 3
              - {name: ss-restls, type: ss, server: 4.4.4.4, port: 443, cipher: aes-128-gcm, password: x, plugin: restls, plugin-opts: {host: a.com, password: b, version-hint: tls13}}
              - {name: ss-kcp, type: ss, server: 5.5.5.5, port: 443, cipher: aes-128-gcm, password: x, plugin: kcptun, plugin-opts: {key: k}}
              - {name: ss-obfs, type: ss, server: 6.6.6.6, port: 443, cipher: aes-128-gcm, password: x, plugin: obfs, plugin-opts: {mode: tls, host: bing.com}}
        """.trimIndent()
        val (result, root) = convert(yaml)
        val all = nodes(root)
        val ss = all.getValue("ss-stls")
        assertEquals("ss-stls-shadowtls", ss.getString("detour"))
        val stls = all.getValue("ss-stls-shadowtls")
        assertEquals("shadowtls", stls.getString("type"))
        assertEquals(3, stls.getInt("version"))
        assertEquals("stls-pw", stls.getString("password"))
        assertEquals(443, stls.getInt("server_port"))
        assertEquals("cloud.tencent.com", stls.getJSONObject("tls").getString("server_name"))
        assertEquals("chrome", stls.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
        assertFalse("ss-restls" in all)
        assertFalse("ss-kcp" in all)
        assertEquals("obfs-local", all.getValue("ss-obfs").getString("plugin"))
        assertTrue(result.notes.any { it.contains("restls") && it.contains("kcptun") })
        // The helper is not offered as a node in the generated selector.
        val members = all.getValue("节点选择").getJSONArray("outbounds").toString()
        assertFalse(members.contains("shadowtls"))
    }

    @Test
    fun clashVlessVisionVmessOptionsAndSmux() {
        val yaml = """
            proxies:
              - name: vision
                type: vless
                server: v.example.com
                port: 443
                uuid: $uuid
                flow: xtls-rprx-vision-udp443
                tls: true
                servername: v.example.com
                client-fingerprint: chrome
                reality-opts: {public-key: PUBKEY, short-id: 0123}
              - {name: vless-enc, type: vless, server: e.example.com, port: 443, uuid: $uuid, encryption: "mlkem768x25519plus.native.0rtt.abc"}
              - {name: vmess-http, type: vmess, server: h.example.com, port: 80, uuid: $uuid, cipher: auto, network: http, http-opts: {path: [/]}}
              - name: vmess-mux
                type: vmess
                server: m.example.com
                port: 443
                uuid: $uuid
                cipher: auto
                global-padding: true
                authenticated-length: true
                tfo: true
                smux: {enabled: true, protocol: h2mux, max-connections: 4, padding: true}
                dialer-proxy: vision
        """.trimIndent()
        val (result, root) = convert(yaml)
        val all = nodes(root)
        val vision = all.getValue("vision")
        assertEquals("xtls-rprx-vision", vision.getString("flow"))
        assertEquals("0123", vision.getJSONObject("tls").getJSONObject("reality").getString("short_id"))
        assertFalse("vless-enc" in all)
        assertFalse("vmess-http" in all)
        val mux = all.getValue("vmess-mux")
        assertTrue(mux.getBoolean("global_padding"))
        assertTrue(mux.getBoolean("authenticated_length"))
        assertTrue(mux.getBoolean("tcp_fast_open"))
        assertEquals("h2mux", mux.getJSONObject("multiplex").getString("protocol"))
        assertEquals(4, mux.getJSONObject("multiplex").getInt("max_connections"))
        assertEquals("vision", mux.getString("detour"))
        assertTrue(result.notes.any { it.contains("vless encryption") && it.contains("tcp+http") })
    }

    @Test
    fun clashTuicWireguardAndDirect() {
        val yaml = """
            proxies:
              - {name: tuic5, type: tuic, server: t.example.com, port: 443, uuid: $uuid, password: pw, congestion-controller: bbr, udp-relay-mode: quic, reduce-rtt: true, heartbeat-interval: 10000, alpn: [h3]}
              - {name: tuic4, type: tuic, server: t4.example.com, port: 443, token: tok}
              - name: wg
                type: wireguard
                ip: 172.16.0.2
                ipv6: 2606:4700:110:8a36::2
                private-key: cGrivatekeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                mtu: 1280
                peers:
                  - server: 162.159.192.1
                    port: 2408
                    public-key: bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
                    pre-shared-key: psk
                    allowed-ips: ['0.0.0.0/0']
                    reserved: [1, 2, 3]
              - {name: 直连节点, type: direct}
            proxy-groups:
              - {name: PROXY, type: select, proxies: [tuic5, wg, 直连节点]}
        """.trimIndent()
        val (result, root) = convert(yaml)
        val all = nodes(root)
        val tuic = all.getValue("tuic5")
        assertEquals("quic", tuic.getString("udp_relay_mode"))
        assertTrue(tuic.getBoolean("zero_rtt_handshake"))
        assertEquals("10000ms", tuic.getString("heartbeat"))
        assertEquals("h3", tuic.getJSONObject("tls").getJSONArray("alpn").getString(0))
        assertFalse("tuic4" in all)
        assertTrue(result.notes.any { it.contains("tuic v4") })
        val wg = all.getValue("wg")
        assertEquals(JSONArray(listOf("172.16.0.2/32", "2606:4700:110:8a36::2/128")).toString(), wg.getJSONArray("address").toString())
        val peer = wg.getJSONArray("peers").getJSONObject(0)
        assertEquals("162.159.192.1", peer.getString("address"))
        assertEquals(2408, peer.getInt("port"))
        assertEquals("psk", peer.getString("pre_shared_key"))
        assertEquals("0.0.0.0/0", peer.getJSONArray("allowed_ips").getString(0))
        assertEquals(3, peer.getJSONArray("reserved").getInt(2))
        assertEquals(1, root.getJSONArray("endpoints").length())
        assertEquals("direct", all.getValue("直连节点").getString("type"))
    }

    @Test
    fun clashIncludeAllFiltersAndInlineProviders() {
        val yaml = """
            proxies:
              - {name: 🇭🇰 HK 01, type: ss, server: 1.1.1.1, port: 1, cipher: aes-128-gcm, password: x}
              - {name: 🇯🇵 JP 01, type: ss, server: 1.1.1.2, port: 1, cipher: aes-128-gcm, password: x}
              - {name: 🇭🇰 HK 02 x10, type: trojan, server: 1.1.1.3, port: 443, password: x}
            proxy-providers:
              extra:
                type: inline
                payload:
                  - {name: 🇺🇸 US 01, type: ss, server: 1.1.1.4, port: 1, cipher: aes-128-gcm, password: x}
                  - {name: 🇭🇰 HK 03, type: ss, server: 1.1.1.5, port: 1, cipher: aes-128-gcm, password: x}
            proxy-groups:
              - {name: 全部, type: select, include-all: true}
              - {name: 香港, type: url-test, include-all: true, filter: "(?i)港|HK", exclude-filter: "x10"}
              - {name: 只要节点源, type: select, use: [extra]}
              - {name: 非 trojan, type: select, include-all-proxies: true, exclude-type: "Trojan"}
            rules:
              - MATCH,全部
        """.trimIndent()
        val (_, root) = convert(yaml)
        val all = nodes(root)
        fun members(tag: String) = all.getValue(tag).getJSONArray("outbounds").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        assertEquals(5, members("全部").size)
        assertEquals(listOf("🇭🇰 HK 01", "🇭🇰 HK 03"), members("香港").sorted())
        assertEquals(listOf("🇺🇸 US 01", "🇭🇰 HK 03"), members("只要节点源"))
        assertEquals(listOf("🇭🇰 HK 01", "🇯🇵 JP 01"), members("非 trojan"))
    }

    @Test
    fun bareProxiesListGetsSelectorAndAutoTest() {
        val yaml = """
            proxies:
              - {name: a, type: ss, server: 1.1.1.1, port: 1, cipher: aes-128-gcm, password: x}
              - {name: b, type: trojan, server: b.example.com, port: 443, password: x}
        """.trimIndent()
        val (_, root) = convert(yaml)
        val all = nodes(root)
        val selector = all.getValue("节点选择")
        assertEquals("selector", selector.getString("type"))
        assertEquals("自动选择", selector.getString("default"))
        assertEquals("urltest", all.getValue("自动选择").getString("type"))
        assertEquals("节点选择", root.getJSONObject("route").getString("final"))
    }

    // ---------------------------------------------------------------- share links

    @Test
    fun hysteria2LinksWithPortListMportAndNoPort() {
        val text = """
            hysteria2://pw%40x@hop.example.com:443,20000-30000/?sni=hop.example.com&obfs=salamander&obfs-password=ob#hop
            hy2://pw@mport.example.com:8443?mport=30000-40000&insecure=1#mport
            hysteria2://pw@noport.example.com/?sni=noport.example.com#noport
            hy2://pw@[2001:db8::1]:443?alpn=h3#v6
        """.trimIndent()
        val (_, root) = convert(text)
        val all = nodes(root)
        val hop = all.getValue("hop")
        assertEquals("pw@x", hop.getString("password"))
        assertEquals(JSONArray(listOf("443:443", "20000:30000")).toString(), hop.getJSONArray("server_ports").toString())
        assertEquals("ob", hop.getJSONObject("obfs").getString("password"))
        val mport = all.getValue("mport")
        assertEquals("30000:40000", mport.getJSONArray("server_ports").getString(0))
        assertTrue(mport.getJSONObject("tls").getBoolean("insecure"))
        assertEquals(443, all.getValue("noport").getInt("server_port"))
        assertEquals("2001:db8::1", all.getValue("v6").getString("server"))
    }

    @Test
    fun severalLinksPerLineWithCommentsAndBlankLines() {
        val ss = "ss://" + b64("aes-128-gcm:pw") + "@1.2.3.4:8388#ss%201"
        val text = """
            # my nodes

            $ss vless://$uuid@v.example.com:443?security=tls&sni=v.example.com&alpn=h2,http/1.1&fp=chrome#vless
            trojan://pw@t.example.com:443?sni=t.example.com#trojan,anytls://pw@a.example.com:443?sni=a.example.com#anytls
            // tuic below
            - tuic://$uuid:pw@tu.example.com:443?congestion_control=bbr&udp_relay_mode=quic&alpn=h3#tuic
        """.trimIndent()
        val (result, root) = convert(text)
        assertEquals(ConfigIngest.Format.ShareLinks, result.format)
        val all = nodes(root)
        for (tag in listOf("ss 1", "vless", "trojan", "anytls", "tuic")) assertTrue(tag, tag in all)
        assertEquals(JSONArray(listOf("h2", "http/1.1")).toString(), all.getValue("vless").getJSONObject("tls").getJSONArray("alpn").toString())
        assertEquals("quic", all.getValue("tuic").getString("udp_relay_mode"))
        assertEquals("自动选择", all.getValue("节点选择").getString("default"))
    }

    @Test
    fun base64BlobOfMixedLinks() {
        val vmess = "vmess://" + b64(
            """{"v":"2","ps":"vm","add":"vm.example.com","port":"443","id":"$uuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ws?ed=2048","tls":"tls","sni":"vm.example.com","alpn":"h2,http/1.1","fp":"chrome"}""",
        )
        val blob = b64(
            listOf(
                vmess,
                "socks://" + b64("user:pass") + "@s.example.com:1080#socks",
                "https://user:pw@h.example.com:443#https-proxy",
                "ssr://notsupported",
            ).joinToString("\n"),
        )
        val (_, root) = convert(blob)
        val all = nodes(root)
        val vm = all.getValue("vm")
        assertEquals("chrome", vm.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
        assertEquals("h2", vm.getJSONObject("tls").getJSONArray("alpn").getString(0))
        assertEquals(2048, vm.getJSONObject("transport").getInt("max_early_data"))
        val socks = all.getValue("socks")
        assertEquals("user", socks.getString("username"))
        assertEquals("pass", socks.getString("password"))
        val https = all.getValue("https-proxy")
        assertEquals("http", https.getString("type"))
        assertTrue(https.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun ssLinkPluginsAndUnsupportedTransportsAreSkippedPerLink() {
        val ss = "ss://" + b64("aes-128-gcm:pw")
        val text = listOf(
            "$ss@1.1.1.1:443?plugin=shadow-tls%3Bhost%3Dcloud.tencent.com%3Bpassword%3Dsp%3Bversion%3D3#stls",
            "$ss@1.1.1.2:443?obfs=http&obfsParam=bing.com#rocket-obfs",
            "$ss@1.1.1.3:443?plugin=kcptun%3Bkey%3Dk#kcp",
            "vless://$uuid@x.example.com:80?type=tcp&headerType=http&host=x#tcp-http",
            "vless://$uuid@y.example.com:443?encryption=mlkem768x25519plus.native.0rtt.x&security=tls#vless-enc",
            "vless://$uuid@z.example.com:443?security=reality&pbk=PK&sid=ab&flow=xtls-rprx-vision&sni=www.apple.com#reality",
        ).joinToString("\n")
        val (result, root) = convert(text)
        val all = nodes(root)
        assertEquals("stls-shadowtls", all.getValue("stls").getString("detour"))
        assertEquals(3, all.getValue("stls-shadowtls").getInt("version"))
        assertEquals("obfs=http;obfs-host=bing.com", all.getValue("rocket-obfs").getString("plugin_opts"))
        assertFalse("kcp" in all)
        assertFalse("tcp-http" in all)
        assertFalse("vless-enc" in all)
        assertEquals("xtls-rprx-vision", all.getValue("reality").getString("flow"))
        assertTrue(result.notes.any { it.contains("跳过 3") })
    }

    @Test
    fun httpSubscriptionUrlIsNotANode() {
        val url = "https://sub.example.com/api/v1/client/subscribe?token=abc"
        assertFalse(ConfigIngest.looksConvertible(url))
        assertEquals(ConfigIngest.Format.Unknown, ConfigIngest.adapt(url).format)
        assertTrue(ConfigIngest.looksConvertible("http://u:p@proxy.example.com:8080#office"))
    }

    // ---------------------------------------------------------------- Xray / v2rayN JSON

    @Test
    fun xrayFullConfigConvertsProxyOutbounds() {
        val json = """
            {
              "log": {"loglevel": "warning"},
              "inbounds": [{"port": 10808, "protocol": "socks", "settings": {"udp": true}}],
              "outbounds": [
                {"tag": "proxy", "protocol": "vless",
                 "settings": {"vnext": [{"address": "r.example.com", "port": 443, "users": [{"id": "$uuid", "encryption": "none", "flow": "xtls-rprx-vision"}]}]},
                 "streamSettings": {"network": "tcp", "security": "reality",
                   "realitySettings": {"serverName": "www.microsoft.com", "fingerprint": "chrome", "publicKey": "PUBKEY", "shortId": "6ba85179e30d4fc2", "spiderX": "/"}}},
                {"tag": "ws", "protocol": "vmess",
                 "settings": {"vnext": [{"address": "w.example.com", "port": 443, "users": [{"id": "$uuid", "alterId": 0, "security": "auto"}]}]},
                 "streamSettings": {"network": "ws", "security": "tls", "tlsSettings": {"serverName": "w.example.com", "alpn": ["http/1.1"]}, "wsSettings": {"path": "/ray", "headers": {"Host": "w.example.com"}}}},
                {"tag": "grpc", "protocol": "trojan",
                 "settings": {"servers": [{"address": "g.example.com", "port": 443, "password": "pw"}]},
                 "streamSettings": {"network": "grpc", "security": "tls", "grpcSettings": {"serviceName": "svc"}, "sockopt": {"dialerProxy": "ws"}}},
                {"tag": "ss", "protocol": "shadowsocks", "settings": {"servers": [{"address": "s.example.com", "port": 8388, "method": "2022-blake3-aes-128-gcm", "password": "AAAAAAAAAAAAAAAAAAAAAA==", "uot": true}]}},
                {"tag": "kcp", "protocol": "vmess", "settings": {"vnext": [{"address": "k.example.com", "port": 1, "users": [{"id": "$uuid"}]}]}, "streamSettings": {"network": "kcp"}},
                {"tag": "direct", "protocol": "freedom"},
                {"tag": "block", "protocol": "blackhole"}
              ],
              "routing": {"rules": [{"type": "field", "outboundTag": "direct", "domain": ["geosite:cn"]}]}
            }
        """.trimIndent()
        val (result, root) = convert(json)
        val all = nodes(root)
        val reality = all.getValue("proxy")
        assertEquals("xtls-rprx-vision", reality.getString("flow"))
        val tls = reality.getJSONObject("tls")
        assertEquals("www.microsoft.com", tls.getString("server_name"))
        assertEquals("PUBKEY", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
        val ws = all.getValue("ws")
        assertEquals("/ray", ws.getJSONObject("transport").getString("path"))
        assertEquals("w.example.com", ws.getJSONObject("transport").getJSONObject("headers").getString("Host"))
        val grpc = all.getValue("grpc")
        assertEquals("svc", grpc.getJSONObject("transport").getString("service_name"))
        assertEquals("ws", grpc.getString("detour"))
        assertTrue(all.getValue("ss").getBoolean("udp_over_tcp"))
        assertFalse("kcp" in all)
        assertTrue(result.notes.any { it.contains("Xray") })
    }

    @Test
    fun singleXrayOutboundAndWireguard() {
        val vless = """{"protocol":"vless","tag":"one","settings":{"address":"o.example.com","port":443,"id":"$uuid","encryption":"none"},"streamSettings":{"network":"httpupgrade","security":"tls","httpupgradeSettings":{"path":"/up","host":"cdn.example.com"}}}"""
        val (_, root) = convert(vless)
        val one = nodes(root).getValue("one")
        assertEquals("httpupgrade", one.getJSONObject("transport").getString("type"))
        assertEquals("cdn.example.com", one.getJSONObject("transport").getString("host"))

        val wg = """[{"protocol":"wireguard","tag":"warp","settings":{"secretKey":"cGrivatekeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","address":["172.16.0.2/32","2606:4700::2/128"],"peers":[{"publicKey":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=","endpoint":"engage.cloudflareclient.com:2408"}],"reserved":[1,2,3],"mtu":1280}}]"""
        val (_, wgRoot) = convert(wg)
        val ep = nodes(wgRoot).getValue("warp")
        assertEquals("engage.cloudflareclient.com", ep.getJSONArray("peers").getJSONObject(0).getString("address"))
        assertEquals(1, wgRoot.getJSONArray("endpoints").length())
    }

    // ---------------------------------------------------------------- sing-box JSON

    @Test
    fun singBoxSingleOutboundArrayAndOutboundsOnly() {
        val one = """{"type":"trojan","tag":"t1","server":"t.example.com","server_port":443,"password":"pw","tls":{"enabled":true}}"""
        val (r1, root1) = convert(one)
        assertEquals(ConfigIngest.Format.SingBox, r1.format)
        assertEquals("节点选择", root1.getJSONObject("route").getString("final"))

        val arr = """[{"type":"ss","tag":"x"},{"type":"shadowsocks","tag":"s1","server":"1.1.1.1","server_port":1,"method":"aes-128-gcm","password":"p"},{"type":"vmess","tag":"v1","server":"v.example.com","server_port":443,"uuid":"$uuid"}]"""
        val (_, root2) = convert(arr)
        assertEquals("自动选择", nodes(root2).getValue("节点选择").getString("default"))

        val only = """{"outbounds":[{"type":"shadowsocks","tag":"s1","server":"1.1.1.1","server_port":1,"method":"aes-128-gcm","password":"p"},{"type":"direct","tag":"direct"}]}"""
        val (_, root3) = convert(only)
        assertTrue("节点选择" in nodes(root3))
    }

    @Test
    fun legacyWireguardOutboundMovesToEndpoint() {
        val json = """
            {"outbounds":[
              {"type":"wireguard","tag":"wg","server":"162.159.192.1","server_port":2408,"local_address":["172.16.0.2/32"],
               "private_key":"cGrivatekeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","peer_public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=","reserved":[0,0,0],"mtu":1280},
              {"type":"selector","tag":"proxy","outbounds":["wg"]},
              {"type":"direct","tag":"direct"}],
             "route":{"final":"proxy"}}
        """.trimIndent()
        val (_, root) = convert(json)
        val ep = root.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wg", ep.getString("tag"))
        assertEquals("172.16.0.2/32", ep.getJSONArray("address").getString(0))
        assertEquals(2408, ep.getJSONArray("peers").getJSONObject(0).getInt("port"))
        assertEquals(1280, ep.getInt("mtu"))
        assertFalse(ep.has("local_address"))
        assertFalse(nodes(root).values.any { it.optString("type") == "wireguard" && it.has("server") })
    }

    // ---------------------------------------------------------------- single / mixed paste

    @Test
    fun singleClashProxyMappingAndFlowMap() {
        val mapping = """
            name: solo
            type: trojan
            server: solo.example.com
            port: 443
            password: pw
            sni: solo.example.com
        """.trimIndent()
        val (_, root) = convert(mapping)
        assertEquals("trojan", nodes(root).getValue("solo").getString("type"))

        val flow = "{name: flow-one, type: ss, server: 9.9.9.9, port: 8388, cipher: aes-256-gcm, password: pw}"
        val (_, root2) = convert(flow)
        assertEquals("shadowsocks", nodes(root2).getValue("flow-one").getString("type"))

        val list = "- {name: l1, type: ss, server: 9.9.9.9, port: 8388, cipher: aes-256-gcm, password: pw}\n" +
            "- {name: l2, type: vless, server: v.example.com, port: 443, uuid: $uuid, tls: true}"
        val (_, root3) = convert(list)
        assertTrue("l1" in nodes(root3) && "l2" in nodes(root3))

        val clashJson = """[{"name":"j1","type":"ss","server":"1.1.1.1","port":1,"cipher":"aes-128-gcm","password":"p"}]"""
        val (_, root4) = convert(clashJson)
        assertEquals("shadowsocks", nodes(root4).getValue("j1").getString("type"))
    }

    @Test
    fun linksMixedWithYamlAndJsonFragments() {
        val text = """
            trojan://pw@t.example.com:443?sni=t.example.com#link-trojan
            - {name: yaml-ss, type: ss, server: 1.1.1.1, port: 8388, cipher: aes-128-gcm, password: pw}
            - name: yaml-hy2
              type: hysteria2
              server: h.example.com
              ports: 20000-30000
              password: pw
            hysteria2://pw@h2.example.com:443#link-hy2
        """.trimIndent()
        val (_, root) = convert(text)
        val all = nodes(root)
        for (tag in listOf("link-trojan", "yaml-ss", "yaml-hy2", "link-hy2")) assertTrue(tag, tag in all)
        assertEquals(4, all.getValue("自动选择").getJSONArray("outbounds").length())

        val withJson = """
            vless://$uuid@v.example.com:443?security=tls&sni=v.example.com#link-vless
            {"type":"shadowsocks","tag":"json-ss","server":"2.2.2.2","server_port":8388,"method":"aes-128-gcm","password":"p"}
        """.trimIndent()
        val (_, root2) = convert(withJson)
        assertTrue("link-vless" in nodes(root2) && "json-ss" in nodes(root2))
    }

    @Test
    fun linksNextToAFullClashFileAreKept() {
        val text = """
            vless://$uuid@v.example.com:443?security=tls&sni=v.example.com#extra-link
            proxies:
              - {name: c1, type: ss, server: 1.1.1.1, port: 8388, cipher: aes-128-gcm, password: pw}
            proxy-groups:
              - {name: PROXY, type: select, proxies: [c1]}
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val (_, root) = convert(text)
        val all = nodes(root)
        assertTrue("extra-link" in all)
        val members = all.getValue("PROXY").getJSONArray("outbounds").toString()
        assertTrue(members.contains("extra-link"))
        assertEquals("PROXY", root.getJSONObject("route").getString("final"))
    }

    // ---------------------------------------------------------------- ECH DNS

    @Test
    fun echDohHintAddsLocalDnsResolver() {
        val link = "vless://$uuid@e.example.com:443?security=tls&sni=e.example.com&ech=cloudflare-ech.com%2Bhttps%3A%2F%2Fdns.alidns.com%2Fdns-query#ech"
        val result = ConfigIngest.adapt(link)
        val root = JSONObject(result.content)
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        val types = (0 until servers.length()).associate { servers.getJSONObject(it).getString("tag") to servers.getJSONObject(it).getString("type") }
        assertEquals("local", types["local"])
        val doh = (0 until servers.length()).map { servers.getJSONObject(it) }.first { it.getString("type") == "https" }
        assertEquals("local", doh.getString("domain_resolver"))
        assertStartable(root)

        // An existing local server is reused, not duplicated.
        val withLocal = """{"dns":{"servers":[{"type":"local","tag":"sys"}]},"outbounds":[{"type":"vless","tag":"n","server":"a","server_port":443,"uuid":"$uuid","tls":{"enabled":true,"ech":{"enabled":true,"config":"cloudflare-ech.com+https://dns.alidns.com/dns-query"}}}]}"""
        val sanitized = JSONObject(ConfigCompat.sanitize(withLocal))
        val list = sanitized.getJSONObject("dns").getJSONArray("servers")
        val resolvers = (0 until list.length()).mapNotNull { list.getJSONObject(it).optString("domain_resolver").takeIf { r -> r.isNotEmpty() } }
        assertTrue(resolvers.isNotEmpty())
        assertTrue(resolvers.all { it == "sys" })
        assertEquals(1, (0 until list.length()).count { list.getJSONObject(it).getString("type") == "local" })
    }

    @Test
    fun yamlParserEdgeCases() {
        val parsed = MiniYaml.parse(
            """
            a: "line\nnext"
            b: 'single ''quoted'''
            c: yes
            d: ~
            e: [x, "y, z", {k: v}]
            f: !!str 123
            """.trimIndent(),
        ) as Map<*, *>
        assertEquals("line\nnext", parsed["a"])
        assertEquals("single 'quoted'", parsed["b"])
        assertEquals("yes", parsed["c"])
        assertNull(parsed["d"])
        assertEquals(listOf("x", "y, z", mapOf("k" to "v")), parsed["e"])
        assertEquals("123", parsed["f"])
        assertNotNull(parsed)
    }
}
