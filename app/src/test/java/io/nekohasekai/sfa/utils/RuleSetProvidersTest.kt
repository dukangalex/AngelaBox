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
    fun srsDecoderDumpsIpv4RangeAsCidr() {
        val lines = SrsDecoder.decode(srsIpSet(4, byteArrayOf(1, 0, 1, 0), byteArrayOf(1, 0, 1, 255.toByte())))
        assertEquals(listOf("1.0.1.0/24"), lines)
    }

    @Test
    fun srsDecoderSplitsUnalignedIpv4Range() {
        val lines = SrsDecoder.decode(
            srsIpSet(4, byteArrayOf(1, 0, 1, 1), byteArrayOf(1, 0, 1, 4)),
        )
        assertEquals(listOf("1.0.1.1/32", "1.0.1.2/31", "1.0.1.4/32"), lines)
    }

    @Test
    fun srsDecoderDumpsIpv6RangeAsCidr() {
        val from = byteArrayOf(
            0x20, 0x01, 0x02, 0x50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        val to = byteArrayOf(
            0x20, 0x01, 0x02, 0x53,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val lines = SrsDecoder.decode(srsIpSet(16, from, to))
        assertEquals(listOf("2001:250::/30"), lines)
    }

    @Test
    fun formatIpv6CompressesLikePython() {
        val addr = byteArrayOf(
            0x20, 0x01, 0x02, 0x50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        assertEquals("2001:250::", SrsDecoder.formatIpv6(addr))
        val tail = ByteArray(16)
        tail[15] = 1
        assertEquals("::1", SrsDecoder.formatIpv6(tail))
    }

    private fun srsIpSet(addrLen: Int, from: ByteArray, to: ByteArray): ByteArray {
        val payload = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = payload.write(v)
        fun uvarint(v: Int) {
            var x = v
            while (x >= 0x80) {
                payload.write((x and 0x7F) or 0x80)
                x = x ushr 7
            }
            payload.write(x)
        }
        fun u64(v: Long) {
            for (shift in 56 downTo 0 step 8) payload.write(((v ushr shift) and 0xFF).toInt())
        }
        uvarint(1)
        u8(0)
        u8(6)
        u8(1)
        u64(1)
        uvarint(addrLen)
        payload.write(from)
        uvarint(addrLen)
        payload.write(to)
        u8(255)
        u8(0)
        val raw = payload.toByteArray()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(256)
        val n = deflater.deflate(buf)
        deflater.end()
        val srs = ByteArray(4 + n)
        srs[0] = 0x53
        srs[1] = 0x52
        srs[2] = 0x53
        srs[3] = 0x01
        System.arraycopy(buf, 0, srs, 4, n)
        return srs
    }
}
