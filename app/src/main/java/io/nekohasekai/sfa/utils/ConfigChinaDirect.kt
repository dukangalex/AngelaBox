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
 * The legacy [apply] does **not** inject a DNS server. Earlier builds added
 * `chainbox-cn-dns` with `detour: "direct"`, which sing-box 1.12+ rejects
 * ("detour to an empty direct outbound makes no sense") and kills reload
 * with RPC EOF. The system-explicit segment (SystemExplicitSegment) instead
 * uses the split builders below plus [SYS_CN_DNS_TAG], whose detour target
 * is the guaranteed direct from [ensureDetourableDirect] — see NF-18.
 */
object ConfigChinaDirect {
    const val CN_DNS_TAG = "chainbox-cn-dns"
    const val DIRECT_FALLBACK_TAG = "chainbox-direct"

    /**
     * App-owned China DNS server (design v2 §2.2 item 4, NF-18). Unlike the
     * legacy [CN_DNS_TAG] — which pointed at an empty direct and crashed
     * 1.12+ kernels — this server is always written together with a detour
     * to the direct outbound [ensureDetourableDirect] guarantees, and the
     * `sys-` prefix exempts it from broken-detour stripping
     * (ConfigCompat.stripBrokenDnsDetours).
     */
    const val SYS_CN_DNS_TAG = "sys-cn-dns"
    const val SYS_CN_DNS_ADDRESS = "223.5.5.5"

    /** Pinned definitions the system-explicit segment self-injects. */
    private val CN_RULE_SET_URLS: List<Pair<String, String>> = listOf(
        "geosite-cn" to (RuleSetPins.geositeBase() + "geosite-cn.srs"),
        "geoip-cn" to (RuleSetPins.geoipBase() + "geoip-cn.srs"),
        "geosite-geolocation-!cn" to (RuleSetPins.geositeBase() + "geosite-geolocation-!cn.srs"),
    )

    /** Tags of the pinned CN rule-sets, in [CN_RULE_SET_URLS] order. */
    val CN_RULE_SET_TAGS: List<String> = CN_RULE_SET_URLS.map { it.first }

    /**
     * CN rule-set tags the startup ladder has already given up on for
     * this run: it dropped every remote set ([dropAll]), or a drop
     * rung is removing the named sets ([needles] are the drop rung's
     * file names or tags, as ConfigDiagnose extracts them). The
     * system-explicit segment runs AFTER the rule-set rungs inside
     * ConfigQuicOverride.apply, so without this it re-injects
     * the very sets the ladder removed and every recovery retry fails
     * the same way (v1.0.133-beta field failure). D9: startup survival
     * outranks the explicit layer — the caller degrades China direct
     * to the hardcoded lists for the suppressed tags instead.
     *
     * Replace-rung needles are deliberately NOT passed here: the
     * replace rung rewrites to the same pinned RuleSetPins URLs the
     * segment injects, so a replaced set keeps working at full
     * strength and there is nothing to suppress.
     */
    fun ladderSuppressedCnRuleSets(
        dropAll: Boolean,
        needles: Collection<String>,
    ): Set<String> {
        if (dropAll) return CN_RULE_SET_TAGS.toSet()
        if (needles.isEmpty()) return emptySet()
        val stems = needles.map {
            it.trim().lowercase().substringAfterLast('/').substringBefore('?').removeSuffix(".srs")
        }.toSet()
        return CN_RULE_SET_TAGS.filter { it in stems }.toSet()
    }

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

    /** [CHINA_DNS_IPS] without their CIDR suffixes, for exact host matches. */
    private val CHINA_DNS_PLAIN_IPS: Set<String> =
        CHINA_DNS_IPS.map { it.substringBefore('/') }.toSet()

    /**
     * True when a dns server entry points at a known China DNS endpoint
     * (AliDNS / DNSPod / Baidu / 114DNS / OneDNS ...), matched on the
     * server address or domain from the lists above — never on the tag,
     * which subscriptions choose freely. China DNS servers are
     * direct-reach bootstrap/mirror resolvers: they only answer
     * correctly when reached directly, so the DNS-protect overwrite
     * must neither promote one to the protected final resolver nor
     * force its detour onto the proxy egress (v1.0.133-beta did both to
     * a subscription's own `dns-cn`, breaking its DNS outright).
     */
    fun isChinaDnsServer(server: JSONObject): Boolean {
        val host = dnsServerHost(server) ?: return false
        if (host in CHINA_DNS_PLAIN_IPS) return true
        if (host in CHINA_DNS_DOMAINS) return true
        return CHINA_DNS_DOMAIN_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    /** Host part of a dns server entry: native `server`, or legacy `address` URL. */
    private fun dnsServerHost(server: JSONObject): String? {
        val raw = server.optString("server").trim()
            .ifBlank { server.optString("address").trim() }
            .lowercase()
        if (raw.isEmpty()) return null
        val authority = if ("://" in raw) {
            raw.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
        } else {
            raw.substringBefore('/')
        }
        if (authority.startsWith("[")) {
            // IPv6 literal, optionally with a port: [2400:3200::1]:53
            return authority.substringAfter('[').substringBefore(']').ifBlank { null }
        }
        val colons = authority.count { it == ':' }
        val host = if (colons == 1) authority.substringBefore(':') else authority
        return host.ifBlank { null }
    }

    fun apply(root: JSONObject) {
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val direct = findOrCreateDirect(outs)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val injected = chinaRouteRules(direct, existingRuleSetTags(route))
        val old = route.optJSONArray("rules") ?: JSONArray()
        // Idempotency guard: callers rebuild from the raw profile on every
        // start today, but a second apply on the SAME root must not stack
        // another copy of the whole rule block.
        if (!startsWithRules(old, injected)) {
            val merged = JSONArray()
            for (i in 0 until injected.length()) merged.put(injected.get(i))
            for (i in 0 until old.length()) merged.put(old.get(i))
            route.put("rules", merged)
        }
        dropLegacyChinaDns(root)
        ConfigCompat.stripBrokenDnsDetours(root)
    }

    /** True when [rules] already begins with exactly the [injected] block. */
    private fun startsWithRules(rules: JSONArray, injected: JSONArray): Boolean {
        if (injected.length() == 0 || rules.length() < injected.length()) return false
        for (i in 0 until injected.length()) {
            val a = rules.optJSONObject(i) ?: return false
            val b = injected.optJSONObject(i) ?: return false
            if (fingerprint(a) != fingerprint(b)) return false
        }
        return true
    }

    /** Key-order-insensitive canonical form, for rule comparison only. */
    internal fun fingerprint(value: Any?): String = when (value) {
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iter = value.keys()
            while (iter.hasNext()) keys += iter.next()
            keys.sorted().joinToString(",", "{", "}") { k -> "$k=${fingerprint(value.opt(k))}" }
        }
        is JSONArray ->
            (0 until value.length()).joinToString(",", "[", "]") { fingerprint(value.opt(it)) }
        else -> value.toString()
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
            val token = tag.trim().lowercase().substringAfterLast('/')
            token == "geoip-cn" || token == "geoip_cn"
        }
        val geosite = existingRuleSets.filter { tag ->
            val token = tag.trim().lowercase().substringAfterLast('/')
            token == "geosite-cn" || token == "geosite_cn" ||
                token == "geosite-geolocation-cn" || token == "cn"
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

    // ---- System-explicit split blocks (design v2 §2.2 items 4/5/6) ----
    // The pipeline no longer calls the monolithic [apply]: each split switch
    // (chinaIpDomainDirect / chinaPublicDnsDirect / lanDirect) registers its
    // own block with SystemExplicitSegment, which prepends them as one
    // ordered block. [apply] stays as the legacy all-on composition.

    /** LAN direct block (order table #7). */
    fun lanRouteRules(directTag: String): JSONArray {
        val rules = JSONArray()
        rules.put(
            JSONObject()
                .put("ip_is_private", true)
                .put("outbound", directTag),
        )
        // ip_is_private's exact range table lives in sagernet/sing (not
        // vendored in the pinned kernel tree), so CGNAT and the mDNS
        // multicast address are written out explicitly instead of assumed.
        rules.put(
            JSONObject()
                .put("ip_cidr", JSONArray().put("100.64.0.0/10").put("224.0.0.251/32"))
                .put("outbound", directTag),
        )
        rules.put(
            JSONObject()
                .put("domain_suffix", toArray(LAN_DOMAIN_SUFFIXES))
                .put("domain", JSONArray().put("localhost"))
                .put("outbound", directTag),
        )
        return rules
    }

    /** China public DNS direct block (order table #8). */
    fun publicDnsRouteRules(directTag: String): JSONArray {
        val rules = JSONArray()
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
        return rules
    }

    /** China IP/domain direct block (order table #9); needs [ensureCnRuleSets]. */
    fun chinaIpDomainRouteRules(
        directTag: String,
        ruleSetTags: List<String> = listOf("geosite-cn", "geoip-cn"),
    ): JSONArray {
        val rules = JSONArray()
        if (ruleSetTags.isNotEmpty()) {
            rules.put(
                JSONObject()
                    .put("rule_set", toArray(ruleSetTags))
                    .put("outbound", directTag),
            )
        }
        // The hardcoded CN suffix list stays as the domain-side fallback:
        // if the geosite-cn download fails at startup, these still catch
        // the major CN domains (the set itself is the recovery ladder's
        // problem, not this block's).
        rules.put(
            JSONObject()
                .put("domain_suffix", ConfigNormalize.cnDomainSuffixArray())
                .put("outbound", directTag),
        )
        return rules
    }

    /**
     * DNS mirror rules for the China item (official "Without DNS leaks"
     * shape): CN domains resolve through [SYS_CN_DNS_TAG] so their CDN
     * answers are domestic, everything else keeps the protected final.
     * Woven ahead of the fakeip rule by ConfigQuicOverride.ensureFakeIpDns —
     * behind it, A/AAAA queries would be answered with fake addresses first
     * and the mirror would never fire.
     *
     * [available] is the set of CN rule-set tags actually defined in
     * this run's config: a rule naming a set the recovery ladder
     * dropped is never emitted (the reference would dangle, and the
     * logical rule needs both of its sets to mean anything).
     */
    fun cnDnsMirrorRules(available: Set<String> = CN_RULE_SET_TAGS.toSet()): JSONArray {
        val rules = JSONArray()
        if ("geosite-cn" in available) {
            rules.put(
                JSONObject()
                    .put("rule_set", "geosite-cn")
                    .put("server", SYS_CN_DNS_TAG),
            )
        }
        if ("geosite-geolocation-!cn" in available && "geoip-cn" in available) {
            rules.put(
                JSONObject()
                    .put("type", "logical")
                    .put("mode", "and")
                    .put(
                        "rules",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("rule_set", "geosite-geolocation-!cn")
                                    .put("invert", true),
                            )
                            .put(JSONObject().put("rule_set", "geoip-cn")),
                    )
                    .put("server", SYS_CN_DNS_TAG),
            )
        }
        return rules
    }

    /**
     * Self-inject the pinned CN rule-set definitions (runtime config only;
     * the stored profile is untouched). A same-tag entry the subscription
     * already carries is replaced by the app pinned definition — the design
     * makes the pinned definition win so heal pins and this injection can
     * never disagree. Returns the tags whose definition was replaced, for
     * the caller's OverrideNotice.
     *
     * Tags in [skip] are neither injected nor rewritten: the startup
     * ladder has given up on them for this run (see
     * [ladderSuppressedCnRuleSets]) and re-adding them would resurrect
     * the exact download failure the ladder is recovering from.
     *
     * [seedPath] resolves the bundled seed's installed path for a tag
     * (137a, RuleSetSeeds); when it yields a path, the definition
     * carries it as `initial_path` — the kernel's cold-start floor,
     * warn-only if unreadable. The definition stays type:remote with
     * the pinned URL, so updates keep flowing. Stamping is silent: a
     * seed is an app-local concern and never joins the [replaced]
     * notice, which is about the pinned definition itself.
     */
    fun ensureCnRuleSets(
        root: JSONObject,
        skip: Set<String> = emptySet(),
        seedPath: (String) -> String? = { null },
    ): List<String> {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        val replaced = mutableListOf<String>()
        for ((tag, url) in CN_RULE_SET_URLS) {
            if (tag in skip) continue
            var existing: JSONObject? = null
            for (i in 0 until sets.length()) {
                val item = sets.optJSONObject(i) ?: continue
                if (item.optString("tag") == tag) {
                    existing = item
                    break
                }
            }
            val definition: JSONObject
            if (existing == null) {
                definition = JSONObject()
                    .put("tag", tag)
                    .put("type", "remote")
                    .put("format", "binary")
                    .put("url", url)
                sets.put(definition)
            } else {
                definition = existing
                if (!existing.optString("type").equals("remote", true) ||
                    existing.optString("format") != "binary" ||
                    existing.optString("url") != url
                ) {
                    existing.put("type", "remote")
                    existing.put("format", "binary")
                    existing.put("url", url)
                    existing.remove("path")
                    existing.remove("download_url")
                    existing.remove("download_detour")
                    replaced += tag
                }
            }
            val seed = seedPath(tag)
            if (seed != null && definition.optString("initial_path") != seed) {
                definition.put("initial_path", seed)
            }
        }
        return replaced
    }

    /**
     * Inject (or align) the app-owned China DNS server. Its detour always
     * points at [directTag] from [ensureDetourableDirect], so the pair can
     * never reproduce the legacy empty-direct crash.
     */
    fun ensureSysCnDns(root: JSONObject, directTag: String) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (server.optString("tag") != SYS_CN_DNS_TAG) continue
            server.put("type", "udp")
            server.put("server", SYS_CN_DNS_ADDRESS)
            server.put("detour", directTag)
            return
        }
        servers.put(
            JSONObject()
                .put("type", "udp")
                .put("tag", SYS_CN_DNS_TAG)
                .put("server", SYS_CN_DNS_ADDRESS)
                .put("detour", directTag),
        )
    }

    /**
     * The direct outbound DNS detours may point at: [findOrCreateDirect]
     * plus the guarantee that it is not an *empty* direct. The kernel
     * rejects a detour to an empty direct (its DialerOptions are all
     * default); a `domain_resolver` makes the options non-default in both
     * the kernel's DeepEqual check and keeps the intent explicit. The
     * resolver named is the route default resolver when it points at a
     * defined DNS server (the same fallback the chain compiler uses),
     * otherwise a local server — created with the exact shape
     * ConfigIngest.ensureLocalDns writes.
     */
    fun ensureDetourableDirect(root: JSONObject): String {
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val tag = findOrCreateDirect(outs)
        val direct = find(outs, tag) ?: return tag
        if (!direct.has("domain_resolver") && ConfigCompat.isEmptyDirect(direct)) {
            direct.put("domain_resolver", directResolverTag(root))
        }
        return tag
    }

    private fun directResolverTag(root: JSONObject): String {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        val named = when (val raw = root.optJSONObject("route")?.opt("default_domain_resolver")) {
            is String -> raw.trim()
            is JSONObject -> raw.optString("server").trim()
            else -> ""
        }
        if (named.isNotEmpty() && findServer(servers, named) != null) return named
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val tag = server.optString("tag").trim()
            if (tag.isNotEmpty() && server.optString("type").equals("local", true)) return tag
        }
        if (findServer(servers, "local") == null) {
            servers.put(JSONObject().put("type", "local").put("tag", "local"))
        }
        return "local"
    }

    private fun findServer(servers: JSONArray, tag: String): JSONObject? {
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (server.optString("tag") == tag) return server
        }
        return null
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
