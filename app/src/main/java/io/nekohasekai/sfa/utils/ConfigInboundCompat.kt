package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Import/startup shim for sing-box 1.11–1.13 field removals. Not a kernel
 * change: leftover inbound sniff/domain_strategy and type:dns/block
 * outbounds become route actions. sing-box 1.13 rejects leftover
 * legacy inbound fields; this overlay strips them before libbox decode.
 * GitHub raw rule-set URLs become the jsDelivr testingcf mirror
 * (reachable when raw.githubusercontent.com returns 404).
 */
object ConfigInboundCompat {
    fun apply(root: JSONObject): Boolean {
        var changed = false
        if (migrateLegacyInbounds(root)) changed = true
        if (stripSniffOverrideDestination(root)) changed = true
        if (migrateSpecialOutbounds(root)) changed = true
        if (rewriteRuleSetUrls(root)) changed = true
        if (dropMissingRemoteRuleSets(root)) changed = true
        return changed
    }

    internal fun migrateLegacyInbounds(root: JSONObject): Boolean {
        val inbounds = root.optJSONArray("inbounds") ?: return false
        val usedTags = mutableSetOf<String>()
        for (i in 0 until inbounds.length()) {
            val tag = inbounds.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
            if (tag.isNotEmpty()) usedTags.add(tag)
        }
        val extra = JSONArray()
        var changed = false
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            val hadSniff = ib.has("sniff") || ib.has("sniff_timeout") || ib.has("sniff_override_destination")
            val strategy = ib.optString("domain_strategy").trim()
            val hadUdpDisable = ib.has("udp_disable_domain_unmapping")
            val hadUdpConnect = ib.has("udp_connect")
            val hadUdpTimeout = ib.has("udp_timeout")
            if (!hadSniff && strategy.isEmpty() && !hadUdpDisable && !hadUdpConnect && !hadUdpTimeout) {
                continue
            }
            var tag = ib.optString("tag").trim()
            if (tag.isEmpty()) {
                val base = ib.optString("type").ifBlank { "in" } + "-in"
                tag = uniqueTag(base, usedTags)
                ib.put("tag", tag)
                usedTags.add(tag)
            }
            if (strategy.isNotEmpty()) {
                extra.put(
                    JSONObject()
                        .put("inbound", tag)
                        .put("action", "resolve")
                        .put("strategy", strategy),
                )
                ib.remove("domain_strategy")
            }
            val sniffOn = ib.optBoolean("sniff") ||
                ib.has("sniff_timeout") ||
                ib.optBoolean("sniff_override_destination")
            if (sniffOn) {
                val rule = JSONObject().put("inbound", tag).put("action", "sniff")
                val timeout = ib.optString("sniff_timeout").trim()
                if (timeout.isNotEmpty()) rule.put("timeout", timeout)
                extra.put(rule)
            }
            ib.remove("sniff")
            ib.remove("sniff_timeout")
            ib.remove("sniff_override_destination")
            if (hadUdpDisable || hadUdpConnect || hadUdpTimeout) {
                val rule = JSONObject().put("inbound", tag).put("action", "route-options")
                if (hadUdpDisable) {
                    rule.put("udp_disable_domain_unmapping", ib.optBoolean("udp_disable_domain_unmapping"))
                    ib.remove("udp_disable_domain_unmapping")
                }
                if (hadUdpConnect) {
                    rule.put("udp_connect", ib.optBoolean("udp_connect"))
                    ib.remove("udp_connect")
                }
                if (hadUdpTimeout) {
                    rule.put("udp_timeout", ib.get("udp_timeout"))
                    ib.remove("udp_timeout")
                }
                extra.put(rule)
            }
            changed = true
        }
        if (!changed) return false
        prependRouteRules(root, extra)
        return true
    }

    /**
     * sing-box 1.14 sniff action has no `override_destination`. Scripts and
     * older overlays still emit it; strip so libbox can decode.
     */
    internal fun stripSniffOverrideDestination(root: JSONObject): Boolean {
        val rules = root.optJSONObject("route")?.optJSONArray("rules") ?: return false
        return stripSniffOverrideInRules(rules)
    }

    private fun stripSniffOverrideInRules(rules: JSONArray): Boolean {
        var changed = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val nested = rule.optJSONArray("rules")
            if (nested != null && stripSniffOverrideInRules(nested)) changed = true
            if (rule.optString("action") == "sniff" && rule.has("override_destination")) {
                rule.remove("override_destination")
                changed = true
            }
        }
        return changed
    }

    internal fun migrateSpecialOutbounds(root: JSONObject): Boolean {
        val outs = root.optJSONArray("outbounds") ?: return false
        val dnsTags = mutableSetOf<String>()
        val blockTags = mutableSetOf<String>()
        val keep = JSONArray()
        var changed = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val type = o.optString("type").trim().lowercase()
            val tag = o.optString("tag").trim()
            when (type) {
                "dns" -> {
                    if (tag.isNotEmpty()) dnsTags.add(tag)
                    changed = true
                }
                "block" -> {
                    if (tag.isNotEmpty()) blockTags.add(tag)
                    changed = true
                }
                else -> keep.put(o)
            }
        }
        if (!changed) return false
        replaceArray(outs, keep)
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val list = o.optJSONArray("outbounds") ?: continue
            val filtered = JSONArray()
            var listChanged = false
            for (j in 0 until list.length()) {
                val item = list.opt(j)
                val t = when (item) {
                    is String -> item
                    is JSONObject -> item.optString("tag")
                    else -> ""
                }.trim()
                if (t in dnsTags || t in blockTags) {
                    listChanged = true
                    continue
                }
                filtered.put(list.get(j))
            }
            if (listChanged) o.put("outbounds", filtered)
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        rewriteSpecialOutboundRules(route.optJSONArray("rules"), dnsTags, blockTags)
        val finalTag = route.optString("final").trim()
        when {
            finalTag in blockTags -> {
                route.remove("final")
                val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
                rules.put(JSONObject().put("action", "reject"))
            }
            finalTag in dnsTags -> route.remove("final")
        }
        return true
    }

    private fun rewriteSpecialOutboundRules(
        rules: JSONArray?,
        dnsTags: Set<String>,
        blockTags: Set<String>,
    ) {
        if (rules == null) return
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val nested = rule.optJSONArray("rules")
            if (nested != null) rewriteSpecialOutboundRules(nested, dnsTags, blockTags)
            val ob = rule.optString("outbound").trim()
            when {
                ob in dnsTags -> {
                    rule.remove("outbound")
                    if (rule.optString("action").isBlank()) rule.put("action", "hijack-dns")
                }
                ob in blockTags -> {
                    rule.remove("outbound")
                    if (rule.optString("action").isBlank()) rule.put("action", "reject")
                }
            }
        }
    }

    internal fun rewriteRuleSetUrls(root: JSONObject): Boolean {
        val route = root.optJSONObject("route") ?: return false
        val sets = route.optJSONArray("rule_set") ?: return false
        var changed = false
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            for (key in listOf("url", "download_url")) {
                val current = item.optString(key).trim()
                if (current.isEmpty()) continue
                val rewritten = rewriteGithubRawUrl(current)
                if (rewritten != current) {
                    item.put(key, rewritten)
                    changed = true
                }
            }
        }
        return changed
    }

    internal fun rewriteGithubRawUrl(url: String): String {
        val trimmed = url.trim()
        val jsd = JSDELIVR.matchEntire(trimmed)
        if (jsd != null) {
            if (jsd.groupValues[1].equals(JSDELIVR_HOST, true)) return trimmed
            return "https://$JSDELIVR_HOST/gh/${jsd.groupValues[2]}"
        }
        val raw = RAW_GITHUB.matchEntire(trimmed)
        if (raw != null) {
            val owner = raw.groupValues[1]
            val repo = raw.groupValues[2]
            val ref = raw.groupValues[3]
            val path = raw.groupValues[4]
            return "https://$JSDELIVR_HOST/gh/$owner/$repo@$ref/$path"
        }
        val gh = GITHUB_RAW.matchEntire(trimmed)
        if (gh != null) {
            val owner = gh.groupValues[1]
            val repo = gh.groupValues[2]
            val rest = gh.groupValues[3]
            val slash = rest.indexOf('/')
            if (slash > 0) {
                val ref = rest.substring(0, slash)
                val path = rest.substring(slash + 1)
                return "https://$JSDELIVR_HOST/gh/$owner/$repo@$ref/$path"
            }
        }
        return trimmed
    }

    /**
     * Remote rule-sets that 404 on the jsDelivr testingcf mirror abort kernel
     * start and cancel the rest. Drop those files and any route/DNS rules
     * that only pointed at them. Overlay scripts keep working after a
     * previous import of the old default script.
     */
    internal fun dropMissingRemoteRuleSets(root: JSONObject): Boolean {
        val route = root.optJSONObject("route") ?: return false
        val sets = route.optJSONArray("rule_set") ?: return false
        val dropTags = mutableSetOf<String>()
        val keep = JSONArray()
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val url = item.optString("url").ifBlank { item.optString("download_url") }.trim()
            val file = url.substringAfterLast('/').substringBefore('?').lowercase()
            val tag = item.optString("tag").trim()
            val remote = item.optString("type").equals("remote", true) || url.startsWith("http")
            if (remote && file in MISSING_RULESET_FILES) {
                if (tag.isNotEmpty()) dropTags.add(tag)
                continue
            }
            keep.put(item)
        }
        if (dropTags.isEmpty() && keep.length() == sets.length()) return false
        replaceArray(sets, keep)
        stripDroppedRuleSets(route.optJSONArray("rules"), dropTags)
        stripDroppedRuleSets(root.optJSONObject("dns")?.optJSONArray("rules"), dropTags)
        return true
    }

    private fun stripDroppedRuleSets(rules: JSONArray?, dropTags: Set<String>): Boolean {
        if (rules == null || dropTags.isEmpty()) return false
        var changed = false
        var i = 0
        while (i < rules.length()) {
            val rule = rules.optJSONObject(i)
            if (rule == null) {
                i++
                continue
            }
            if (stripDroppedRuleSets(rule.optJSONArray("rules"), dropTags)) changed = true
            val raw = rule.opt("rule_set")
            var remove = false
            when (raw) {
                is String -> if (raw.trim() in dropTags) remove = true
                is JSONArray -> {
                    val kept = JSONArray()
                    var listChanged = false
                    for (j in 0 until raw.length()) {
                        val t = raw.optString(j).trim()
                        if (t in dropTags) {
                            listChanged = true
                        } else if (t.isNotEmpty()) {
                            kept.put(t)
                        }
                    }
                    if (listChanged) {
                        changed = true
                        if (kept.length() == 0) {
                            remove = true
                        } else if (kept.length() == 1) {
                            rule.put("rule_set", kept.getString(0))
                        } else {
                            rule.put("rule_set", kept)
                        }
                    }
                }
            }
            if (remove) {
                rules.remove(i)
                changed = true
                continue
            }
            i++
        }
        return changed
    }

    private fun prependRouteRules(root: JSONObject, extra: JSONArray) {
        if (extra.length() == 0) return
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray()
        for (i in 0 until extra.length()) merged.put(extra.get(i))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
    }

    private fun uniqueTag(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 1
        while ("$base-$n" in used) n++
        return "$base-$n"
    }

    private fun replaceArray(target: JSONArray, keep: JSONArray) {
        while (target.length() > 0) target.remove(0)
        for (i in 0 until keep.length()) target.put(keep.get(i))
    }

    private const val JSDELIVR_HOST = "testingcf.jsdelivr.net"
    private val JSDELIVR =
        Regex("^https?://([^/]*jsdelivr\\.net)/gh/(.+)$")
    private val RAW_GITHUB =
        Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
    private val GITHUB_RAW =
        Regex("^https?://github\\.com/([^/]+)/([^/]+)/raw/(.+)$")
    private val MISSING_RULESET_FILES = setOf(
        "geosite-biliintl.srs",
        "geosite-apple-cn.srs",
        "geosite-tracker.srs",
        "geoip-private.srs",
        "geoip-google.srs",
        "geoip-telegram.srs",
        "geoip-netflix.srs",
        "geoip-facebook.srs",
        "geoip-twitter.srs",
        "geoip-cloudflare.srs",
        "geoip-cloudfront.srs",
        "geoip-fastly.srs",
    )
}
