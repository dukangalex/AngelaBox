package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

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
        /** Chinese reason. Do not start, and do not fall back to DIRECT. */
        val fatal: String? = null,
    )

    enum class Format { SingBox, Clash, ShareLinks, Unknown }

    private val pendingEchDoh = ThreadLocal.withInitial { mutableListOf<Pair<String, String>>() }

    private fun finishEch(result: Result): Result {
        val hints = pendingEchDoh.get()
        if (hints.isEmpty()) return result
        val trimmed = result.content.trim()
        if (trimmed.isEmpty() || trimmed.first() != '{') return result
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return result
        }
        if (!installEchDoh(root, hints)) return result
        val notes = result.notes + "节点的 ECH 会按链接里的 DNS 地址查询"
        return result.copy(content = root.toString(), notes = notes)
    }

    /** Format conversion only. China Direct / ads / QUIC are not written here. */
    fun adapt(content: String): Result {
        pendingEchDoh.get().clear()
        return finishEch(adaptUnlocked(content))
    }

    private fun adaptUnlocked(content: String): Result {
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
            return convertClash(trimmed)
                ?: unsupported("Clash 配置无法解析，没有改成直连。")
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
                if (isClashDocument(obj)) {
                    convertClashTree(jsonTree(obj) as? Map<*, *> ?: emptyMap<String, Any?>())
                } else if (obj.has("outbounds") || obj.has("inbounds") || obj.has("route") || obj.has("dns")) {
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

    private val CLASH_TOP = Regex("(?m)^\\s*(proxies|proxy-groups|proxy-providers)\\s*:")
    private val CLASH_MIXED = Regex("(?m)^\\s*mixed-port\\s*:")
    private val CLASH_PORT = Regex("(?m)^\\s*(port|socks-port|redir-port|tproxy-port|mixed-port)\\s*:")
    private val CLASH_RULES = Regex("(?m)^\\s*rules\\s*:")

    private fun looksLikeClash(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        if (CLASH_TOP.containsMatchIn(lower)) return true
        if (CLASH_MIXED.containsMatchIn(lower)) return true
        return CLASH_PORT.containsMatchIn(lower) && CLASH_RULES.containsMatchIn(lower)
    }

    private fun mapIgnoreCase(tree: Map<*, *>, key: String): Any? {
        tree[key]?.let { return it }
        for ((k, v) in tree) {
            if (k is String && k.equals(key, ignoreCase = true)) return v
        }
        return null
    }

    private fun isClashDocument(obj: JSONObject): Boolean {
        if (obj.has("proxies") || obj.has("proxy-groups") || obj.has("proxy-providers")) return true
        return obj.has("mixed-port") && !obj.has("outbounds")
    }

    private fun convertClash(text: String): Result? {
        val tree = MiniYaml.parse(text) as? Map<*, *> ?: return null
        return convertClashTree(tree)
    }

    private fun convertClashTree(tree: Map<*, *>): Result {
        val proxies = asMapList(mapIgnoreCase(tree, "proxies"))
        val groups = asMapList(mapIgnoreCase(tree, "proxy-groups") ?: mapIgnoreCase(tree, "proxy_groups"))
        val rules = asStringList(mapIgnoreCase(tree, "rules"))
        val providers = asMap(
            mapIgnoreCase(tree, "rule-providers") ?: mapIgnoreCase(tree, "rule_providers"),
        ) ?: emptyMap<Any?, Any?>()
        val notes = mutableListOf<String>()
        val outbounds = JSONArray()
        val endpoints = JSONArray()
        val tags = LinkedHashSet<String>()
        val skips = SkipBag()
        var skipped = 0
        for (proxy in proxies) {
            val converted = convertClashProxy(proxy, skips)
            if (converted == null) {
                skipped++
                continue
            }
            val tag = converted.optString("tag")
            if (tag.isBlank() || !tags.add(tag)) continue
            if (converted.optString("type") == "wireguard") {
                endpoints.put(converted)
            } else {
                outbounds.put(converted)
            }
        }
        if (tags.isEmpty()) {
            return unsupported(unsupportedNodeMessage(skips))
        }
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
        val usedSets = LinkedHashMap<String, String?>()
        var finalTag = groupTags.firstOrNull() ?: leafTags.first()
        var skippedRules = 0
        for (rawRule in rules) {
            val parsed = parseClashRule(rawRule, tags, providers) ?: run {
                skippedRules++
                continue
            }
            if (parsed.finalTag != null) {
                finalTag = parsed.finalTag
                continue
            }
            parsed.rule?.let { mappedRules.put(it) }
            parsed.ruleSets.forEach { (tag, url) ->
                if (!usedSets.containsKey(tag)) usedSets[tag] = url
            }
        }
        val root = JSONObject()
        root.put("outbounds", outbounds)
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        val route = JSONObject()
        if (mappedRules.length() > 0) route.put("rules", mappedRules)
        route.put("final", finalTag)
        if (usedSets.isNotEmpty()) {
            val sets = JSONArray()
            usedSets.forEach { (tag, url) -> sets.put(remoteRuleSet(tag, url)) }
            route.put("rule_set", sets)
            root.put(
                "http_clients",
                JSONArray().put(JSONObject().put("tag", "angela-http-direct")),
            )
            route.put("default_http_client", "angela-http-direct")
        }
        root.put("route", route)
        notes += "已将 Clash 配置转为 sing-box，能识别的分流已保留"
        if (skippedRules > 0) {
            notes += "有 $skippedRules 条分流暂时对不上 sing-box，已跳过，没有改成直连"
        }
        if (skipped > 0) notes += skipNote(skipped, skips)
        return Result(root.toString(), notes, Format.Clash)
    }

    private class SkipBag {
        var xhttp = 0
        var masque = 0
    }

    private fun skipNote(skipped: Int, skips: SkipBag): String {
        val bits = mutableListOf<String>()
        if (skips.xhttp > 0) bits += "xhttp"
        if (skips.masque > 0) bits += "MASQUE"
        return if (bits.isEmpty()) {
            "跳过 $skipped 个内核暂不支持的节点"
        } else {
            "跳过 $skipped 个内核暂不支持的节点（${bits.joinToString("、")}）"
        }
    }

    private fun unsupportedNodeMessage(skips: SkipBag): String {
        val bits = mutableListOf<String>()
        if (skips.xhttp > 0) bits += "xhttp"
        if (skips.masque > 0) bits += "MASQUE"
        val named = if (bits.isEmpty()) "这些协议" else bits.joinToString("、")
        return "没有可用节点（$named 当前内核还不支持）。没有改成直连。"
    }

    private fun unsupported(message: String): Result =
        Result("", listOf(message), Format.Clash, message)

    private fun convertClashProxy(raw: Map<*, *>, skips: SkipBag): JSONObject? {
        val name = str(raw["name"]).ifBlank { return null }
        val type = str(raw["type"]).lowercase()
        if (type == "masque" || type == "masque-client") {
            skips.masque++
            return null
        }
        val network = str(raw["network"]).lowercase()
        if (network == "xhttp" || network == "splithttp") {
            skips.xhttp++
            return null
        }
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
                if (!putTransport(out, raw)) return null
            }
            "vless" -> {
                out.put("type", "vless")
                out.put("uuid", str(raw["uuid"]))
                str(raw["flow"]).takeIf { it.isNotEmpty() }?.let { out.put("flow", it) }
                str(raw["packet-encoding"] ?: raw["packet_encoding"]).takeIf { it.isNotEmpty() }
                    ?.let { out.put("packet_encoding", it) }
                putTls(out, raw)
                if (!putTransport(out, raw)) return null
            }
            "trojan" -> {
                out.put("type", "trojan")
                out.put("password", str(raw["password"]))
                putTls(out, raw, defaultEnabled = true)
                if (!putTransport(out, raw)) return null
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
                val locals = mutableListOf<String>()
                val local = raw["ip"] ?: raw["ipv6"] ?: raw["local-address"] ?: raw["local_address"]
                when (local) {
                    is List<*> -> local.forEach { value ->
                        str(value).takeIf { it.isNotEmpty() }?.let { locals += it }
                    }
                    null -> Unit
                    else -> str(local).takeIf { it.isNotEmpty() }?.let { locals += it }
                }
                val reserved = when (val value = raw["reserved"]) {
                    is List<*> -> value.mapNotNull { intVal(it) }
                    else -> str(value).split(',').mapNotNull { it.trim().toIntOrNull() }
                }
                return wireguardEndpoint(
                    tag = name,
                    privateKey = str(raw["private-key"] ?: raw["private_key"]),
                    peerPublic = str(raw["public-key"] ?: raw["public_key"]),
                    server = server,
                    port = port,
                    addresses = locals,
                    reserved = reserved,
                    mtu = intVal(raw["mtu"]),
                )
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
        val ech = asMap(raw["ech-opts"] ?: raw["ech_opts"])
        val echOn = ech != null && boolVal(ech["enable"] ?: ech["enabled"]) != false
        val enabled = boolVal(raw["tls"]) ?: defaultEnabled ||
            raw["reality-opts"] != null || raw["reality_opts"] != null || echOn
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
        if (echOn && ech != null) {
            putEch(
                tls,
                ech["config"],
                str(ech["query-server-name"] ?: ech["query_server_name"]),
            )
        }
        out.put("tls", tls)
    }

    private fun putEch(tls: JSONObject, raw: Any?, queryName: String) {
        val share = (raw as? String)?.let { parseEchShare(it) }
        val query = share?.queryName?.takeIf { it.isNotEmpty() } ?: queryName
        val pemSource = if (share != null && share.queryName.isNotEmpty()) null else raw
        val echObj = JSONObject().put("enabled", true)
        val pem = echPemFrom(pemSource)
        if (pem != null) echObj.put("config", JSONArray().put(pem))
        if (query.isNotEmpty()) echObj.put("query_server_name", query)
        if (pem == null && query.isEmpty()) return
        share?.dohUrl?.takeIf { query.isNotEmpty() }?.let { pendingEchDoh.get().add(query to it) }
        tls.put("ech", echObj)
    }

    /**
     * Xray / v2rayNG: `ech=cloudflare-ech.com+https://dns.example/dns-query`.
     * The name is sing-box `query_server_name`. The URL is only a DNS hint;
     * the config list is not a PEM.
     */
    internal fun parseEchShare(raw: String): EchShare? {
        val text = raw.trim()
        if (text.isEmpty() || text == "0" || text.equals("false", true)) return null
        if (text == "1" || text.equals("true", true)) return EchShare("", null)
        val plus = text.indexOf('+')
        if (plus > 0) {
            val name = text.substring(0, plus).trim()
            val rest = text.substring(plus + 1).trim()
            if (isDnsName(name) && rest.startsWith("https://", true)) {
                return EchShare(name, rest)
            }
        }
        val space = text.indexOf(' ')
        if (space > 0) {
            val name = text.substring(0, space).trim()
            val rest = text.substring(space + 1).trim()
            if (isDnsName(name) && rest.startsWith("https://", true)) {
                return EchShare(name, rest)
            }
        }
        if (isDnsName(text) && text.contains('.')) return EchShare(text, null)
        return null
    }

    internal data class EchShare(val queryName: String, val dohUrl: String?)

    private fun isDnsName(name: String): Boolean {
        if (name.length !in 3..253 || !name.contains('.')) return false
        if (name.any { it.isWhitespace() || it == '/' || it == ':' || it == '+' || it == '=' }) return false
        return name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }

    /**
     * sing-box accepts exactly one PEM block of type "ECH CONFIGS".
     * Clash / v2ray usually store raw base64. Already-valid PEM stays.
     * Unusable text returns null so the caller can fall back to DNS fetch
     * or drop ECH. This does not invent a config the kernel cannot parse.
     */
    private fun echPemFrom(raw: Any?): String? {
        val items = when (raw) {
            null, JSONObject.NULL -> return null
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> listOf(raw)
            else -> listOf(raw.toString())
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        if (items.size == 1 && isCleanEchPem(items[0])) return items[0].trim()
        val chunks = ArrayList<ByteArray>()
        for (item in items) {
            val decoded = decodeEchBytes(item) ?: return null
            if (decoded.isNotEmpty()) chunks.add(decoded)
        }
        if (chunks.isEmpty()) return null
        val all = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(all, pos)
            pos += chunk.size
        }
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(all)
        return "-----BEGIN ECH CONFIGS-----\n$body\n-----END ECH CONFIGS-----"
    }

    private fun isCleanEchPem(text: String): Boolean {
        val start = text.indexOf("-----BEGIN ECH CONFIGS-----")
        if (start < 0) return false
        val endMark = "-----END ECH CONFIGS-----"
        val end = text.indexOf(endMark, start)
        if (end < 0) return false
        return text.substring(end + endMark.length).isBlank()
    }

    private fun decodeEchBytes(item: String): ByteArray? {
        val blocks = ECH_PEM.findAll(item).toList()
        if (blocks.isNotEmpty()) {
            val parts = ArrayList<ByteArray>()
            for (block in blocks) {
                val one = decodeB64(block.groupValues[1]) ?: return null
                parts.add(one)
            }
            if (parts.size == 1) return parts[0]
            val all = ByteArray(parts.sumOf { it.size })
            var pos = 0
            for (part in parts) {
                part.copyInto(all, pos)
                pos += part.size
            }
            return all
        }
        val compact = item.replace("\\s".toRegex(), "")
        if (compact.length < 4) return null
        if (!compact.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }) {
            return null
        }
        return decodeB64(item)
    }

    internal fun normalizeEchConfigs(root: JSONObject): Boolean {
        val mark = pendingEchDoh.get().size
        var changed = false
        fun walk(key: String) {
            val arr = root.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val node = arr.optJSONObject(i) ?: continue
                if (normalizeEchOn(node)) changed = true
            }
        }
        walk("outbounds")
        walk("endpoints")
        val fresh = pendingEchDoh.get().drop(mark)
        if (installEchDoh(root, fresh)) changed = true
        return changed
    }

    internal fun stripEch(root: JSONObject): Boolean {
        var changed = false
        fun walk(key: String) {
            val arr = root.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val tls = arr.optJSONObject(i)?.optJSONObject("tls") ?: continue
                if (tls.has("ech")) {
                    tls.remove("ech")
                    changed = true
                }
            }
        }
        walk("outbounds")
        walk("endpoints")
        return changed
    }

    private fun normalizeEchOn(node: JSONObject): Boolean {
        val tls = node.optJSONObject("tls") ?: return false
        val ech = tls.optJSONObject("ech") ?: return false
        val query = ech.optString("query_server_name").ifBlank { ech.optString("query-server-name") }.trim()
        val rawConfig = echConfigText(ech.opt("config"))
        val share = parseEchShare(rawConfig)
        if (share != null && share.queryName.isNotEmpty()) {
            ech.remove("config")
            ech.put("enabled", true)
            ech.put("query_server_name", share.queryName)
            ech.remove("query-server-name")
            share.dohUrl?.let { pendingEchDoh.get().add(share.queryName to it) }
            return true
        }
        val pem = echPemFrom(ech.opt("config"))
        if (pem != null) {
            val current = ech.optJSONArray("config")
            val same = current != null && current.length() == 1 && current.optString(0) == pem
            if (!same) {
                ech.put("enabled", true)
                ech.put("config", JSONArray().put(pem))
                if (query.isNotEmpty()) ech.put("query_server_name", query)
                ech.remove("query-server-name")
                return true
            }
            return false
        }
        if (!ech.has("config") || echConfigEmpty(ech.opt("config"))) return false
        ech.remove("config")
        if (query.isNotEmpty()) {
            ech.put("enabled", true)
            ech.put("query_server_name", query)
            ech.remove("query-server-name")
            return true
        }
        tls.remove("ech")
        return true
    }

    private fun echConfigText(raw: Any?): String = when (raw) {
        null, JSONObject.NULL -> ""
        is String -> raw.trim()
        is JSONArray -> if (raw.length() == 1) raw.optString(0).trim() else ""
        else -> raw.toString().trim()
    }

    private fun echConfigEmpty(raw: Any?): Boolean = when (raw) {
        null, JSONObject.NULL -> true
        is String -> raw.isBlank()
        is JSONArray -> raw.length() == 0 || (0 until raw.length()).all { raw.optString(it).isBlank() }
        else -> raw.toString().isBlank()
    }

    /** @return false when the transport is not in this kernel (do not invent a substitute). */
    private fun putTransport(out: JSONObject, raw: Map<*, *>): Boolean {
        val network = str(raw["network"]).lowercase()
        if (network.isEmpty() || network == "tcp" || network == "raw") return true
        val type = when (network) {
            "ws" -> "ws"
            "grpc" -> "grpc"
            "http", "h2" -> "http"
            "httpupgrade" -> "httpupgrade"
            "quic" -> "quic"
            else -> return false
        }
        val transport = JSONObject().put("type", type)
        when (type) {
            "ws" -> fillWsLike(transport, asMap(raw["ws-opts"] ?: raw["ws_opts"]), raw["ws-path"], raw["ws-headers"])
            "httpupgrade" -> {
                val opts = asMap(raw["httpupgrade-opts"] ?: raw["httpupgrade_opts"] ?: raw["http-opts"] ?: raw["http_opts"])
                str(opts?.get("path") ?: raw["path"]).takeIf { it.isNotEmpty() }?.let { transport.put("path", it) }
                val host = str(opts?.get("host") ?: raw["host"])
                if (host.isNotEmpty()) transport.put("host", host)
            }
            "grpc" -> {
                val opts = asMap(raw["grpc-opts"] ?: raw["grpc_opts"]) ?: emptyMap<Any?, Any?>()
                str(opts["grpc-service-name"] ?: opts["service_name"] ?: opts["serviceName"])
                    .takeIf { it.isNotEmpty() }?.let { transport.put("service_name", it) }
            }
            "http" -> {
                val opts = asMap(raw["h2-opts"] ?: raw["http-opts"] ?: raw["http_opts"])
                str(opts?.get("path")).takeIf { it.isNotEmpty() }?.let { transport.put("path", it) }
                val host = opts?.get("host")
                when (host) {
                    is List<*> -> str(host.firstOrNull()).takeIf { it.isNotEmpty() }
                        ?.let { transport.put("host", JSONArray().put(it)) }
                    else -> str(host).takeIf { it.isNotEmpty() }
                        ?.let { transport.put("host", JSONArray().put(it)) }
                }
            }
        }
        out.put("transport", transport)
        return true
    }

    private fun fillWsLike(transport: JSONObject, opts: Map<*, *>?, path: Any?, hostFallback: Any?) {
        val map = opts ?: emptyMap<String, Any?>()
        str(map["path"] ?: path).takeIf { it.isNotEmpty() }?.let { applyWsPath(transport, it) }
        val maxEd = intVal(map["max-early-data"] ?: map["max_early_data"])
        if (maxEd != null && maxEd > 0) transport.put("max_early_data", maxEd)
        val header = str(map["early-data-header-name"] ?: map["early_data_header_name"])
        if (header.isNotEmpty()) {
            transport.put("early_data_header_name", header)
        } else if (transport.has("max_early_data")) {
            transport.put("early_data_header_name", "Sec-WebSocket-Protocol")
        }
        val headers = asMap(map["headers"])
        val host = str(headers?.get("Host") ?: headers?.get("host") ?: map["host"] ?: hostFallback)
        if (host.isNotEmpty()) {
            transport.put("headers", JSONObject().put("Host", host))
        }
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
        val ruleSets: Map<String, String?> = emptyMap(),
        val finalTag: String? = null,
    )

    private fun parseClashRule(raw: String, known: Set<String>, providers: Map<*, *>): ClashRule? {
        val parts = splitTopLevel(raw)
        if (parts.isEmpty()) return null
        val kind = parts[0].uppercase()
        val fields = parts.drop(1).filter { !it.equals("no-resolve", true) }
        if (kind == "MATCH" || kind == "FINAL") {
            val target = mapSpecialTag(fields.firstOrNull() ?: return null)
            return ClashRule(finalTag = target)
        }
        if (fields.size < 2) return null
        val target = mapSpecialTag(fields.last())
        val payload = fields.dropLast(1).joinToString(",")
        val sets = LinkedHashMap<String, String?>()
        val reject = target.equals("REJECT", true) || target.equals("REJECT-DROP", true)
        val outbound = when {
            reject -> null
            target in known || target == "direct" -> target
            else -> return null
        }
        val rule = when (kind) {
            "AND", "OR", "NOT" -> parseLogical(kind, payload, known, providers, sets)
            "GEOIP" -> geoipCondition(payload, sets)
            "GEOSITE" -> geositeCondition(payload, sets)
            "RULE-SET" -> ruleSetCondition(payload, providers, sets)
            else -> matchField(kind, payload)
        } ?: return null
        if (reject) {
            rule.put("action", "reject")
            if (target.equals("REJECT-DROP", true)) rule.put("method", "drop")
        } else {
            rule.put("outbound", outbound)
        }
        return ClashRule(rule = rule, ruleSets = sets)
    }

    private fun parseLogical(
        kind: String,
        payload: String,
        known: Set<String>,
        providers: Map<*, *>,
        sets: MutableMap<String, String?>,
    ): JSONObject? {
        val inner = unwrapParens(payload)
        if (kind == "NOT") {
            val sub = parseClashCondition(inner, known, providers, sets) ?: return null
            sub.put("invert", true)
            return sub
        }
        val pieces = splitTopLevel(inner)
        val rules = JSONArray()
        for (piece in pieces) {
            val sub = parseClashCondition(unwrapParens(piece), known, providers, sets) ?: return null
            rules.put(sub)
        }
        if (rules.length() == 0) return null
        return JSONObject()
            .put("type", "logical")
            .put("mode", kind.lowercase())
            .put("rules", rules)
    }

    private fun parseClashCondition(
        body: String,
        known: Set<String>,
        providers: Map<*, *>,
        sets: MutableMap<String, String?>,
    ): JSONObject? {
        val parts = splitTopLevel(body)
        if (parts.isEmpty()) return null
        val kind = parts[0].uppercase()
        if (kind == "AND" || kind == "OR" || kind == "NOT") {
            return parseLogical(kind, parts.getOrNull(1) ?: return null, known, providers, sets)
        }
        if (parts.size < 2) return null
        val payload = parts[1]
        return when (kind) {
            "GEOIP" -> geoipCondition(payload, sets)
            "GEOSITE" -> geositeCondition(payload, sets)
            "RULE-SET" -> ruleSetCondition(payload, providers, sets)
            else -> matchField(kind, payload)
        }
    }

    private fun geoipCondition(payload: String, sets: MutableMap<String, String?>): JSONObject? {
        if (payload.equals("private", true) || payload.equals("lan", true)) {
            return JSONObject().put("ip_is_private", true)
        }
        val tag = if (payload.equals("CN", true)) "geoip-cn" else "geoip-${payload.lowercase()}"
        if (ConfigInboundCompat.officialRuleSetFile(tag) == null && !tag.startsWith("geoip-")) return null
        if (tag == "geoip-private" || tag == "geoip-fastly" || tag == "geoip-cloudfront") return null
        sets.putIfAbsent(tag, null)
        return JSONObject().put("rule_set", tag)
    }

    private fun geositeCondition(payload: String, sets: MutableMap<String, String?>): JSONObject? {
        val tag = when {
            payload.equals("CN", true) -> "geosite-cn"
            payload.startsWith("geosite-") -> payload
            else -> "geosite-${payload.lowercase()}"
        }
        sets.putIfAbsent(tag, null)
        return JSONObject().put("rule_set", tag)
    }

    private fun ruleSetCondition(
        payload: String,
        providers: Map<*, *>,
        sets: MutableMap<String, String?>,
    ): JSONObject? {
        if (payload.equals("private", true) || payload.equals("lan", true)) {
            return JSONObject().put("ip_is_private", true)
        }
        val resolved = resolveRuleProvider(payload, providers) ?: return null
        sets.putIfAbsent(resolved.first, resolved.second)
        return JSONObject().put("rule_set", resolved.first)
    }

    private fun resolveRuleProvider(name: String, providers: Map<*, *>): Pair<String, String?>? {
        val provider = asMap(providers[name]) ?: providers.entries.firstOrNull { (key, _) ->
            key is String && key.equals(name, true)
        }?.value?.let { asMap(it) }
        val url = str(provider?.get("url"))
        if (url.endsWith(".srs", true) || url.contains(".srs?", true)) {
            return name to url
        }
        val stem = name.trim().lowercase()
        val mapped = when (stem) {
            "reject", "ad", "ads", "advertising", "category-ads-all", "banad", "banads" ->
                "geosite-category-ads-all"
            "cn", "china", "direct", "geosite-cn" -> "geosite-cn"
            "cnip", "cn-ip", "china-ip", "geoip-cn" -> "geoip-cn"
            "gfw", "proxy", "geolocation-!cn", "geosite-geolocation-!cn" -> "geosite-geolocation-!cn"
            else -> null
        }
        if (mapped != null) return mapped to null
        val file = ConfigInboundCompat.officialRuleSetFile(stem)
        if (file != null) return file.removeSuffix(".srs") to null
        if (stem.startsWith("geosite-") || stem.startsWith("geoip-")) return stem to null
        return null
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
            "DST-PORT", "PORT" -> rule.put("port", portLiteral(payload) ?: return null)
            "SRC-PORT" -> rule.put("source_port", portLiteral(payload) ?: return null)
            "PROCESS-NAME" -> rule.put("process_name", payload)
            "PROCESS-PATH" -> rule.put("process_path", payload)
            "NETWORK" -> rule.put("network", payload.lowercase())
            else -> return null
        }
        return rule
    }

    private fun portLiteral(payload: String): Any? {
        intVal(payload)?.let { return it }
        val range = Regex("""^(\d{1,5})\s*[-:]\s*(\d{1,5})$""").matchEntire(payload.trim()) ?: return null
        val start = range.groupValues[1].toInt()
        val end = range.groupValues[2].toInt()
        if (start > 65535 || end > 65535) return null
        return "$start:$end"
    }

    private fun splitTopLevel(raw: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (ch in raw) {
            when (ch) {
                '(' -> {
                    depth++
                    cur.append(ch)
                }
                ')' -> {
                    if (depth > 0) depth--
                    cur.append(ch)
                }
                ',' -> if (depth == 0) {
                    val piece = cur.toString().trim()
                    if (piece.isNotEmpty()) parts += piece
                    cur.clear()
                } else {
                    cur.append(ch)
                }
                else -> cur.append(ch)
            }
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) parts += tail
        return parts
    }

    private fun unwrapParens(value: String): String {
        var text = value.trim()
        while (text.startsWith("(") && text.endsWith(")") && parenWrapsAll(text)) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private fun parenWrapsAll(text: String): Boolean {
        var depth = 0
        for (i in text.indices) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && i != text.lastIndex) return false
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    private fun convertShareLinks(text: String): Result? {
        val lines = expandShareText(text)
        if (lines.isEmpty()) return null
        val outbounds = JSONArray()
        val endpoints = JSONArray()
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
            if (converted.optString("type") == "wireguard") {
                endpoints.put(converted)
            } else {
                outbounds.put(converted)
            }
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
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        val notes = mutableListOf(
            "已将节点链接转为 sing-box 配置",
            "节点链接没有分流。建议开启默认脚本，应用不会自动开启。",
        )
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
            "wireguard", "wg" -> parseWireGuard(body)
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
        if (net == "xhttp" || net == "splithttp") return null
        if (net.isNotEmpty() && net != "tcp") {
            val mapped = when (net) {
                "ws" -> "ws"
                "grpc" -> "grpc"
                "http", "h2" -> "http"
                "httpupgrade" -> "httpupgrade"
                "quic" -> "quic"
                else -> return null
            }
            val transport = JSONObject().put("type", mapped)
            obj.optString("path").takeIf { it.isNotEmpty() }?.let {
                if (mapped == "grpc") transport.put("service_name", it) else applyWsPath(transport, it)
            }
            obj.optString("host").takeIf { it.isNotEmpty() }
                ?.let { transport.put("headers", JSONObject().put("Host", it)) }
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
        if (!putQueryTransport(out, query)) return@parseUserHostQuery false
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
        true
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
        true
    }

    private fun parseTuic(body: String): JSONObject? = parseUserHostQuery(body, "tuic") { out, query, host ->
        val user = body.substringBefore('@')
        val uuid = user.substringBefore(':')
        val password = user.substringAfter(':', "")
        out.put("uuid", uuid)
        if (password.isNotEmpty()) out.put("password", password)
        putQueryTls(out, query, host, defaultOn = true)
        query["congestion_control"]?.let { out.put("congestion_control", it) }
        true
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
        fill: (JSONObject, Map<String, String>, String) -> Boolean,
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
        if (!fill(out, query, host)) return null
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
        val ech = query["ech"]?.trim().orEmpty()
        if (ech.isNotEmpty() && ech != "0" && !ech.equals("false", true)) {
            val share = parseEchShare(ech)
            val flag = share == null && (ech == "1" || ech.equals("true", true))
            val queryName = share?.queryName?.takeIf { it.isNotEmpty() }
                ?: query["echQuery"] ?: query["ech_query"]
                ?: if (flag) query["sni"].orEmpty() else ""
            putEch(tls, if (share != null || flag) null else ech, queryName)
            share?.dohUrl?.takeIf { queryName.isNotEmpty() }?.let {
                pendingEchDoh.get().add(queryName to it)
            }
        }
        out.put("tls", tls)
    }

    private fun putQueryTransport(out: JSONObject, query: Map<String, String>): Boolean {
        val rawType = (query["type"] ?: query["network"]).orEmpty().lowercase()
        if (rawType.isEmpty() || rawType == "tcp" || rawType == "raw") return true
        val type = when (rawType) {
            "ws" -> "ws"
            "grpc" -> "grpc"
            "http", "h2" -> "http"
            "httpupgrade" -> "httpupgrade"
            "quic" -> "quic"
            else -> return false
        }
        val transport = JSONObject().put("type", type)
        (query["path"] ?: query["serviceName"])?.let { value ->
            if (type == "grpc") transport.put("service_name", value) else applyWsPath(transport, value)
        }
        query["host"]?.let { transport.put("headers", JSONObject().put("Host", it)) }
        out.put("transport", transport)
        return true
    }

    private fun applyWsPath(transport: JSONObject, rawPath: String) {
        val split = splitEarlyData(rawPath)
        transport.put("path", split.first)
        val ed = split.second
        if (ed != null && ed > 0) {
            transport.put("max_early_data", ed)
            if (!transport.has("early_data_header_name")) {
                transport.put("early_data_header_name", "Sec-WebSocket-Protocol")
            }
        }
    }

    /** v2ray path `/?ed=2048` is early data, not part of the websocket path. */
    internal fun splitEarlyData(rawPath: String): Pair<String, Int?> {
        val q = rawPath.indexOf('?')
        if (q < 0) return rawPath to null
        val base = rawPath.substring(0, q).ifBlank { "/" }
        var ed: Int? = null
        val keep = ArrayList<String>()
        for (part in rawPath.substring(q + 1).split('&')) {
            if (part.isEmpty()) continue
            val key = part.substringBefore('=')
            if (key == "ed") {
                ed = part.substringAfter('=', "").toIntOrNull()
            } else {
                keep.add(part)
            }
        }
        val path = if (keep.isEmpty()) base else "$base?${keep.joinToString("&")}"
        return path to ed
    }

    private fun installEchDoh(root: JSONObject, hints: List<Pair<String, String>>): Boolean {
        if (hints.isEmpty()) return false
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        var changed = false
        val byName = linkedMapOf<String, String>()
        for ((name, doh) in hints) {
            if (name.isNotBlank() && doh.startsWith("https://", true)) byName.putIfAbsent(name, doh)
        }
        for ((name, doh) in byName) {
            val uri = try {
                URI(doh)
            } catch (_: Exception) {
                continue
            }
            val host = uri.host?.trim().orEmpty()
            if (host.isEmpty()) continue
            val tag = "ech-" + host.lowercase().replace('.', '-').take(40)
            if (!dnsServerHas(servers, tag)) {
                val server = JSONObject()
                    .put("type", "https")
                    .put("tag", tag)
                    .put("server", host)
                    .put("domain_resolver", "local")
                if (uri.port > 0 && uri.port != 443) server.put("server_port", uri.port)
                val path = uri.rawPath?.takeIf { it.isNotEmpty() && it != "/" && it != "/dns-query" }
                if (path != null) server.put("path", path)
                servers.put(server)
                changed = true
            }
            if (prependEchRule(dns, name, tag)) changed = true
        }
        return changed
    }

    /**
     * After scripts rewrite DNS, still send ECH HTTPS lookups to a resolver
     * that returns type-65 records. Prefer the link's DoH server, then the
     * script's proxied `dns-remote`.
     */
    internal fun ensureEchQueryRoute(root: JSONObject): Boolean {
        val names = linkedSetOf<String>()
        fun walk(key: String) {
            val arr = root.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val query = arr.optJSONObject(i)
                    ?.optJSONObject("tls")
                    ?.optJSONObject("ech")
                    ?.optString("query_server_name")
                    ?.trim()
                    .orEmpty()
                if (query.isNotEmpty()) names.add(query)
            }
        }
        walk("outbounds")
        walk("endpoints")
        if (names.isEmpty()) return false
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        var changed = false
        var tag = firstDnsTag(servers) { it.startsWith("ech-") }
            ?: firstDnsTag(servers) { it == "dns-remote" }
        if (tag == null) {
            tag = "ech-dns"
            if (!dnsServerHas(servers, tag)) {
                servers.put(
                    JSONObject()
                        .put("type", "tcp")
                        .put("tag", tag)
                        .put("server", "8.8.8.8")
                        .put("server_port", 53)
                        .put("domain_resolver", "local"),
                )
                changed = true
            }
        }
        val missing = names.filter { !dnsRuleHasDomain(dns.optJSONArray("rules"), it) }
        if (missing.isEmpty()) return changed
        return prependEchRule(dns, missing, tag) || changed
    }

    private fun prependEchRule(dns: JSONObject, name: String, tag: String): Boolean =
        prependEchRule(dns, listOf(name), tag)

    private fun prependEchRule(dns: JSONObject, names: List<String>, tag: String): Boolean {
        val rules = dns.optJSONArray("rules") ?: JSONArray().also { dns.put("rules", it) }
        val need = names.filter { !dnsRuleHasDomain(rules, it) }
        if (need.isEmpty()) return false
        val domain = JSONArray()
        need.forEach { domain.put(it) }
        val merged = JSONArray()
        merged.put(JSONObject().put("domain", domain).put("action", "route").put("server", tag))
        for (i in 0 until rules.length()) merged.put(rules.get(i))
        dns.put("rules", merged)
        return true
    }

    private fun dnsServerHas(servers: JSONArray, tag: String): Boolean {
        for (i in 0 until servers.length()) {
            if (servers.optJSONObject(i)?.optString("tag") == tag) return true
        }
        return false
    }

    private fun firstDnsTag(servers: JSONArray, match: (String) -> Boolean): String? {
        for (i in 0 until servers.length()) {
            val tag = servers.optJSONObject(i)?.optString("tag")?.trim().orEmpty()
            if (tag.isNotEmpty() && match(tag)) return tag
        }
        return null
    }

    private fun dnsRuleHasDomain(rules: JSONArray?, name: String): Boolean {
        if (rules == null) return false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val domain = rule.opt("domain")
            if (domain is String && domain.equals(name, true)) return true
            if (domain is JSONArray) {
                for (j in 0 until domain.length()) {
                    if (domain.optString(j).equals(name, true)) return true
                }
            }
        }
        return false
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

    private fun remoteRuleSet(tag: String, url: String? = null): JSONObject {
        val repo = if (tag.startsWith("geoip-")) "sing-geoip@rule-set" else "sing-geosite@rule-set"
        val resolved = url?.takeIf { it.startsWith("http", true) }
            ?: "https://testingcf.jsdelivr.net/gh/SagerNet/$repo/$tag.srs"
        return JSONObject()
            .put("tag", tag)
            .put("type", "remote")
            .put("format", "binary")
            .put("url", resolved)
            .put("http_client", "angela-http-direct")
    }

    private fun wireguardEndpoint(
        tag: String,
        privateKey: String,
        peerPublic: String,
        server: String,
        port: Int,
        addresses: List<String>,
        reserved: List<Int>,
        mtu: Int?,
    ): JSONObject? {
        if (privateKey.isBlank() || peerPublic.isBlank() || server.isBlank() || port <= 0) return null
        val locals = JSONArray()
        (addresses.ifEmpty { listOf("172.16.0.2/32") }).forEach { locals.put(it) }
        val peer = JSONObject()
            .put("address", server)
            .put("port", port)
            .put("public_key", peerPublic)
            .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
        if (reserved.isNotEmpty()) {
            val arr = JSONArray()
            reserved.forEach { arr.put(it) }
            peer.put("reserved", arr)
        }
        val endpoint = JSONObject()
            .put("type", "wireguard")
            .put("tag", tag)
            .put("private_key", privateKey)
            .put("address", locals)
            .put("peers", JSONArray().put(peer))
        mtu?.let { endpoint.put("mtu", it) }
        return endpoint
    }

    private fun parseWireGuard(body: String): JSONObject? {
        val (main, fragment) = splitFragment(body)
        val user = urlDecodeKeepPlus(main.substringBefore('@'))
        val hostPortQuery = main.substringAfter('@', "")
        if (user.isBlank() || hostPortQuery.isEmpty()) return null
        val hostPort = hostPortQuery.substringBefore('?')
        val query = parseQuery(hostPortQuery.substringAfter('?', ""))
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = intVal(hostPort.substringAfterLast(':')) ?: return null
        val addresses = (query["address"] ?: query["ip"] ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val reserved = (query["reserved"] ?: "")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        val peerPublic = urlDecodeKeepPlus(
            query["publickey"] ?: query["public_key"] ?: query["publicKey"] ?: query["peer"].orEmpty(),
        )
        return wireguardEndpoint(
            tag = urlDecode(fragment).ifBlank { "WG-$host" },
            privateKey = user,
            peerPublic = peerPublic,
            server = host,
            port = port,
            addresses = addresses,
            reserved = reserved,
            mtu = intVal(query["mtu"]),
        )
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

    /** Base64 keys use '+'. URLDecoder would turn that into a space. */
    private fun urlDecodeKeepPlus(value: String): String =
        urlDecode(value.replace("+", "%2B"))

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

    private fun jsonTree(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = jsonTree(value.opt(key))
            }
            map
        }
        is JSONArray -> (0 until value.length()).map { jsonTree(value.opt(it)) }
        else -> value
    }

    private val SHARE_LINE = Regex("(?i)^(ss|ssr|vmess|vless|trojan|hysteria2?|hy2|tuic|socks5?|http|wireguard|wg)://")
    private val ECH_PEM = Regex("-----BEGIN ECH CONFIGS-----([\\s\\S]*?)-----END ECH CONFIGS-----")
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
