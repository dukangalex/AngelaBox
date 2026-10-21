package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kernel acceptance oracle for one candidate config: returns null when
 * the kernel accepts it, or the kernel's error text when it does not.
 * Production wires this to Libbox.checkConfig (BoxService); unit tests
 * inject a fake, because libbox is not available on the JVM.
 */
fun interface ConfigCheckOracle {
    fun check(configJson: String): String?
}

/**
 * Call/deadline budget for one quarantine run. checkConfig is a local
 * call (well under a second), but a pathological config can carry
 * hundreds of candidate parts, so the orchestrator caps its calls and
 * honours the caller's deadline; on exhaustion it jumps straight to
 * the floor built from the parts not yet proven bad.
 */
class QuarantineBudget(
    val maxCheckCalls: Int = 240,
    val expired: () -> Boolean = { false },
) {
    var checkCalls: Int = 0
        private set

    internal fun exhausted(): Boolean = checkCalls >= maxCheckCalls || expired()

    internal fun spend() {
        checkCalls++
    }
}

/**
 * Fidelity-first degradation, the last ladder rung before giving up
 * (PLAN: 保真降级档）. The start ladder attributes failures by error
 * text, so a structural fault outside every known pattern used to kill
 * the whole profile: one bad DNS server, one bad route rule or one bad
 * node meant "valid config, cannot start". This orchestrator instead
 * asks the kernel itself (via the injected check oracle) which parts
 * are bad, removes ONLY those — rule sets (with their referencing
 * rules), DNS servers, route rules, inbounds, outbounds/endpoints with
 * cascading group pruning — and keeps everything else field-for-field.
 * Only when isolation cannot converge does it fall back to the floor:
 * a minimal config assembled from the surviving leaves, in the exact
 * shape the import layer already ships (wrapNodes / autoGroups).
 *
 * Everything here is a pure JSON transform on a runtime copy; the
 * stored profile is never mutated, so the next start retries the full
 * original config. Removals are reported by name so the caller can
 * tell the user exactly what was quarantined.
 */
object ConfigQuarantine {

    data class Outcome(
        val content: String,
        /** Named removals (bad parts first, then cascade effects). */
        val removedItems: List<String>,
        val floorUsed: Boolean,
        val lastCheckError: String?,
    )

    private class BudgetExhausted : Exception()

    private data class Candidate(val id: String, val label: String)

    // Group types whose "outbounds" are member references, not leaves.
    private val GROUP_TYPES = setOf("selector", "urltest", "url-test", "load-balance", "fallback")

    // Floor group tags, field-for-field the import layer's autoGroups
    // shape (ConfigIngest.AUTO_SELECT / AUTO_TEST) so the dashboard
    // shows the same group names as an imported link collection.
    private const val FLOOR_SELECT = "节点选择"
    private const val FLOOR_TEST = "自动选择"

    /**
     * Degrade [content] until the oracle accepts it. Returns null when
     * even the floor cannot be assembled or accepted (no surviving
     * leaf, or the floor itself is rejected) — the caller then fails
     * with [Outcome.lastCheckError]-grade diagnostics.
     */
    fun degrade(
        content: String,
        oracle: ConfigCheckOracle,
        budget: QuarantineBudget = QuarantineBudget(),
    ): Outcome? {
        val root = parseObject(content) ?: return null
        val session = Session(oracle, budget)
        val provenBad = linkedSetOf<String>()
        val isolated = try {
            session.isolate(root, provenBad)
        } catch (_: BudgetExhausted) {
            null
        }
        if (isolated != null) return isolated
        // Floor phase: keep the leaves that were not proven bad. The
        // floor discards route rules / rule sets / the original DNS
        // rules wholesale, so faults isolation could not localise
        // (unknown sections, cross-section faults, budget exhaustion)
        // usually vanish here; the floor itself is isolated again in
        // case a surviving leaf is bad after all.
        val floor = buildFloorTree(root, provenBad) ?: return null
        val labels = collectCandidates(root)
            .filter { it.id in provenBad }
            .map { it.label }
        // One direct try first (the plan's "超预算跳 floor 试一次"):
        // a floor the kernel accepts as-is needs no second isolation.
        val floorJson = floor.toString()
        if (!budget.expired() && session.checkBypassingCap(floorJson) == null) {
            return Outcome(floorJson, labels, true, session.lastError)
        }
        val floorOutcome = try {
            session.isolate(floor, linkedSetOf())
        } catch (_: BudgetExhausted) {
            null
        } ?: return null
        return floorOutcome.copy(
            floorUsed = true,
            removedItems = labels + floorOutcome.removedItems,
            lastCheckError = floorOutcome.lastCheckError ?: session.lastError,
        )
    }

    /**
     * The minimal viable config (floor) for [content] with nothing
     * excluded, or null when the config has no usable leaf node.
     * Exposed for the pipeline-fatal recovery path, which tries a
     * chain-compiled floor before the unchained one.
     */
    fun buildFloor(content: String): String? {
        val root = parseObject(content) ?: return null
        return buildFloorTree(root, emptySet())?.toString()
    }

    private fun parseObject(content: String): JSONObject? {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed[0] != '{') return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    /** One degrade run's worth of oracle plumbing (counting + memo). */
    private class Session(
        private val oracle: ConfigCheckOracle,
        private val budget: QuarantineBudget,
    ) {
        var lastError: String? = null
            private set

        private var memoJson: String? = null
        private var memoError: String? = null
        private var memoValid = false

        fun check(json: String): String? {
            if (memoValid && memoJson == json) return memoError
            if (budget.exhausted()) throw BudgetExhausted()
            budget.spend()
            return record(json, oracle.check(json))
        }

        /**
         * The floor's one direct try: not subject to the call cap
         * (the caller checks the deadline itself), still recorded for
         * diagnostics and the memo.
         */
        fun checkBypassingCap(json: String): String? {
            if (memoValid && memoJson == json) return memoError
            return record(json, oracle.check(json))
        }

        private fun record(json: String, error: String?): String? {
            if (error != null) lastError = error
            memoJson = json
            memoError = error
            memoValid = true
            return error
        }

        /**
         * Isolate the bad parts of [root]: on success returns the
         * accepted degraded config; on non-convergence returns null
         * (faults outside the candidate sections, or removing every
         * candidate still fails). [provenBad] is filled with the ids
         * proven bad even on failure, for the caller's floor phase.
         */
        fun isolate(root: JSONObject, provenBad: MutableSet<String>): Outcome? {
            // Entry normalisation first: reference heals + a guaranteed
            // TUN. A tree the kernel already accepts starts whole —
            // quarantine must not remove anything from a working config
            // just because it was invoked.
            val entry = rebuild(root, emptySet()).first
            if (check(entry.toString()) == null) {
                return Outcome(entry.toString(), emptyList(), false, null)
            }
            val candidates = collectCandidates(root)
            if (candidates.isEmpty()) return null
            val all = candidates.map { it.id }.toSet()
            // Sanity probe: removing every candidate must fix the tree,
            // otherwise the fault lives outside the candidate sections
            // and bisection cannot converge.
            if (check(rebuild(root, all).first.toString()) != null) return null
            while (true) {
                if (check(rebuild(root, provenBad).first.toString()) == null) {
                    val (degraded, cascade) = rebuild(root, provenBad)
                    val labels = candidates
                        .filter { it.id in provenBad }
                        .map { it.label }
                    return Outcome(degraded.toString(), labels + cascade, false, lastError)
                }
                val remaining = candidates.filter { it.id !in provenBad }
                if (remaining.isEmpty()) return null
                provenBad += findCulprit(root, provenBad, remaining)
            }
        }

        /**
         * Find one culprit in [cands]. Invariants on entry:
         * fails(base) is true, fails(base + cands) is false, where
         * fails(S) means "the tree with S removed is still rejected".
         */
        private fun findCulprit(
            root: JSONObject,
            base: Set<String>,
            cands: List<Candidate>,
        ): String {
            if (cands.size == 1) return cands[0].id
            val mid = cands.size / 2
            val a = cands.subList(0, mid)
            val b = cands.subList(mid, cands.size)
            val aIds = a.map { it.id }.toSet()
            return if (fails(root, base + aIds)) {
                findCulprit(root, base + aIds, b)
            } else {
                findCulprit(root, base, a)
            }
        }

        private fun fails(root: JSONObject, removed: Set<String>): Boolean =
            check(rebuild(root, removed).first.toString()) != null
    }

    // ----------------------------------------------------------------
    // Candidates
    // ----------------------------------------------------------------

    private fun collectCandidates(root: JSONObject): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val route = root.optJSONObject("route")
        val sets = route?.optJSONArray("rule_set")
        if (sets != null) {
            for (i in 0 until sets.length()) {
                val item = sets.optJSONObject(i) ?: continue
                val tag = item.optString("tag").trim()
                val id = if (tag.isNotEmpty()) "rs:$tag" else "rs:@$i"
                out += Candidate(id, if (tag.isNotEmpty()) "规则集「$tag」" else "第 ${i + 1} 个规则集")
            }
        }
        val servers = root.optJSONObject("dns")?.optJSONArray("servers")
        if (servers != null) {
            for (i in 0 until servers.length()) {
                val tag = servers.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
                out += Candidate(
                    "dns:@$i",
                    if (tag.isNotEmpty()) "DNS 服务器「$tag」" else "第 ${i + 1} 个 DNS 服务器",
                )
            }
        }
        val rules = route?.optJSONArray("rules")
        if (rules != null) {
            for (i in 0 until rules.length()) {
                out += Candidate("rule:@$i", "第 ${i + 1} 条分流规则")
            }
        }
        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null) {
            for (i in 0 until inbounds.length()) {
                val tag = inbounds.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
                out += Candidate(
                    "in:@$i",
                    if (tag.isNotEmpty()) "入站「$tag」" else "第 ${i + 1} 个入站",
                )
            }
        }
        val outs = root.optJSONArray("outbounds")
        if (outs != null) {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                val tag = o.optString("tag").trim()
                if (tag.isEmpty()) continue
                val type = o.optString("type").trim().lowercase()
                out += Candidate(
                    "out:$tag",
                    if (type in GROUP_TYPES) "分组「$tag」" else "节点「$tag」",
                )
            }
        }
        val endpoints = root.optJSONArray("endpoints")
        if (endpoints != null) {
            for (i in 0 until endpoints.length()) {
                val tag = endpoints.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
                if (tag.isEmpty()) continue
                out += Candidate("ep:$tag", "节点「$tag」")
            }
        }
        return out
    }

    // ----------------------------------------------------------------
    // Rebuild: deep copy + targeted removals + reference fixups
    // ----------------------------------------------------------------

    /**
     * Deep-copy [source], remove the parts named by [bad] (candidate
     * ids), then repair every reference that removal may have broken.
     * Returns the copy and the cascade-removal descriptions (things
     * removed only because a proven-bad part disappeared).
     */
    private fun rebuild(source: JSONObject, bad: Set<String>): Pair<JSONObject, List<String>> {
        val root = JSONObject(source.toString())
        val cascade = mutableListOf<String>()
        if (bad.isEmpty()) {
            fixups(root, cascade)
            return root to cascade
        }
        // Capture removal targets from the copy itself, by identity for
        // index-addressed sections (indices are stable because every
        // rebuild starts from the pristine source).
        val route = root.optJSONObject("route")
        val ruleTargets = captureIndexed(route?.optJSONArray("rules"), bad, "rule")
        val dns = root.optJSONObject("dns")
        val dnsTargets = captureIndexed(dns?.optJSONArray("servers"), bad, "dns")
        val inboundTargets = captureIndexed(root.optJSONArray("inbounds"), bad, "in")

        removeObjects(route?.optJSONArray("rules"), ruleTargets)
        removeObjects(root.optJSONArray("inbounds"), inboundTargets)

        // D4: DNS servers, then final / rule references / empty fallback.
        if (dnsTargets.isNotEmpty() && dns != null) {
            val removedTags = dnsTargets
                .map { it.optString("tag").trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            removeObjects(dns.optJSONArray("servers"), dnsTargets)
            val servers = dns.optJSONArray("servers")
            if (servers == null || servers.length() == 0) {
                val minimal = JSONArray()
                    .put(JSONObject().put("type", "local").put("tag", "local"))
                    .put(
                        JSONObject().put("type", "tcp").put("tag", "remote")
                            .put("server", "1.1.1.1").put("server_port", 53),
                    )
                dns.put("servers", minimal)
                dns.put("final", "remote")
                cascade += "DNS 服务器已全部隔离，换成最小可用 DNS"
            } else {
                if (dns.optString("final").trim() in removedTags) {
                    val firstTag = (0 until servers.length())
                        .mapNotNull { servers.optJSONObject(it)?.optString("tag")?.trim() }
                        .firstOrNull { it.isNotEmpty() }
                    if (firstTag != null) dns.put("final", firstTag) else dns.remove("final")
                }
                val dnsRules = dns.optJSONArray("rules")
                if (dnsRules != null && removedTags.isNotEmpty()) {
                    val kept = JSONArray()
                    var dropped = 0
                    for (i in 0 until dnsRules.length()) {
                        val rule = dnsRules.optJSONObject(i)
                        if (rule != null && rule.optString("server").trim() in removedTags) {
                            dropped++
                        } else {
                            kept.put(dnsRules.get(i))
                        }
                    }
                    if (dropped > 0) {
                        replaceArray(dnsRules, kept)
                        cascade += "指向被隔离 DNS 的 $dropped 条解析规则已一并去掉"
                    }
                }
            }
        }

        // D7: outbounds / endpoints by tag.
        removeTagged(root.optJSONArray("outbounds"), bad, "out")
        removeTagged(root.optJSONArray("endpoints"), bad, "ep")

        // D3: rule sets by id, plus the rules that referenced them.
        val sets = route?.optJSONArray("rule_set")
        if (sets != null) {
            val droppedTags = mutableSetOf<String>()
            val kept = JSONArray()
            for (i in 0 until sets.length()) {
                val item = sets.optJSONObject(i) ?: continue
                val tag = item.optString("tag").trim()
                val id = if (tag.isNotEmpty()) "rs:$tag" else "rs:@$i"
                if (id in bad) {
                    if (tag.isNotEmpty()) droppedTags += tag
                } else {
                    kept.put(item)
                }
            }
            if (droppedTags.isNotEmpty() || kept.length() != sets.length()) {
                replaceArray(sets, kept)
                var stripped = 0
                stripped += stripRuleSetRefs(route?.optJSONArray("rules"), droppedTags)
                stripped += stripRuleSetRefs(
                    root.optJSONObject("dns")?.optJSONArray("rules"),
                    droppedTags,
                )
                if (stripped > 0) {
                    cascade += "只引用被隔离规则集的 $stripped 条规则已一并去掉"
                }
            }
        }

        fixups(root, cascade)
        return root to cascade
    }

    private fun captureIndexed(arr: JSONArray?, bad: Set<String>, prefix: String): Set<JSONObject> {
        if (arr == null) return emptySet()
        val out = mutableSetOf<JSONObject>()
        for (i in 0 until arr.length()) {
            if ("$prefix:@$i" in bad) arr.optJSONObject(i)?.let { out += it }
        }
        return out
    }

    private fun removeObjects(arr: JSONArray?, targets: Set<JSONObject>) {
        if (arr == null || targets.isEmpty()) return
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            if (item is JSONObject && item in targets) continue
            kept.put(item)
        }
        replaceArray(arr, kept)
    }

    private fun removeTagged(arr: JSONArray?, bad: Set<String>, prefix: String) {
        if (arr == null) return
        val kept = JSONArray()
        var changed = false
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
            val tag = item?.optString("tag")?.trim().orEmpty()
            if (item != null && tag.isNotEmpty() && "$prefix:$tag" in bad) {
                changed = true
                continue
            }
            kept.put(arr.get(i))
        }
        if (changed) replaceArray(arr, kept)
    }

    /** Remove route/dns rules whose only rule_set reference was dropped. */
    private fun stripRuleSetRefs(rules: JSONArray?, dropTags: Set<String>): Int {
        if (rules == null || dropTags.isEmpty()) return 0
        var removed = 0
        var i = 0
        while (i < rules.length()) {
            val rule = rules.optJSONObject(i)
            if (rule == null) {
                i++
                continue
            }
            removed += stripRuleSetRefs(rule.optJSONArray("rules"), dropTags)
            var drop = false
            when (val raw = rule.opt("rule_set")) {
                is String -> if (raw.trim() in dropTags) drop = true
                is JSONArray -> {
                    val kept = JSONArray()
                    for (j in 0 until raw.length()) {
                        val t = raw.optString(j).trim()
                        if (t.isNotEmpty() && t !in dropTags) kept.put(t)
                    }
                    if (kept.length() != raw.length()) {
                        if (kept.length() == 0) {
                            drop = true
                        } else if (kept.length() == 1) {
                            rule.put("rule_set", kept.getString(0))
                        } else {
                            rule.put("rule_set", kept)
                        }
                    }
                }
            }
            if (drop) {
                rules.remove(i)
                removed++
                continue
            }
            i++
        }
        return removed
    }

    /**
     * Repairs shared by every rebuild, test copies included: prune
     * group members / defaults / finals / rule outbounds that point at
     * removed parts (the startup heal's own algorithm), drop groups
     * the removals emptied, strip DNS detours and dangling resolvers,
     * and guarantee the Android TUN exists.
     */
    private fun fixups(root: JSONObject, cascade: MutableList<String>) {
        // Empty-group cascade: pruning members can empty a group, and
        // removing that group can empty its parent. The kernel refuses
        // empty selectors, so emptied groups cannot stay.
        var guard = 0
        while (guard++ <= 64) {
            ConfigInboundCompat.healMissingOutboundRefsWithReport(root)
            if (!pruneEmptyGroups(root, cascade)) break
        }
        ConfigCompat.stripBrokenDnsDetours(root)
        ConfigInboundCompat.healDanglingDomainResolvers(root)
        ConfigInboundCompat.ensureAndroidTun(root)
        // route.final may have pointed at a group the cascade removed.
        val route = root.optJSONObject("route")
        if (route != null) {
            val final = route.optString("final").trim()
            if (final.isNotEmpty() && final !in allTags(root)) {
                val fallback = firstGroupTag(root) ?: firstLeafTag(root)
                if (fallback != null) route.put("final", fallback) else route.remove("final")
            }
        }
    }

    private fun pruneEmptyGroups(root: JSONObject, cascade: MutableList<String>): Boolean {
        val outs = root.optJSONArray("outbounds") ?: return false
        val kept = JSONArray()
        var removedAny = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i)
            val members = o?.optJSONArray("outbounds")
            val tag = o?.optString("tag")?.trim().orEmpty()
            if (o != null && members != null && members.length() == 0 && tag.isNotEmpty()) {
                cascade += "分组「$tag」成员已被隔离为空，已去掉"
                removedAny = true
                continue
            }
            kept.put(outs.get(i))
        }
        if (removedAny) replaceArray(outs, kept)
        return removedAny
    }

    private fun allTags(root: JSONObject): Set<String> {
        val tags = mutableSetOf<String>()
        for (key in listOf("outbounds", "endpoints")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val tag = arr.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
                if (tag.isNotEmpty()) tags += tag
            }
        }
        return tags
    }

    private fun firstGroupTag(root: JSONObject): String? {
        val outs = root.optJSONArray("outbounds") ?: return null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("type").trim().lowercase() in GROUP_TYPES) {
                val tag = o.optString("tag").trim()
                if (tag.isNotEmpty()) return tag
            }
        }
        return null
    }

    private fun firstLeafTag(root: JSONObject): String? {
        val outs = root.optJSONArray("outbounds") ?: return null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val type = o.optString("type").trim().lowercase()
            if (type in GROUP_TYPES) continue
            val tag = o.optString("tag").trim()
            if (tag.isNotEmpty()) return tag
        }
        return null
    }

    private fun replaceArray(target: JSONArray, keep: JSONArray) {
        while (target.length() > 0) target.remove(0)
        for (i in 0 until keep.length()) target.put(keep.get(i))
    }

    // ----------------------------------------------------------------
    // Floor: minimal viable config from the surviving leaves
    // ----------------------------------------------------------------

    /**
     * Assemble the floor from [source]'s leaves that were not proven
     * bad ([badIds] are candidate ids). Same shape as the import
     * layer's wrapNodes/autoGroups output: surviving leaves verbatim
     * (detours between survivors kept; detours to removed parts are
     * stripped by the shared reference heal, by name in its notes —
     * never silently retargeted at a direct outbound), a direct
     * outbound, selector + urltest over all leaves, one standard TUN,
     * the original DNS servers that were not proven bad (or a minimal
     * local + tcp pair), and route.final at the selector. Nothing
     * else is carried over: no route rules, no rule sets, no
     * experimental sections — the floor only promises "it connects".
     */
    private fun buildFloorTree(source: JSONObject, badIds: Set<String>): JSONObject? {
        val badOutTags = badIds
            .filter { it.startsWith("out:") || it.startsWith("ep:") }
            .map { it.substringAfter(':') }
            .toSet()
        val badDnsIdx = badIds
            .filter { it.startsWith("dns:@") }
            .mapNotNull { it.substringAfter('@').toIntOrNull() }
            .toSet()

        val leaves = mutableListOf<JSONObject>()
        val leafTags = mutableListOf<String>()
        val endpoints = JSONArray()
        var direct: JSONObject? = null
        val outs = source.optJSONArray("outbounds")
        if (outs != null) {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                val tag = o.optString("tag").trim()
                if (tag.isEmpty() || tag in badOutTags) continue
                val type = o.optString("type").trim().lowercase()
                when {
                    type in GROUP_TYPES -> Unit // groups are rebuilt, not carried
                    type == "direct" -> if (direct == null) direct = JSONObject(o.toString())
                    type == "block" || type == "dns" -> Unit // special outbounds stay out
                    type == "wireguard" -> {
                        endpoints.put(JSONObject(o.toString()))
                        leafTags += tag
                    }
                    else -> {
                        leaves += JSONObject(o.toString())
                        leafTags += tag
                    }
                }
            }
        }
        val eps = source.optJSONArray("endpoints")
        if (eps != null) {
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val tag = e.optString("tag").trim()
                if (tag.isEmpty() || tag in badOutTags) continue
                endpoints.put(JSONObject(e.toString()))
                leafTags += tag
            }
        }
        if (leafTags.isEmpty()) return null

        val root = JSONObject()
        ConfigQuicOverride.applyLogLevel(root)

        // DNS: original servers that were not proven bad, else minimal.
        val dns = JSONObject()
        val servers = JSONArray()
        val srcServers = source.optJSONObject("dns")?.optJSONArray("servers")
        if (srcServers != null) {
            for (i in 0 until srcServers.length()) {
                if (i in badDnsIdx) continue
                srcServers.optJSONObject(i)?.let { servers.put(JSONObject(it.toString())) }
            }
        }
        if (servers.length() == 0) {
            servers.put(JSONObject().put("type", "local").put("tag", "local"))
            servers.put(
                JSONObject().put("type", "tcp").put("tag", "remote")
                    .put("server", "1.1.1.1").put("server_port", 53),
            )
            dns.put("final", "remote")
        } else {
            val survivingTags = (0 until servers.length())
                .mapNotNull { servers.optJSONObject(it)?.optString("tag")?.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val srcFinal = source.optJSONObject("dns")?.optString("final")?.trim().orEmpty()
            if (srcFinal.isNotEmpty() && srcFinal in survivingTags) {
                dns.put("final", srcFinal)
            } else if (survivingTags.isNotEmpty()) {
                dns.put("final", survivingTags.first())
            }
        }
        dns.put("servers", servers)
        root.put("dns", dns)

        root.put("inbounds", JSONArray())
        ConfigInboundCompat.ensureAndroidTun(root)

        val outbounds = JSONArray()
        outbounds.put(direct ?: JSONObject().put("type", "direct").put("tag", "direct"))
        leaves.forEach { outbounds.put(it) }
        val members = JSONArray()
        leafTags.forEach { members.put(it) }
        val selector = JSONObject()
            .put("type", "selector")
            .put("tag", FLOOR_SELECT)
            .put("outbounds", members)
            .put("interrupt_exist_connections", false)
        if (leafTags.size >= 2) {
            val test = JSONObject()
                .put("type", "urltest")
                .put("tag", FLOOR_TEST)
                .put("outbounds", JSONArray(leafTags))
                .put("url", "https://www.gstatic.com/generate_204")
                .put("interval", "5m")
                .put("idle_timeout", "30m")
                .put("interrupt_exist_connections", false)
            members.put(FLOOR_TEST)
            selector.put("default", FLOOR_TEST)
            outbounds.put(selector)
            outbounds.put(test)
        } else {
            outbounds.put(selector)
        }
        root.put("outbounds", outbounds)
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        root.put("route", JSONObject().put("final", FLOOR_SELECT))

        // Strip detours whose target did not survive (named by the
        // caller's removed list, not silently retargeted).
        val cascade = mutableListOf<String>()
        fixups(root, cascade)
        return root
    }
}
