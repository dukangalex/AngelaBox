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
        if (healDirectDestinationOverride(root)) changed = true
        if (migrateSpecialOutbounds(root)) changed = true
        if (rewriteRuleSetUrls(root)) changed = true
        if (healRemoteRuleSets(root)) changed = true
        if (sanitizeClashDownloadUrls(root)) changed = true
        if (healDownloadClients(root)) changed = true
        if (healMissingOutboundRefs(root)) changed = true
        if (ensureHijackDns(root)) changed = true
        if (bindLoopbackOnly(root)) changed = true
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
                if (!RemoteUrlGuard.isPublicHttpsUrl(rewritten)) {
                    item.remove(key)
                    changed = true
                    continue
                }
                if (rewritten != current) {
                    item.put(key, rewritten)
                    changed = true
                }
            }
        }
        return changed
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
     * start. Rewrite short / missing filenames to the official SagerNet
     * geosite/geoip URL and keep the original tag so route rules still match.
     * Only drop a set when there is no known replacement (e.g. geoip-fastly).
     */
    internal fun healRemoteRuleSets(root: JSONObject): Boolean {
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
            val remote = item.optString("type").equals("remote", true) || url.startsWith("http")
            if (!remote) {
                keep.put(item)
                continue
            }
            if (url.isEmpty()) {
                val officialFromTag = officialRuleSetUrl(tag)
                if (officialFromTag.isNotEmpty()) {
                    item.put("url", officialFromTag)
                    keep.put(item)
                    changed = true
                } else {
                    if (tag.isNotEmpty()) dropTags.add(tag)
                    changed = true
                }
                continue
            }
            val official = officialRuleSetUrl(file)
            when {
                official.isNotEmpty() && url != official && looksBrokenRuleSet(file, url) -> {
                    item.put(urlKey, official)
                    keep.put(item)
                    changed = true
                }
                file in UNREPLACEABLE_RULESET_FILES -> {
                    if (tag.isNotEmpty()) dropTags.add(tag)
                    changed = true
                }
                else -> keep.put(item)
            }
        }
        if (!changed && dropTags.isEmpty() && keep.length() == sets.length()) return false
        replaceArray(sets, keep)
        if (dropTags.isNotEmpty()) {
            stripDroppedRuleSets(route.optJSONArray("rules"), dropTags)
            stripDroppedRuleSets(root.optJSONObject("dns")?.optJSONArray("rules"), dropTags)
        }
        return true
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
            val official = officialRuleSetUrl(file).ifBlank {
                officialRuleSetUrl(tag).ifBlank {
                    want.firstOrNull { ruleSetMatchesNeedle(tag, file, it) }?.let { officialRuleSetUrl(it) }.orEmpty()
                }
            }
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

    /**
     * Last-resort exact drop used by tests. Same matching rules as replace;
     * no substring-in-URL.
     */
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

    /**
     * After scripts rebuild groups, leftover DNS/route/outbound detours and
     * selector members still name the old tags. Rewrite or drop them so the
     * kernel can start. Missing non-China route targets fall back to the
     * primary selector, never silently to DIRECT.
     */
    internal fun healMissingOutboundRefs(root: JSONObject): Boolean {
        val tags = outboundTags(root)
        if (tags.isEmpty()) return false
        var changed = ConfigCompat.stripBrokenDnsDetours(root)
        val outs = root.optJSONArray("outbounds") ?: JSONArray()
        var selectorTag: String? = null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type").trim().lowercase()
            if ((type == "selector" || type == "urltest") && selectorTag == null && tag.isNotEmpty()) {
                selectorTag = tag
            }
        }
        val proxyFallback = selectorTag
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
        val route = root.optJSONObject("route") ?: return changed
        val finalTag = route.optString("final").trim()
        if (finalTag.isNotEmpty() && finalTag !in tags) {
            if (!proxyFallback.isNullOrEmpty()) route.put("final", proxyFallback) else route.remove("final")
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
        if (rewriteMissingRuleOutbounds(route.optJSONArray("rules"), tags, proxyFallback)) {
            changed = true
        }
        return changed
    }

    private fun rewriteMissingRuleOutbounds(
        rules: JSONArray?,
        tags: Set<String>,
        proxyFallback: String?,
    ): Boolean {
        if (rules == null) return false
        var changed = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rewriteMissingRuleOutbounds(rule.optJSONArray("rules"), tags, proxyFallback)) {
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
            } else {
                rule.remove("outbound")
            }
            changed = true
        }
        return changed
    }

    private fun outboundTags(root: JSONObject): Set<String> {
        val outs = root.optJSONArray("outbounds") ?: return emptySet()
        val tags = linkedSetOf<String>()
        for (i in 0 until outs.length()) {
            val tag = outs.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
            if (tag.isNotEmpty()) tags.add(tag)
        }
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
    private const val GEOSITE_BASE =
        "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/"
    private const val GEOIP_BASE =
        "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/"
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

    private fun looksBrokenRuleSet(file: String, url: String): Boolean {
        val stem = ruleSetStem(file)
        if (file in MISSING_RULESET_FILES) return true
        if (stem.isNotEmpty() && !stem.startsWith("geosite-") && !stem.startsWith("geoip-")) return true
        if (url.contains("sing-geosite", true) && !stem.startsWith("geosite-")) return true
        if (url.contains("sing-geoip", true) && file in MISSING_RULESET_FILES) return true
        return false
    }
}
