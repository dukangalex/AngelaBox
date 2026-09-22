package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/**
 * First-pass ingest so a subscription with usable nodes can start, even when
 * the file is Clash YAML or a V2Ray/SS URI list instead of sing-box JSON.
 *
 * Routing from the source is kept (Clash rules → route.rules). China Direct /
 * ads / QUIC are not written here — those belong to overlay scripts.
 */
object ConfigIngest {
    data class Result(
        val content: String,
        val notes: List<String> = emptyList(),
        val format: Format = Format.SingBox,
    )

    enum class Format { SingBox, Clash, ShareLinks, Unknown }

    /** Format conversion only. China Direct / ads / QUIC are not written here. */
    fun adapt(content: String): Result {
        val trimmed = stripBom(content).trim()
        if (trimmed.isEmpty()) return Result(content, emptyList(), Format.Unknown)
        if (trimmed.length > ConfigCompat.MAX_CONFIG_CHARS) return Result(content)

        val jsonish = trimmed.first() == '{' || trimmed.first() == '['
        if (jsonish) {
            parseSingBox(trimmed)?.let { return it }
            parseVmessJsonBlob(trimmed)?.let { return it }
        }
        decodeSharePayload(trimmed)?.let { payload ->
            convertShareLinks(payload)?.let { return it }
        }
        if (looksLikeClash(trimmed)) {
            convertClash(trimmed)?.let { return it }
        }
        decodeClashPayload(trimmed)?.let { yaml ->
            convertClash(yaml)?.let { return it }
        }
        convertShareLinks(trimmed)?.let { return it }
        return Result(content, emptyList(), Format.Unknown)
    }

    fun looksConvertible(content: String): Boolean {
        val trimmed = stripBom(content).trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.first() == '{' || trimmed.first() == '[') return true
        if (looksLikeClash(trimmed)) return true
        if (SHARE_LINE.containsMatchIn(trimmed)) return true
        if (decodeSharePayload(trimmed) != null) return true
        return decodeClashPayload(trimmed) != null
    }

    private fun parseSingBox(raw: String): Result? {
        val root = try {
            if (raw.first() == '[') {
                val arr = JSONArray(raw)
                if (arr.length() == 0) return null
                val first = arr.optJSONObject(0) ?: return null
                if (!first.has("type") && !first.has("tag")) return null
                wrapLeaves(arr, "已将节点列表包成可启动配置")
            } else {
                val obj = JSONObject(raw)
                if (obj.has("outbounds") || obj.has("inbounds") || obj.has("route") || obj.has("dns")) {
                    Result(raw, emptyList(), Format.SingBox)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
        return root
    }

    private fun looksLikeClash(text: String): Boolean {
        val head = text.take(4000)
        return head.contains("proxies:") ||
            head.contains("proxy-groups:") ||
            head.contains("proxy-providers:") ||
            head.contains("mixed-port:") ||
            (head.contains("\nrules:") && (head.contains("DOMAIN-SUFFIX") || head.contains("GEOIP,")))
    }

    private fun convertClash(text: String): Result? {
        val tree = MiniYaml.parse(text) as? Map<*, *> ?: return null
        val proxies = asMapList(tree["proxies"])
        val groups = asMapList(tree["proxy-groups"] ?: tree["proxy_groups"])
        val rules = asStringList(tree["rules"])
        val notes = mutableListOf<String>()
        val outbounds = JSONArray()
        val tags = LinkedHashSet<String>()
        var skipped = 0
        for (proxy in proxies) {
            val converted = convertClashProxy(proxy)
            if (converted == null) {
                skipped++
                continue
            }
            val tag = converted.optString("tag")
            if (tag.isBlank() || !tags.add(tag)) continue
            outbounds.put(converted)
        }
        if (tags.isEmpty()) return null
        val leafTags = tags.toList()
        ensureDirect(outbounds, tags)
        val groupTags = LinkedHashSet<String>()
        for (group in groups) {
            val converted = convertClashGroup(group, tags) ?: continue
            val tag = converted.optString("tag")
            if (tag.isBlank() || !tags.add(tag)) continue
            groupTags.add(tag)
            outbounds.put(converted)
        }
        val mappedRules = JSONArray()
        val usedSets = LinkedHashSet<String>()
        var finalTag = groupTags.firstOrNull() ?: leafTags.first()
        for (rawRule in rules) {
            val parsed = parseClashRule(rawRule, tags) ?: continue
            if (parsed.finalTag != null) {
                finalTag = parsed.finalTag
                continue
            }
            parsed.rule?.let { mappedRules.put(it) }
            parsed.ruleSet?.let { usedSets.add(it) }
        }
        val root = JSONObject()
        root.put("outbounds", outbounds)
        val route = JSONObject()
        if (mappedRules.length() > 0) route.put("rules", mappedRules)
        route.put("final", finalTag)
        if (usedSets.isNotEmpty()) {
            val sets = JSONArray()
            usedSets.forEach { tag -> sets.put(remoteRuleSet(tag)) }
            route.put("rule_set", sets)
            root.put(
                "http_clients",
                JSONArray().put(JSONObject().put("tag", "angela-http-direct")),
            )
            route.put("default_http_client", "angela-http-direct")
        }
        root.put("route", route)
        notes += "已将 Clash 配置转为 sing-box，分流规则已保留"
        if (skipped > 0) notes += "跳过 $skipped 个内核暂不支持的节点"
        return Result(root.toString(), notes, Format.Clash)
    }

    private fun convertClashProxy(raw: Map<*, *>): JSONObject? {
        val name = str(raw["name"]).ifBlank { return null }
        val type = str(raw["type"]).lowercase()
        val server = str(raw["server"])
        val port = intVal(raw["port"]) ?: return null
        if (server.isBlank()) return null
        val out = JSONObject()
        out.put("tag", name)
        out.put("server", server)
        out.put("server_port", port)
        when (type) {
            "ss", "shadowsocks" -> {
                out.put("type", "shadowsocks")
                out.put("method", str(raw["cipher"]).ifBlank { "aes-256-gcm" })
                out.put("password", str(raw["password"]))
                val plugin = str(raw["plugin"])
                if (plugin.isNotEmpty()) {
                    out.put("plugin", plugin)
                    val opts = raw["plugin-opts"] ?: raw["plugin_opts"]
                    if (opts != null) out.put("plugin_opts", pluginOpts(opts))
                }
            }
            "vmess" -> {
                out.put("type", "vmess")
                out.put("uuid", str(raw["uuid"]))
                val security = str(raw["cipher"]).ifBlank { "auto" }
                out.put("security", security)
                intVal(raw["alterId"] ?: raw["alter-id"])?.let { out.put("alter_id", it) }
                putTls(out, raw)
                putTransport(out, raw)
            }
            "vless" -> {
                out.put("type", "vless")
                out.put("uuid", str(raw["uuid"]))
                str(raw["flow"]).takeIf { it.isNotEmpty() }?.let { out.put("flow", it) }
                str(raw["packet-encoding"] ?: raw["packet_encoding"]).takeIf { it.isNotEmpty() }
                    ?.let { out.put("packet_encoding", it) }
                putTls(out, raw)
                putTransport(out, raw)
            }
            "trojan" -> {
                out.put("type", "trojan")
                out.put("password", str(raw["password"]))
                putTls(out, raw, defaultEnabled = true)
                putTransport(out, raw)
            }
            "hysteria2", "hy2" -> {
                out.put("type", "hysteria2")
                out.put("password", str(raw["password"]).ifBlank { str(raw["auth"]) })
                putTls(out, raw, defaultEnabled = true)
                val obfs = raw["obfs"] ?: raw["obfs-password"]
                val obfsPwd = str(raw["obfs-password"]).ifBlank { str(obfs) }
                if (obfsPwd.isNotEmpty() && str(raw["obfs"]).lowercase() != "none") {
                    out.put(
                        "obfs",
                        JSONObject().put("type", "salamander").put("password", obfsPwd),
                    )
                }
            }
            "hysteria" -> {
                out.put("type", "hysteria")
                intVal(raw["up"] ?: raw["up-mbps"])?.let { out.put("up_mbps", it) }
                intVal(raw["down"] ?: raw["down-mbps"])?.let { out.put("down_mbps", it) }
                str(raw["auth-str"] ?: raw["auth_str"]).takeIf { it.isNotEmpty() }
                    ?.let { out.put("auth_str", it) }
                putTls(out, raw, defaultEnabled = true)
            }
            "tuic" -> {
                out.put("type", "tuic")
                str(raw["uuid"]).takeIf { it.isNotEmpty() }?.let { out.put("uuid", it) }
                out.put("password", str(raw["password"]))
                str(raw["congestion-controller"] ?: raw["congestion_control"])
                    .takeIf { it.isNotEmpty() }?.let { out.put("congestion_control", it) }
                putTls(out, raw, defaultEnabled = true)
            }
            "wireguard" -> {
                out.put("type", "wireguard")
                out.put("private_key", str(raw["private-key"] ?: raw["private_key"]))
                out.put("peer_public_key", str(raw["public-key"] ?: raw["public_key"]))
                val local = raw["ip"] ?: raw["ipv6"] ?: raw["local-address"]
                if (local != null) {
                    val arr = JSONArray()
                    when (local) {
                        is List<*> -> local.forEach { arr.put(it.toString()) }
                        else -> arr.put(local.toString())
                    }
                    out.put("local_address", arr)
                }
                intVal(raw["mtu"])?.let { out.put("mtu", it) }
            }
            "socks", "socks5" -> {
                out.put("type", "socks")
                str(raw["username"]).takeIf { it.isNotEmpty() }?.let { out.put("username", it) }
                str(raw["password"]).takeIf { it.isNotEmpty() }?.let { out.put("password", it) }
            }
            "http" -> {
                out.put("type", "http")
                str(raw["username"]).takeIf { it.isNotEmpty() }?.let { out.put("username", it) }
                str(raw["password"]).takeIf { it.isNotEmpty() }?.let { out.put("password", it) }
            }
            else -> return null
        }
        return out
    }

    private fun putTls(out: JSONObject, raw: Map<*, *>, defaultEnabled: Boolean = false) {
        val enabled = boolVal(raw["tls"]) ?: defaultEnabled ||
            raw["reality-opts"] != null || raw["reality_opts"] != null
        if (!enabled) return
        val tls = JSONObject().put("enabled", true)
        val sni = str(raw["servername"] ?: raw["sni"] ?: raw["server-name"])
        if (sni.isNotEmpty()) tls.put("server_name", sni)
        if (boolVal(raw["skip-cert-verify"] ?: raw["skip_cert_verify"]) == true) {
            tls.put("insecure", true)
        }
        val fp = str(raw["client-fingerprint"] ?: raw["client_fingerprint"])
        if (fp.isNotEmpty()) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
        val reality = asMap(raw["reality-opts"] ?: raw["reality_opts"])
        if (reality != null) {
            val pub = str(reality["public-key"] ?: reality["public_key"])
            val shortId = str(reality["short-id"] ?: reality["short_id"])
            tls.put(
                "reality",
                JSONObject()
                    .put("enabled", true)
                    .put("public_key", pub)
                    .put("short_id", shortId),
            )
        }
        out.put("tls", tls)
    }

    private fun putTransport(out: JSONObject, raw: Map<*, *>) {
        val network = str(raw["network"]).lowercase()
        if (network.isEmpty() || network == "tcp") return
        val transport = JSONObject().put("type", network)
        when (network) {
            "ws" -> {
                val opts = asMap(raw["ws-opts"] ?: raw["ws_opts"]) ?: emptyMap<String, Any?>()
                str(opts["path"] ?: raw["ws-path"]).takeIf { it.isNotEmpty() }
                    ?.let { transport.put("path", it) }
                val headers = asMap(opts["headers"])
                val host = str(headers?.get("Host") ?: headers?.get("host") ?: raw["ws-headers"])
                if (host.isNotEmpty()) {
                    transport.put("headers", JSONObject().put("Host", host))
                }
            }
            "grpc" -> {
                val opts = asMap(raw["grpc-opts"] ?: raw["grpc_opts"]) ?: emptyMap()
                str(opts["grpc-service-name"] ?: opts["service_name"])
                    .takeIf { it.isNotEmpty() }?.let { transport.put("service_name", it) }
            }
            "http", "h2" -> {
                transport.put("type", if (network == "h2") "http" else "http")
                val opts = asMap(raw["h2-opts"] ?: raw["http-opts"] ?: raw["http_opts"])
                str(opts?.get("path")).takeIf { it.isNotEmpty() }?.let { transport.put("path", it) }
            }
        }
        out.put("transport", transport)
    }

    private fun convertClashGroup(raw: Map<*, *>, known: Set<String>): JSONObject? {
        val name = str(raw["name"]).ifBlank { return null }
        val type = str(raw["type"]).lowercase()
        val members = asStringList(raw["proxies"]).map { mapSpecialTag(it) }
            .filter { it.isNotEmpty() && (it in known || it == "direct") }
        if (members.isEmpty()) return null
        val arr = JSONArray()
        members.forEach { arr.put(it) }
        val out = JSONObject().put("tag", name).put("outbounds", arr)
        return when (type) {
            "select", "selector" -> out.put("type", "selector").put("interrupt_exist_connections", false)
            "url-test", "urltest", "fallback", "load-balance" -> {
                out.put("type", "urltest")
                out.put("url", str(raw["url"]).ifBlank { "https://www.gstatic.com/generate_204" })
                val interval = str(raw["interval"]).ifBlank { "300" }
                out.put("interval", if (interval.endsWith("s") || interval.endsWith("m")) interval else "${interval}s")
                intVal(raw["tolerance"])?.let { out.put("tolerance", it) }
                out.put("idle_timeout", "30m")
                out.put("interrupt_exist_connections", false)
            }
            else -> null
        }
    }

    private data class ClashRule(
        val rule: JSONObject? = null,
        val ruleSet: String? = null,
        val finalTag: String? = null,
    )

    private fun parseClashRule(raw: String, known: Set<String>): ClashRule? {
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val kind = parts[0].uppercase()
        if (kind == "MATCH" || kind == "FINAL") {
            val target = mapSpecialTag(parts.getOrNull(1) ?: return null)
            return ClashRule(finalTag = target)
        }
        if (parts.size < 3) return null
        val payload = parts[1]
        val target = mapSpecialTag(parts[2])
        if (target.equals("REJECT", true) || target.equals("REJECT-DROP", true)) {
            val rule = matchField(kind, payload) ?: return null
            rule.put("action", "reject")
            if (target.equals("REJECT-DROP", true)) rule.put("method", "drop")
            return ClashRule(rule = rule)
        }
        val outbound = if (target in known || target == "direct") target else return null
        when (kind) {
            "GEOIP" -> {
                val tag = if (payload.equals("CN", true)) "geoip-cn" else "geoip-${payload.lowercase()}"
                return ClashRule(
                    rule = JSONObject().put("rule_set", tag).put("outbound", outbound),
                    ruleSet = tag,
                )
            }
            "GEOSITE" -> {
                val tag = when {
                    payload.equals("CN", true) -> "geosite-cn"
                    payload.startsWith("geosite-") -> payload
                    else -> "geosite-${payload.lowercase()}"
                }
                return ClashRule(
                    rule = JSONObject().put("rule_set", tag).put("outbound", outbound),
                    ruleSet = tag,
                )
            }
        }
        val rule = matchField(kind, payload) ?: return null
        rule.put("outbound", outbound)
        return ClashRule(rule = rule)
    }

    private fun matchField(kind: String, payload: String): JSONObject? {
        val rule = JSONObject()
        when (kind) {
            "DOMAIN" -> rule.put("domain", payload)
            "DOMAIN-SUFFIX" -> rule.put("domain_suffix", payload)
            "DOMAIN-KEYWORD" -> rule.put("domain_keyword", payload)
            "DOMAIN-REGEX" -> rule.put("domain_regex", payload)
            "IP-CIDR", "IP-CIDR6" -> rule.put("ip_cidr", payload)
            "SRC-IP-CIDR" -> rule.put("source_ip_cidr", payload)
            "DST-PORT" -> intVal(payload)?.let { rule.put("port", it) } ?: return null
            "SRC-PORT" -> intVal(payload)?.let { rule.put("source_port", it) } ?: return null
            "PROCESS-NAME" -> rule.put("process_name", payload)
            "PROCESS-PATH" -> rule.put("process_path", payload)
            "NETWORK" -> rule.put("network", payload.lowercase())
            else -> return null
        }
        return rule
    }

    private fun convertShareLinks(text: String): Result? {
        val lines = expandShareText(text)
        if (lines.isEmpty()) return null
        val outbounds = JSONArray()
        val tags = LinkedHashSet<String>()
        var skipped = 0
        for (line in lines) {
            val converted = convertShareLine(line)
            if (converted == null) {
                skipped++
                continue
            }
            var tag = converted.optString("tag").ifBlank { "node-${tags.size + 1}" }
            var n = 2
            val base = tag
            while (!tags.add(tag)) {
                tag = "$base-$n"
                n++
            }
            converted.put("tag", tag)
            outbounds.put(converted)
        }
        if (tags.isEmpty()) return null
        ensureDirect(outbounds, tags)
        val selector = JSONObject()
            .put("type", "selector")
            .put("tag", "节点选择")
            .put("outbounds", JSONArray(tags.filter { it != "direct" }))
            .put("interrupt_exist_connections", false)
        outbounds.put(selector)
        val root = JSONObject()
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("final", "节点选择"))
        val notes = mutableListOf("已将节点链接转为 sing-box 配置")
        if (skipped > 0) notes += "跳过 $skipped 条无法识别的链接"
        return Result(root.toString(), notes, Format.ShareLinks)
    }

    private fun convertShareLine(line: String): JSONObject? {
        val raw = line.trim()
        val scheme = raw.substringBefore("://", "").lowercase()
        val body = raw.substringAfter("://", "")
        if (scheme.isEmpty() || body.isEmpty()) return null
        return when (scheme) {
            "ss" -> parseSs(body)
            "vmess" -> parseVmess(body)
            "vless" -> parseVless(body)
            "trojan" -> parseTrojan(body)
            "hysteria2", "hy2" -> parseHysteria2(body)
            "tuic" -> parseTuic(body)
            "socks", "socks5" -> parseUserHost(body, "socks")
            "http", "https" -> if (scheme == "http") parseUserHost(body, "http") else null
            else -> null
        }
    }

    private fun parseSs(body: String): JSONObject? {
        val (main, fragment) = splitFragment(body)
        val decoded = if ('@' in main && !main.substringBefore('@').contains(':')) {
            main
        } else if ('@' in main) {
            main
        } else {
            val inner = decodeB64(main.substringBefore('#'))?.toString(Charsets.UTF_8) ?: return null
            if ('@' in inner) inner else "$inner@placeholder"
        }
        val userHost = if ('@' in decoded) decoded else return null
        val user = userHost.substringBefore('@')
        val hostPort = userHost.substringAfter('@')
        val methodPass = decodeB64(user)?.toString(Charsets.UTF_8) ?: user
        val method = methodPass.substringBefore(':')
        val password = methodPass.substringAfter(':', "")
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = intVal(hostPort.substringAfterLast(':')) ?: return null
        val tag = fragment.ifBlank { host }
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", urlDecode(tag))
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)
    }

    private fun parseVmess(body: String): JSONObject? {
        val json = decodeB64(body.substringBefore('#'))?.toString(Charsets.UTF_8) ?: return null
        val obj = JSONObject(json)
        val tag = obj.optString("ps").ifBlank { obj.optString("add") }
        val out = JSONObject()
            .put("type", "vmess")
            .put("tag", tag)
            .put("server", obj.optString("add"))
            .put("server_port", obj.optString("port").toIntOrNull() ?: return null)
            .put("uuid", obj.optString("id"))
            .put("security", obj.optString("scy").ifBlank { "auto" })
        obj.optString("aid").toIntOrNull()?.let { out.put("alter_id", it) }
        val tlsOn = obj.optString("tls").equals("tls", true)
        if (tlsOn) {
            val tls = JSONObject().put("enabled", true)
            val sni = obj.optString("sni").ifBlank { obj.optString("host") }
            if (sni.isNotEmpty()) tls.put("server_name", sni)
            out.put("tls", tls)
        }
        val net = obj.optString("net").lowercase()
        if (net.isNotEmpty() && net != "tcp") {
            val transport = JSONObject().put("type", net)
            obj.optString("path").takeIf { it.isNotEmpty() }?.let { transport.put("path", it) }
            obj.optString("host").takeIf { it.isNotEmpty() }
                ?.let { transport.put("headers", JSONObject().put("Host", it)) }
            if (net == "grpc") {
                obj.optString("path").takeIf { it.isNotEmpty() }?.let { transport.put("service_name", it) }
            }
            out.put("transport", transport)
        }
        return out
    }

    private fun parseVless(body: String): JSONObject? = parseUserHostQuery(body, "vless") { out, query, host ->
        out.put("uuid", query["id"] ?: out.optString("uuid"))
        val uuid = body.substringBefore('@')
        out.put("uuid", uuid)
        query["flow"]?.let { out.put("flow", it) }
        query["packetEncoding"]?.let { out.put("packet_encoding", it) }
        putQueryTls(out, query, host, defaultOn = query["security"].equals("tls", true) || query["security"].equals("reality", true))
        putQueryTransport(out, query)
        if (query["security"].equals("reality", true)) {
            val tls = out.optJSONObject("tls") ?: JSONObject().put("enabled", true).also { out.put("tls", it) }
            tls.put(
                "reality",
                JSONObject()
                    .put("enabled", true)
                    .put("public_key", query["pbk"].orEmpty())
                    .put("short_id", query["sid"].orEmpty()),
            )
            query["fp"]?.let { fp ->
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            }
        }
    }

    private fun parseTrojan(body: String): JSONObject? = parseUserHostQuery(body, "trojan") { out, query, host ->
        out.put("password", body.substringBefore('@'))
        putQueryTls(out, query, host, defaultOn = true)
        putQueryTransport(out, query)
    }

    private fun parseHysteria2(body: String): JSONObject? = parseUserHostQuery(body, "hysteria2") { out, query, host ->
        out.put("password", body.substringBefore('@'))
        putQueryTls(out, query, host, defaultOn = true)
        query["obfs-password"]?.let { pwd ->
            out.put("obfs", JSONObject().put("type", "salamander").put("password", pwd))
        }
    }

    private fun parseTuic(body: String): JSONObject? = parseUserHostQuery(body, "tuic") { out, query, host ->
        val user = body.substringBefore('@')
        val uuid = user.substringBefore(':')
        val password = user.substringAfter(':', "")
        out.put("uuid", uuid)
        if (password.isNotEmpty()) out.put("password", password)
        putQueryTls(out, query, host, defaultOn = true)
        query["congestion_control"]?.let { out.put("congestion_control", it) }
    }

    private fun parseUserHost(body: String, type: String): JSONObject? {
        val (main, fragment) = splitFragment(body)
        val userHost = main
        val hostPort = userHost.substringAfter('@', userHost)
        val userPass = if ('@' in userHost) userHost.substringBefore('@') else ""
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = intVal(hostPort.substringAfterLast(':')) ?: return null
        val out = JSONObject()
            .put("type", type)
            .put("tag", urlDecode(fragment).ifBlank { host })
            .put("server", host)
            .put("server_port", port)
        if (userPass.isNotEmpty()) {
            out.put("username", urlDecode(userPass.substringBefore(':')))
            out.put("password", urlDecode(userPass.substringAfter(':', "")))
        }
        return out
    }

    private fun parseUserHostQuery(
        body: String,
        type: String,
        fill: (JSONObject, Map<String, String>, String) -> Unit,
    ): JSONObject? {
        val (main, fragment) = splitFragment(body)
        val hostPortQuery = main.substringAfter('@', "")
        if (hostPortQuery.isEmpty()) return null
        val hostPort = hostPortQuery.substringBefore('?')
        val query = parseQuery(hostPortQuery.substringAfter('?', ""))
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = intVal(hostPort.substringAfterLast(':')) ?: return null
        val out = JSONObject()
            .put("type", type)
            .put("tag", urlDecode(fragment).ifBlank { host })
            .put("server", host)
            .put("server_port", port)
        fill(out, query, host)
        return out
    }

    private fun putQueryTls(out: JSONObject, query: Map<String, String>, host: String, defaultOn: Boolean) {
        val security = query["security"].orEmpty().lowercase()
        val on = defaultOn || security == "tls" || security == "reality"
        if (!on) return
        val tls = JSONObject().put("enabled", true)
        val sni = query["sni"] ?: query["host"] ?: host
        if (sni.isNotEmpty()) tls.put("server_name", sni)
        if (query["allowInsecure"] == "1" || query["insecure"] == "1") tls.put("insecure", true)
        query["fp"]?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
        out.put("tls", tls)
    }

    private fun putQueryTransport(out: JSONObject, query: Map<String, String>) {
        val type = (query["type"] ?: query["network"]).orEmpty().lowercase()
        if (type.isEmpty() || type == "tcp") return
        val transport = JSONObject().put("type", type)
        (query["path"] ?: query["serviceName"])?.let { value ->
            if (type == "grpc") transport.put("service_name", value) else transport.put("path", value)
        }
        query["host"]?.let { transport.put("headers", JSONObject().put("Host", it)) }
        out.put("transport", transport)
    }

    private fun wrapLeaves(arr: JSONArray, note: String): Result {
        val tags = LinkedHashSet<String>()
        val outbounds = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val tag = item.optString("tag").ifBlank { "node-${i + 1}" }
            item.put("tag", tag)
            if (!tags.add(tag)) continue
            outbounds.put(item)
        }
        if (tags.isEmpty()) return Result("{}", emptyList(), Format.Unknown)
        ensureDirect(outbounds, tags)
        outbounds.put(
            JSONObject()
                .put("type", "selector")
                .put("tag", "节点选择")
                .put("outbounds", JSONArray(tags.filter { it != "direct" }))
                .put("interrupt_exist_connections", false),
        )
        val root = JSONObject()
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("final", "节点选择"))
        return Result(root.toString(), listOf(note), Format.SingBox)
    }

    private fun ensureDirect(outbounds: JSONArray, tags: MutableSet<String>) {
        if (tags.any { it.equals("direct", true) }) return
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        tags.add("direct")
    }

    private fun remoteRuleSet(tag: String): JSONObject {
        val repo = if (tag.startsWith("geoip-")) "sing-geoip@rule-set" else "sing-geosite@rule-set"
        return JSONObject()
            .put("tag", tag)
            .put("type", "remote")
            .put("format", "binary")
            .put("url", "https://testingcf.jsdelivr.net/gh/SagerNet/$repo/$tag.srs")
            .put("http_client", "angela-http-direct")
    }

    private fun mapSpecialTag(name: String): String {
        val t = name.trim()
        return when {
            t.equals("DIRECT", true) || t == "直连" -> "direct"
            else -> t
        }
    }

    private fun pluginOpts(raw: Any?): String {
        if (raw is Map<*, *>) {
            return raw.entries.joinToString(";") { "${it.key}=${it.value}" }
        }
        return raw?.toString().orEmpty()
    }

    private fun expandShareText(text: String): List<String> {
        val direct = text.lineSequence().map { it.trim() }.filter { SHARE_LINE.containsMatchIn(it) }.toList()
        if (direct.isNotEmpty()) return direct
        val decoded = decodeSharePayload(text) ?: return emptyList()
        return decoded.lineSequence().map { it.trim() }.filter { SHARE_LINE.containsMatchIn(it) }.toList()
    }

    private fun decodeSharePayload(text: String): String? {
        val compact = text.trim().replace("\\s".toRegex(), "")
        if (compact.length < 16 || compact.any { it !in B64_CHARS }) return null
        val bytes = decodeB64(compact) ?: return null
        val decoded = bytes.toString(Charsets.UTF_8)
        return decoded.takeIf { SHARE_LINE.containsMatchIn(it) }
    }

    private fun decodeClashPayload(text: String): String? {
        val compact = text.trim().replace("\\s".toRegex(), "")
        if (compact.length < 16 || compact.any { it !in B64_CHARS }) return null
        val decoded = decodeB64(compact)?.toString(Charsets.UTF_8) ?: return null
        return decoded.takeIf { looksLikeClash(it) }
    }

    private fun parseVmessJsonBlob(raw: String): Result? {
        return try {
            val lines = if (raw.first() == '[') {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    vmessShareLine(obj)
                }
            } else {
                val obj = JSONObject(raw)
                if (obj.has("outbounds") || obj.has("inbounds") || obj.has("route") || obj.has("dns")) {
                    return null
                }
                listOfNotNull(vmessShareLine(obj))
            }
            if (lines.isEmpty()) null else convertShareLinks(lines.joinToString("\n"))
        } catch (_: Exception) {
            null
        }
    }

    private fun vmessShareLine(obj: JSONObject): String? {
        if (!obj.has("add") || !obj.has("id")) return null
        val encoded = Base64.getEncoder().encodeToString(obj.toString().toByteArray())
        return "vmess://$encoded"
    }

    private fun decodeB64(value: String): ByteArray? {
        val cleaned = value.trim().replace("\n", "").replace("\r", "").replace("_", "/").replace("-", "+")
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: Exception) {
            try {
                Base64.getUrlDecoder().decode(padded)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun splitFragment(value: String): Pair<String, String> {
        val idx = value.indexOf('#')
        return if (idx < 0) value to "" else value.substring(0, idx) to value.substring(idx + 1)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isEmpty()) return@mapNotNull null
            key to urlDecode(pair.substringAfter('=', ""))
        }.toMap()
    }

    private fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }

    private fun stripBom(value: String): String =
        if (value.isNotEmpty() && value[0] == '\uFEFF') value.substring(1) else value

    private fun str(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim()
        else -> value.toString().trim()
    }

    private fun intVal(value: Any?): Int? = when (value) {
        null -> null
        is Number -> value.toInt()
        else -> value.toString().substringBefore(' ').toIntOrNull()
    }

    private fun boolVal(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }
        else -> null
    }

    private fun asMap(value: Any?): Map<*, *>? = value as? Map<*, *>

    private fun asMapList(value: Any?): List<Map<*, *>> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { it as? Map<*, *> }
    }

    private fun asStringList(value: Any?): List<String> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
    }

    private val SHARE_LINE = Regex("(?i)^(ss|ssr|vmess|vless|trojan|hysteria2?|hy2|tuic|socks5?|http)://")
    private const val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
}

internal object MiniYaml {
    fun parse(text: String): Any? {
        val lines = tokenize(text)
        if (lines.isEmpty()) return null
        val index = intArrayOf(0)
        return parseNode(lines, index, 0)
    }

    private data class YLine(val indent: Int, val raw: String)

    private fun tokenize(text: String): List<YLine> {
        val out = ArrayList<YLine>()
        text.lineSequence().forEach { original ->
            val noComment = stripComment(original)
            if (noComment.isBlank()) return@forEach
            var indent = 0
            while (indent < noComment.length && noComment[indent] == ' ') indent++
            val content = noComment.trim()
            if (content.isEmpty() || content == "---" || content == "...") return@forEach
            out += YLine(indent, content)
        }
        return out
    }

    private fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble -> return line.substring(0, i).replace("\t", "  ")
            }
        }
        return line.replace("\t", "  ")
    }

    private fun parseNode(lines: List<YLine>, index: IntArray, minIndent: Int): Any? {
        if (index[0] >= lines.size) return null
        val line = lines[index[0]]
        if (line.indent < minIndent) return null
        return if (line.raw.startsWith("- ")) {
            parseList(lines, index, line.indent)
        } else {
            parseMap(lines, index, line.indent)
        }
    }

    private fun parseList(lines: List<YLine>, index: IntArray, indent: Int): List<Any?> {
        val list = ArrayList<Any?>()
        while (index[0] < lines.size) {
            val line = lines[index[0]]
            if (line.indent < indent) break
            if (line.indent > indent) break
            if (!line.raw.startsWith("- ")) break
            val rest = line.raw.substring(2).trim()
            index[0]++
            when {
                rest.isEmpty() -> list += parseNode(lines, index, indent + 1)
                rest.startsWith("{") || rest.startsWith("[") -> list += parseFlow(rest)
                ':' in rest && !rest.startsWith("{") -> {
                    val map = LinkedHashMap<String, Any?>()
                    val (k, v) = splitPair(rest)
                    map[k] = parseScalarOrChild(v, lines, index, indent + 2)
                    while (index[0] < lines.size) {
                        val child = lines[index[0]]
                        if (child.indent <= indent) break
                        if (child.raw.startsWith("- ")) {
                            val existing = map.values.lastOrNull()
                            if (existing is MutableList<*>) {
                                @Suppress("UNCHECKED_CAST")
                                (existing as MutableList<Any?>).addAll(parseList(lines, index, child.indent) as Collection<Any?>)
                            } else {
                                val key = map.keys.last()
                                map[key] = parseNode(lines, index, child.indent)
                            }
                        } else {
                            val (ck, cv) = splitPair(child.raw)
                            index[0]++
                            map[ck] = parseScalarOrChild(cv, lines, index, child.indent + 1)
                        }
                    }
                    list += map
                }
                else -> list += parseScalar(rest)
            }
        }
        return list
    }

    private fun parseMap(lines: List<YLine>, index: IntArray, indent: Int): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (index[0] < lines.size) {
            val line = lines[index[0]]
            if (line.indent < indent) break
            if (line.raw.startsWith("- ")) break
            if (line.indent > indent) break
            val (k, v) = splitPair(line.raw)
            index[0]++
            map[k] = parseScalarOrChild(v, lines, index, indent + 1)
        }
        return map
    }

    private fun parseScalarOrChild(
        value: String,
        lines: List<YLine>,
        index: IntArray,
        childIndent: Int,
    ): Any? {
        if (value.isNotEmpty()) {
            return if (value.startsWith("{") || value.startsWith("[")) parseFlow(value) else parseScalar(value)
        }
        if (index[0] >= lines.size) return emptyMap<String, Any?>()
        val next = lines[index[0]]
        return if (next.indent >= childIndent) parseNode(lines, index, next.indent) else emptyMap<String, Any?>()
    }

    private fun splitPair(raw: String): Pair<String, String> {
        val idx = raw.indexOf(':')
        if (idx < 0) return raw.trim() to ""
        val key = raw.substring(0, idx).trim().trim('"', '\'')
        val value = raw.substring(idx + 1).trim()
        return key to value
    }

    private fun parseScalar(raw: String): Any? {
        val v = raw.trim()
        if (v == "|" || v == ">") return ""
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length - 1)
        }
        when (v.lowercase()) {
            "true", "yes", "on" -> return true
            "false", "no", "off" -> return false
            "null", "~", "" -> return null
        }
        v.toIntOrNull()?.let { return it }
        v.toLongOrNull()?.let { return it }
        v.toDoubleOrNull()?.let { return it }
        return v
    }

    private fun parseFlow(raw: String): Any? {
        val s = raw.trim()
        return when {
            s.startsWith("{") -> parseFlowMap(s)
            s.startsWith("[") -> parseFlowList(s)
            else -> parseScalar(s)
        }
    }

    private fun parseFlowMap(raw: String): Map<String, Any?> {
        val inner = raw.trim().removePrefix("{").removeSuffix("}").trim()
        val map = LinkedHashMap<String, Any?>()
        splitFlow(inner).forEach { part ->
            val (k, v) = splitPair(part)
            map[k] = if (v.startsWith("{") || v.startsWith("[")) parseFlow(v) else parseScalar(v)
        }
        return map
    }

    private fun parseFlowList(raw: String): List<Any?> {
        val inner = raw.trim().removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        return splitFlow(inner).map { part ->
            val p = part.trim()
            if (p.startsWith("{") || p.startsWith("[")) parseFlow(p) else parseScalar(p)
        }
    }

    private fun splitFlow(raw: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var depth = 0
        var inSingle = false
        var inDouble = false
        for (c in raw) {
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                !inSingle && !inDouble && (c == '{' || c == '[') -> {
                    depth++
                    buf.append(c)
                }
                !inSingle && !inDouble && (c == '}' || c == ']') -> {
                    depth--
                    buf.append(c)
                }
                !inSingle && !inDouble && c == ',' && depth == 0 -> {
                    out += buf.toString().trim()
                    buf.clear()
                }
                else -> buf.append(c)
            }
        }
        if (buf.isNotBlank()) out += buf.toString().trim()
        return out
    }
}
