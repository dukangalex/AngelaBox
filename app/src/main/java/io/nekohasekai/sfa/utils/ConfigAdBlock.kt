package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime ad-block overlay. Injects the official sing-geosite ads rule-set
 * and rejects matching traffic. Does not rewrite the subscription file.
 */
object ConfigAdBlock {
    const val RULESET_TAG = "geosite-category-ads-all"
    const val RULESET_URL =
        "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs"

    fun apply(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val tag = ensureRuleSet(route)
        if (alreadyRoutesAds(route, tag)) return
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray()
        merged.put(
            JSONObject()
                .put("rule_set", tag)
                .put("action", "reject"),
        )
        for (i in 0 until old.length()) {
            val rule = old.optJSONObject(i) ?: continue
            if (rule.optString("action") == "reject" && rule.optString("rule_set") == tag) continue
            merged.put(rule)
        }
        route.put("rules", merged)
    }

    private fun alreadyRoutesAds(route: JSONObject, tag: String): Boolean {
        return ruleSetMentioned(route.optJSONArray("rules"), tag)
    }

    private fun ruleSetMentioned(rules: JSONArray?, tag: String): Boolean {
        if (rules == null) return false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (ruleSetMentioned(rule.optJSONArray("rules"), tag)) return true
            when (val raw = rule.opt("rule_set")) {
                is String -> if (raw.trim() == tag) return true
                is JSONArray -> {
                    for (j in 0 until raw.length()) {
                        if (raw.optString(j).trim() == tag) return true
                    }
                }
            }
        }
        return false
    }

    private fun ensureRuleSet(route: JSONObject): String {
        val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val tag = item.optString("tag").trim()
            val url = item.optString("url").trim()
            if (tag.equals(RULESET_TAG, true) ||
                tag.contains("category-ads", ignoreCase = true) ||
                url.contains("geosite-category-ads", ignoreCase = true)
            ) {
                return tag.ifBlank { RULESET_TAG }
            }
        }
        sets.put(
            JSONObject()
                .put("tag", RULESET_TAG)
                .put("type", "remote")
                .put("format", "binary")
                .put("url", RULESET_URL),
        )
        return RULESET_TAG
    }
}
