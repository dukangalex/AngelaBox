package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Materializes a two-hop native chain: entry first, landing/exit last.
 * Packet path is entry → landing → public IP. DNS detours stay on the
 * original outbound (one hop) and are not rewritten onto the chain.
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
    private val CN_RULE_SET_TOKENS = setOf(
        "geoip-cn", "geosite-cn", "geosite-geolocation-cn",
        "geoip_cn", "geosite_cn", "cn",
    )

    data class ApplyRequest(
        val content: String,
        val currentProfileId: Long,
        val entryTag: String?,
        val landingProfileId: Long,
        val landingTag: String,
        val landingContent: String?,
    )

    data class Hop(val profileId: Long, val profileName: String, val tag: String, val type: String)

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
        require(sameProfile || !req.landingContent.isNullOrBlank()) { "跨配置落地内容缺失，无法组链" }
        val landingMergedTag = if (sameProfile) {
            require(req.landingTag != main) { "入口与落地不能是同一个 outbound" }
            find(outs, req.landingTag) ?: error("落地 outbound 不存在：${req.landingTag}")
            prepareGroupHop(outs, req.landingTag, setOf(main), "$LANDING_PREFIX${req.currentProfileId}-", inPlace = true)
        } else {
            val landingRoot = parseConfig(req.landingContent!!, "落地配置")
            val landingOuts = landingRoot.optJSONArray("outbounds") ?: error("落地配置没有 outbounds")
            mergeLandingGraph(outs, landingOuts, req.landingProfileId, req.landingTag)
        }

        val entryExclude = buildSet {
            add(landingMergedTag)
            if (sameProfile) add(req.landingTag)
        }
        val entryHop = prepareGroupHop(outs, main, entryExclude, ENTRY_PREFIX, inPlace = sameProfile)
        val chainTag = "$GENERATED_PREFIX${req.currentProfileId}-${req.landingProfileId}"
        removeOutbound(outs, chainTag)
        outs.put(JSONObject().put("type", NATIVE_CHAIN_TYPE).put("tag", chainTag)
            .put("outbounds", JSONArray().put(entryHop).put(landingMergedTag)))
        route.put("final", chainTag)
        root.put("outbounds", outs)
        pinTrafficToChain(root, chainTag, landingMergedTag, setOf(main, entryHop))
        pinHopServerResolvers(root, entryHop, landingMergedTag)
        return root.toString()
    }

    fun clear(content: String, restoreFinal: String?): String {
        val root = parseConfig(content, "当前配置")
        root.put("outbounds", cleanGeneratedOutbounds(root.optJSONArray("outbounds") ?: JSONArray()))
        root.optJSONObject("route")?.let { route ->
            val final = route.optString("final")
            if (final.startsWith(GENERATED_PREFIX) || final == LEGACY_CHAIN_TAG || final.startsWith(LEGACY_PREFIX)) {
                if (!restoreFinal.isNullOrBlank()) route.put("final", restoreFinal) else route.remove("final")
            }
        }
        return root.toString()
    }

    fun isFinalLike(tag: String): Boolean {
        val t = tag.lowercase()
        return t.contains("漏网") || t.contains("final") || t.contains("剩余") || t.contains("unmatched") || t == "match"
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
            if (tag.isEmpty() || isGeneratedTag(tag) || type !in groupTypes) continue
            if (isForbiddenHop(outs, o, tag)) continue
            var score = if (type == "urltest") 20 else 15
            val t = tag.lowercase()
            if (isFinalLike(tag)) score -= 80
            if (t.contains("proxy") || t.contains("select") || t.contains("节点") || t.contains("选择") || t.contains("自动")) score += 25
            if (score > bestScore) { bestScore = score; best = tag }
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
        require(content.length <= MAX_CONFIG_CHARS) { "${label}过大（>${MAX_CONFIG_CHARS} 字符），已拒绝解析" }
        return JSONObject(content)
    }

    /**
     * True only when every concrete leaf on both hops uses TLS.
     * sing-box cannot reliably detour one TLS protocol through another
     * (SagerNet/sing-box#3205). A group that still contains a non-TLS leaf
     * can avoid that pair, so it does not warn.
     */
    fun bothHopsAreTls(
        entryContent: String,
        entryTag: String,
        landingContent: String,
        landingTag: String,
    ): Boolean {
        return allLeavesUseTls(entryContent, entryTag) && allLeavesUseTls(landingContent, landingTag)
    }

    internal fun allLeavesUseTls(content: String, tag: String): Boolean {
        val outs = parseConfig(content).optJSONArray("outbounds") ?: return false
        val leaves = ArrayList<JSONObject>()
        collectLeafObjects(outs, tag, leaves, HashSet())
        return leaves.isNotEmpty() && leaves.all { usesTls(it) }
    }

    /**
     * Entry and landing server names must resolve on a direct DNS.
     * A resolver that detours through the proxy, or a fake-ip pool, cannot
     * be dialed until the hop is already up.
     */
    internal fun pinHopServerResolvers(root: JSONObject, entryTag: String, landingTag: String) {
        val outs = root.optJSONArray("outbounds") ?: return
        val risky = proxyDetourDnsTags(root)
        val fallback = resolverName(root.optJSONObject("route")?.opt("default_domain_resolver"))
        val fallbackRisky = fallback.isNotEmpty() && fallback in risky
        val leaves = ArrayList<JSONObject>()
        collectLeafObjects(outs, entryTag, leaves, HashSet())
        collectLeafObjects(outs, landingTag, leaves, HashSet())
        var direct: String? = null
        for (leaf in leaves) {
            val current = resolverName(leaf.opt("domain_resolver"))
            val needsDirect = when {
                current.isEmpty() -> fallbackRisky
                else -> current in risky
            }
            if (!needsDirect) continue
            val tag = direct ?: directDnsTag(root) ?: ensureLocalDns(root)
            direct = tag
            pointResolver(leaf, tag)
        }
    }

    internal fun pinTrafficToChain(root: JSONObject, chainTag: String, landingTag: String, entryTags: Set<String> = emptySet()) {
        val outs = root.optJSONArray("outbounds") ?: return
        val protected = mutableSetOf(chainTag, landingTag)
        val directTags = mutableSetOf<String>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type").trim()
            if (tag.isEmpty()) continue
            if (tag in entryTags || tag.startsWith(ENTRY_PREFIX)) continue
            if (type == "direct" || isDirectLike(tag)) { directTags.add(tag); continue }
            if (type in forbiddenTypes || tag.lowercase() in forbiddenTags) protected.add(tag)
            if (tag.startsWith(LANDING_PREFIX)) protected.add(tag)
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val currentFinal = route.optString("final").trim()
        if (currentFinal.isEmpty() || currentFinal !in protected || currentFinal in entryTags || currentFinal in directTags || isDirectLike(currentFinal)) route.put("final", chainTag)
        rewriteRuleOutbounds(route.optJSONArray("rules"), protected, directTags, chainTag)
    }

    internal fun isBypassDirectRule(rule: JSONObject): Boolean {
        val sets = textsOf(rule, "rule_set") + textsOf(rule, "geosite") + textsOf(rule, "geoip")
        if (sets.any { it.contains('!') }) return false
        val suffixes = textsOf(rule, "domain_suffix")
        val cidrs = textsOf(rule, "ip_cidr")
        val extra = textsOf(rule, "domain") + textsOf(rule, "domain_keyword") +
            textsOf(rule, "domain_regex") + textsOf(rule, "ip_cidr6")
        if (extra.isNotEmpty()) return false
        if (sets.any { !isExplicitCnRuleSet(it) }) return false
        if (suffixes.any { !isCnSuffix(it) }) return false
        if (cidrs.any { !isPrivateCidr(it) }) return false
        val privateFlag = rule.optBoolean("ip_is_private", false)
        return privateFlag || sets.isNotEmpty() || suffixes.isNotEmpty() || cidrs.isNotEmpty()
    }

    internal fun isExplicitCnRuleSet(raw: String): Boolean {
        val token = raw.trim().lowercase().substringAfterLast('/')
        return token in CN_RULE_SET_TOKENS
    }

    internal fun isCnSuffix(raw: String): Boolean {
        val s = raw.trim().lowercase().trimStart('.')
        return s == "cn" || s.endsWith(".cn")
    }

    internal fun isPrivateCidr(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        val slash = s.indexOf('/')
        val ip = if (slash >= 0) s.substring(0, slash) else s
        val prefix = if (slash >= 0) s.substring(slash + 1).toIntOrNull() ?: return false else null
        return if (':' in ip) isPrivateIpv6Literal(ip, prefix) else isPrivateIpv4Literal(ip, prefix)
    }

    private fun isPrivateIpv4Literal(ip: String, prefix: Int?): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val b = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return false
            if (n !in 0..255) return false
            if (parts[i] != n.toString()) return false
            b[i] = n
        }
        if (prefix != null && prefix !in 0..32) return false
        val a = b[0]
        val c = b[1]
        return a == 10 || a == 127 || a == 0 ||
            (a == 192 && c == 168) ||
            (a == 172 && c in 16..31) ||
            (a == 169 && c == 254)
    }

    private fun isPrivateIpv6Literal(ip: String, prefix: Int?): Boolean {
        val t = ip.lowercase()
        if (t.any { it !in '0'..'9' && it !in 'a'..'f' && it != ':' }) return false
        if (prefix != null && prefix !in 0..128) return false
        if (t == "::1" || t == "::") return true
        if (t.startsWith("fc") || t.startsWith("fd") || t.startsWith("fe80")) return true
        return false
    }

    internal fun isDirectLike(tag: String): Boolean {
        val t = tag.trim().lowercase()
        return t == "direct" || t.contains("直连") || t == "chainbox-direct"
    }

    private fun rewriteRuleOutbounds(rules: JSONArray?, protected: Set<String>, directTags: Set<String>, chainTag: String) {
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
            if (outbound !in protected) rule.put("outbound", chainTag)
        }
    }

    private fun textsOf(rule: JSONObject, key: String): List<String> {
        val raw = rule.opt(key) ?: return emptyList()
        return when (raw) { is String -> listOf(raw); is JSONArray -> (0 until raw.length()).map { raw.optString(it) }; else -> emptyList() }
    }

    fun displayHopTag(tag: String): String {
        val t = tag.trim()
        if (t.isEmpty() || isGeneratedChainTag(t)) return ""
        if (t.startsWith(ENTRY_PREFIX)) return t.removePrefix(ENTRY_PREFIX)
        if (t.startsWith(LANDING_PREFIX)) return t.removePrefix(LANDING_PREFIX).replaceFirst(Regex("^\\d+-"), "")
        if (t.startsWith(LEGACY_PREFIX)) return t.removePrefix(LEGACY_PREFIX)
        return t
    }

    private fun isGeneratedChainTag(tag: String): Boolean = tag == LEGACY_CHAIN_TAG || tag.startsWith(GENERATED_PREFIX)

    /** Recursively removes terminal/bypass members from selector/urltest graphs. */
    private fun prepareGroupHop(outs: JSONArray, tag: String, extraExclude: Set<String>, tagPrefix: String, inPlace: Boolean = false): String {
        val visiting = mutableSetOf<String>()
        val rewritten = mutableMapOf<String, String>()

        fun sanitize(currentTag: String, depth: Int, root: Boolean): String {
            require(depth <= MAX_MERGE_DEPTH) { "链式入口分组嵌套过深：$currentTag" }
            require(currentTag !in visiting) { "链式入口分组存在循环：$currentTag" }
            rewritten[currentTag]?.let { return it }
            val original = find(outs, currentTag) ?: error("链式入口引用不存在的 outbound：$currentTag")
            val type = original.optString("type").trim()
            require(type !in forbiddenTypes) { "不能使用 $type 作为链式跳板：$currentTag" }
            if (type !in groupTypes) {
                require(original.optString("detour").isBlank()) { "outbound 含 detour，无法安全嵌入 Chain：$currentTag" }
                return currentTag
            }
            visiting.add(currentTag)
            val members = original.optJSONArray("outbounds") ?: error("分组没有 outbounds：$currentTag")
            require(members.length() <= MAX_GROUP_MEMBERS) { "分组成员过多：$currentTag" }
            val mapped = JSONArray()
            for (i in 0 until members.length()) {
                val member = members.optString(i).trim()
                if (member.isEmpty() || member in forbiddenTags || member in extraExclude) continue
                val child = find(outs, member) ?: error("分组 $currentTag 引用了不存在的 outbound：$member")
                val childType = child.optString("type").trim()
                if (childType in forbiddenTypes) continue
                mapped.put(if (childType in groupTypes) sanitize(member, depth + 1, false) else member)
            }
            require(mapped.length() > 0) { "分组过滤 DIRECT/落地后没有可用代理：$currentTag。请另选入口或落地。" }
            val newTag = if (root && inPlace) currentTag else "$tagPrefix$currentTag"
            val clone = if (root && inPlace) original else JSONObject(original.toString()).put("tag", newTag)
            clone.put("outbounds", mapped)
            if (clone.has("default")) {
                val d = clone.optString("default").trim()
                if (d.isBlank() || d in forbiddenTags || d in extraExclude) clone.remove("default")
                else {
                    val dObj = find(outs, d)
                    if (dObj == null || dObj.optString("type") in forbiddenTypes) clone.remove("default")
                    else clone.put("default", if (dObj.optString("type") in groupTypes) sanitize(d, depth + 1, false) else d)
                }
            }
            if (!(root && inPlace)) {
                removeOutbound(outs, newTag)
                outs.put(clone)
            }
            rewritten[currentTag] = newTag
            visiting.remove(currentTag)
            return newTag
        }
        return sanitize(tag, 0, true)
    }

    private fun isForbiddenHop(outs: JSONArray, o: JSONObject, tag: String): Boolean {
        val type = o.optString("type")
        if (type in forbiddenTypes || tag in forbiddenTags) return true
        if (type !in groupTypes) return false
        return runCatching {
            val visiting = mutableSetOf<String>()
            fun usable(current: String, depth: Int): Boolean {
                if (depth > MAX_MERGE_DEPTH || current in visiting) return false
                val obj = find(outs, current) ?: return false
                val t = obj.optString("type")
                if (t in forbiddenTypes || current in forbiddenTags) return false
                if (t !in groupTypes) return true
                visiting.add(current)
                val members = obj.optJSONArray("outbounds") ?: return false
                val result = (0 until members.length()).any { usable(members.optString(it).trim(), depth + 1) }
                visiting.remove(current)
                return result
            }
            usable(tag, 0)
        }.getOrDefault(false).not()
    }

    private fun isGeneratedTag(tag: String): Boolean = tag == LEGACY_CHAIN_TAG || tag.startsWith(GENERATED_PREFIX) || tag.startsWith(LANDING_PREFIX) || tag.startsWith(ENTRY_PREFIX) || tag.startsWith(LEGACY_PREFIX)

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
            require(tag !in visiting) { "落地配置拓扑存在循环：$tag" }
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
                    val child = find(src, member) ?: error("落地分组引用不存在的 outbound：$member")
                    if (child.optString("type") in forbiddenTypes) continue
                    mapped.put(merge(member, depth + 1))
                }
                require(mapped.length() > 0) { "落地分组过滤后没有可用代理：$tag" }
                clone.put("outbounds", mapped)
                if (clone.has("default")) {
                    val d = clone.optString("default")
                    if (d.isNotBlank() && d !in forbiddenTags) clone.put("default", merge(d, depth + 1)) else clone.remove("default")
                }
            }
            if (find(dst, newTag) == null) dst.put(clone)
            visiting.remove(tag)
            return newTag
        }
        return merge(rootTag)
    }

    private val alwaysTlsTypes = setOf("hysteria", "hysteria2", "tuic", "anytls", "naive", "shadowtls")

    private fun collectLeafObjects(
        outs: JSONArray,
        tag: String,
        into: MutableList<JSONObject>,
        seen: MutableSet<String>,
    ) {
        if (tag.isEmpty() || !seen.add(tag)) return
        val outbound = find(outs, tag) ?: return
        val type = outbound.optString("type").trim()
        if (type in groupTypes) {
            val members = outbound.optJSONArray("outbounds") ?: return
            for (i in 0 until members.length()) {
                collectLeafObjects(outs, members.optString(i).trim(), into, seen)
            }
            return
        }
        if (type.isEmpty() || type in forbiddenTypes) return
        into.add(outbound)
    }

    private fun usesTls(outbound: JSONObject): Boolean {
        val type = outbound.optString("type").trim().lowercase()
        if (type in alwaysTlsTypes) return true
        val tls = outbound.optJSONObject("tls") ?: return false
        return tls.optBoolean("enabled", false)
    }

    private fun proxyDetourDnsTags(root: JSONObject): Set<String> {
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return emptySet()
        val tags = HashSet<String>()
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val tag = server.optString("tag").trim()
            if (tag.isEmpty()) continue
            val type = server.optString("type").trim().lowercase()
            if (type == "fakeip") {
                tags.add(tag)
                continue
            }
            val detour = server.optString("detour").trim()
            if (detour.isNotEmpty() && !isDirectLike(detour)) tags.add(tag)
        }
        return tags
    }

    private fun directDnsTag(root: JSONObject): String? {
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return null
        var plain: String? = null
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val tag = server.optString("tag").trim()
            if (tag.isEmpty()) continue
            if (server.optString("type").equals("local", true)) return tag
            val detour = server.optString("detour").trim()
            val type = server.optString("type").trim().lowercase()
            if (plain == null && type != "fakeip" && (detour.isEmpty() || isDirectLike(detour))) plain = tag
        }
        return plain
    }

    private fun ensureLocalDns(root: JSONObject): String {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        servers.put(JSONObject().put("type", "local").put("tag", "local"))
        return "local"
    }

    private fun resolverName(raw: Any?): String {
        return when (raw) {
            is String -> raw.trim()
            is JSONObject -> raw.optString("server").trim()
            else -> ""
        }
    }

    private fun pointResolver(outbound: JSONObject, server: String) {
        val raw = outbound.opt("domain_resolver")
        if (raw is JSONObject) raw.put("server", server) else outbound.put("domain_resolver", server)
    }

    private fun find(outs: JSONArray, tag: String): JSONObject? {
        for (i in 0 until outs.length()) if (outs.optJSONObject(i)?.optString("tag") == tag) return outs.optJSONObject(i)
        return null
    }

    private fun removeOutbound(outs: JSONArray, tag: String) {
        for (i in outs.length() - 1 downTo 0) if (outs.optJSONObject(i)?.optString("tag") == tag) outs.remove(i)
    }
}
