package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for ChainBox runtime chain materialization.
 * The source profile remains the user's configuration; only the runtime
 * overlay is rebuilt on every apply/update. Subscription refresh overwrites
 * the JSON file but does not clear per-profile ChainBindings. If the saved
 * entry tag disappeared, apply() falls back to resolveMainTag so the landing
 * binding stays in effect. The final outbound is always a native sing-box
 * `type: chain` outbound.
 *
 * Packet path: outbounds[0] is the entry (closest to the client), last is the
 * landing/exit (public IP). The kernel clones later hops with detour=previous
 * so IP checks show the landing node, not the front airport.
 * Chain hops are user-selected outbounds. Selector/urltest groups may contain
 * DIRECT as a UI choice; those members are stripped from the hop clone so the
 * generated chain never includes an unauthorized DIRECT/block/dns hop.
 * Connection failure is fail-closed (no silent DIRECT fallback).
 *
 * After the chain outbound is created, route rules that would have used the
 * front airport as an exit are rewritten to the chain tag. DNS detours stay
 * on their original outbound so same-profile chains do not add an extra DNS
 * hop versus Clash Meta. The entry outbound is only hop 0 and must never
 * become the public exit.
 */
object ChainRuntimeCompiler {
    const val NATIVE_CHAIN_TYPE = "chain"
    const val GENERATED_PREFIX = "chainbox-chain-"
    const val LANDING_PREFIX = "chainbox-landing-"
    const val ENTRY_PREFIX = "chainbox-entry-"
    const val LEGACY_PREFIX = "ext-"
    const val LEGACY_CHAIN_TAG = "my-chain"
    const val MAX_CONFIG_CHARS = 8 * 1024 * 1024
    const val MAX_MERGE_DEPTH = 24
    const val MAX_GROUP_MEMBERS = 512

    private val forbiddenTypes = setOf("direct", "block", "dns", NATIVE_CHAIN_TYPE)
    private val forbiddenTags = setOf("direct", "block", "dns")
    private val groupTypes = setOf("selector", "urltest")
    private val CN_TOKEN = Regex("""(?<![a-z0-9_-])cn(?![a-z0-9_-])""")

    data class ApplyRequest(
        val content: String,
        val currentProfileId: Long,
        val entryTag: String?,
        val landingProfileId: Long,
        val landingTag: String,
        val landingContent: String?,
    )

    data class Hop(
        val profileId: Long,
        val profileName: String,
        val tag: String,
        val type: String,
    )

    fun apply(req: ApplyRequest): String {
        require(req.landingTag.isNotEmpty()) { "未选择链式落地出口" }
        val root = parseConfig(req.content, "当前配置")
        val outs = cleanGeneratedOutbounds(root.optJSONArray("outbounds") ?: JSONArray())
        root.put("outbounds", outs)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val routeFinal = route.optString("final").trim()
        val requested = req.entryTag?.trim().orEmpty()
        val main = when {
            requested.isNotEmpty() && find(outs, requested) != null -> requested
            else -> resolveMainTag(outs, routeFinal) ?: error("无法识别当前配置的链式入口，请到「工具 → 链式代理」手动选择入口")
        }

        val sameProfile = req.landingProfileId == req.currentProfileId
        require(sameProfile || !req.landingContent.isNullOrBlank()) {
            "跨配置落地内容缺失，无法组链"
        }
        val landingMergedTag = if (sameProfile) {
            require(req.landingTag != main) { "入口与落地不能是同一个 outbound" }
            find(outs, req.landingTag) ?: error("落地 outbound 不存在：${req.landingTag}")
            prepareGroupHop(
                outs,
                req.landingTag,
                extraExclude = setOf(main),
                tagPrefix = "$LANDING_PREFIX${req.currentProfileId}-",
                inPlace = true,
            )
        } else {
            val landingRoot = parseConfig(req.landingContent!!, "落地配置")
            val landingOuts = landingRoot.optJSONArray("outbounds") ?: error("落地配置没有 outbounds")
            mergeLandingGraph(outs, landingOuts, req.landingProfileId, req.landingTag)
        }

        val entryExclude = buildSet {
            add(landingMergedTag)
            if (sameProfile) add(req.landingTag)
        }
        val entryHop = prepareGroupHop(
            outs,
            main,
            extraExclude = entryExclude,
            tagPrefix = ENTRY_PREFIX,
            inPlace = sameProfile,
        )

        val chainTag = "$GENERATED_PREFIX${req.currentProfileId}-${req.landingProfileId}"
        removeOutbound(outs, chainTag)
        val chain = JSONObject()
            .put("type", NATIVE_CHAIN_TYPE)
            .put("tag", chainTag)
            .put("outbounds", JSONArray().put(entryHop).put(landingMergedTag))
        outs.put(chain)
        route.put("final", chainTag)
        root.put("outbounds", outs)
        pinTrafficToChain(root, chainTag, landingMergedTag, setOf(main, entryHop))
        return root.toString()
    }

    fun clear(content: String, restoreFinal: String?): String {
        val root = parseConfig(content, "当前配置")
        val cleaned = cleanGeneratedOutbounds(root.optJSONArray("outbounds") ?: JSONArray())
        root.put("outbounds", cleaned)
        val route = root.optJSONObject("route")
        if (route != null) {
            val final = route.optString("final")
            if (final.startsWith(GENERATED_PREFIX) || final == LEGACY_CHAIN_TAG || final.startsWith(LEGACY_PREFIX)) {
                if (!restoreFinal.isNullOrBlank()) route.put("final", restoreFinal) else route.remove("final")
            }
        }
        return root.toString()
    }

    fun isFinalLike(tag: String): Boolean {
        val t = tag.lowercase()
        return t.contains("漏网") || t.contains("final") || t.contains("剩余") ||
            t.contains("unmatched") || t == "match"
    }

    fun resolveMainTag(outs: JSONArray, routeFinal: String): String? {
        if (routeFinal.isNotEmpty() && !isFinalLike(routeFinal) && !routeFinal.startsWith(GENERATED_PREFIX)) {
            val o = find(outs, routeFinal)
            if (o != null && o.optString("type") in groupTypes && !isForbiddenHop(outs, o, routeFinal)) return routeFinal
        }
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type")
            if (tag.isEmpty() || isGeneratedTag(tag)) continue
            if (type !in groupTypes) continue
            if (isForbiddenHop(outs, o, tag)) continue
            var score = if (type == "urltest") 20 else 15
            val t = tag.lowercase()
            if (isFinalLike(tag)) score -= 80
            if (t.contains("proxy") || t.contains("select") || t.contains("节点") || t.contains("选择") || t.contains("自动")) score += 25
            if (score > bestScore) {
                bestScore = score
                best = tag
            }
        }
        return best
    }

    fun listSelectableHops(content: String, profileId: Long, profileName: String): List<Hop> {
        val outs = parseConfig(content, "配置").optJSONArray("outbounds") ?: return emptyList()
        return buildList {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                val tag = o.optString("tag").trim()
                val type = o.optString("type").trim()
                if (tag.isEmpty() || isGeneratedTag(tag) || type in forbiddenTypes) continue
                add(Hop(profileId, profileName, tag, type))
            }
        }
    }

    fun parseConfig(content: String, label: String = "配置"): JSONObject {
        require(content.length <= MAX_CONFIG_CHARS) {
            "${label}过大（>${MAX_CONFIG_CHARS} 字符），已拒绝解析"
        }
        return JSONObject(content)
    }

    /**
     * Front airport outbounds may only appear as hop 0 of the generated chain.
     * Rewrite route.final and route.rules[].outbound so unmatched / Global /
     * explicit-proxy traffic cannot exit via the entry. Non-China DIRECT is
     * also rewritten to the chain so those flows leave via the landing hop;
     * China / LAN / private DIRECT rules stay. DNS detours are left
     * unchanged so queries stay one hop (Clash Meta behaviour).
     * entryTags are never treated as a valid public exit.
     */
    internal fun pinTrafficToChain(
        root: JSONObject,
        chainTag: String,
        landingTag: String,
        entryTags: Set<String> = emptySet(),
    ) {
        val outs = root.optJSONArray("outbounds") ?: return
        val protected = mutableSetOf(chainTag, landingTag)
        val directTags = mutableSetOf<String>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type").trim()
            if (tag.isEmpty()) continue
            if (tag in entryTags || tag.startsWith(ENTRY_PREFIX)) continue
            if (type == "direct" || isDirectLike(tag)) {
                directTags.add(tag)
                continue
            }
            if (type in forbiddenTypes || tag.lowercase() in forbiddenTags) protected.add(tag)
            if (tag.startsWith(LANDING_PREFIX)) protected.add(tag)
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val currentFinal = route.optString("final").trim()
        if (currentFinal.isEmpty() || currentFinal !in protected || currentFinal in entryTags ||
            currentFinal in directTags || isDirectLike(currentFinal)
        ) {
            route.put("final", chainTag)
        }
        rewriteRuleOutbounds(route.optJSONArray("rules"), protected, directTags, chainTag)
    }

    /**
     * China / LAN / private DIRECT rules stay. Any other DIRECT (including a
     * bare MATCH or clash Direct) is rewritten to the chain so non-China
     * traffic cannot skip the landing hop.
     */
    internal fun isBypassDirectRule(rule: JSONObject): Boolean {
        if (rule.optBoolean("ip_is_private", false)) return true
        val haystack = buildString {
            listOf("rule_set", "geosite", "geoip", "domain_suffix", "domain", "domain_keyword", "ip_cidr").forEach { key ->
                textsOf(rule, key).forEach { append(' ').append(it.lowercase()) }
            }
        }
        if (haystack.contains("geoip-cn") || haystack.contains("geosite-cn") ||
            haystack.contains("geoip_cn") || haystack.contains("geosite_cn")
        ) {
            return true
        }
        if (CN_TOKEN.containsMatchIn(haystack) || haystack.contains("china") || haystack.contains("中国")) return true
        if (haystack.contains(".cn") || haystack.contains("cn.")) return true
        val lan = listOf("local", "lan", "localhost", "home.arpa", "internal", "intranet", "private", "localdomain")
        if (lan.any { haystack.contains(it) }) return true
        if (haystack.contains("10.") || haystack.contains("192.168.") || haystack.contains("172.16.") ||
            haystack.contains("127.0.0.") || haystack.contains("fc00") || haystack.contains("fe80")
        ) {
            return true
        }
        return false
    }

    internal fun isDirectLike(tag: String): Boolean {
        val t = tag.trim().lowercase()
        if (t.isEmpty()) return false
        return t == "direct" || t.contains("直连") || t == "chainbox-direct"
    }

    private fun rewriteRuleOutbounds(
        rules: JSONArray?,
        protected: Set<String>,
        directTags: Set<String>,
        chainTag: String,
    ) {
        if (rules == null) return
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            rewriteRuleOutbounds(rule.optJSONArray("rules"), protected, directTags, chainTag)
            val outbound = rule.optString("outbound").trim()
            if (outbound.isEmpty()) continue
            if (isDirectLike(outbound) || outbound in directTags) {
                if (!isBypassDirectRule(rule)) rule.put("outbound", chainTag)
                continue
            }
            if (outbound !in protected) {
                rule.put("outbound", chainTag)
            }
        }
    }

    private fun textsOf(rule: JSONObject, key: String): List<String> {
        val raw = rule.opt(key) ?: return emptyList()
        return when (raw) {
            is String -> listOf(raw)
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            else -> emptyList()
        }
    }

    fun displayHopTag(tag: String): String {
        val t = tag.trim()
        if (t.isEmpty() || isGeneratedChainTag(t)) return ""
        if (t.startsWith(ENTRY_PREFIX)) return t.removePrefix(ENTRY_PREFIX)
        if (t.startsWith(LANDING_PREFIX)) {
            return t.removePrefix(LANDING_PREFIX).replaceFirst(Regex("^\\d+-"), "")
        }
        if (t.startsWith(LEGACY_PREFIX)) return t.removePrefix(LEGACY_PREFIX)
        return t
    }

    private fun isGeneratedChainTag(tag: String): Boolean =
        tag == LEGACY_CHAIN_TAG || tag.startsWith(GENERATED_PREFIX)

    private fun prepareGroupHop(
        outs: JSONArray,
        tag: String,
        extraExclude: Set<String>,
        tagPrefix: String,
        inPlace: Boolean = false,
    ): String {
        val original = find(outs, tag) ?: error("outbound 不存在：$tag")
        val type = original.optString("type")
        require(type !in forbiddenTypes) { "不能使用 $type 作为链式跳板：$tag" }
        if (type !in groupTypes) {
            require(original.optString("detour").isBlank()) { "outbound 含 detour，无法安全嵌入 Chain：$tag" }
            return tag
        }
        val members = original.optJSONArray("outbounds") ?: error("分组没有 outbounds：$tag")
        require(members.length() <= MAX_GROUP_MEMBERS) { "分组成员过多：$tag" }
        val mapped = JSONArray()
        for (i in 0 until members.length()) {
            val member = members.optString(i).trim()
            if (member.isEmpty() || member in forbiddenTags || member in extraExclude) continue
            val child = find(outs, member)
            if (child != null && child.optString("type") in forbiddenTypes) continue
            mapped.put(member)
        }
        require(mapped.length() > 0) { "分组过滤 DIRECT/落地后没有可用代理：$tag。请另选入口或落地。" }
        val needsRewrite = mapped.length() != members.length()
        if (!needsRewrite) return tag
        fun stripDefault(obj: JSONObject) {
            if (!obj.has("default")) return
            val d = obj.optString("default")
            if (d.isBlank() || d in forbiddenTags || d in extraExclude) obj.remove("default")
        }
        if (inPlace) {
            original.put("outbounds", mapped)
            stripDefault(original)
            return tag
        }
        val newTag = tagPrefix + tag
        val clone = JSONObject(original.toString()).put("tag", newTag).put("outbounds", mapped)
        stripDefault(clone)
        removeOutbound(outs, newTag)
        outs.put(clone)
        return newTag
    }

    private fun isForbiddenHop(outs: JSONArray, o: JSONObject, tag: String): Boolean {
        val type = o.optString("type")
        if (type in forbiddenTypes || tag in forbiddenTags) return true
        if (type !in groupTypes) return false
        val members = o.optJSONArray("outbounds") ?: return true
        var usable = 0
        for (i in 0 until members.length()) {
            val member = members.optString(i).trim()
            if (member.isEmpty() || member in forbiddenTags) continue
            val child = find(outs, member)
            if (child != null && child.optString("type") in forbiddenTypes) continue
            usable++
        }
        return usable == 0
    }

    private fun isGeneratedTag(tag: String): Boolean =
        tag == LEGACY_CHAIN_TAG ||
            tag.startsWith(GENERATED_PREFIX) ||
            tag.startsWith(LANDING_PREFIX) ||
            tag.startsWith(ENTRY_PREFIX) ||
            tag.startsWith(LEGACY_PREFIX)

    private fun cleanGeneratedOutbounds(source: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until source.length()) {
            val o = source.optJSONObject(i) ?: continue
            val tag = o.optString("tag")
            if (isGeneratedTag(tag)) continue
            if (o.optString("detour").startsWith(LEGACY_PREFIX)) o.remove("detour")
            out.put(o)
        }
        return out
    }

    private fun mergeLandingGraph(dst: JSONArray, src: JSONArray, profileId: Long, rootTag: String): String {
        val visiting = mutableSetOf<String>()
        val merged = mutableMapOf<String, String>()

        fun merge(tag: String, depth: Int = 0): String {
            require(tag.isNotBlank()) { "落地 outbound 为空" }
            require(depth <= MAX_MERGE_DEPTH) { "落地配置分组嵌套过深" }
            if (tag in visiting) error("落地配置拓扑存在循环：$tag")
            merged[tag]?.let { return it }
            visiting.add(tag)
            val original = find(src, tag) ?: error("落地配置引用不存在的 outbound：$tag")
            val type = original.optString("type")
            require(type != NATIVE_CHAIN_TYPE) { "不允许把已有 Chain 作为落地 Chain 的子链：$tag" }
            require(type !in setOf("direct", "block", "dns")) { "落地不能使用 $type：$tag" }
            require(original.optString("detour").isBlank()) { "落地 outbound 含 detour，无法安全嵌入 Chain：$tag" }

            val newTag = "$LANDING_PREFIX$profileId-$tag"
            merged[tag] = newTag
            val clone = JSONObject(original.toString()).put("tag", newTag)
            if (type in groupTypes) {
                val members = original.optJSONArray("outbounds") ?: error("落地分组没有 outbounds：$tag")
                require(members.length() <= MAX_GROUP_MEMBERS) { "落地分组成员过多：$tag" }
                val mapped = JSONArray()
                for (i in 0 until members.length()) {
                    val member = members.optString(i)
                    if (member in forbiddenTags) continue
                    val child = find(src, member)
                    if (child != null && child.optString("type") in forbiddenTypes) continue
                    mapped.put(merge(member, depth + 1))
                }
                require(mapped.length() > 0) { "落地分组过滤后没有可用代理：$tag" }
                clone.put("outbounds", mapped)
                if (clone.has("default")) {
                    val d = clone.optString("default")
                    if (d.isNotBlank() && d !in forbiddenTags) clone.put("default", merge(d, depth + 1))
                    else clone.remove("default")
                }
            }
            if (find(dst, newTag) == null) dst.put(clone)
            visiting.remove(tag)
            return newTag
        }
        return merge(rootTag)
    }

    private fun find(outs: JSONArray, tag: String): JSONObject? {
        for (i in 0 until outs.length()) if (outs.optJSONObject(i)?.optString("tag") == tag) return outs.optJSONObject(i)
        return null
    }

    private fun removeOutbound(outs: JSONArray, tag: String) {
        for (i in outs.length() - 1 downTo 0) if (outs.optJSONObject(i)?.optString("tag") == tag) outs.remove(i)
    }
}
