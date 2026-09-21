package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.Deflater

class RuleSetProvidersTest {

    @Test
    fun parseKeepsRemoteTagsAndUrls() {
        val content = """
            {
              "route": {
                "rule_set": [
                  {"type": "remote", "tag": "abema", "format": "binary",
                   "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-abema.srs"},
                  {"type": "remote", "tag": "amazon",
                   "url": "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-amazon.srs"}
                ]
              }
            }
        """.trimIndent()
        val items = RuleSetProviders.parse(content)
        assertEquals(listOf("abema", "amazon"), items.map { it.tag })
        assertTrue(items[0].remote)
        assertTrue(items[0].url.contains("geosite-abema"))
    }

    @Test
    fun updateItemWritesInitialPath() {
        val root = JSONObject(
            """
            {"route":{"rule_set":[{"type":"remote","tag":"apple","url":"https://example.com/apple.srs"}]}}
            """.trimIndent(),
        )
        assertTrue(
            RuleSetProviders.updateItem(root, "apple") { it.put("initial_path", "/tmp/apple.srs") },
        )
        val item = root.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0)
        assertEquals("/tmp/apple.srs", item.getString("initial_path"))
        assertEquals("apple", item.getString("tag"))
    }

    @Test
    fun flattenDomainSuffixLooksLikeClashVerge() {
        val rules = JSONArray(
            """
            [
              {"domain_suffix":["abema-tv.com","abema.io","abema.tv"]},
              {"domain":["abematv.akamaized.net","linear-abematv.akamaized.net"]}
            ]
            """.trimIndent(),
        )
        val lines = RuleSetProviders.flattenRules(rules)
        assertEquals(
            listOf(
                "+.abema-tv.com",
                "+.abema.io",
                "+.abema.tv",
                "abematv.akamaized.net",
                "linear-abematv.akamaized.net",
            ),
            lines,
        )
        val numbered = RuleSetProviders.numbered(lines)
        assertTrue(numbered.startsWith("1 +.abema-tv.com"))
        assertTrue(numbered.contains("5 linear-abematv.akamaized.net"))
    }

    @Test
    fun sourceUrlSwapsSrsToJson() {
        assertEquals(
            "https://example.com/geosite-abema.json",
            RuleSetProviders.sourceUrl("https://example.com/geosite-abema.srs"),
        )
        assertNull(RuleSetProviders.sourceUrl("https://example.com/geosite-abema.json"))
    }

    @Test
    fun listEntriesReadsSourceJson() {
        val dir = File.createTempFile("ruleset", "dir").apply {
            delete()
            mkdirs()
        }
        val file = File(dir, "abema.json")
        file.writeText(
            """
            {"version":3,"rules":[{"domain_suffix":["abema-tv.com","abema.io"]}]}
            """.trimIndent(),
        )
        assertEquals(listOf("+.abema-tv.com", "+.abema.io"), RuleSetProviders.listEntries(file))
        assertEquals(2, RuleSetProviders.sourceRuleCount(file))
    }

    @Test
    fun listEntriesDecompilesSrsLikeClashVerge() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("geosite-abema.srs")!!.readBytes()
        val file = File.createTempFile("geosite-abema", ".srs")
        file.writeBytes(bytes)
        val lines = RuleSetProviders.listEntries(file)
        assertEquals(21, lines.size)
        assertEquals("abematv.akamaized.net", lines.first())
        assertEquals("+.abema-tv.com", lines[5])
        assertEquals("+.winticket.jp", lines.last())
        assertTrue(lines.none { it.matches(Regex("^\\d+\\s+.+")) })
        assertEquals(21, RuleSetProviders.sourceRuleCount(file))
    }

    @Test
    fun srsDecoderMatchesPythonOracle() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("geosite-abema.srs")!!.readBytes()
        val lines = SrsDecoder.decode(bytes)
        assertEquals(
            listOf(
                "abematv.akamaized.net",
                "ds-linear-abematv.akamaized.net",
                "ds-vod-abematv.akamaized.net",
                "linear-abematv.akamaized.net",
                "vod-abematv.akamaized.net",
                "+.abema-tv.com",
                "+.abema.io",
                "+.abema.tv",
                "+.abematv.co.jp",
                "+.adx.promo",
                "+.ameba.jp",
                "+.amebame.com",
                "+.amebaownd.com",
                "+.amebaowndme.com",
                "+.ameblo.jp",
                "+.bucketeer.jp",
                "+.dokusho-ojikan.jp",
                "+.hayabusa.dev",
                "+.hayabusa.io",
                "+.hayabusa.media",
                "+.winticket.jp",
            ),
            lines,
        )
    }

    @Test
    fun srsDecoderRejectsGarbage() {
        assertTrue(SrsDecoder.decode(byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
        assertTrue(SrsDecoder.listEntries(File.createTempFile("empty", ".srs")).isEmpty())
    }

    @Test
    fun srsTopLevelCountReadsUvarint() {
        val payload = byteArrayOf(21)
        val deflater = Deflater()
        deflater.setInput(payload)
        deflater.finish()
        val buf = ByteArray(64)
        val n = deflater.deflate(buf)
        deflater.end()
        val srs = ByteArray(4 + n)
        srs[0] = 0x53
        srs[1] = 0x52
        srs[2] = 0x53
        srs[3] = 0x01
        System.arraycopy(buf, 0, srs, 4, n)
        val file = File.createTempFile("geosite", ".srs")
        file.writeBytes(srs)
        assertEquals(21, RuleSetProviders.srsTopLevelCount(file))
    }

    @Test
    fun proxyProvidersSynthesizeFromLeafOutbounds() {
        val content = """
            {
              "outbounds": [
                {"type":"vless","tag":"JP-1"},
                {"type":"trojan","tag":"US-2"},
                {"type":"selector","tag":"proxy","outbounds":["JP-1","US-2"]},
                {"type":"direct","tag":"direct"}
              ]
            }
        """.trimIndent()
        val items = ProxyProviders.parse(content, "myai", "https://example.com/sub")
        assertEquals(1, items.size)
        assertEquals("myai", items[0].tag)
        assertEquals(listOf("JP-1", "US-2"), items[0].entries)
        assertTrue(items[0].remote)
    }

    @Test
    fun proxyProvidersPreferNamedClashLeftover() {
        val content = """
            {
              "proxy-providers": {
                "provider_entry_J": {"type":"http","url":"https://example.com/j.yaml"}
              },
              "outbounds": [{"type":"vless","tag":"JP-1"}]
            }
        """.trimIndent()
        val items = ProxyProviders.parse(content, "myai", "https://example.com/sub")
        assertEquals(listOf("provider_entry_J"), items.map { it.tag })
        assertTrue(items[0].remote)
    }
}
