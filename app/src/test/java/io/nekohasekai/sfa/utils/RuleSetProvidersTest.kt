package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}