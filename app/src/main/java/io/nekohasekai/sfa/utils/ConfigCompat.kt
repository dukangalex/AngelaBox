package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Clash/mihomo → sing-box field coercion applied on import, remote refresh
 * and runtime overlay. This is a compatibility shim, not a second kernel:
 * user nodes stay intact; only fields the current libbox cannot decode
 * are rewritten. Runtime overlays (chain / China direct / ads / WebRTC) run
 * after this step.
 *
 * sing-box `ShadowsocksOutboundOptions.plugin_opts` is a string. Clash writes
 * an object (`plugin-opts: { mode, host }`), which makes libbox fail with
 * "cannot unmarshal object into Go struct field ... of type string".
 *
 * sing-box 1.12+ also rejects DNS `detour` pointing at an empty `direct`
 * outbound. Those detours are stripped so the kernel uses the default
 * (direct) dialer instead of refusing to start.
 *
 * sing-box 1.14 removes `dns.fakeip` and legacy `servers[].address`. Import
 * and startup migrate them to typed servers so old subscriptions still load.
 *
 * DNS `type: rcode` / `type: predefined` as *servers* are not registered
 * transports ("unknown transport type: rcode"). They become DNS *rule*
 * actions (`action: predefined`) matching the 1.12 migration guide.
 *
 * sing-box 1.11 deprecated inbound sniff/domain_strategy and special
 * outbounds (`type: dns` / `type: block`); 1.13 rejects leftover
 * legacy inbound fields. Import and startup rewrite those fields into
 * route actions so older subscriptions still load. GitHub
 * `raw.githubusercontent.com` rule-set URLs are rewritten to the jsDelivr
 * testingcf mirror so LFS/raw 404s and GitHub unavailability do not block start.
 */
object ConfigCompat {
    const val MAX_CONFIG_CHARS = 8 * 1024 * 1024

    fun sanitize(content: String): String {
        val ingested = ConfigIngest.adapt(content)
        if (!ingested.fatal.isNullOrBlank()) {
            throw IllegalArgumentException(ingested.fatal)
        }
        val trimmed = ingested.content.trim()
        if (trimmed.isEmpty() || trimmed[0] != '{') {
            throw IllegalArgumentException("这份订阅不是 sing-box 能读的 JSON，也没能转成可用配置，已拒绝，没有改成直连。")
        }
        if (trimmed.length > MAX_CONFIG_CHARS) {
            throw IllegalArgumentException("配置过大，已拒绝，没有改成直连。")
        }
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("配置不是合法 JSON，已拒绝，没有改成直连。")
        }
        var changed = false
        if (stripClashResidue(root)) changed = true
        if (dropUnsupportedLeaves(root)) changed = true
        if (coerceTcpKeepAlive(root)) changed = true
        val outs = root.optJSONArray("outbounds")
        if (outs != null) {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (sanitizeOutbound(o)) changed = true
            }
        }
        if (migrateLegacyDns(root)) changed = true
        if (ConfigInboundCompat.apply(root)) changed = true
        if (ConfigIngest.normalizeEchConfigs(root)) changed = true
        if (stripBrokenDnsDetours(root)) changed = true
        return if (changed) root.toString() else ingested.content
    }

    fun sanitizeOutbound(o: JSONObject): Boolean {
        var changed = coerceKeepAliveField(o)
        if (o.has("plugin-opts") && !o.has("plugin_opts")) {
            o.put("plugin_opts", o.get("plugin-opts"))
            o.remove("plugin-opts")
            changed = true
        }
        if (!o.has("plugin_opts")) return changed
        val raw = o.get("plugin_opts")
        if (raw is String) return changed
        if (raw is JSONObject) {
            o.put("plugin_opts", objectToPluginOpts(o.optString("plugin"), raw))
            return true
        }
        o.remove("plugin_opts")
        return true
    }

    internal fun coerceTcpKeepAlive(node: Any?): Boolean {
        var changed = false
        when (node) {
            is JSONObject -> {
                if (coerceKeepAliveField(node)) changed = true
                val names = node.names() ?: return changed
                for (i in 0 until names.length()) {
                    if (coerceTcpKeepAlive(node.opt(names.optString(i)))) changed = true
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (coerceTcpKeepAlive(node.opt(i))) changed = true
                }
            }
        }
        return changed
    }

    private fun coerceKeepAliveField(o: JSONObject): Boolean {
        when (val keepAlive = o.opt("tcp_keep_alive")) {
            is Boolean -> {
                if (keepAlive) {
                    o.put("tcp_keep_alive", "60s")
                } else {
                    o.remove("tcp_keep_alive")
                    o.put("disable_tcp_keep_alive", true)
                }
                return true
            }
            is String -> {
                when (keepAlive.trim().lowercase()) {
                    "true" -> {
                        o.put("tcp_keep_alive", "60s")
                        return true
                    }
                    "false" -> {
                        o.remove("tcp_keep_alive")
                        o.put("disable_tcp_keep_alive", true)
                        return true
                    }
                }
            }
        }
        return false
    }

    internal fun stripClashResidue(root: JSONObject): Boolean {
        var changed = false
        for (key in CLASH_DOC_KEYS) {
            if (root.has(key)) {
                root.remove(key)
                changed = true
            }
        }
        return changed
    }

    /**
     * xhttp / MASQUE are not in this kernel. Drop those leaves. If nothing
     * usable remains, fail closed instead of leaving a direct-only config.
     */
    internal fun dropUnsupportedLeaves(root: JSONObject): Boolean {
        val outs = root.optJSONArray("outbounds") ?: return false
        val dropped = linkedSetOf<String>()
        val keep = JSONArray()
        var changed = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (isUnsupportedLeaf(o)) {
                val tag = o.optString("tag").trim()
                if (tag.isNotEmpty()) dropped += tag
                changed = true
                continue
            }
            keep.put(o)
        }
        if (!changed) return false
        while (outs.length() > 0) outs.remove(0)
        for (i in 0 until keep.length()) outs.put(keep.get(i))
        val tags = linkedSetOf<String>()
        for (i in 0 until outs.length()) {
            val tag = outs.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
            if (tag.isNotEmpty()) tags += tag
        }
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val members = o.optJSONArray("outbounds") ?: continue
            val next = JSONArray()
            for (j in 0 until members.length()) {
                val item = members.opt(j)
                val tag = when (item) {
                    is String -> item.trim()
                    is JSONObject -> item.optString("tag").trim()
                    else -> ""
                }
                if (tag.isEmpty() || tag in tags) next.put(members.get(j))
            }
            o.put("outbounds", next)
        }
        if (dropped.isNotEmpty() && !hasUsableLeaf(root)) {
            throw IllegalArgumentException(
                "当前内核还不支持这份配置里的 xhttp / MASQUE。没有改成直连。",
            )
        }
        return true
    }

    private fun isUnsupportedLeaf(o: JSONObject): Boolean {
        val type = o.optString("type").trim().lowercase()
        if (type == "masque" || type == "masque-client" || type == "xhttp") return true
        val transport = o.optJSONObject("transport")?.optString("type")?.trim()?.lowercase().orEmpty()
        return transport == "xhttp" || transport == "splithttp"
    }

    private fun hasUsableLeaf(root: JSONObject): Boolean {
        val outs = root.optJSONArray("outbounds") ?: return false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val type = o.optString("type").trim().lowercase()
            if (type.isEmpty()) continue
            if (type in GROUP_OR_DIRECT) continue
            if (isUnsupportedLeaf(o)) continue
            return true
        }
        return false
    }

    private val CLASH_DOC_KEYS = arrayOf(
        "mixed-port",
        "socks-port",
        "redir-port",
        "tproxy-port",
        "allow-lan",
        "bind-address",
        "proxies",
        "proxy-groups",
        "proxy-providers",
        "rule-providers",
        "external-controller",
        "external-ui",
        "cfw-bypass",
        "cfw-latency-timeout",
        "log-level",
    )

    private val GROUP_OR_DIRECT = setOf(
        "selector", "urltest", "url-test", "load-balance", "fallback",
        "direct", "block", "dns", "relay", "chain",
    )

    /**
     * Convert `dns.fakeip` + `servers[].address` to sing-box 1.12 typed
     * servers. 1.14 rejects leftover `dns.fakeip` with
     * "legacy DNS fakeip options are deprecated...".
     */
    fun migrateLegacyDns(root: JSONObject): Boolean {
        val dns = root.optJSONObject("dns") ?: return false
        var changed = false
        var inet4: String? = null
        var inet6: String? = null
        var fakeipEnabled = false
        val fakeipObj = dns.optJSONObject("fakeip")
        if (fakeipObj != null) {
            fakeipEnabled = fakeipObj.optBoolean("enabled", true)
            inet4 = fakeipObj.optString("inet4_range").trim().ifEmpty { null }
            inet6 = fakeipObj.optString("inet6_range").trim().ifEmpty { null }
            if (fakeipEnabled) {
                inet4 = inet4 ?: "198.18.0.0/15"
                inet6 = inet6 ?: "fc00::/18"
            }
            dns.remove("fakeip")
            changed = true
        }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also {
            if (fakeipEnabled) dns.put("servers", it)
        }
        var hasFakeipServer = false
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (migrateLegacyServer(server, inet4, inet6)) changed = true
            if (server.optString("type").equals("fakeip", true)) {
                hasFakeipServer = true
                if (inet4 != null && server.optString("inet4_range").isBlank()) {
                    server.put("inet4_range", inet4)
                    changed = true
                }
                if (inet6 != null && server.optString("inet6_range").isBlank()) {
                    server.put("inet6_range", inet6)
                    changed = true
                }
            }
        }
        if (fakeipEnabled && !hasFakeipServer) {
            val fake = JSONObject().put("type", "fakeip").put("tag", "fakeip")
            if (inet4 != null) fake.put("inet4_range", inet4)
            if (inet6 != null) fake.put("inet6_range", inet6)
            servers.put(fake)
            hasFakeipServer = true
            changed = true
        }
        if (hasFakeipServer && fakeipEnabled) ensureFakeipRule(dns)
        if (migrateRcodeServers(dns)) changed = true
        return changed
    }

    private fun ensureFakeipRule(dns: JSONObject) {
        val rules = dns.optJSONArray("rules") ?: JSONArray().also { dns.put("rules", it) }
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("server") == "fakeip") return
        }
        val types = JSONArray().put("A").put("AAAA")
        val injected = JSONObject().put("query_type", types).put("server", "fakeip")
        val merged = JSONArray().put(injected)
        for (i in 0 until rules.length()) merged.put(rules.get(i))
        dns.put("rules", merged)
    }

    internal fun migrateLegacyServer(server: JSONObject, inet4: String?, inet6: String?): Boolean {
        var changed = false
        if (server.has("address_resolver")) {
            if (!server.has("domain_resolver")) {
                server.put("domain_resolver", server.get("address_resolver"))
            }
            server.remove("address_resolver")
            changed = true
        }
        val type = server.optString("type").trim()
        if (type.isNotEmpty() && !type.equals("legacy", true)) {
            if (server.has("address")) {
                server.remove("address")
                changed = true
            }
            return changed
        }
        if (!server.has("address")) return changed
        val address = server.optString("address").trim()
        server.remove("address")
        when {
            address.equals("local", true) -> server.put("type", "local")
            address.equals("fakeip", true) -> {
                server.put("type", "fakeip")
                if (inet4 != null && server.optString("inet4_range").isBlank()) {
                    server.put("inet4_range", inet4)
                }
                if (inet6 != null && server.optString("inet6_range").isBlank()) {
                    server.put("inet6_range", inet6)
                }
            }
            address.startsWith("tcp://", true) ->
                applyHost(server, "tcp", address.substring(6))
            address.startsWith("tls://", true) ->
                applyHost(server, "tls", address.substring(6))
            address.startsWith("quic://", true) ->
                applyHost(server, "quic", address.substring(7))
            address.startsWith("udp://", true) ->
                applyHost(server, "udp", address.substring(6))
            address.startsWith("https://", true) ->
                applyUrl(server, "https", address.substring(8))
            address.startsWith("h3://", true) ->
                applyUrl(server, "h3", address.substring(5))
            address.startsWith("dhcp://", true) -> {
                server.put("type", "dhcp")
                val iface = address.substring(7).trim()
                if (iface.isNotEmpty() && !iface.equals("auto", true)) {
                    server.put("interface", iface)
                }
            }
            address.startsWith("rcode://", true) -> {
                server.put("type", "rcode")
                server.put("rcode", address.substring(8).trim())
            }
            else -> applyHost(server, "udp", address)
        }
        return true
    }

    /**
     * `type: rcode` / `type: predefined` are not DNS *transports* in
     * sing-box 1.12+. Drop those servers and rewrite rules that pointed
     * at them to `action: predefined` (same RCODE).
     */
    internal fun migrateRcodeServers(dns: JSONObject): Boolean {
        val servers = dns.optJSONArray("servers") ?: return false
        val rcodeByTag = linkedMapOf<String, String>()
        val keep = JSONArray()
        var changed = false
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val rcode = extractRcode(server)
            if (rcode == null) {
                keep.put(server)
                continue
            }
            changed = true
            val tag = server.optString("tag").trim()
            if (tag.isNotEmpty()) rcodeByTag[tag] = rcode
        }
        if (!changed) return false
        if (keep.length() == 0) {
            keep.put(JSONObject().put("type", "local").put("tag", "chainbox-dns-local"))
        }
        replaceArray(servers, keep)

        val rules = dns.optJSONArray("rules") ?: JSONArray().also { dns.put("rules", it) }
        val rewritten = JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val mapped = rcodeByTag[rule.optString("server").trim()]
            if (mapped != null) {
                rule.remove("server")
                rule.put("action", "predefined")
                rule.put("rcode", mapped)
            }
            rewritten.put(rule)
        }
        val finalTag = dns.optString("final").trim()
        val mappedFinal = rcodeByTag[finalTag]
        if (mappedFinal != null) {
            dns.remove("final")
            rewritten.put(
                JSONObject().put("action", "predefined").put("rcode", mappedFinal),
            )
            val fallback = keep.optJSONObject(0)?.optString("tag").orEmpty()
            if (fallback.isNotEmpty()) dns.put("final", fallback)
        }
        dns.put("rules", rewritten)
        return true
    }

    internal fun extractRcode(server: JSONObject): String? {
        val type = server.optString("type").trim().lowercase()
        val address = server.optString("address").trim()
        if (type == "rcode" || address.startsWith("rcode://", true)) {
            val raw = when {
                server.optString("rcode").isNotBlank() -> server.optString("rcode")
                address.startsWith("rcode://", true) -> address.substring(8)
                else -> "success"
            }
            return canonicalizeRcode(raw)
        }
        if (type != "predefined") return null
        val direct = server.optString("rcode").trim()
        if (direct.isNotBlank()) return canonicalizeRcode(direct)
        val responses = server.optJSONArray("responses")
        val first = responses?.optJSONObject(0)?.optString("rcode").orEmpty()
        return canonicalizeRcode(first.ifBlank { "NOERROR" })
    }

    internal fun canonicalizeRcode(raw: String): String {
        return when (raw.trim().lowercase().replace("-", "_")) {
            "", "success", "noerror", "no_error", "0" -> "NOERROR"
            "format_error", "formerr", "form_err", "1" -> "FORMERR"
            "server_failure", "servfail", "serv_fail", "2" -> "SERVFAIL"
            "name_error", "nxdomain", "nx_domain", "3" -> "NXDOMAIN"
            "not_implemented", "notimp", "not_imp", "4" -> "NOTIMP"
            "refused", "5" -> "REFUSED"
            else -> raw.trim().uppercase()
        }
    }

    private fun replaceArray(target: JSONArray, keep: JSONArray) {
        while (target.length() > 0) target.remove(0)
        for (i in 0 until keep.length()) target.put(keep.get(i))
    }

    private fun applyUrl(server: JSONObject, type: String, restRaw: String) {
        var rest = restRaw
        val hash = rest.indexOf('#')
        if (hash >= 0) rest = rest.substring(0, hash)
        val slash = rest.indexOf('/')
        val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else ""
        applyHost(server, type, hostPort)
        if (path.isNotBlank() && path != "/" && path != "/dns-query") {
            server.put("path", path)
        }
    }

    internal fun applyHost(server: JSONObject, type: String, hostPortRaw: String) {
        server.put("type", type)
        val hp = hostPortRaw.trim()
        if (hp.startsWith("[")) {
            val end = hp.indexOf(']')
            if (end > 0) {
                server.put("server", hp.substring(1, end))
                if (end + 1 < hp.length && hp[end + 1] == ':') {
                    hp.substring(end + 2).toIntOrNull()?.let { server.put("server_port", it) }
                }
                return
            }
        }
        val last = hp.lastIndexOf(':')
        val first = hp.indexOf(':')
        if (last > 0 && last == first) {
            val port = hp.substring(last + 1).toIntOrNull()
            if (port != null) {
                server.put("server", hp.substring(0, last))
                server.put("server_port", port)
                return
            }
        }
        server.put("server", hp)
    }

    /**
     * Drop DNS detours that would crash sing-box 1.12+:
     * - target outbound does not exist
     * - target is a `direct` outbound with no bind/override (empty direct)
     * Missing detour = default dialer = direct connect, which is what
     * `detour: "direct"` was trying to express.
     */
    fun stripBrokenDnsDetours(root: JSONObject): Boolean {
        val dns = root.optJSONObject("dns") ?: return false
        val servers = dns.optJSONArray("servers") ?: return false
        val outs = root.optJSONArray("outbounds") ?: JSONArray()
        val tags = mutableSetOf<String>()
        val emptyDirect = mutableSetOf<String>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            if (tag.isEmpty()) continue
            tags.add(tag)
            if (o.optString("type").equals("direct", true) && isEmptyDirect(o)) {
                emptyDirect.add(tag)
            }
        }
        var changed = false
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val detour = server.optString("detour").trim()
            if (detour.isEmpty()) continue
            if (detour !in tags || detour in emptyDirect) {
                server.remove("detour")
                changed = true
            }
        }
        return changed
    }

    internal fun isEmptyDirect(o: JSONObject): Boolean {
        if (!o.optString("type").equals("direct", true)) return false
        val marked = o.opt("routing_mark")
        if (marked is Number && marked.toInt() != 0) return false
        if (marked is String && marked.isNotBlank() && marked != "0") return false
        val keys = listOf(
            "override_address",
            "bind_interface",
            "inet4_bind_address",
            "inet6_bind_address",
            "inet4_address",
            "inet6_address",
        )
        if (keys.any { key -> o.optString(key).isNotBlank() }) return false
        if (o.optString("connect_timeout").isNotBlank()) return false
        if (o.optBoolean("reuse_addr") || o.optBoolean("tcp_fast_open")) return false
        val port = o.opt("override_port")
        return !(port is Number && port.toInt() != 0)
    }

    internal fun objectToPluginOpts(plugin: String, obj: JSONObject): String {
        val isObfs = plugin.contains("obfs", ignoreCase = true)
        val parts = mutableListOf<String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k) ?: continue
            when (v) {
                is Boolean -> {
                    if (v) parts.add(if (isObfs && k == "tls") "obfs=tls" else k)
                }
                else -> {
                    val value = v.toString()
                    parts.add(
                        when {
                            isObfs && k == "mode" -> "obfs=$value"
                            isObfs && (k == "host" || k == "obfs-host") -> "obfs-host=$value"
                            isObfs && (k == "uri" || k == "obfs-uri") -> "obfs-uri=$value"
                            else -> "$k=$value"
                        },
                    )
                }
            }
        }
        return parts.joinToString(";")
    }
}
