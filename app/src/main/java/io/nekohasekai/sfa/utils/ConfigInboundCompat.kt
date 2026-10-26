package io.nekohasekai.sfa.utils

import java.net.InetAddress
import java.net.URI
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
        var changed = applyKernelCompat(root)
        if (applyStartupPolicy(root)) changed = true
        return changed
    }

    /**
     * Fields the current kernel rejects or that 404 at startup.
     * Does not add routing the file did not already have.
     *
     * When [ruleSetNotes] is given, every rule-set URL swap / drop is
     * appended there by name — a swapped set changes routing semantics
     * and must be visible in the caller's user-facing notes.
     */
    fun applyKernelCompat(root: JSONObject, ruleSetNotes: MutableList<String>? = null): Boolean {
        var changed = false
        if (migrateLegacyInbounds(root)) changed = true
        if (stripDeprecatedTunStack(root)) changed = true
        if (stripSniffOverrideDestination(root)) changed = true
        if (healDirectDestinationOverride(root)) changed = true
        if (migrateSpecialOutbounds(root)) changed = true
        val urlRewrite = rewriteRuleSetUrlsWithReport(root)
        if (urlRewrite.changed) changed = true
        if (ruleSetNotes != null) {
            val heal = healRemoteRuleSetsWithReport(root)
            if (heal.changed) changed = true
            // A set the SSRF guard stripped (its address was not a
            // public HTTPS URL) gets a note naming that cause; the
            // generic lines below describe sets the official repos
            // themselves superseded or took offline.
            heal.swapped.forEach {
                ruleSetNotes += if (it in urlRewrite.guardStrippedTags) {
                    "规则集「$it」的原地址不是公共 HTTPS 地址，未被采用，已换成官方版"
                } else {
                    "规则集「$it」的地址已换成官方版"
                }
            }
            heal.dropped.forEach {
                ruleSetNotes += if (it in urlRewrite.guardStrippedTags) {
                    "规则集「$it」的原地址不是公共 HTTPS 地址，未被采用，已去掉"
                } else {
                    "规则集「$it」官方已下线，已去掉"
                }
            }
        } else if (healRemoteRuleSets(root)) {
            changed = true
        }
        if (sanitizeClashDownloadUrls(root)) changed = true
        if (healDownloadClients(root)) changed = true
        if (healMissingOutboundRefs(root)) changed = true
        if (bindLoopbackOnly(root)) changed = true
        if (ruleSetNotes != null) {
            val sshStrip = stripTailscaleSshServerWithReport(root)
            if (sshStrip.changed) changed = true
            sshStrip.enabledTags.forEach {
                ruleSetNotes += "Tailscale 端点「$it」带有 ssh_server（远程 SSH 的登录策略由其 tailnet 控制端决定），已移除"
            }
        } else if (stripTailscaleSshServer(root)) {
            changed = true
        }
        return changed
    }

    /**
     * Official remote rule-sets are only kept on disk when
     * `experimental.cache_file` is enabled, and a selector's choice is
     * remembered the same way. `store_dns` is the 1.14 replacement for the
     * removed reject-DNS cache. `flush_interval` is 1.15: without it a
     * killed process drops the buffer and the next start downloads again.
     * Missing fields only. An explicit `enabled: false` is left alone.
     * Runtime overlay; do not write this back into the saved profile.
     */
    internal fun ensureCacheFile(root: JSONObject, cacheId: String): Boolean {
        val experimental = root.optJSONObject("experimental")
            ?: JSONObject().also { root.put("experimental", it) }
        val cache = experimental.optJSONObject("cache_file")
            ?: JSONObject().also { experimental.put("cache_file", it) }
        if (cache.has("enabled") && !cache.optBoolean("enabled")) return false
        var changed = false
        if (!cache.optBoolean("enabled", false)) {
            cache.put("enabled", true)
            changed = true
        }
        if (cache.optString("cache_id").isBlank() && cacheId.isNotBlank()) {
            cache.put("cache_id", "profile-$cacheId")
            changed = true
        }
        if (!cache.has("store_dns")) {
            cache.put("store_dns", true)
            changed = true
        }
        if (!cache.has("store_fakeip")) {
            cache.put("store_fakeip", true)
            changed = true
        }
        if (cache.optString("flush_interval").isBlank()) {
            cache.put("flush_interval", "1m")
            changed = true
        }
        return changed
    }

    /**
     * A direct rule-set download has no read timeout in this kernel. A
     * connect timeout at least stops a blackholed TCP handshake from sitting
     * in Start() until the process is killed. Clients that already name a
     * detour or a timeout are left alone. Runtime overlay only.
     */
    internal fun ensureDirectHttpTimeout(root: JSONObject) {
        val clients = root.optJSONArray("http_clients") ?: return
        for (i in 0 until clients.length()) {
            val client = clients.optJSONObject(i) ?: continue
            if (client.optString("detour").isNotBlank()) continue
            if (client.optString("connect_timeout").isNotBlank()) continue
            client.put("connect_timeout", "10s")
        }
    }

    /**
     * Startup routing for a config this app generated from Clash or a share
     * link. A sing-box file is left alone: official clients do not insert
     * sniff, DNS hijack, or a fake-ip reject into a document that already
     * has its own route.
     */
    fun applyStartupPolicy(root: JSONObject): Boolean {
        var changed = false
        if (ensureHijackDns(root)) changed = true
        if (rejectStaleFakeIp(root)) changed = true
        if (ensureSniff(root)) changed = true
        if (ConfigIngest.ensureEchQueryRoute(root)) changed = true
        if (ensureDefaultDomainResolver(root)) changed = true
        if (healDanglingDomainResolvers(root)) changed = true
        return changed
    }

    /**
     * G-11: products this app generated (Clash / share-link conversions)
     * carried no route.default_domain_resolver, so every start popped the
     * kernel's Deprecated Warning (the field is required since sing-box
     * 1.12 and the implicit fallback disappears in 1.14). Fill it with
     * what the kernel would have fallen back to, so DNS behaviour does
     * not change: the DNS final server when one is named, else a local
     * (system) resolver — creating that server when the product has no
     * DNS section at all, which is the converted shape. An existing
     * value, string or object form, is never overwritten. Only runs via
     * applyStartupPolicy, so native sing-box files stay untouched.
     */
    internal fun ensureDefaultDomainResolver(root: JSONObject): Boolean {
        val route = root.optJSONObject("route") ?: return false
        when (val existing = route.opt("default_domain_resolver")) {
            is String -> if (existing.isNotBlank()) return false
            is JSONObject -> if (existing.optString("server").isNotBlank()) return false
        }
        val dns = root.optJSONObject("dns")
        val servers = dns?.optJSONArray("servers")
        val serverTags = mutableSetOf<String>()
        var localTag: String? = null
        if (servers != null) {
            for (i in 0 until servers.length()) {
                val server = servers.optJSONObject(i) ?: continue
                val tag = server.optString("tag").trim()
                if (tag.isEmpty()) continue
                serverTags += tag
                if (localTag == null && server.optString("type").equals("local", true)) {
                    localTag = tag
                }
            }
        }
        val finalTag = dns?.optString("final")?.trim().orEmpty()
        val pick = when {
            finalTag.isNotEmpty() && finalTag in serverTags -> finalTag
            localTag != null -> localTag
            else -> ensureLocalDnsServer(root)
        }
        route.put("default_domain_resolver", pick)
        return true
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
     * sing-box 1.15 uses sing-tun's own TCP/IP stack. The inbound `stack`
     * field is deprecated and will be removed in 1.17. Strip it silently:
     * 1.14 then uses the default mixed stack; 1.15 uses the new stack.
     * Do not surface this as a dashboard 「已修正」 — almost every TUN
     * profile still has the field.
     */
    internal fun stripDeprecatedTunStack(root: JSONObject): Boolean {
        val inbounds = root.optJSONArray("inbounds") ?: return false
        var changed = false
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (!ib.optString("type").equals("tun", ignoreCase = true)) continue
            if (ib.has("stack")) {
                ib.remove("stack")
                changed = true
            }
        }
        return changed
    }

    /**
     * Android VPN needs one TUN. Runtime only: normalize a bad tun (the
     * kernel returns invalid argument for inet6 / stack / huge MTU) and
     * drop only the mixed inbound that would bind the app's own
     * 127.0.0.1:17890 listener twice.
     */
    internal fun ensureAndroidTun(root: JSONObject): Boolean {
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
        var changed = false
        var keptTun = false
        val keep = JSONArray()
        val usedTags = linkedSetOf<String>()
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            val type = ib.optString("type").trim().lowercase()
            if (type == "mixed" && isLoopbackListen(ib)) {
                changed = true
                continue
            }
            if (type == "tun") {
                if (keptTun) {
                    changed = true
                    continue
                }
                if (normalizeTun(ib)) changed = true
                keptTun = true
            } else if (bindListenToLoopback(ib)) {
                changed = true
            }
            val tag = ib.optString("tag").trim()
            if (tag.isNotEmpty()) usedTags += tag
            keep.put(ib)
        }
        if (!keptTun) {
            var tag = "tun-in"
            if (tag in usedTags) {
                var n = 2
                while ("tun-in-$n" in usedTags) n++
                tag = "tun-in-$n"
            }
            keep.put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", tag)
                    .put("address", JSONArray().put(TUN_ADDRESS))
                    .put("auto_route", true)
                    .put("dns_mode", "hijack")
                    .put("mtu", 1500),
            )
            changed = true
        }
        if (changed) {
            while (inbounds.length() > 0) inbounds.remove(0)
            for (i in 0 until keep.length()) inbounds.put(keep.get(i))
        }
        return changed
    }

    /**
     * A config that arrives from a subscription must not turn the phone into an
     * unauthenticated proxy for everyone on the same network. sing-box already
     * defaults to loopback when `listen` is absent, so only an explicit
     * non-loopback address is rewritten; a unix-socket path is left alone.
     *
     * Same reasoning as [bindLoopbackOnly] for the Clash API: the kernel binds
     * what the JSON says, and nothing in the import path asks the user whether
     * they meant to share their connection with the LAN.
     */
    private fun bindListenToLoopback(ib: JSONObject): Boolean {
        if (!ib.has("listen")) return false
        val current = ib.optString("listen").trim()
        if (current.isEmpty() || current.startsWith("/") || isLoopbackHost(current)) return false
        ib.put("listen", "127.0.0.1")
        return true
    }

    /**
     * Same policy as [bindListenToLoopback] for every non-tun inbound, with the
     * rebound entries named so the caller can tell the user. Returns
     * "<listen>:<port>" for each inbound that was rewritten.
     */
    fun rebindInboundsToLoopback(root: JSONObject): List<String> {
        val inbounds = root.optJSONArray("inbounds") ?: return emptyList()
        val rebound = mutableListOf<String>()
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("type").trim().lowercase() == "tun") continue
            val before = ib.optString("listen").trim()
            if (!bindListenToLoopback(ib)) continue
            val port = ib.optInt("listen_port", 0)
            rebound += if (port > 0) "$before:$port" else before
        }
        return rebound
    }

    private fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().trimStart('[').trimEnd(']').lowercase()
        return h == "127.0.0.1" || h == "localhost" || h == "::1" || h.startsWith("127.")
    }

    private fun isLoopbackListen(ib: JSONObject): Boolean {
        // Only a mixed inbound that would actually bind the app's own
        // 127.0.0.1:17890 listener collides, so only that one is dropped.
        // Any other inbound is kept but rebound to loopback by
        // [bindListenToLoopback]; the user never asked for LAN sharing, so
        // there is nothing to preserve by leaving it on 0.0.0.0.
        if (ib.optInt("listen_port") != 17890) return false
        val listen = ib.optString("listen").trim().lowercase()
        return listen.isEmpty() || listen == "127.0.0.1" || listen == "localhost" ||
            listen == "::1" || listen == "[::1]" || listen == "0.0.0.0" || listen == "::"
    }

    private fun normalizeTun(ib: JSONObject): Boolean {
        var changed = false
        for (key in listOf("stack", "gso", "inet6_address", "inet4_address", "endpoint_independent_nat")) {
            if (ib.has(key)) {
                ib.remove(key)
                changed = true
            }
        }
        val v4 = v4Cidrs(ib.opt("address"))
        val wanted = if (v4.isEmpty()) JSONArray().put(TUN_ADDRESS) else {
            val arr = JSONArray()
            v4.forEach { arr.put(it) }
            arr
        }
        if (!sameStringArray(ib.opt("address"), wanted)) {
            ib.put("address", wanted)
            changed = true
        }
        val mtu = when (val raw = ib.opt("mtu")) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }
        // Android's kernel default is 9000 when mtu is omitted. That burns
        // memory and some phones reject it. Pin absent/oversized values to
        // 1500, but respect an explicit smaller MTU (some networks need it).
        if (mtu <= 0 || mtu > 1500) {
            ib.put("mtu", 1500)
            changed = true
        }
        if (!ib.optBoolean("auto_route")) {
            ib.put("auto_route", true)
            changed = true
        }
        // Official default. disabled / native leave port 53 on the system resolver.
        if (!ib.optString("dns_mode").equals("hijack", true)) {
            ib.put("dns_mode", "hijack")
            changed = true
        }
        return changed
    }

    private fun v4Cidrs(raw: Any?): List<String> {
        val values = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> listOf(raw)
            else -> emptyList()
        }
        return values.map { it.trim() }.filter { isV4Cidr(it) }
    }

    private fun isV4Cidr(text: String): Boolean {
        val slash = text.indexOf('/')
        if (slash <= 0) return false
        val prefix = text.substring(slash + 1).toIntOrNull() ?: return false
        if (prefix !in 8..30) return false
        val parts = text.substring(0, slash).split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return@all false
            n in 0..255 && part == n.toString()
        }
    }

    private fun sameStringArray(raw: Any?, wanted: JSONArray): Boolean {
        if (raw !is JSONArray || raw.length() != wanted.length()) return false
        for (i in 0 until wanted.length()) {
            if (raw.optString(i) != wanted.optString(i)) return false
        }
        return true
    }

    private const val TUN_ADDRESS = "172.19.0.1/30"

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

    /**
     * sing-box 1.13 removed direct outbound override_address/override_port.
     * Scripts that still emit them fail-close the kernel. Strip always so
     * start succeeds; blackhole-like tags become a local socks sink so
     * ads/remote selectors keep a selectable reject member.
     */
    internal fun healDirectDestinationOverride(root: JSONObject): Boolean {
        val outs = root.optJSONArray("outbounds") ?: return false
        var changed = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (!o.optString("type").equals("direct", true)) continue
            val addr = o.optString("override_address").trim()
            val port = when (val raw = o.opt("override_port")) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull() ?: 0
                else -> 0
            }
            if (addr.isEmpty() && port == 0) continue
            o.remove("override_address")
            o.remove("override_port")
            changed = true
            if (looksLikeBlackhole(o.optString("tag"), addr, port)) {
                o.put("type", "socks")
                o.put("server", "127.0.0.1")
                o.put("server_port", 9)
            }
        }
        return changed
    }

    private fun looksLikeBlackhole(tag: String, addr: String, port: Int): Boolean {
        val t = tag.trim().lowercase()
        if (t.contains("reject") || t.contains("block") || t.contains("blackhole")) return true
        val a = addr.lowercase()
        return a == "240.0.0.1" || a == "0.0.0.0" || a == "127.0.0.1" || a == "::1"
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

    /** What [rewriteRuleSetUrls] did, for callers with a notes channel. */
    internal data class RuleSetUrlRewrite(
        val changed: Boolean,
        /**
         * Tags whose only address the SSRF guard removed (it was not
         * a public HTTPS URL), leaving the set address-less. Callers
         * naming a later heal swap/drop for one of these tags must
         * say the original address was not adopted and why — the
         * generic "official successor / taken offline" wording would
         * misdescribe a set the guard, not the upstream repo, removed.
         */
        val guardStrippedTags: List<String>,
    )

    internal fun rewriteRuleSetUrls(root: JSONObject): Boolean =
        rewriteRuleSetUrlsWithReport(root).changed

    internal fun rewriteRuleSetUrlsWithReport(root: JSONObject): RuleSetUrlRewrite {
        val route = root.optJSONObject("route") ?: return RuleSetUrlRewrite(false, emptyList())
        val sets = route.optJSONArray("rule_set") ?: return RuleSetUrlRewrite(false, emptyList())
        var changed = false
        val stripped = mutableListOf<String>()
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val hadAddress = listOf("url", "download_url")
                .any { item.optString(it).isNotBlank() }
            for (key in listOf("url", "download_url")) {
                val current = item.optString(key).trim()
                if (current.isEmpty()) continue
                val rewritten = rewriteGithubRawUrl(current)
                if (!RemoteUrlGuard.isPublicHttpsUrl(rewritten)) {
                    item.remove(key)
                    changed = true
                    continue
                }
                if (ruleSetUrlResolvesBlocked(rewritten)) {
                    item.remove(key)
                    changed = true
                    continue
                }
                if (rewritten != current) {
                    item.put(key, rewritten)
                    changed = true
                }
            }
            if (hadAddress && listOf("url", "download_url")
                    .all { item.optString(it).isBlank() }
            ) {
                val tag = item.optString("tag").trim()
                if (tag.isNotEmpty() && tag !in stripped) stripped += tag
            }
        }
        return RuleSetUrlRewrite(changed, stripped)
    }

    /**
     * Resolution half of the rule-set guard above: [RemoteUrlGuard.isPublicHttpsUrl]
     * never resolves, so a public-looking hostname whose answers are private
     * / loopback would still be handed to the kernel, which fetches it with
     * no address policy of its own. Resolve the host here and classify every
     * answer with the same policy — one blocked answer strips the URL just
     * like a syntactic failure (and lands the tag in the same guard-stripped
     * report). Literal IPs are already classified by the syntactic check.
     * A host that does not resolve at all keeps its URL: this runs at
     * startup too, and an offline start must not strip sets it cannot judge.
     */
    private fun ruleSetUrlResolvesBlocked(url: String): Boolean {
        val host = try {
            URI(url).host
        } catch (_: Exception) {
            null
        }?.trim().orEmpty()
        if (host.isEmpty() || RemoteUrlGuard.isLiteralIp(host)) return false
        val addresses = try {
            InetAddress.getAllByName(host)?.toList().orEmpty()
        } catch (_: Exception) {
            return false
        }
        return addresses.any { !RemoteUrlGuard.isAddressAllowed(it, RemoteUrlGuard.Kind.SCRIPT) }
    }

    internal fun sanitizeClashDownloadUrls(root: JSONObject): Boolean {
        val clash = root.optJSONObject("experimental")?.optJSONObject("clash_api") ?: return false
        var changed = false
        for (key in listOf("external_ui_download_url")) {
            val url = clash.optString(key).trim()
            if (url.isEmpty()) continue
            if (!RemoteUrlGuard.isPublicHttpsUrl(url)) {
                clash.remove(key)
                changed = true
            }
        }
        val ui = clash.optString("external_ui").trim()
        if (ui.contains("://") && !RemoteUrlGuard.isPublicHttpsUrl(ui)) {
            clash.remove("external_ui")
            changed = true
        }
        return changed
    }

    internal fun rewriteGithubRawUrl(url: String): String {
        val trimmed = url.trim()
        val jsd = JSDELIVR.matchEntire(trimmed)
        if (jsd != null) {
            // Keep a mirror the caller already chose, but pin the ref: the
            // mutable branch is the supply-chain hole, not the CDN hostname.
            val mirror =
                if (jsd.groupValues[1].equals(JSDELIVR_HOST, true)) {
                    trimmed
                } else {
                    "https://$JSDELIVR_HOST/gh/${jsd.groupValues[2]}"
                }
            return RuleSetPins.pin(mirror)
        }
        val raw = RAW_GITHUB.matchEntire(trimmed)
        if (raw != null) {
            val owner = raw.groupValues[1]
            val repo = raw.groupValues[2]
            val ref = raw.groupValues[3]
            val path = raw.groupValues[4]
            return RuleSetPins.pin("https://$JSDELIVR_HOST/gh/$owner/$repo@$ref/$path")
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
                return RuleSetPins.pin("https://$JSDELIVR_HOST/gh/$owner/$repo@$ref/$path")
            }
        }
        return trimmed
    }

    /** What [healRemoteRuleSets] did, for callers with a notes channel. */
    internal data class RuleSetHeal(
        val changed: Boolean,
        /** Tags whose URL was rewritten to (or filled from) the official file. */
        val swapped: List<String>,
        /** Tags dropped because the official repo no longer ships the file. */
        val dropped: List<String>,
    )

    /**
     * Remote rule-sets whose address provably cannot load are pointed at
     * the official SagerNet successor, keeping the original tag so route
     * rules still match. "Provably" is narrow (AB-025): the URL itself
     * points at the official SagerNet repos and names a file verified
     * absent there, or the URL is empty and the tag names an official
     * file of the same kind. Third-party URLs are the provider's own
     * hosting choice and stay verbatim — a filename that merely looks unofficial
     * (MetaCubeX-style youtube.srs, telegram-ip.srs) is not evidence.
     * A set is dropped only when the official repos have no successor
     * for it at all and the URL points at those repos. Every swap/drop
     * is recorded in the report variant: replacing a provider's
     * self-hosted set changes routing semantics, so it must never
     * happen silently — callers surface the names in their notes.
     */
    internal fun healRemoteRuleSets(root: JSONObject): Boolean =
        healRemoteRuleSetsWithReport(root).changed

    internal fun healRemoteRuleSetsWithReport(root: JSONObject): RuleSetHeal {
        val none = RuleSetHeal(false, emptyList(), emptyList())
        val route = root.optJSONObject("route") ?: return none
        val sets = route.optJSONArray("rule_set") ?: return none
        val dropTags = mutableSetOf<String>()
        val swapped = mutableListOf<String>()
        val keep = JSONArray()
        var changed = false
        for (i in 0 until sets.length()) {
            val rawItem = sets.get(i)
            val item = rawItem as? JSONObject
            if (item == null) {
                // Not an object: not ours to judge — keep it verbatim
                // instead of silently dropping it from the array.
                keep.put(rawItem)
                continue
            }
            val urlKey = if (item.optString("url").isNotBlank()) "url" else "download_url"
            val url = item.optString(urlKey).trim()
            val file = url.substringAfterLast('/').substringBefore('?').lowercase()
            val tag = item.optString("tag").trim()
            val remote = item.optString("type").equals("remote", true) || url.startsWith("http")
            if (!remote) {
                keep.put(item)
                continue
            }
            if (url.isEmpty()) {
                // Fill from the tag only with a same-kind official
                // file (AB-025 exception revoked): an IP-list tag
                // whose only official namesake is a domain list gets
                // no fill at all and drops below with its name
                // recorded — a cross-kind fill would silently change
                // what the set's rules match, exactly what the swap
                // path below refuses to do.
                val officialFromTag = officialRuleSetSwapUrl(tag)
                if (officialFromTag.isNotEmpty()) {
                    item.put("url", officialFromTag)
                    keep.put(item)
                    swapped += tag.ifBlank { file }
                    changed = true
                } else {
                    if (tag.isNotEmpty()) dropTags.add(tag)
                    changed = true
                }
                continue
            }
            // AB-025: a non-empty URL is the provider's own choice and
            // stays verbatim unless there is hard evidence against the
            // exact address: it points at the official SagerNet repos
            // AND names a file provably absent there (see
            // isProvenMissingAtOfficial — the MISSING set plus the
            // repos' verified file-naming structure). Even then the
            // replacement must be the same kind of set — an IP list
            // is never swapped for a domain list (see
            // officialRuleSetSwapUrl).
            val official = officialRuleSetSwapUrl(file)
            when {
                isProvenMissingAtOfficial(url, file) &&
                    official.isNotEmpty() && url != official -> {
                    item.put(urlKey, official)
                    keep.put(item)
                    swapped += tag.ifBlank { file }
                    changed = true
                }
                // No official successor exists at all (geoip-private,
                // geoip-fastly, geoip-cloudfront — sing-geoip ships
                // country codes only, verified against its listing).
                // Drop only the official-repo form, whose 404 is
                // proven; the same file name on a third-party host
                // may well exist there and stays verbatim.
                isOfficialRuleSetUrl(url) && file in UNREPLACEABLE_RULESET_FILES -> {
                    if (tag.isNotEmpty()) dropTags.add(tag)
                    changed = true
                }
                else -> keep.put(item)
            }
        }
        if (!changed && dropTags.isEmpty()) return none
        replaceArray(sets, keep)
        if (dropTags.isNotEmpty()) {
            stripDroppedRuleSets(route.optJSONArray("rules"), dropTags)
            stripDroppedRuleSets(root.optJSONObject("dns")?.optJSONArray("rules"), dropTags)
        }
        return RuleSetHeal(true, swapped, dropTags.toList())
    }

    /** @deprecated Name kept so older tests still compile; delegates to [healRemoteRuleSets]. */
    internal fun dropMissingRemoteRuleSets(root: JSONObject): Boolean = healRemoteRuleSets(root)

    /**
     * After a kernel 404: rewrite matching remote rule-sets to the official
     * testingcf URL. Tag stays so existing route/DNS rules keep working.
     * Drop only when there is no known replacement. Matching is exact on
     * tag/filename — never a substring of the URL (that used to wipe every
     * GitHub-hosted set when the needle was "github" or "1").
     */
    internal fun replaceRemoteRuleSetsMatching(root: JSONObject, needles: Collection<String>): Boolean {
        val want = needles.map { ruleSetStem(it) }.filter { ConfigDiagnose.isPlausibleRuleSetName(it) }
        if (want.isEmpty()) return false
        val route = root.optJSONObject("route") ?: return false
        val sets = route.optJSONArray("rule_set") ?: return false
        val dropTags = mutableSetOf<String>()
        val keep = JSONArray()
        var changed = false
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val urlKey = if (item.optString("url").isNotBlank()) "url" else "download_url"
            val url = item.optString(urlKey).trim()
            val file = url.substringAfterLast('/').substringBefore('?').lowercase()
            val tag = item.optString("tag").trim()
            val hit = want.any { needle -> ruleSetMatchesNeedle(tag, file, needle) }
            if (!hit) {
                keep.put(item)
                continue
            }
            val candidate = officialRuleSetUrl(file).ifBlank {
                officialRuleSetUrl(tag).ifBlank {
                    want.firstOrNull { ruleSetMatchesNeedle(tag, file, it) }?.let { officialRuleSetUrl(it) }.orEmpty()
                }
            }
            // AB-025: even on this kernel-404 rescue path the official
            // successor must be the same kind of set as the one that
            // failed; a cross-kind candidate counts as "no successor"
            // and the set is dropped below instead of silently
            // changing what its rules match.
            val official = acceptSameKind(candidate, file.ifBlank { tag })
            if (official.isNotEmpty() && official != url) {
                item.put(urlKey, official)
                keep.put(item)
                changed = true
            } else if (official.isNotEmpty()) {
                keep.put(item)
            } else {
                if (tag.isNotEmpty()) dropTags.add(tag)
                changed = true
            }
        }
        if (!changed && dropTags.isEmpty()) return false
        replaceArray(sets, keep)
        if (dropTags.isNotEmpty()) {
            stripDroppedRuleSets(route.optJSONArray("rules"), dropTags)
            stripDroppedRuleSets(root.optJSONObject("dns")?.optJSONArray("rules"), dropTags)
        }
        return true
    }

    /** Tags of remote rule-sets in an already-built runtime config. */
    internal fun remoteRuleSetTags(content: String): List<String> {
        if (content.isBlank() || content.length > ConfigCompat.MAX_CONFIG_CHARS) return emptyList()
        return try {
            val sets = JSONObject(content).optJSONObject("route")?.optJSONArray("rule_set")
                ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until sets.length()) {
                val tag = sets.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
                if (ConfigDiagnose.isPlausibleRuleSetName(tag)) out += tag
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Last resort when official rule-set URLs still cannot be downloaded.
     * Removes every remote set and the rules that name them. Local sets,
     * nodes, groups, and rules that do not use those tags stay.
     */
    internal fun dropAllRemoteRuleSets(root: JSONObject): Boolean {
        val route = root.optJSONObject("route") ?: return false
        val sets = route.optJSONArray("rule_set") ?: return false
        val dropTags = mutableSetOf<String>()
        val keep = JSONArray()
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val url = item.optString("url").ifBlank { item.optString("download_url") }.trim()
            val tag = item.optString("tag").trim()
            val remote = item.optString("type").equals("remote", true) || url.startsWith("http")
            if (remote) {
                if (tag.isNotEmpty()) dropTags.add(tag)
                continue
            }
            keep.put(item)
        }
        if (dropTags.isEmpty() && keep.length() == sets.length()) return false
        replaceArray(sets, keep)
        if (dropTags.isNotEmpty()) {
            stripDroppedRuleSets(route.optJSONArray("rules"), dropTags)
            stripDroppedRuleSets(root.optJSONObject("dns")?.optJSONArray("rules"), dropTags)
        }
        return true
    }

    /** Last-resort exact drop used by tests. Same matching rules as replace. */
    internal fun dropRemoteRuleSetsMatching(root: JSONObject, needles: Collection<String>): Boolean {
        val want = needles.map { ruleSetStem(it) }.filter { ConfigDiagnose.isPlausibleRuleSetName(it) }
        if (want.isEmpty()) return false
        val route = root.optJSONObject("route") ?: return false
        val sets = route.optJSONArray("rule_set") ?: return false
        val dropTags = mutableSetOf<String>()
        val keep = JSONArray()
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val url = item.optString("url").ifBlank { item.optString("download_url") }.trim()
            val file = url.substringAfterLast('/').substringBefore('?').lowercase()
            val tag = item.optString("tag").trim()
            val hit = want.any { needle -> ruleSetMatchesNeedle(tag, file, needle) }
            if (hit) {
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

    /**
     * sing-box 1.14 downloads remote rule-sets through `http_clients` /
     * `route.default_http_client` (`download_detour` is deprecated). Airport
     * templates still point those at `proxy-select`. Overlay scripts replace
     * selector groups, so start dies with "outbound detour not found".
     *
     * Official 1.14 style: a shared HTTP client with no detour uses the
     * default (system) dialer. testingcf.jsdelivr.net is reachable in China
     * without a proxy, so that is the right client. Never detour to an
     * empty `direct` — 1.12+ rejects that for any dialer.
     */
    internal const val HTTP_DIRECT_TAG = "angela-http-direct"

    internal fun healDownloadClients(root: JSONObject): Boolean {
        val tags = outboundTags(root)
        val emptyDirect = emptyDirectTags(root)
        var changed = false
        val clients = root.optJSONArray("http_clients") ?: JSONArray().also {
            root.put("http_clients", it)
            changed = true
        }
        var i = 0
        while (i < clients.length()) {
            val client = clients.optJSONObject(i)
            if (client == null) {
                i++
                continue
            }
            val detour = client.optString("detour").trim()
            if (detour.isNotEmpty() && (detour !in tags || detour in emptyDirect)) {
                client.remove("detour")
                changed = true
            }
            i++
        }
        var safeTag = ""
        for (j in 0 until clients.length()) {
            val client = clients.optJSONObject(j) ?: continue
            val tag = client.optString("tag").trim()
            if (tag.isEmpty()) continue
            if (client.optString("detour").isBlank() && safeTag.isEmpty()) safeTag = tag
        }
        if (safeTag.isEmpty()) {
            safeTag = HTTP_DIRECT_TAG
            if (!hasHttpClient(clients, safeTag)) {
                clients.put(JSONObject().put("tag", safeTag))
                changed = true
            }
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val current = route.optString("default_http_client").trim()
        if (current.isEmpty() || !hasHttpClient(clients, current)) {
            route.put("default_http_client", safeTag)
            changed = true
        }
        val sets = route.optJSONArray("rule_set")
        if (sets != null) {
            for (s in 0 until sets.length()) {
                val item = sets.optJSONObject(s) ?: continue
                val download = item.optString("download_detour").trim()
                if (download.isNotEmpty() && (download !in tags || download in emptyDirect)) {
                    item.remove("download_detour")
                    item.put("http_client", safeTag)
                    changed = true
                }
                when (val hc = item.opt("http_client")) {
                    is String -> {
                        if (hc.isNotBlank() && !hasHttpClient(clients, hc)) {
                            item.put("http_client", safeTag)
                            changed = true
                        }
                    }
                    is JSONObject -> {
                        val d = hc.optString("detour").trim()
                        if (d.isNotEmpty() && (d !in tags || d in emptyDirect)) {
                            hc.remove("detour")
                            changed = true
                        }
                    }
                }
            }
        }
        val clash = root.optJSONObject("experimental")?.optJSONObject("clash_api")
        if (clash != null) {
            val ui = clash.optString("external_ui_download_detour").trim()
            if (ui.isNotEmpty() && (ui !in tags || ui in emptyDirect)) {
                clash.remove("external_ui_download_detour")
                changed = true
            }
        }
        return changed
    }

    /** What [healMissingOutboundRefs] did, for callers with a notes channel. */
    internal data class RefHeal(
        val changed: Boolean,
        /** "missing → fallback" pairs for every rule/final retarget. */
        val retargets: List<String>,
    )

    /**
     * After scripts rebuild groups, leftover DNS/route/outbound detours and
     * selector members still name the old tags. Rewrite or drop them so the
     * kernel can start. Missing non-China route targets fall back to the
     * primary selector, never silently to DIRECT.
     */
    internal fun healMissingOutboundRefs(root: JSONObject): Boolean =
        healMissingOutboundRefsWithReport(root).changed

    internal fun healMissingOutboundRefsWithReport(root: JSONObject): RefHeal {
        val tags = outboundTags(root)
        if (tags.isEmpty()) return RefHeal(false, emptyList())
        val retargets = linkedSetOf<String>()
        var changed = ConfigCompat.stripBrokenDnsDetours(root)
        val outs = root.optJSONArray("outbounds") ?: JSONArray()
        // The fallback for a dangling reference is the profile's primary
        // selector. route.final names it by definition, so prefer it; only
        // when final is missing or not a group do we use outbounds order —
        // in converted configs the first group is often a region group,
        // and retargeting rules there exits traffic via the wrong region.
        val finalCandidate = root.optJSONObject("route")?.optString("final")?.trim().orEmpty()
        var firstGroupTag: String? = null
        var finalGroupTag: String? = null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type").trim().lowercase()
            if (type != "selector" && type != "urltest") continue
            if (tag.isEmpty()) continue
            if (firstGroupTag == null) firstGroupTag = tag
            if (tag == finalCandidate) finalGroupTag = tag
        }
        val proxyFallback = finalGroupTag ?: firstGroupTag
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val detour = o.optString("detour").trim()
            if (detour.isNotEmpty() && detour !in tags) {
                o.remove("detour")
                changed = true
            }
            val members = o.optJSONArray("outbounds") ?: continue
            val kept = JSONArray()
            var listChanged = false
            for (j in 0 until members.length()) {
                val item = members.opt(j)
                val t = when (item) {
                    is String -> item.trim()
                    is JSONObject -> item.optString("tag").trim()
                    else -> ""
                }
                if (t.isEmpty()) continue
                if (t in tags) {
                    kept.put(members.get(j))
                } else {
                    listChanged = true
                }
            }
            if (listChanged) {
                o.put("outbounds", kept)
                changed = true
            }
            val defaultTag = o.optString("default").trim()
            if (defaultTag.isNotEmpty() && defaultTag !in tags) {
                val first = kept.optString(0).ifBlank {
                    kept.optJSONObject(0)?.optString("tag").orEmpty()
                }
                if (first.isNotEmpty()) o.put("default", first) else o.remove("default")
                changed = true
            }
        }
        val dns = root.optJSONObject("dns")
        if (dns != null) {
            val servers = dns.optJSONArray("servers")
            val serverTags = mutableSetOf<String>()
            if (servers != null) {
                for (i in 0 until servers.length()) {
                    val tag = servers.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
                    if (tag.isNotEmpty()) serverTags.add(tag)
                }
            }
            val resolver = dns.optString("domain_resolver").trim()
            if (resolver.isNotEmpty() && resolver !in serverTags) {
                dns.remove("domain_resolver")
                changed = true
            }
        }
        val route = root.optJSONObject("route") ?: return RefHeal(changed, retargets.toList())
        val finalTag = route.optString("final").trim()
        if (finalTag.isNotEmpty() && finalTag !in tags) {
            if (!proxyFallback.isNullOrEmpty()) {
                route.put("final", proxyFallback)
                retargets += "$finalTag → $proxyFallback"
            } else {
                route.remove("final")
            }
            changed = true
        }
        val defaultResolver = route.opt("default_domain_resolver")
        if (defaultResolver is String && defaultResolver.isNotBlank()) {
            val dnsServers = root.optJSONObject("dns")?.optJSONArray("servers")
            val serverTags = mutableSetOf<String>()
            if (dnsServers != null) {
                for (i in 0 until dnsServers.length()) {
                    val tag = dnsServers.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
                    if (tag.isNotEmpty()) serverTags.add(tag)
                }
            }
            if (defaultResolver !in serverTags && defaultResolver !in tags) {
                route.remove("default_domain_resolver")
                changed = true
            }
        }
        if (rewriteMissingRuleOutbounds(route.optJSONArray("rules"), tags, proxyFallback, retargets)) {
            changed = true
        }
        return RefHeal(changed, retargets.toList())
    }

    private fun rewriteMissingRuleOutbounds(
        rules: JSONArray?,
        tags: Set<String>,
        proxyFallback: String?,
        retargets: MutableSet<String>,
    ): Boolean {
        if (rules == null) return false
        var changed = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rewriteMissingRuleOutbounds(rule.optJSONArray("rules"), tags, proxyFallback, retargets)) {
                changed = true
            }
            val ob = rule.optString("outbound").trim()
            if (ob.isEmpty() || ob in tags) continue
            val action = rule.optString("action").trim().lowercase()
            if (action == "reject" || action == "hijack-dns" || action == "sniff" || action == "resolve") {
                rule.remove("outbound")
                changed = true
                continue
            }
            if (!proxyFallback.isNullOrEmpty()) {
                rule.put("outbound", proxyFallback)
                retargets += "$ob → $proxyFallback"
            } else {
                rule.remove("outbound")
            }
            changed = true
        }
        return changed
    }

    /**
     * Kernel error `domain resolver not found: local` means an outbound
     * names a DNS tag that is not in `dns.servers`. Add a system resolver
     * and point dangling names at it. Does not rewrite node detours to direct.
     * Idempotent on an already-healed tree.
     */
    internal fun healDanglingDomainResolvers(root: JSONObject): Boolean {
        val tags = dnsServerTags(root)
        if (!hasDanglingResolver(root, tags)) return false
        val localTag = ensureLocalDnsServer(root)
        return retargetDanglingResolvers(root, dnsServerTags(root), localTag) || localTag !in tags
    }

    /** Runtime JSON after overlays. Returns the same string when nothing changed. */
    fun healRuntimeConfig(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed[0] != '{') return content
        if (trimmed.length > ConfigCompat.MAX_CONFIG_CHARS) return content
        return try {
            val root = JSONObject(trimmed)
            if (!healDanglingDomainResolvers(root)) content else root.toString()
        } catch (_: Exception) {
            content
        }
    }

    private fun dnsServerTags(root: JSONObject): Set<String> {
        val tags = linkedSetOf<String>()
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return tags
        for (i in 0 until servers.length()) {
            val tag = servers.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
            if (tag.isNotEmpty()) tags.add(tag)
        }
        return tags
    }

    private fun hasDanglingResolver(node: Any?, tags: Set<String>): Boolean {
        when (node) {
            is JSONObject -> {
                val keys = jsonKeys(node)
                for (key in keys) {
                    if (key == "domain_resolver" || key == "default_domain_resolver") {
                        val name = resolverServerName(node.opt(key))
                        if (name.isNotEmpty() && name !in tags) return true
                    } else if (hasDanglingResolver(node.opt(key), tags)) {
                        return true
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (hasDanglingResolver(node.opt(i), tags)) return true
                }
            }
        }
        return false
    }

    private fun ensureLocalDnsServer(root: JSONObject): String {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val tag = server.optString("tag").trim()
            if (tag.isNotEmpty() && server.optString("type").equals("local", true)) return tag
        }
        servers.put(JSONObject().put("type", "local").put("tag", "local"))
        return "local"
    }

    private fun retargetDanglingResolvers(node: Any?, tags: Set<String>, localTag: String): Boolean {
        var changed = false
        when (node) {
            is JSONObject -> {
                val keys = jsonKeys(node)
                for (key in keys) {
                    if (key == "domain_resolver" || key == "default_domain_resolver") {
                        if (pointResolverAt(node, key, tags, localTag)) changed = true
                    } else if (retargetDanglingResolvers(node.opt(key), tags, localTag)) {
                        changed = true
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (retargetDanglingResolvers(node.opt(i), tags, localTag)) changed = true
                }
            }
        }
        return changed
    }

    private fun pointResolverAt(parent: JSONObject, key: String, tags: Set<String>, localTag: String): Boolean {
        when (val value = parent.opt(key)) {
            is String -> {
                val name = value.trim()
                if (name.isEmpty() || name in tags) return false
                parent.put(key, localTag)
                return true
            }
            is JSONObject -> {
                val name = value.optString("server").trim()
                if (name.isEmpty() || name in tags) return false
                value.put("server", localTag)
                return true
            }
            else -> return false
        }
    }

    private fun resolverServerName(value: Any?): String = when (value) {
        is String -> value.trim()
        is JSONObject -> value.optString("server").trim()
        else -> ""
    }

    private fun jsonKeys(node: JSONObject): List<String> {
        val keys = ArrayList<String>()
        val it = node.keys()
        while (it.hasNext()) keys.add(it.next())
        return keys
    }

    private fun outboundTags(root: JSONObject): Set<String> {
        val tags = linkedSetOf<String>()
        fun add(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val tag = arr.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
                if (tag.isNotEmpty()) tags.add(tag)
            }
        }
        add(root.optJSONArray("outbounds"))
        add(root.optJSONArray("endpoints"))
        return tags
    }

    private fun emptyDirectTags(root: JSONObject): Set<String> {
        val outs = root.optJSONArray("outbounds") ?: return emptySet()
        val empty = linkedSetOf<String>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            if (tag.isNotEmpty() && ConfigCompat.isEmptyDirect(o)) empty.add(tag)
        }
        return empty
    }

    private fun hasHttpClient(clients: JSONArray, tag: String): Boolean {
        if (tag.isEmpty()) return false
        for (i in 0 until clients.length()) {
            if (clients.optJSONObject(i)?.optString("tag") == tag) return true
        }
        return false
    }

    /**
     * Domain rules do nothing until the client hello is sniffed. Put sniff
     * after hijack-dns so port 53 is not held for the sniff timeout.
     * Subscriptions that already sniff are left alone.
     */
    internal fun ensureSniff(root: JSONObject): Boolean {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        if (hasAction(rules, "sniff")) return false
        var insertAt = 0
        while (insertAt < rules.length()) {
            val rule = rules.optJSONObject(insertAt) ?: break
            val action = rule.optString("action")
            if (!action.equals("hijack-dns", true) && !action.equals("reject", true)) break
            if (action.equals("reject", true) && !cidrHas(rule.opt("ip_cidr"), "198.18.0.0/15")) break
            insertAt++
        }
        val sniff = JSONObject().put("action", "sniff")
        val merged = JSONArray()
        for (i in 0 until insertAt) merged.put(rules.get(i))
        merged.put(sniff)
        for (i in insertAt until rules.length()) merged.put(rules.get(i))
        route.put("rules", merged)
        return true
    }

    private fun hasAction(rules: JSONArray, action: String): Boolean {
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("action").equals(action, true)) return true
            val nested = rule.optJSONArray("rules")
            if (nested != null && hasAction(nested, action)) return true
        }
        return false
    }

    /**
     * TUN DNS must be hijacked before any routing rule. Overlay scripts
     * inject this; keep a startup fallback so a subscription without
     * hijack-dns cannot leak port 53 or drop YouTube/Gemini lookups.
     */
    internal fun ensureHijackDns(root: JSONObject): Boolean {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("action").equals("hijack-dns", true)) return false
        }
        val extra = JSONArray()
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(
                JSONObject()
                    .put("port", 53)
                    .put("network", JSONArray().put("udp").put("tcp"))
                    .put("action", "hijack-dns"),
            )
        prependRouteRules(root, extra)
        return true
    }

    /**
     * 198.18.0.0/15 is Clash fake-ip. A real mapping is rewritten to a domain
     * before rules run. An address that is still in this range is a stale
     * answer and must not be dialed through a node.
     */
    internal fun rejectStaleFakeIp(root: JSONObject): Boolean {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (!rule.optString("action").equals("reject", true)) continue
            if (cidrHas(rule.opt("ip_cidr"), "198.18.0.0/15")) return false
        }
        prependRouteRules(
            root,
            JSONArray().put(JSONObject().put("ip_cidr", "198.18.0.0/15").put("action", "reject")),
        )
        return true
    }

    private fun cidrHas(raw: Any?, cidr: String): Boolean {
        if (raw is String) return raw.trim().equals(cidr, true)
        if (raw is JSONArray) {
            for (i in 0 until raw.length()) {
                if (raw.optString(i).trim().equals(cidr, true)) return true
            }
        }
        return false
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

    /** What [stripTailscaleSshServer] did, for callers with a notes channel. */
    internal data class SshServerStrip(
        val changed: Boolean,
        /** Tags of endpoints whose `ssh_server` was enabled (anything but an explicit `false`). */
        val enabledTags: List<String>,
    )

    internal fun stripTailscaleSshServer(root: JSONObject): Boolean =
        stripTailscaleSshServerWithReport(root).changed

    /**
     * A tailscale endpoint's `ssh_server` makes the kernel run a Tailscale
     * SSH server on this device. Who may log in — and which local user a
     * login maps to, `root` included — is decided entirely by the tailnet
     * control plane the profile itself enrolls the device into, i.e. by
     * whoever wrote the profile. The app has no feature that needs the
     * server and no UI that shows it running, so the key is removed at
     * normalization, whatever its value; an explicit `false` goes too
     * (the kernel default is off, so nothing changes for it). Endpoints
     * that had it enabled are named in the report variant so the caller
     * can tell the user what was taken out.
     */
    internal fun stripTailscaleSshServerWithReport(root: JSONObject): SshServerStrip {
        val endpoints = root.optJSONArray("endpoints") ?: return SshServerStrip(false, emptyList())
        var changed = false
        val enabled = mutableListOf<String>()
        for (i in 0 until endpoints.length()) {
            val ep = endpoints.optJSONObject(i) ?: continue
            if (!ep.optString("type").equals("tailscale", true)) continue
            if (!ep.has("ssh_server")) continue
            val was = ep.opt("ssh_server")
            ep.remove("ssh_server")
            changed = true
            if (was != false && was != JSONObject.NULL) {
                enabled += ep.optString("tag").trim()
                    .ifBlank { ep.optString("hostname").trim() }
                    .ifBlank { "tailscale" }
            }
        }
        return SshServerStrip(changed, enabled)
    }

    internal fun bindLoopbackOnly(root: JSONObject): Boolean {
        val clash = root.optJSONObject("experimental")?.optJSONObject("clash_api") ?: return false
        var changed = false
        for (key in listOf("external_controller", "listen")) {
            if (!clash.has(key)) continue
            val raw = clash.optString(key).trim()
            if (raw.isEmpty()) continue
            val rebound = rebindToLoopback(raw)
            if (rebound != raw) {
                clash.put(key, rebound)
                changed = true
            }
        }
        return changed
    }

    internal fun rebindToLoopback(listen: String): String {
        val s = listen.trim()
        if (s.isEmpty() || s.startsWith("/")) return s
        val host: String
        val port: String
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return "127.0.0.1:9090"
            host = s.substring(1, end)
            port = s.substring(end + 1).trimStart(':').ifBlank { "9090" }
        } else {
            val colon = s.lastIndexOf(':')
            if (colon < 0) {
                host = s
                port = "9090"
            } else {
                host = s.substring(0, colon)
                port = s.substring(colon + 1).ifBlank { "9090" }
            }
        }
        val h = host.lowercase()
        if (h == "127.0.0.1" || h == "localhost") return "127.0.0.1:$port"
        return "127.0.0.1:$port"
    }

    private const val JSDELIVR_HOST = "testingcf.jsdelivr.net"

    // Pinned tags, not the mutable @rule-set branch: a rule-set decides
    // proxy-vs-direct, so its URL is a supply-chain input. See RuleSetPins.
    private val GEOSITE_BASE = RuleSetPins.geositeBase()
    private val GEOIP_BASE = RuleSetPins.geoipBase()
    private val JSDELIVR =
        Regex("^https?://([^/]*jsdelivr\\.net)/gh/(.+)$")
    private val RAW_GITHUB =
        Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
    private val GITHUB_RAW =
        Regex("^https?://github\\.com/([^/]+)/([^/]+)/raw/(.+)$")

    private val UNREPLACEABLE_RULESET_FILES = setOf(
        "geoip-private.srs",
        "geoip-fastly.srs",
        "geoip-cloudfront.srs",
    )

    /**
     * Files verified absent from the official SagerNet rule-set repos
     * (checked against the jsDelivr listings of sing-geosite and
     * sing-geoip, 2026-10-23: sing-geoip ships country-code files
     * only). The set only ever convicts URLs that point at those
     * repos; the same file name on a third-party host proves nothing.
     */
    private val MISSING_RULESET_FILES = setOf(
        "geosite-biliintl.srs",
        "geosite-apple-cn.srs",
        "geosite-tracker.srs",
        "geosite-category-ai!cn.srs",
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

    private val KNOWN_GEOSITE_STEMS = setOf(
        "telegram", "github", "gitlab", "google", "youtube", "netflix",
        "facebook", "twitter", "apple", "microsoft", "instagram", "discord",
        "steam", "openai", "tiktok", "spotify", "amazon", "paypal", "aws",
        "azure", "dropbox", "onedrive", "icloud", "linkedin", "snap", "hulu",
        "disney", "hbo", "bbc", "bahamut", "abema", "blizzard", "epicgames",
        "ea", "ubisoft", "bilibili", "cloudflare",
    )

    internal fun ruleSetStem(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.substringAfterLast('/')
        s = s.substringBefore('?')
        if (s.endsWith(".srs")) s = s.dropLast(4)
        return s
    }

    internal fun officialRuleSetUrl(raw: String): String {
        val file = officialRuleSetFile(raw) ?: return ""
        return if (file.startsWith("geoip-")) GEOIP_BASE + file else GEOSITE_BASE + file
    }

    /** What a rule set's content matches on: CIDR lists vs domain lists. */
    private enum class RuleSetKind { DOMAIN, IP }

    /**
     * Kind of the set a file/tag [stem] names: geoip- prefixed and
     * -ip suffixed stems hold CIDR lists; everything else (geosite-,
     * category-, plain domain-list stems) holds domain lists. The two
     * kinds are not interchangeable behind a route rule, so a swap
     * must never cross from one to the other.
     */
    private fun ruleSetKindOf(stem: String): RuleSetKind =
        if (stem.startsWith("geoip-") || stem.endsWith("-ip")) {
            RuleSetKind.IP
        } else {
            RuleSetKind.DOMAIN
        }

    /**
     * [candidate] (an official URL from [officialRuleSetUrl]) accepted
     * only when it names the same kind of set as [sourceRaw] (a file
     * name or tag) describes; a cross-kind candidate becomes "".
     */
    private fun acceptSameKind(candidate: String, sourceRaw: String): String {
        if (candidate.isEmpty()) return ""
        val sourceKind = ruleSetKindOf(ruleSetStem(sourceRaw))
        val candidateKind = ruleSetKindOf(ruleSetStem(candidate))
        return if (sourceKind == candidateKind) candidate else ""
    }

    /**
     * Official successor URL for [raw] when a swap is allowed at all:
     * like [officialRuleSetUrl], but a successor of a different kind
     * (a geoip name whose only official file is a geosite list)
     * yields "" — the original address then stays as written.
     */
    internal fun officialRuleSetSwapUrl(raw: String): String =
        acceptSameKind(officialRuleSetUrl(raw), raw)

    /**
     * True when [url] points at the official SagerNet rule-set repos
     * (any jsDelivr GH mirror host, raw GitHub, or github.com), where
     * file existence is a known, verified quantity. Any other host is
     * a third-party mirror whose contents this app cannot judge, so
     * its addresses are never rewritten on suspicion alone.
     */
    private fun isOfficialRuleSetUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/gh/sagernet/sing-geosite") ||
            u.contains("/gh/sagernet/sing-geoip") ||
            u.contains("raw.githubusercontent.com/sagernet/sing-geosite/") ||
            u.contains("raw.githubusercontent.com/sagernet/sing-geoip/") ||
            u.contains("github.com/sagernet/sing-geosite/") ||
            u.contains("github.com/sagernet/sing-geoip/")
    }

    /**
     * True when [url] points at an official SagerNet repo AND [file]
     * provably does not exist in that repo. Proof comes from the
     * repos' actual file listings (jsDelivr data API, 2026-10-23):
     * sing-geosite ships only geosite- prefixed files (1,681 of
     * them), sing-geoip ships only geoip- country-code files (237),
     * and MISSING_RULESET_FILES names the correctly-prefixed files
     * that are absent anyway. URLs pointing anywhere else can never
     * be "proven missing" — their host's contents are its own.
     */
    private fun isProvenMissingAtOfficial(url: String, file: String): Boolean {
        if (!isOfficialRuleSetUrl(url)) return false
        if (file in MISSING_RULESET_FILES) return true
        val u = url.lowercase()
        val inGeositeRepo = u.contains("/gh/sagernet/sing-geosite") ||
            u.contains("raw.githubusercontent.com/sagernet/sing-geosite/") ||
            u.contains("github.com/sagernet/sing-geosite/")
        if (inGeositeRepo) return !file.startsWith("geosite-")
        val cc = file.removeSuffix(".srs").removePrefix("geoip-")
        return !(file.startsWith("geoip-") && cc.length == 2 && cc.all { it.isLetter() })
    }

    internal fun officialRuleSetFile(raw: String): String? {
        val stem = ruleSetStem(raw)
        if (stem.isEmpty()) return null
        val aliased = when (stem) {
            "telegram-ip", "geoip-telegram" -> "geosite-telegram"
            "geoip-google" -> "geosite-google"
            "geoip-netflix" -> "geosite-netflix"
            "geoip-facebook" -> "geosite-facebook"
            "geoip-twitter" -> "geosite-twitter"
            "geoip-cloudflare" -> "geosite-cloudflare"
            "biliintl", "geosite-biliintl" -> "geosite-bilibili"
            "apple-cn", "geosite-apple-cn" -> "geosite-apple@cn"
            "tracker", "geosite-tracker" -> "geosite-category-ads-all"
            "category-ai!cn", "geosite-category-ai!cn",
            "category-ai-!cn", "geosite-category-ai-!cn",
            -> "geosite-category-ai-!cn"
            "geoip-private", "geoip-fastly", "geoip-cloudfront" -> return null
            else -> stem
        }
        return when {
            aliased.startsWith("geosite-") -> "$aliased.srs"
            aliased.startsWith("geoip-") -> {
                val cc = aliased.removePrefix("geoip-")
                if (cc.length == 2 && cc.all { it.isLetter() }) "$aliased.srs" else "geosite-$cc.srs"
            }
            aliased.startsWith("category-") -> "geosite-$aliased.srs"
            aliased in KNOWN_GEOSITE_STEMS -> "geosite-$aliased.srs"
            else -> null
        }
    }

    internal fun ruleSetMatchesNeedle(tag: String, file: String, needle: String): Boolean {
        val n = ruleSetStem(needle)
        if (!ConfigDiagnose.isPlausibleRuleSetName(n)) return false
        val t = tag.trim().lowercase()
        val f = ruleSetStem(file)
        return t == n || f == n ||
            t == "geosite-$n" || f == "geosite-$n" ||
            t == "geoip-$n" || f == "geoip-$n" ||
            n == "geosite-$t" || n == "geoip-$t" ||
            n == "geosite-$f" || n == "geoip-$f"
    }

}
