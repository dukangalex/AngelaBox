package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime "China direct" overlay. Does not rewrite the subscription file.
 *
 * Force-applies six **route** bypasses whenever the switch is on, even if the
 * profile has no matching geoip/geosite/dns/route sections:
 * 1. China IPs (reuse geoip-cn if present)
 * 2. China domains
 * 3. China public DNS IPs
 * 4. China public DNS domains
 * 5. LAN IPs
 * 6. LAN domains
 *
 * Does **not** inject a DNS server. Earlier builds added
 * `chainbox-cn-dns` with `detour: "direct"`, which sing-box 1.12+ rejects
 * ("detour to an empty direct outbound makes no sense") and kills reload
 * with RPC EOF. Omitting detour still uses the default outbound — often the
 * proxy or a generated chain — so China DNS would go through the tunnel and
 * fight the bypass. The six items above are "绕过" (route direct), not
 * "改用国内 DNS".
 */
object ConfigChinaDirect {
    const val CN_DNS_TAG = "chainbox-cn-dns"
    const val DIRECT_FALLBACK_TAG = "chainbox-direct"

    val LAN_DOMAIN_SUFFIXES: List<String> = listOf(
        "local", "lan", "localhost", "home.arpa", "localdomain",
        "internal", "intranet", "private", "local.lan",
    )

    val CHINA_DNS_IPS: List<String> = listOf(
        "114.114.114.114/32", "114.114.115.115/32",
        "223.5.5.5/32", "223.6.6.6/32",
        "180.76.76.76/32",
        "119.29.29.29/32", "119.28.28.28/32",
        "1.2.4.8/32", "210.2.4.8/32",
        "117.50.10.10/32", "117.50.11.11/32", "117.50.22.22/32",
        "1.12.12.12/32", "120.53.53.53/32",
        "101.226.4.6/32", "123.125.81.6/32",
        "202.96.134.133/32", "202.96.128.86/32",
        "2400:3200::1/128", "2400:3200:baba::1/128",
        "2402:4e00::/128", "2400:da00::6666/128",
        "240c::6666/128", "240c::6644/128",
    )

    val CHINA_DNS_DOMAINS: List<String> = listOf(
        "dns.alidns.com", "doh.pub", "dns.pub", "dot.pub",
        "d.alidns.com", "dns.qq.com", "pdns.aliyun.com",
        "dns.hichina.com", "onedns.net",
    )

    val CHINA_DNS_DOMAIN_SUFFIXES: List<String> = listOf(
        "alidns.com", "dnspod.com", "dnspod.cn", "doh.pub", "dns.pub",
        "360.cn", "onedns.net", "hichina.com",
    )

    fun apply(root: JSONObject) {
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val direct = findOrCreateDirect(outs)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val injected = chinaRouteRules(direct, existingRuleSetTags(route))
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
        dropLegacyChinaDns(root)
        ConfigCompat.stripBrokenDnsDetours(root)
    }

    fun chinaRouteRules(directTag: String, existingRuleSets: Set<String>): JSONArray {
        val rules = JSONArray()
        rules.put(
            JSONObject()
                .put("ip_is_private", true)
                .put("outbound", directTag),
        )
        rules.put(
            JSONObject()
                .put("domain_suffix", toArray(LAN_DOMAIN_SUFFIXES))
                .put("domain", JSONArray().put("localhost"))
                .put("outbound", directTag),
        )
        rules.put(
            JSONObject()
                .put("ip_cidr", toArray(CHINA_DNS_IPS))
                .put("outbound", directTag),
        )
        rules.put(
            JSONObject()
                .put("domain", toArray(CHINA_DNS_DOMAINS))
                .put("domain_suffix", toArray(CHINA_DNS_DOMAIN_SUFFIXES))
                .put("outbound", directTag),
        )
        val geoip = existingRuleSets.filter { tag ->
            val t = tag.lowercase()
            t.contains("geoip-cn") || t.contains("geoip_cn") || t == "cn-ip" || t == "china-ip"
        }
        val geosite = existingRuleSets.filter { tag ->
            val t = tag.lowercase()
            t.contains("geosite-cn") || t.contains("geosite_cn") || t == "cn" || t == "china"
        }
        if (geoip.isNotEmpty()) {
            rules.put(JSONObject().put("rule_set", toArray(geoip)).put("outbound", directTag))
        }
        if (geosite.isNotEmpty()) {
            rules.put(JSONObject().put("rule_set", toArray(geosite)).put("outbound", directTag))
        }
        rules.put(
            JSONObject()
                .put("domain_suffix", ConfigNormalize.cnDomainSuffixArray())
                .put("outbound", directTag),
        )
        return rules
    }

    fun findOrCreateDirect(outs: JSONArray): String {
        val preferred = listOf("direct", "DIRECT", "直连", "🚀 直连", "🎯 全球直连")
        for (tag in preferred) {
            val o = find(outs, tag)
            if (o != null && o.optString("type").equals("direct", true)) return tag
        }
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (!o.optString("type").equals("direct", true)) continue
            val tag = o.optString("tag").trim()
            if (tag.isEmpty()) continue
            val lower = tag.lowercase()
            if (lower == "dns" || lower == "block" || lower == "reject") continue
            return tag
        }
        val tag = if (find(outs, "direct") == null) "direct" else DIRECT_FALLBACK_TAG
        if (find(outs, tag) == null) {
            outs.put(JSONObject().put("type", "direct").put("tag", tag))
        }
        return tag
    }

    /**
     * Older overlays injected `chainbox-cn-dns`. Drop it so a leftover
     * server cannot crash the kernel on reload.
     */
    internal fun dropLegacyChinaDns(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: return
        val servers = dns.optJSONArray("servers")
        if (servers != null) removeServer(servers, CN_DNS_TAG)
        val rules = dns.optJSONArray("rules") ?: return
        val keep = JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("server") == CN_DNS_TAG) continue
            keep.put(rule)
        }
        dns.put("rules", keep)
    }

    private fun existingRuleSetTags(route: JSONObject): Set<String> {
        val arr = route.optJSONArray("rule_set") ?: return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val tag = item.optString("tag").trim()
            if (tag.isNotEmpty()) out.add(tag)
        }
        return out
    }

    private fun removeServer(servers: JSONArray, tag: String) {
        val keep = JSONArray()
        for (i in 0 until servers.length()) {
            val item = servers.optJSONObject(i) ?: continue
            if (item.optString("tag") == tag) continue
            keep.put(item)
        }
        while (servers.length() > 0) servers.remove(0)
        for (i in 0 until keep.length()) servers.put(keep.get(i))
    }

    private fun find(outs: JSONArray, tag: String): JSONObject? {
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == tag) return o
        }
        return null
    }

    private fun toArray(items: Collection<String>): JSONArray {
        val a = JSONArray()
        items.forEach { a.put(it) }
        return a
    }
}
