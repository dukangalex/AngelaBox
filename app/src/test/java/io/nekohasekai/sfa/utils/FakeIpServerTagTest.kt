package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field collateral from the v1.0.135-beta log: while connections
 * were being answered, the kernel repeatedly logged
 * `dns: transport not found: dns-fakeip`.
 *
 * Root cause (NOT the rule-set strip path — stripping never touches
 * dns.servers): two app stages disagreed about the fakeip server's
 * tag. The legacy conversion (ConfigCompat) creates the fakeip
 * server tagged "fakeip" and its own A/AAAA rule for it, but
 * ConfigQuicOverride.ensureFakeIpDns hardcoded the woven A/AAAA rule
 * to server "dns-fakeip" — so any config whose fakeip server carries
 * a different tag got a second rule naming a transport that does not
 * exist. Queries fell through to later rules (hence answers still
 * arrived), but every A/AAAA lookup that hit the phantom rule logged
 * the error.
 *
 * The fix makes ensureFakeIpDns use the fakeip server's actual tag
 * and retarget a stale canonical-name rule onto it. The
 * `router: missing fakeip record` lines from the same log are
 * unrelated: those addresses were minted by a previous kernel
 * instance (store_fakeip is on, cache_id is per-profile stable), and
 * apps holding them across a restart/profile switch ask the new
 * instance for records it never made.
 */
class FakeIpServerTagTest {

    private fun dnsWith(fakeIpTag: String, ruleServer: String): JSONObject = JSONObject()
        .put(
            "dns", JSONObject()
                .put(
                    "servers", JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "fakeip")
                                .put("tag", fakeIpTag)
                                .put("inet4_range", "198.18.0.0/15"),
                        )
                        .put(
                            JSONObject()
                                .put("tag", "dns-remote")
                                .put("type", "https")
                                .put("server", "8.8.8.8"),
                        ),
                )
                .put("final", "dns-remote")
                .put(
                    "rules", JSONArray()
                        .put(
                            JSONObject()
                                .put("query_type", JSONArray().put("A").put("AAAA"))
                                .put("server", ruleServer),
                        ),
                ),
        )
        .put(
            "outbounds", JSONArray()
                .put(JSONObject().put("tag", "proxy").put("type", "direct")),
        )
        .put("route", JSONObject().put("final", "proxy"))

    private fun definedDnsTags(root: JSONObject): Set<String> {
        val servers = root.getJSONObject("dns").getJSONArray("servers")
        return (0 until servers.length()).map { servers.getJSONObject(it).getString("tag") }.toSet()
    }

    private fun dnsRuleServers(root: JSONObject): List<String> {
        val rules = root.getJSONObject("dns").optJSONArray("rules") ?: return emptyList()
        return (0 until rules.length()).mapNotNull {
            rules.getJSONObject(it).optString("server").ifBlank { null }
        }
    }

    private fun fakeIpRules(root: JSONObject): List<JSONObject> {
        val rules = root.getJSONObject("dns").getJSONArray("rules")
        val defined = definedDnsTags(root)
        val fakeTags = (0 until root.getJSONObject("dns").getJSONArray("servers").length())
            .map { root.getJSONObject("dns").getJSONArray("servers").getJSONObject(it) }
            .filter { it.optString("type") == "fakeip" }
            .map { it.getString("tag") }
        assertTrue(fakeTags.isNotEmpty())
        return (0 until rules.length())
            .map { rules.getJSONObject(it) }
            .filter { it.optString("server") in fakeTags && it.optJSONArray("query_type") != null }
            .also { assertTrue(defined.containsAll(dnsRuleServers(root))) }
    }

    @Test
    fun legacyFakeipTagIsUsedForTheWovenRule() {
        // ConfigCompat's shape: server tagged "fakeip", rule for "fakeip".
        val root = dnsWith(fakeIpTag = "fakeip", ruleServer = "fakeip")
        ConfigQuicOverride.applyDnsProtect(root, overwriteFinal = false)
        // No rule may reference a transport that does not exist.
        assertTrue(definedDnsTags(root).containsAll(dnsRuleServers(root)))
        assertTrue(dnsRuleServers(root).none { it == "dns-fakeip" })
        // Exactly one A/AAAA fakeip rule, naming the real tag.
        val woven = fakeIpRules(root)
        assertEquals(1, woven.size)
        assertEquals("fakeip", woven.single().getString("server"))
    }

    @Test
    fun staleCanonicalRuleIsRetargetedToTheRealTag() {
        // The pre-fix weave persisted in a profile: rule says
        // "dns-fakeip", server is tagged "fakeip".
        val root = dnsWith(fakeIpTag = "fakeip", ruleServer = "dns-fakeip")
        ConfigQuicOverride.applyDnsProtect(root, overwriteFinal = false)
        assertTrue(definedDnsTags(root).containsAll(dnsRuleServers(root)))
        val woven = fakeIpRules(root)
        assertEquals(1, woven.size)
        assertEquals("fakeip", woven.single().getString("server"))
    }

    @Test
    fun canonicalTagBehaviourIsUnchanged() {
        // The bundled script's shape: everything already "dns-fakeip".
        val root = dnsWith(fakeIpTag = "dns-fakeip", ruleServer = "dns-fakeip")
        ConfigQuicOverride.applyDnsProtect(root, overwriteFinal = false)
        assertTrue(definedDnsTags(root).containsAll(dnsRuleServers(root)))
        val woven = fakeIpRules(root)
        assertEquals(1, woven.size)
        assertEquals("dns-fakeip", woven.single().getString("server"))
    }
}
