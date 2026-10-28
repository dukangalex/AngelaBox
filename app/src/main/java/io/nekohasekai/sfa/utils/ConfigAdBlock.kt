package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime ad-block overlay. Injects the official sing-geosite ads rule-set
 * and rejects matching traffic. Does not rewrite the subscription file.
 */
object ConfigAdBlock {
    const val RULESET_TAG = "geosite-category-ads-all"
    // Canonical rule-set branch URL — see RuleSetPins for why the branch
    // ref is the pin (compiled sets exist nowhere else).
    val RULESET_URL: String = RuleSetPins.adsRuleSetUrl()

    fun apply(root: JSONObject, seedPath: (String) -> String? = { null }) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val tag = ensureRuleSet(route, seedPath)
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
            if (rule.optString("action") == "reject" && ruleMentionsSet(rule, tag)) continue
            merged.put(rule)
        }
        route.put("rules", merged)
    }

    /**
     * Block-collecting variant for the system-explicit segment: ensures the
     * rule-set definition and returns the reject rule for the segment's
     * ordered block, or an empty array when the config already rejects the
     * ads set somewhere (same coverage test as [apply]).
     */
    fun applyBlock(root: JSONObject, seedPath: (String) -> String? = { null }): JSONArray {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val tag = ensureRuleSet(route, seedPath)
        if (alreadyRoutesAds(route, tag)) return JSONArray()
        return JSONArray().put(
            JSONObject()
                .put("rule_set", tag)
                .put("action", "reject"),
        )
    }

    private fun alreadyRoutesAds(route: JSONObject, tag: String): Boolean {
        // "Routes ads" only counts when the ads set is actually REJECTED.
        // A rule that merely mentions the set — e.g. one that sends it to
        // a proxy group — leaves ads flowing; treating any mention as
        // coverage made the ad-block switch a silent no-op.
        return ruleSetRejected(route.optJSONArray("rules"), tag, inheritedReject = false)
    }

    private fun ruleSetRejected(rules: JSONArray?, tag: String, inheritedReject: Boolean): Boolean {
        if (rules == null) return false
        // An ancestor rule already rejects: any mention below it counts.
        if (inheritedReject) return ruleSetMentioned(rules, tag)
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val rejects = rule.optString("action").equals("reject", true)
            if (rejects && ruleMentionsSet(rule, tag)) return true
            if (ruleSetRejected(rule.optJSONArray("rules"), tag, rejects)) return true
        }
        return false
    }

    private fun ruleMentionsSet(rule: JSONObject, tag: String): Boolean {
        return when (val raw = rule.opt("rule_set")) {
            is String -> raw.trim() == tag
            is JSONArray -> (0 until raw.length()).any { raw.optString(it).trim() == tag }
            else -> false
        }
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

    private fun ensureRuleSet(route: JSONObject, seedPath: (String) -> String?): String {
        // Bundled seed (137a, RuleSetSeeds): stamp the installed seed
        // as `initial_path` — the kernel's cold-start floor — but only
        // on definitions that ARE the canonical app set: ones created
        // here, or an existing entry with the canonical tag AND the
        // pinned URL. A same-tag entry pointing at some other URL may
        // serve different content; seeding it with the official bytes
        // would silently swap its rules at cold start.
        fun stamp(item: JSONObject) {
            val seed = seedPath(RULESET_TAG) ?: return
            if (item.optString("initial_path") != seed) item.put("initial_path", seed)
        }
        val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        var blankMatch: JSONObject? = null
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val tag = item.optString("tag").trim()
            val url = item.optString("url").trim()
            if (tag.equals(RULESET_TAG, true)) {
                if (url == RULESET_URL) stamp(item)
                return tag
            }
            if (tag.contains("category-ads", ignoreCase = true) ||
                url.contains("geosite-category-ads", ignoreCase = true)
            ) {
                if (tag.isNotBlank()) return tag
                // Matched by URL but carries no tag: remember it — if no
                // canonically tagged set exists we name this one, so the
                // reject rule never references a tag that does not exist.
                if (blankMatch == null) blankMatch = item
            }
        }
        if (blankMatch != null) {
            blankMatch.put("tag", RULESET_TAG)
            return RULESET_TAG
        }
        val created = JSONObject()
            .put("tag", RULESET_TAG)
            .put("type", "remote")
            .put("format", "binary")
            .put("url", RULESET_URL)
        stamp(created)
        sets.put(created)
        return RULESET_TAG
    }
}
