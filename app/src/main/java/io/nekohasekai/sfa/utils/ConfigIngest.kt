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
    // Format conversion only. China Direct / ads / QUIC are not written here.
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
    fun adapt(content: String, fetch: ((String) -> String)? = null): Result {
        pendingEchDoh.get().clear()
        return finishEch(adaptUnlocked(content, fetch))
    }

    private fun adaptUnlocked(content: String, fetch: ((String) -> String)?): Result {
        val trimmed = stripBom(content).trim()
        if (trimmed.isEmpty()) return Result(content, emptyList(), Format.Unknown)
        if (trimmed.length > ConfigCompat.MAX_CONFIG_CHARS) return Result(content)

        val opened = openJsonDocument(trimmed)
        val jsonish = opened.first() == '{' || opened.first() == '['
        if (jsonish) {
            parseSingBox(opened, fetch)?.let { return it }
            parseVmessJsonBlob(trimmed)?.let { return it }
        }
        decodeSharePayload(trimmed)?.let { payload ->
            convertShareLinks(payload)?.let { return it }
        }
        if (looksLikeClash(trimmed)) {
            val (links, rest) = splitLinks(trimmed)
            if (links.isNotEmpty()) {
                // Links pasted next to a Clash file: keep both.
                val tree = MiniYaml.parse(rest) as? Map<*, *>
                if (tree != null) return convertClashTree(tree, fetch, links.mapNotNull { convertShareLine(it) })
            }
            return convertClash(trimmed, fetch)
                ?: unsupported("Clash 配置无法解析，没有改成直连。")
        }
        decodeClashPayload(trimmed)?.let { yaml ->
            convertClash(yaml, fetch)?.let { return it }
        }
        convertFragments(trimmed)?.let { return it }
        convertShareLinks(trimmed)?.let { return it }
        return Result(content, emptyList(), Format.Unknown)
    }

    /** Share-link lines vs. everything else (YAML / JSON fragments, comments). */
    private fun splitLinks(text: String): Pair<List<String>, String> {
        val links = ArrayList<String>()
        val rest = StringBuilder()
        for (line in text.lines()) {
            val found = shareLinesOf(line)
            if (found.isNotEmpty()) links += found else rest.append(line).append('\n')
        }
        return links to rest.toString()
    }

    /**
     * Pasted nodes without a full config: a bare Clash `proxies:` list, `- {name: …, type: …}`
     * items, a single proxy mapping, sing-box / Xray outbound objects, each alone or mixed with
     * share links. Returns null when there is no such fragment (plain links take the link path).
     */
    private fun convertFragments(text: String): Result? {
        val (links, rest) = splitLinks(text)
        if (rest.isBlank()) return null
        val tree = try {
            MiniYaml.parse(rest)
        } catch (_: Exception) {
            null
        } ?: return null
        val clash = mutableListOf<Map<*, *>>()
        val nodes = mutableListOf<JSONObject>()
        var xraySkipped = 0
        fun take(item: Any?) {
            val map = item as? Map<*, *> ?: return
            when {
                map["proxies"] is List<*> -> asMapList(map["proxies"]).forEach { take(it) }
                map["outbounds"] is List<*> -> (map["outbounds"] as List<*>).forEach { take(it) }
                map["protocol"] != null && map["type"] == null -> {
                    val node = try {
                        convertXrayOutbound(JSONObject(jsonOf(map)))
                    } catch (_: Exception) {
                        null
                    }
                    if (node != null) nodes += node else if (str(map["protocol"]).lowercase() !in XRAY_NON_PROXY) xraySkipped++
                }
                map["type"] != null && (map.containsKey("server_port") || map.containsKey("peers") ||
                    (map.containsKey("tag") && !map.containsKey("name"))) -> {
                    val node = JSONObject(jsonOf(map))
                    if (isBareOutbound(node)) nodes += node
                }
                map["type"] != null && map["name"] != null -> clash += map
            }
        }
        when (tree) {
            is List<*> -> tree.forEach { take(it) }
            else -> take(tree)
        }
        if (clash.isEmpty() && nodes.isEmpty()) return null
        val linkNodes = links.mapNotNull { convertShareLine(it) }
        if (clash.isNotEmpty()) {
            return convertClashTree(mapOf("proxies" to clash), null, linkNodes + nodes, Format.ShareLinks)
        }
        val notes = mutableListOf("已将节点转为 sing-box 配置", "节点链接没有分流。建议开启默认脚本，应用不会自动开启。")
        val skipped = links.size - linkNodes.size + xraySkipped
        if (skipped > 0) notes += "跳过 $skipped 个无法识别的节点"
        return wrapNodes(linkNodes + nodes, notes, Format.ShareLinks)
    }

    /** YAML tree → JSON text, so pasted JSON / flow-map outbounds become JSONObjects. */
    private fun jsonOf(value: Any?): String = toJson(value).toString()

    private fun toJson(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> if (k != null) obj.put(k.toString(), toJson(v)) } }
        is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
        else -> value
    }

    fun looksConvertible(content: String): Boolean {
        val trimmed = stripBom(content).trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.first() == '{' || trimmed.first() == '[' ||
            trimmed.startsWith("//") || trimmed.startsWith("/*")
        ) {
            return true
        }
        if (looksLikeClash(trimmed)) return true
        if (shareLinesOf(trimmed).isNotEmpty()) return true
        if (looksLikeProxyFragment(trimmed)) return true
        if (decodeSharePayload(trimmed) != null) return true
        return decodeClashPayload(trimmed) != null
    }

    private val FRAGMENT_KEYS = Regex("(?m)^\\s*(?:-\\s*)?\\{?\\s*\"?(?:name|type|protocol)\"?\\s*:")

    /** A pasted Clash proxy (`name:` / `type:`) or Xray outbound (`protocol`) without a full config. */
    private fun looksLikeProxyFragment(text: String): Boolean {
        if (!FRAGMENT_KEYS.containsMatchIn(text)) return false
        return text.contains("server") || text.contains("protocol")
    }

    private fun openJsonDocument(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*")) {
            return JsonConfig.standardize(trimmed).trim()
        }
        return trimmed
    }

    private fun parseSingBox(raw: String, fetch: ((String) -> String)?): Result? {
        val root = try {
            if (raw.first() == '[') {
                val arr = JsonConfig.arrayOrNull(raw) ?: return null
                if (arr.length() == 0) return null
                val first = arr.optJSONObject(0) ?: return null
                if (first.has("protocol") && !first.has("type")) {
                    convertXrayOutbounds(arr)
                } else if (first.has("name") && first.has("type") && !first.has("tag")) {
                    // Clash proxies as a JSON array.
                    convertClashTree(mapOf("proxies" to jsonTree(arr)), fetch, format = Format.ShareLinks)
                } else if (!first.has("type") && !first.has("tag")) {
                    null
                } else if (hasGroups(arr)) {
                    wrapLeaves(arr, "已将节点列表包成可启动配置")
                } else {
                    wrapNodes(jsonObjects(arr), listOf("已将节点列表包成可启动配置"), Format.SingBox)
                }
            } else {
                val parsed = JsonConfig.objectOrNull(raw) ?: return null
                val outs = parsed.optJSONArray("outbounds")
                if (isClashDocument(parsed)) {
                    convertClashTree(jsonTree(parsed) as? Map<*, *> ?: emptyMap<String, Any?>(), fetch)
                } else if (outs != null && isXrayOutbounds(outs)) {
                    convertXrayOutbounds(outs)
                } else if (parsed.has("protocol") && (parsed.has("settings") || parsed.has("streamSettings"))) {
                    convertXrayOutbounds(JSONArray().put(parsed))
                } else if (isBareOutbound(parsed)) {
                    wrapNodes(listOf(parsed), listOf("已将单个节点包成可启动配置"), Format.SingBox)
                } else if (outs != null && isOutboundsOnly(parsed)) {
                    wrapNodes(jsonObjects(outs), listOf("已将节点列表包成可启动配置"), Format.SingBox)
                } else if (parsed.has("outbounds") || parsed.has("inbounds") || parsed.has("route") || parsed.has("dns") || parsed.has("endpoints")) {
                    var stored = try {
                        JSONObject(raw)
                        raw
                    } catch (_: Exception) {
                        JsonConfig.standardize(raw)
                    }
                    val notes = mutableListOf<String>()
                    if (stored != raw) notes += "已按 sing-box 的读法去掉注释和行尾逗号"
                    val tree = JSONObject(stored)
                    if (migrateWireguardOutbounds(tree)) {
                        stored = tree.toString()
                        notes += "WireGuard 出站已改成 sing-box 1.13+ 的 endpoint"
                    }
                    Result(stored, notes, Format.SingBox)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
        return root
    }

    private fun jsonObjects(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    private val GROUP_TYPES = setOf("selector", "urltest")

    private fun hasGroups(arr: JSONArray): Boolean =
        jsonObjects(arr).any { it.optString("type").lowercase() in GROUP_TYPES }

    /** `{"type":"vless","server":…}` pasted on its own. */
    private fun isBareOutbound(obj: JSONObject): Boolean {
        val type = obj.optString("type").lowercase()
        if (type.isEmpty() || type in GROUP_TYPES || type == "direct" || type == "block" || type == "dns") return false
        if (obj.has("outbounds") || obj.has("inbounds") || obj.has("route")) return false
        // Clash proxies use `port` / `name`; sing-box uses `server_port` / `tag`.
        return obj.has("server_port") || obj.has("peers") || (type == "wireguard" && obj.has("local_address"))
    }

    /** `{"outbounds":[nodes…]}` with no groups, routing, DNS or inbounds: wrap like a node list. */
    private fun isOutboundsOnly(obj: JSONObject): Boolean {
        val keys = obj.keys().asSequence().toSet()
        if (!keys.all { it == "outbounds" || it == "log" || it == "endpoints" }) return false
        val outs = obj.optJSONArray("outbounds") ?: return false
        if (obj.has("endpoints")) return false
        if (hasGroups(outs)) return false
        return jsonObjects(outs).any { isBareOutbound(it) }
    }

    private fun isXrayOutbounds(outs: JSONArray): Boolean {
        val items = jsonObjects(outs)
        return items.isNotEmpty() && items.none { it.has("type") } && items.any { it.has("protocol") }
    }

    /** sing-box 1.13 removed the WireGuard outbound; move legacy ones to `endpoints`. */
    internal fun migrateWireguardOutbounds(root: JSONObject): Boolean {
        val outs = root.optJSONArray("outbounds") ?: return false
        val keep = JSONArray()
        val moved = mutableListOf<JSONObject>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i)
            if (o != null && o.optString("type") == "wireguard" && isLegacyWireguard(o)) {
                moved += legacyWireguardEndpoint(o)
            } else {
                keep.put(outs.get(i))
            }
        }
        if (moved.isEmpty()) return false
        root.put("outbounds", keep)
        val endpoints = root.optJSONArray("endpoints") ?: JSONArray().also { root.put("endpoints", it) }
        moved.forEach { endpoints.put(it) }
        return true
    }

    private fun isLegacyWireguard(o: JSONObject): Boolean =
        o.has("local_address") || o.has("server") || o.has("peer_public_key") || o.has("system_interface")

    private val LEGACY_WG_KEYS = setOf(
        "type", "tag", "server", "server_port", "local_address", "peer_public_key", "pre_shared_key",
        "reserved", "peers", "system_interface", "interface_name", "gso", "network",
    )

    private fun legacyWireguardEndpoint(o: JSONObject): JSONObject {
        val ep = JSONObject().put("type", "wireguard").put("tag", o.optString("tag"))
        o.keys().forEach { key -> if (key !in LEGACY_WG_KEYS) ep.put(key, o.get(key)) }
        o.opt("local_address")?.let { ep.put("address", if (it is JSONArray) it else JSONArray().put(it)) }
        if (o.optBoolean("system_interface", false)) ep.put("system", true)
        o.optString("interface_name").takeIf { it.isNotEmpty() }?.let { ep.put("name", it) }
        val peers = JSONArray()
        val legacyPeers = o.optJSONArray("peers")
        if (legacyPeers != null && legacyPeers.length() > 0) {
            for (i in 0 until legacyPeers.length()) {
                val p = legacyPeers.optJSONObject(i) ?: continue
                val peer = JSONObject()
                    .put("address", p.optString("server"))
                    .put("port", p.optInt("server_port"))
                    .put("public_key", p.optString("public_key"))
                    .put("allowed_ips", p.optJSONArray("allowed_ips") ?: JSONArray().put("0.0.0.0/0").put("::/0"))
                p.optString("pre_shared_key").takeIf { it.isNotEmpty() }?.let { peer.put("pre_shared_key", it) }
                p.opt("reserved")?.let { peer.put("reserved", it) }
                peers.put(peer)
            }
        } else {
            val peer = JSONObject()
                .put("address", o.optString("server"))
                .put("port", o.optInt("server_port"))
                .put("public_key", o.optString("peer_public_key"))
                .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
            o.optString("pre_shared_key").takeIf { it.isNotEmpty() }?.let { peer.put("pre_shared_key", it) }
            o.opt("reserved")?.let { peer.put("reserved", it) }
            peers.put(peer)
        }
        ep.put("peers", peers)
        return ep
    }

    /** Xray / v2rayN JSON: a full config, an `outbounds` array or one outbound. Routing is not kept. */
    private fun convertXrayOutbounds(outs: JSONArray): Result? {
        val nodes = mutableListOf<JSONObject>()
        var skipped = 0
        for (o in jsonObjects(outs)) {
            val protocol = o.optString("protocol").lowercase()
            if (protocol in XRAY_NON_PROXY) continue
            val node = try {
                convertXrayOutbound(o)
            } catch (_: Exception) {
                null
            }
            if (node == null) skipped++ else nodes += node
        }
        if (nodes.isEmpty() && skipped == 0) return null
        val notes = mutableListOf(
            "已将 Xray / v2rayN 配置里的节点转为 sing-box",
            "Xray 的路由没有转换。建议开启默认脚本，应用不会自动开启。",
        )
        if (skipped > 0) notes += "跳过 $skipped 个内核暂不支持的节点"
        if (nodes.isEmpty()) {
            val message = "没有可用节点（这些 Xray 出站当前内核还不支持）。没有改成直连。"
            return Result("", listOf(message), Format.ShareLinks, message)
        }
        return wrapNodes(nodes, notes, Format.ShareLinks)
    }

    private val XRAY_NON_PROXY = setOf("freedom", "blackhole", "dns", "loopback", "direct", "block")

    private fun convertXrayOutbound(o: JSONObject): JSONObject? {
        val protocol = o.optString("protocol").lowercase()
        val settings = o.optJSONObject("settings") ?: JSONObject()
        val stream = o.optJSONObject("streamSettings") ?: JSONObject()
        val target = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0)
            ?: settings
        val user = target.optJSONArray("users")?.optJSONObject(0) ?: target
        val address = target.optString("address")
        val port = target.optInt("port", 0)
        if (protocol == "wireguard") return xrayWireguard(o, settings)
        if (address.isEmpty() || port <= 0) return null
        val out = JSONObject()
            .put("tag", o.optString("tag").ifBlank { address })
            .put("server", address)
            .put("server_port", port)
        when (protocol) {
            "vmess" -> {
                out.put("type", "vmess")
                out.put("uuid", user.optString("id"))
                out.put("security", user.optString("security").ifBlank { "auto" })
                user.optInt("alterId", 0).takeIf { it > 0 }?.let { out.put("alter_id", it) }
            }
            "vless" -> {
                val encryption = user.optString("encryption")
                if (encryption.isNotEmpty() && !encryption.equals("none", true)) return null
                out.put("type", "vless")
                out.put("uuid", user.optString("id"))
                val flow = vlessFlow(user.optString("flow")) ?: return null
                if (flow.isNotEmpty()) out.put("flow", flow)
            }
            "trojan" -> {
                out.put("type", "trojan")
                out.put("password", target.optString("password"))
            }
            "shadowsocks" -> {
                out.put("type", "shadowsocks")
                out.put("method", target.optString("method"))
                out.put("password", target.optString("password"))
                if (target.optBoolean("uot", false)) out.put("udp_over_tcp", true)
            }
            "socks", "http" -> {
                out.put("type", protocol)
                user.optString("user").takeIf { it.isNotEmpty() }?.let { out.put("username", it) }
                user.optString("pass").takeIf { it.isNotEmpty() }?.let { out.put("password", it) }
            }
            else -> return null
        }
        if (protocol == "vmess" || protocol == "vless" || protocol == "trojan" || protocol == "http") {
            val query = xrayStreamQuery(stream) ?: return null
            if (protocol == "http" && query.containsKey("type") && query["type"] != "tcp") return null
            putQueryTls(out, query, address, defaultOn = false)
            if (query["security"] == "reality") putReality(out, query)
            if (protocol != "http" && !putQueryTransport(out, query)) return null
        }
        val dialer = stream.optJSONObject("sockopt")?.optString("dialerProxy").orEmpty()
            .ifEmpty { o.optJSONObject("proxySettings")?.optString("tag").orEmpty() }
        if (dialer.isNotEmpty()) out.put("detour", dialer)
        return out
    }

    /** Xray `streamSettings` → the share-link query keys the link parsers already understand. */
    private fun xrayStreamQuery(stream: JSONObject): Map<String, String>? {
        val q = LinkedHashMap<String, String>()
        val network = stream.optString("network").lowercase().ifEmpty { "tcp" }
        when (network) {
            "tcp", "raw" -> {
                val header = (stream.optJSONObject("tcpSettings") ?: stream.optJSONObject("rawSettings"))
                    ?.optJSONObject("header")?.optString("type").orEmpty()
                if (header.equals("http", true)) return null
            }
            "ws", "websocket" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                q["type"] = "ws"
                ws.optString("path").takeIf { it.isNotEmpty() }?.let { q["path"] = it }
                ws.optString("host").ifEmpty { ws.optJSONObject("headers")?.optString("Host").orEmpty() }
                    .takeIf { it.isNotEmpty() }?.let { q["host"] = it }
            }
            "grpc", "gun" -> {
                q["type"] = "grpc"
                stream.optJSONObject("grpcSettings")?.optString("serviceName")
                    ?.takeIf { it.isNotEmpty() }?.let { q["serviceName"] = it }
            }
            "http", "h2" -> {
                val http = stream.optJSONObject("httpSettings") ?: JSONObject()
                q["type"] = "http"
                http.optString("path").takeIf { it.isNotEmpty() }?.let { q["path"] = it }
                val hosts = http.optJSONArray("host")
                if (hosts != null) {
                    q["host"] = (0 until hosts.length()).map { hosts.optString(it) }.filter { it.isNotEmpty() }.joinToString(",")
                }
            }
            "httpupgrade" -> {
                val hu = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                q["type"] = "httpupgrade"
                hu.optString("path").takeIf { it.isNotEmpty() }?.let { q["path"] = it }
                hu.optString("host").takeIf { it.isNotEmpty() }?.let { q["host"] = it }
            }
            "quic" -> q["type"] = "quic"
            else -> return null // kcp / xhttp / splithttp / domainsocket
        }
        when (stream.optString("security").lowercase()) {
            "tls" -> {
                val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
                q["security"] = "tls"
                tls.optString("serverName").takeIf { it.isNotEmpty() }?.let { q["sni"] = it }
                if (tls.optBoolean("allowInsecure", false)) q["allowInsecure"] = "1"
                tls.optString("fingerprint").takeIf { it.isNotEmpty() }?.let { q["fp"] = it }
                val alpn = tls.optJSONArray("alpn")
                if (alpn != null) q["alpn"] = (0 until alpn.length()).joinToString(",") { alpn.optString(it) }
                val ech = tls.opt("echConfigList")
                if (ech is String && ech.isNotBlank()) q["ech"] = ech
            }
            "reality" -> {
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
                q["security"] = "reality"
                reality.optString("serverName").takeIf { it.isNotEmpty() }?.let { q["sni"] = it }
                reality.optString("fingerprint").takeIf { it.isNotEmpty() }?.let { q["fp"] = it }
                q["pbk"] = reality.optString("publicKey").ifEmpty { reality.optString("password") }
                q["sid"] = reality.optString("shortId")
            }
        }
        return q
    }

    private fun putReality(out: JSONObject, query: Map<String, String>) {
        val tls = out.optJSONObject("tls") ?: JSONObject().put("enabled", true).also { out.put("tls", it) }
        tls.put(
            "reality",
            JSONObject()
                .put("enabled", true)
                .put("public_key", query["pbk"].orEmpty())
                .put("short_id", query["sid"].orEmpty()),
        )
        ensureRealityUtls(tls)
    }

    private fun xrayWireguard(o: JSONObject, settings: JSONObject): JSONObject? {
        val peer = settings.optJSONArray("peers")?.optJSONObject(0) ?: return null
        val endpoint = peer.optString("endpoint")
        val (host, portText) = splitHostPort(endpoint)
        val addresses = settings.optJSONArray("address")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: listOfNotNull(settings.optString("address").takeIf { it.isNotEmpty() })
        val reservedArr = settings.optJSONArray("reserved")
        val reserved = if (reservedArr != null) (0 until reservedArr.length()).map { reservedArr.optInt(it) } else emptyList()
        val ep = wireguardEndpoint(
            tag = o.optString("tag").ifBlank { "WG-$host" },
            privateKey = settings.optString("secretKey"),
            peerPublic = peer.optString("publicKey"),
            server = host,
            port = portText.toIntOrNull() ?: return null,
            addresses = addresses.filter { it.isNotEmpty() },
            reserved = reserved,
            mtu = settings.optInt("mtu", 0).takeIf { it > 0 },
        ) ?: return null
        val peerOut = ep.getJSONArray("peers").getJSONObject(0)
        peer.optString("preSharedKey").takeIf { it.isNotEmpty() }?.let { peerOut.put("pre_shared_key", it) }
        peer.optJSONArray("allowedIPs")?.takeIf { it.length() > 0 }?.let { peerOut.put("allowed_ips", it) }
        peer.optInt("keepAlive", 0).takeIf { it > 0 }?.let { peerOut.put("persistent_keepalive_interval", it) }
        return ep
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

    private fun convertClash(text: String, fetch: ((String) -> String)?): Result? {
        val tree = MiniYaml.parse(text) as? Map<*, *> ?: return null
        return convertClashTree(tree, fetch)
    }

    private fun convertClashTree(
        tree: Map<*, *>,
        fetch: ((String) -> String)?,
        extraNodes: List<JSONObject> = emptyList(),
        format: Format = Format.Clash,
    ): Result {
        val proxies = asMapList(mapIgnoreCase(tree, "proxies")).toMutableList()
        val shareFromProviders = JSONArray()
        extraNodes.forEach { shareFromProviders.put(it) }
        val providerMembers = LinkedHashMap<String, List<String>>()
        val providerNotes = mutableListOf<String>()
        pullProxyProviders(tree, fetch, proxies, shareFromProviders, providerMembers, providerNotes)
        val groups = asMapList(mapIgnoreCase(tree, "proxy-groups") ?: mapIgnoreCase(tree, "proxy_groups"))
        val rules = asStringList(mapIgnoreCase(tree, "rules"))
        val providers = asMap(
            mapIgnoreCase(tree, "rule-providers") ?: mapIgnoreCase(tree, "rule_providers"),
        ) ?: emptyMap<Any?, Any?>()
        if (proxies.isEmpty() && shareFromProviders.length() == 0) {
            val sources = asMap(
                mapIgnoreCase(tree, "proxy-providers") ?: mapIgnoreCase(tree, "proxy_providers"),
            )
            val message = if (!sources.isNullOrEmpty()) {
                if (fetch == null) {
                    "这份 Clash 订阅只有远程节点源，节点不在文件里。没有改成直连。"
                } else {
                    "这份 Clash 订阅的远程节点源没有拉到可用节点。没有改成直连。"
                }
            } else {
                "这份 Clash 订阅里没有节点。没有改成直连。"
            }
            return unsupported(message)
        }
        val notes = mutableListOf<String>()
        val outbounds = JSONArray()
        val endpoints = JSONArray()
        val tags = LinkedHashSet<String>()
        val leafTypes = LinkedHashMap<String, String>()
        val helpers = mutableListOf<JSONObject>()
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
            leafTypes[tag] = str(proxy["type"]).lowercase()
            if (converted.optString("type") == "wireguard") {
                endpoints.put(converted)
            } else {
                outbounds.put(converted)
            }
            takeChain(converted)?.let { helpers += it }
        }
        for (i in 0 until shareFromProviders.length()) {
            val converted = shareFromProviders.optJSONObject(i) ?: continue
            val tag = converted.optString("tag")
            if (tag.isBlank() || !tags.add(tag)) continue
            leafTypes[tag] = converted.optString("type")
            if (converted.optString("type") == "wireguard") endpoints.put(converted) else outbounds.put(converted)
            takeChain(converted)?.let { helpers += it }
        }
        if (tags.isEmpty()) {
            return unsupported(unsupportedNodeMessage(skips))
        }
        val leafTags = tags.toList()
        // ss + shadow-tls: the helper outbound gets its tag only now that node tags are final.
        for (helper in helpers) {
            val owner = helper.optString("tag")
            var tag = "$owner-shadowtls"
            var n = 2
            while (tag in tags) tag = "$owner-shadowtls-${n++}"
            tags.add(tag)
            helper.put("tag", tag)
            findNode(outbounds, owner)?.put("detour", tag)
            outbounds.put(helper)
        }
        ensureDirect(outbounds, tags)
        val declared = LinkedHashSet(tags)
        declared.add("direct")
        for (group in groups) {
            val name = str(group["name"])
            if (name.isNotBlank()) declared.add(name)
        }
        val providerTags = providerMembers.values.flatten().toSet()
        val pool = GroupPool(
            inline = leafTags.filter { it !in providerTags },
            providers = providerMembers,
            types = leafTypes,
        )
        val groupTags = LinkedHashSet<String>()
        val groupNodes = mutableListOf<JSONObject>()
        for (group in groups) {
            val converted = convertClashGroup(group, declared, pool) ?: continue
            val tag = converted.optString("tag")
            if (tag.isBlank() || !tags.add(tag)) continue
            groupTags.add(tag)
            groupNodes += converted
        }
        pruneDanglingGroups(groupNodes, tags, groupTags)
        if (groupNodes.isEmpty()) {
            // A bare `proxies:` list (or nodes pasted on their own): give it a selector + auto test.
            autoGroups(leafTags, tags).forEach { group ->
                groupNodes += group
                groupTags.add(group.optString("tag"))
            }
        } else if (extraNodes.isNotEmpty()) {
            // Links pasted next to a full Clash file: offer them in the first selector.
            val first = groupNodes.firstOrNull { it.optString("type") == "selector" }
            val members = first?.optJSONArray("outbounds")
            if (members != null) {
                val have = (0 until members.length()).map { members.optString(it) }.toSet()
                extraNodes.map { it.optString("tag") }.filter { it in tags && it !in have }.forEach { members.put(it) }
            }
        }
        groupNodes.forEach { outbounds.put(it) }
        dropDanglingDetours(outbounds, endpoints, tags)
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
        // `MATCH,<dropped group>` or `MATCH,REJECT` is not an outbound; sing-box refuses a dangling final.
        if (finalTag !in tags) finalTag = groupTags.firstOrNull() ?: leafTags.first()
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
        if (format == Format.Clash) {
            notes += "已将 Clash 配置转为 sing-box，能识别的分流已保留"
        } else {
            notes += "已将节点转为 sing-box 配置"
            if (rules.isEmpty()) notes += "节点链接没有分流。建议开启默认脚本，应用不会自动开启。"
        }
        notes += providerNotes
        if (skippedRules > 0) {
            notes += "有 $skippedRules 条分流暂时对不上 sing-box，已跳过，没有改成直连"
        }
        if (skipped > 0) notes += skipNote(skipped, skips)
        return Result(root.toString(), notes, format)
    }

    private class GroupPool(
        val inline: List<String>,
        val providers: Map<String, List<String>>,
        val types: Map<String, String>,
    )

    /**
     * Selector over every node, plus a url-test "自动选择" (the selector default) when there is
     * more than one node. Used whenever the source has nodes but no groups.
     */
    private fun autoGroups(leaves: List<String>, tags: MutableSet<String>): List<JSONObject> {
        val nodes = leaves.filter { it != "direct" }
        if (nodes.isEmpty()) return emptyList()
        val members = JSONArray()
        nodes.forEach { members.put(it) }
        val selector = JSONObject()
            .put("type", "selector")
            .put("tag", AUTO_SELECT)
            .put("outbounds", members)
            .put("interrupt_exist_connections", false)
        tags.add(AUTO_SELECT)
        if (nodes.size < 2 || AUTO_TEST in tags) return listOf(selector)
        tags.add(AUTO_TEST)
        val test = JSONObject()
            .put("type", "urltest")
            .put("tag", AUTO_TEST)
            .put("outbounds", JSONArray(nodes))
            .put("url", "https://www.gstatic.com/generate_204")
            .put("interval", "5m")
            .put("idle_timeout", "30m")
            .put("interrupt_exist_connections", false)
        members.put(AUTO_TEST)
        selector.put("default", AUTO_TEST)
        return listOf(selector, test)
    }

    private fun findNode(arr: JSONArray, tag: String): JSONObject? {
        for (i in 0 until arr.length()) {
            val node = arr.optJSONObject(i) ?: continue
            if (node.optString("tag") == tag) return node
        }
        return null
    }

    /** mihomo `dialer-proxy` / Xray `dialerProxy` pointing at a node that was skipped would stop the kernel. */
    private fun dropDanglingDetours(outbounds: JSONArray, endpoints: JSONArray, tags: Set<String>) {
        for (arr in listOf(outbounds, endpoints)) {
            for (i in 0 until arr.length()) {
                val node = arr.optJSONObject(i) ?: continue
                val detour = node.optString("detour")
                if (detour.isNotEmpty() && detour !in tags && !detour.equals("direct", true)) node.remove("detour")
            }
        }
    }

    /** A node that needs a helper outbound (ss over shadow-tls) carries it here until tags are final. */
    private fun attachChain(node: JSONObject, helper: JSONObject) {
        node.put(CHAIN_KEY, helper)
    }

    private fun takeChain(node: JSONObject): JSONObject? {
        val helper = node.optJSONObject(CHAIN_KEY) ?: return null
        node.remove(CHAIN_KEY)
        helper.put("tag", node.optString("tag"))
        return helper
    }

    /**
     * A group whose members were all skipped (or a `relay` group) is not emitted. Groups that
     * still list it would make sing-box fail with "outbound not found", so drop those
     * members, and drop groups that end up empty, until nothing changes.
     */
    private fun pruneDanglingGroups(
        groups: MutableList<JSONObject>,
        tags: MutableSet<String>,
        groupTags: MutableSet<String>,
    ) {
        var changed = true
        while (changed) {
            changed = false
            val iter = groups.iterator()
            while (iter.hasNext()) {
                val group = iter.next()
                val members = group.optJSONArray("outbounds") ?: JSONArray()
                val kept = JSONArray()
                for (i in 0 until members.length()) {
                    val member = members.optString(i)
                    if (member in tags) kept.put(member)
                }
                if (kept.length() != members.length()) {
                    group.put("outbounds", kept)
                    changed = true
                }
                if (kept.length() == 0) {
                    val tag = group.optString("tag")
                    tags.remove(tag)
                    groupTags.remove(tag)
                    iter.remove()
                    changed = true
                }
            }
        }
    }

    private class SkipBag {
        var xhttp = 0
        var masque = 0
        val others = LinkedHashMap<String, Int>()

        fun other(name: String) {
            val key = name.ifBlank { "未知" }
            others[key] = (others[key] ?: 0) + 1
        }
    }

    private fun skipBits(skips: SkipBag): List<String> {
        val bits = mutableListOf<String>()
        if (skips.xhttp > 0) bits += "xhttp"
        if (skips.masque > 0) bits += "MASQUE"
        bits += skips.others.keys
        return bits
    }

    private fun skipNote(skipped: Int, skips: SkipBag): String {
        val bits = skipBits(skips)
        return if (bits.isEmpty()) {
            "跳过 $skipped 个内核暂不支持的节点"
        } else {
            "跳过 $skipped 个内核暂不支持的节点（${bits.joinToString("、")}）"
        }
    }

    private fun unsupportedNodeMessage(skips: SkipBag): String {
        val bits = skipBits(skips)
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
        if (type == "direct") return JSONObject().put("type", "direct").put("tag", name)
        val network = str(raw["network"]).lowercase()
        if (network == "xhttp" || network == "splithttp") {
            skips.xhttp++
            return null
        }
        val server = str(raw["server"])
        val hopping = type == "hysteria2" || type == "hy2" || type == "hysteria"
        val port = intVal(raw["port"])
            ?: (if (hopping) firstPort(str(raw["ports"] ?: raw["mport"])) else null)
            ?: (if (type == "hysteria2" || type == "hy2") 443 else null)
            ?: (if (type == "wireguard") 0 else null)
            ?: return null
        if (server.isBlank() && type != "wireguard") return null
        val out = JSONObject()
        out.put("tag", name)
        out.put("server", server)
        out.put("server_port", port)
        when (type) {
            "ss", "shadowsocks" -> {
                out.put("type", "shadowsocks")
                out.put("method", str(raw["cipher"]).ifBlank { "aes-256-gcm" })
                out.put("password", str(raw["password"]))
                val pluginRaw = str(raw["plugin"])
                val opts = raw["plugin-opts"] ?: raw["plugin_opts"]
                when (pluginRaw.lowercase(Locale.US)) {
                    "" -> Unit
                    "shadow-tls", "shadowtls" -> {
                        val helper = shadowTlsHelper(asMap(opts), server, port, str(raw["client-fingerprint"] ?: raw["client_fingerprint"]))
                        if (helper == null) {
                            skips.other("shadow-tls")
                            return null
                        }
                        attachChain(out, helper)
                    }
                    "obfs", "simple-obfs", "obfs-local", "v2ray-plugin" -> {
                        val plugin = ssPluginName(pluginRaw)
                        out.put("plugin", plugin)
                        if (opts != null) out.put("plugin_opts", pluginOpts(plugin, opts))
                    }
                    else -> {
                        // restls / kcptun / gost-plugin... are not in sing-box; skip only this node.
                        skips.other("ss+${pluginRaw.lowercase(Locale.US)}")
                        return null
                    }
                }
                if (boolVal(raw["udp-over-tcp"] ?: raw["udp_over_tcp"]) == true) {
                    out.put("udp_over_tcp", true)
                }
                putSmux(out, raw)
            }
            "vmess" -> {
                if (network == "http") {
                    skips.other("tcp+http 伪装")
                    return null
                }
                out.put("type", "vmess")
                out.put("uuid", str(raw["uuid"]))
                val security = str(raw["cipher"]).ifBlank { "auto" }
                out.put("security", security)
                intVal(raw["alterId"] ?: raw["alter-id"])?.let { out.put("alter_id", it) }
                if (boolVal(raw["global-padding"] ?: raw["global_padding"]) == true) out.put("global_padding", true)
                if (boolVal(raw["authenticated-length"] ?: raw["authenticated_length"]) == true) {
                    out.put("authenticated_length", true)
                }
                str(raw["packet-encoding"] ?: raw["packet_encoding"]).takeIf { it.isNotEmpty() }
                    ?.let { out.put("packet_encoding", it) }
                putTls(out, raw)
                if (!putTransport(out, raw, skips)) return null
                putSmux(out, raw)
            }
            "vless" -> {
                if (network == "http") {
                    skips.other("tcp+http 伪装")
                    return null
                }
                val encryption = str(raw["encryption"])
                if (encryption.isNotEmpty() && !encryption.equals("none", true)) {
                    skips.other("vless encryption")
                    return null
                }
                out.put("type", "vless")
                out.put("uuid", str(raw["uuid"]))
                val flow = vlessFlow(str(raw["flow"])) ?: run {
                    skips.other("xtls ${str(raw["flow"])}")
                    return null
                }
                if (flow.isNotEmpty()) out.put("flow", flow)
                str(raw["packet-encoding"] ?: raw["packet_encoding"]).takeIf { it.isNotEmpty() }
                    ?.let { out.put("packet_encoding", it) }
                putTls(out, raw)
                if (!putTransport(out, raw, skips)) return null
                putSmux(out, raw)
            }
            "trojan" -> {
                if (network == "http") {
                    skips.other("tcp+http 伪装")
                    return null
                }
                out.put("type", "trojan")
                out.put("password", str(raw["password"]))
                putTls(out, raw, defaultEnabled = true)
                if (!putTransport(out, raw, skips)) return null
                putSmux(out, raw)
            }
            "hysteria2", "hy2" -> {
                out.put("type", "hysteria2")
                out.put("password", str(raw["password"]).ifBlank { str(raw["auth"]) })
                mbps(raw["up"] ?: raw["up-mbps"])?.let { out.put("up_mbps", it) }
                mbps(raw["down"] ?: raw["down-mbps"])?.let { out.put("down_mbps", it) }
                putTls(out, raw, defaultEnabled = true)
                putHysteria2Obfs(out, raw)
                putServerPorts(out, raw)
                clashDuration(raw["hop-interval"] ?: raw["hop_interval"])?.let { out.put("hop_interval", it) }
            }
            "hysteria" -> {
                val protocol = str(raw["protocol"] ?: raw["obfs-protocol"]).lowercase()
                if (protocol.isNotEmpty() && protocol != "udp") {
                    skips.other("hysteria $protocol")
                    return null
                }
                out.put("type", "hysteria")
                mbps(raw["up"] ?: raw["up-mbps"] ?: raw["up_mbps"])?.let { out.put("up_mbps", it) }
                mbps(raw["down"] ?: raw["down-mbps"] ?: raw["down_mbps"])?.let { out.put("down_mbps", it) }
                val authStr = str(raw["auth-str"] ?: raw["auth_str"])
                val auth = str(raw["auth"])
                when {
                    authStr.isNotEmpty() -> out.put("auth_str", authStr)
                    // mihomo `auth` is base64 bytes, the same as sing-box `auth`.
                    auth.isNotEmpty() && decodeB64(auth) != null && auth.length % 4 == 0 -> out.put("auth", auth)
                    auth.isNotEmpty() -> out.put("auth_str", auth)
                }
                val obfs = raw["obfs"]
                if (asMap(obfs) == null) {
                    str(obfs).takeIf { it.isNotEmpty() && !it.equals("none", true) }
                        ?.let { out.put("obfs", it) }
                }
                putTls(out, raw, defaultEnabled = true)
                putServerPorts(out, raw)
                clashDuration(raw["hop-interval"] ?: raw["hop_interval"])?.let { out.put("hop_interval", it) }
            }
            "tuic", "tuic-v5" -> {
                val uuid = str(raw["uuid"])
                if (uuid.isEmpty()) {
                    // TUIC v4 (`token:`) is not in sing-box, which speaks v5 only.
                    skips.other("tuic v4")
                    return null
                }
                out.put("type", "tuic")
                out.put("uuid", uuid)
                out.put("password", str(raw["password"]))
                str(raw["congestion-controller"] ?: raw["congestion_control"] ?: raw["congestion-control"])
                    .takeIf { it.isNotEmpty() }?.let { out.put("congestion_control", it) }
                str(raw["udp-relay-mode"] ?: raw["udp_relay_mode"]).lowercase()
                    .takeIf { it == "native" || it == "quic" }?.let { out.put("udp_relay_mode", it) }
                if (boolVal(raw["reduce-rtt"] ?: raw["reduce_rtt"]) == true) out.put("zero_rtt_handshake", true)
                intVal(raw["heartbeat-interval"] ?: raw["heartbeat_interval"])
                    ?.takeIf { it > 0 }?.let { out.put("heartbeat", "${it}ms") }
                putTls(out, raw, defaultEnabled = true)
                if (boolVal(raw["disable-sni"] ?: raw["disable_sni"]) == true) {
                    out.optJSONObject("tls")?.remove("server_name")
                }
            }
            "wireguard" -> {
                if (raw["amnezia-wg-option"] != null || raw["amnezia_wg_option"] != null) {
                    skips.other("AmneziaWG")
                    return null
                }
                return clashWireguard(name, raw, server, port) ?: run {
                    skips.other("wireguard")
                    null
                }
            }
            "socks", "socks5" -> {
                if (boolVal(raw["tls"]) == true) {
                    skips.other("socks5+tls")
                    return null
                }
                out.put("type", "socks")
                str(raw["username"]).takeIf { it.isNotEmpty() }?.let { out.put("username", it) }
                str(raw["password"]).takeIf { it.isNotEmpty() }?.let { out.put("password", it) }
            }
            "http" -> {
                out.put("type", "http")
                str(raw["username"]).takeIf { it.isNotEmpty() }?.let { out.put("username", it) }
                str(raw["password"]).takeIf { it.isNotEmpty() }?.let { out.put("password", it) }
                putTls(out, raw)
                asMap(raw["headers"])?.let { headers ->
                    val obj = JSONObject()
                    headers.forEach { (k, v) -> if (k != null && v != null) obj.put(k.toString(), str(v)) }
                    if (obj.length() > 0) out.put("headers", obj)
                }
            }
            "anytls", "any-tls" -> {
                out.put("type", "anytls")
                out.put("password", str(raw["password"]))
                putTls(out, raw, defaultEnabled = true)
                clashDuration(raw["idle-session-check-interval"] ?: raw["idle_session_check_interval"])
                    ?.let { out.put("idle_session_check_interval", it) }
                clashDuration(raw["idle-session-timeout"] ?: raw["idle_session_timeout"])
                    ?.let { out.put("idle_session_timeout", it) }
                intVal(raw["min-idle-session"] ?: raw["min_idle_session"])
                    ?.let { out.put("min_idle_session", it) }
            }
            "naive" -> {
                out.put("type", "naive")
                str(raw["username"]).takeIf { it.isNotEmpty() }?.let { out.put("username", it) }
                str(raw["password"]).takeIf { it.isNotEmpty() }?.let { out.put("password", it) }
                putTls(out, raw, defaultEnabled = true)
            }
            "ssh" -> {
                val user = str(raw["username"]).ifBlank { str(raw["user"]) }
                val password = str(raw["password"])
                val key = str(raw["private-key"] ?: raw["private_key"])
                if (user.isBlank() || (password.isBlank() && key.isBlank())) {
                    skips.other("ssh")
                    return null
                }
                out.put("type", "ssh")
                out.put("user", user)
                if (password.isNotEmpty()) out.put("password", password)
                if (key.isNotEmpty()) out.put("private_key", key)
                str(raw["private-key-passphrase"] ?: raw["private_key_passphrase"])
                    .takeIf { it.isNotEmpty() }?.let { out.put("private_key_passphrase", it) }
                putStringList(out, "host_key", raw["host-key"] ?: raw["host_key"])
                putStringList(out, "host_key_algorithms", raw["host-key-algorithms"] ?: raw["host_key_algorithms"])
            }
            "shadowtls" -> {
                out.put("type", "shadowtls")
                str(raw["password"]).takeIf { it.isNotEmpty() }?.let { out.put("password", it) }
                intVal(raw["version"])?.let { out.put("version", it) }
                putTls(out, raw, defaultEnabled = true)
            }
            "snell" -> {
                val version = intVal(raw["version"]) ?: 4
                val psk = str(raw["psk"])
                if ((version != 4 && version != 6) || psk.isBlank()) {
                    skips.other("snell v$version")
                    return null
                }
                out.put("type", "snell")
                out.put("version", version)
                out.put("psk", psk)
                if (version == 4) {
                    val obfs = asMap(raw["obfs-opts"] ?: raw["obfs_opts"])
                    val mode = str(obfs?.get("mode")).ifBlank { str(raw["obfs"]) }
                    if (mode.isNotEmpty() && !mode.equals("none", true)) out.put("obfs_mode", mode)
                    str(obfs?.get("host")).takeIf { it.isNotEmpty() }?.let { out.put("obfs_host", it) }
                } else {
                    val v6 = asMap(raw["v6-opts"] ?: raw["v6_opts"])
                    str(v6?.get("mode")).takeIf { it.isNotEmpty() }?.let { out.put("mode", it) }
                }
            }
            "ssr", "shadowsocksr" -> {
                skips.other("ssr")
                return null
            }
            else -> {
                // mieru / sudoku / trojan-go / juicity... have no sing-box outbound.
                skips.other(type.ifBlank { "未知" })
                return null
            }
        }
        putClashDial(out, raw)
        return out
    }

    /** mihomo dial options that have a direct sing-box equivalent. */
    private fun putClashDial(out: JSONObject, raw: Map<*, *>) {
        if (boolVal(raw["tfo"] ?: raw["fast-open"]) == true) out.put("tcp_fast_open", true)
        if (boolVal(raw["mptcp"]) == true) out.put("tcp_multi_path", true)
        val dialer = str(raw["dialer-proxy"] ?: raw["dialer_proxy"])
        if (dialer.isNotEmpty()) out.put("detour", mapSpecialTag(dialer))
    }

    /** sing-box only knows `xtls-rprx-vision`; mihomo's `-udp443` variant is the same flow. null = unsupported (XTLS v1). */
    private fun vlessFlow(raw: String): String? {
        val flow = raw.trim()
        if (flow.isEmpty() || flow.equals("none", true)) return ""
        if (flow.startsWith("xtls-rprx-vision", ignoreCase = true)) return "xtls-rprx-vision"
        return null
    }

    /** "100", 100, "100 Mbps", "1 Gbps" → whole Mbps for sing-box `up_mbps` / `down_mbps`. */
    private fun mbps(raw: Any?): Int? {
        if (raw is Number) return raw.toInt().takeIf { it > 0 }
        val text = str(raw).lowercase(Locale.US).replace(" ", "")
        if (text.isEmpty()) return null
        val number = Regex("^([0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val value = when {
            text.contains("gbps") || text.endsWith("g") -> number * 1000
            text.contains("kbps") || text.endsWith("k") -> number / 1000
            else -> number
        }
        return value.toInt().takeIf { it > 0 }
    }

    /** mihomo `smux:` → sing-box `multiplex` (same sing-mux protocol). */
    private fun putSmux(out: JSONObject, raw: Map<*, *>) {
        val smux = asMap(raw["smux"]) ?: return
        if (boolVal(smux["enabled"]) != true) return
        val mux = JSONObject().put("enabled", true)
        str(smux["protocol"]).lowercase().takeIf { it == "smux" || it == "yamux" || it == "h2mux" }
            ?.let { mux.put("protocol", it) }
        intVal(smux["max-connections"] ?: smux["max_connections"])?.let { mux.put("max_connections", it) }
        intVal(smux["min-streams"] ?: smux["min_streams"])?.let { mux.put("min_streams", it) }
        intVal(smux["max-streams"] ?: smux["max_streams"])?.let { mux.put("max_streams", it) }
        if (boolVal(smux["padding"]) == true) mux.put("padding", true)
        val brutal = asMap(smux["brutal-opts"] ?: smux["brutal_opts"])
        if (brutal != null && boolVal(brutal["enabled"]) == true) {
            val b = JSONObject().put("enabled", true)
            mbps(brutal["up"])?.let { b.put("up_mbps", it) }
            mbps(brutal["down"])?.let { b.put("down_mbps", it) }
            mux.put("brutal", b)
        }
        out.put("multiplex", mux)
    }

    /**
     * mihomo `plugin: shadow-tls` → a sing-box shadowtls outbound that the shadowsocks node
     * dials through (`detour`), as in the sing-box ShadowTLS client example.
     */
    private fun shadowTlsHelper(opts: Map<*, *>?, server: String, port: Int, fingerprint: String): JSONObject? {
        if (opts == null) return null
        val host = str(opts["host"] ?: opts["sni"])
        if (host.isEmpty()) return null
        val version = intVal(opts["version"]) ?: 2
        if (version !in 1..3) return null
        val password = str(opts["password"])
        if (version >= 2 && password.isEmpty()) return null
        val tls = JSONObject().put("enabled", true).put("server_name", host)
        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint.ifBlank { "chrome" }))
        val helper = JSONObject()
            .put("type", "shadowtls")
            .put("server", server)
            .put("server_port", port)
            .put("version", version)
            .put("tls", tls)
        if (version >= 2) helper.put("password", password)
        return helper
    }

    /** mihomo wireguard: flat (`server` / `public-key`) or `peers:` list; `ip` and `ipv6` both count. */
    private fun clashWireguard(name: String, raw: Map<*, *>, server: String, port: Int): JSONObject? {
        val locals = mutableListOf<String>()
        for (key in listOf("ip", "ipv6", "local-address", "local_address", "address")) {
            when (val value = raw[key]) {
                is List<*> -> value.forEach { item -> str(item).takeIf { it.isNotEmpty() }?.let { locals += it } }
                null -> Unit
                else -> str(value).split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { locals += it }
            }
        }
        val peer = asMapList(raw["peers"]).firstOrNull()
        val source: Map<*, *> = peer ?: raw
        val reserved = when (val value = source["reserved"] ?: raw["reserved"]) {
            is List<*> -> value.mapNotNull { intVal(it) }
            else -> str(value).split(',').mapNotNull { it.trim().toIntOrNull() }
        }
        val endpoint = wireguardEndpoint(
            tag = name,
            privateKey = str(raw["private-key"] ?: raw["private_key"]),
            peerPublic = str(source["public-key"] ?: source["public_key"]),
            server = str(source["server"]).ifBlank { server },
            port = intVal(source["port"]) ?: port,
            addresses = locals,
            reserved = reserved,
            mtu = intVal(raw["mtu"]),
        ) ?: return null
        val peerOut = endpoint.getJSONArray("peers").getJSONObject(0)
        str(source["pre-shared-key"] ?: source["pre_shared_key"] ?: raw["pre-shared-key"])
            .takeIf { it.isNotEmpty() }?.let { peerOut.put("pre_shared_key", it) }
        val allowed = source["allowed-ips"] ?: source["allowed_ips"]
        if (allowed is List<*> && allowed.isNotEmpty()) {
            peerOut.put("allowed_ips", JSONArray(allowed.map { str(it) }.filter { it.isNotEmpty() }))
        }
        intVal(raw["persistent-keepalive"] ?: raw["persistent_keepalive"] ?: source["persistent-keepalive"])
            ?.takeIf { it > 0 }?.let { peerOut.put("persistent_keepalive_interval", it) }
        str(raw["dialer-proxy"] ?: raw["dialer_proxy"]).takeIf { it.isNotEmpty() }
            ?.let { endpoint.put("detour", mapSpecialTag(it)) }
        return endpoint
    }

    private fun firstPort(ports: String): Int? =
        ports.split(',', '/').firstOrNull()?.trim()?.substringBefore('-')?.trim()?.toIntOrNull()

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
        putStringList(tls, "alpn", raw["alpn"])
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
            ensureRealityUtls(tls)
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

    private fun putStringList(obj: JSONObject, key: String, raw: Any?) {
        val arr = JSONArray()
        when (raw) {
            is List<*> -> raw.forEach { str(it).takeIf { item -> item.isNotEmpty() }?.let { arr.put(it) } }
            else -> str(raw).takeIf { it.isNotEmpty() }?.let { arr.put(it) }
        }
        if (arr.length() > 0) obj.put(key, arr)
    }

    private fun putHysteria2Obfs(out: JSONObject, raw: Map<*, *>) {
        val obfsRaw = raw["obfs"]
        val obfsMap = asMap(obfsRaw)
        val obfsType = if (obfsMap != null) str(obfsMap["type"] ?: obfsMap["mode"]) else str(obfsRaw)
        if (obfsType.equals("none", true)) return
        val password = str(raw["obfs-password"] ?: raw["obfs_password"])
            .ifBlank { if (obfsMap != null) str(obfsMap["password"]) else "" }
        if (password.isEmpty()) return
        out.put(
            "obfs",
            JSONObject().put("type", obfsType.ifBlank { "salamander" }).put("password", password),
        )
    }

    /** Clash `ports: 20000-55000`. sing-box uses `server_ports` and rejects a leftover `server_port`. */
    private fun putServerPorts(out: JSONObject, raw: Map<*, *>) {
        val ports = str(raw["ports"] ?: raw["mport"])
        putPortList(out, ports)
    }

    /** `443,20000-30000` (also `/` separated, as some panels write it) → sing-box `server_ports`. */
    private fun putPortList(out: JSONObject, ports: String) {
        if (ports.isEmpty() || !ports.any { it == '-' || it == ':' || it == ',' || it == '/' }) return
        val arr = JSONArray()
        ports.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            // sing-box ParsePorts rejects an entry without ':' ("bad port range: 443").
            val range = part.replace('-', ':')
            arr.put(if (':' in range) range else "$range:$range")
        }
        if (arr.length() == 0) return
        out.put("server_ports", arr)
        out.remove("server_port")
    }

    private fun clashDuration(raw: Any?): String? {
        val text = str(raw)
        if (text.isEmpty()) return null
        if (text.any { it.isLetter() }) return text
        val seconds = text.toLongOrNull() ?: return null
        if (seconds <= 0) return null
        return "${seconds}s"
    }

    /** @return false when the transport is not in this kernel (do not invent a substitute). */
    private fun putTransport(out: JSONObject, raw: Map<*, *>, skips: SkipBag): Boolean {
        val network = str(raw["network"]).lowercase()
        if (network.isEmpty() || network == "tcp" || network == "raw") return true
        val wsOpts = asMap(raw["ws-opts"] ?: raw["ws_opts"])
        // mihomo: `network: ws` + `ws-opts.v2ray-http-upgrade: true` is HTTPUpgrade, not websocket.
        val wsUpgrade = network == "ws" &&
            boolVal(wsOpts?.get("v2ray-http-upgrade") ?: wsOpts?.get("v2ray_http_upgrade")) == true
        val type = when (network) {
            "ws" -> if (wsUpgrade) "httpupgrade" else "ws"
            "grpc" -> "grpc"
            "http", "h2" -> "http"
            "httpupgrade" -> "httpupgrade"
            "quic" -> "quic"
            else -> {
                skips.other(network)
                return false
            }
        }
        val transport = JSONObject().put("type", type)
        when (type) {
            "ws" -> fillWsLike(transport, wsOpts, raw["ws-path"], raw["ws-headers"])
            "httpupgrade" -> {
                val opts = if (wsUpgrade) {
                    wsOpts
                } else {
                    asMap(raw["httpupgrade-opts"] ?: raw["httpupgrade_opts"] ?: raw["http-opts"] ?: raw["http_opts"])
                }
                str(opts?.get("path") ?: raw["path"]).takeIf { it.isNotEmpty() }?.let {
                    transport.put("path", splitEarlyData(it).first)
                }
                val headers = asMap(opts?.get("headers"))
                val host = str(opts?.get("host") ?: headers?.get("Host") ?: headers?.get("host") ?: raw["host"])
                if (host.isNotEmpty()) transport.put("host", host)
            }
            "grpc" -> {
                val opts = asMap(raw["grpc-opts"] ?: raw["grpc_opts"]) ?: emptyMap<Any?, Any?>()
                str(opts["grpc-service-name"] ?: opts["service_name"] ?: opts["serviceName"])
                    .takeIf { it.isNotEmpty() }?.let { transport.put("service_name", it) }
            }
            "http" -> {
                val opts = asMap(raw["h2-opts"] ?: raw["h2_opts"] ?: raw["http-opts"] ?: raw["http_opts"])
                // h2-opts.path is a string; http-opts.path is a list (`path: ['/']`).
                val path = opts?.get("path")
                str(if (path is List<*>) path.firstOrNull() else path)
                    .takeIf { it.isNotEmpty() }?.let { transport.put("path", it) }
                // h2-opts.host is a list; http-opts carries it as headers.Host (string or list).
                val headers = asMap(opts?.get("headers"))
                val host = opts?.get("host") ?: headers?.get("Host") ?: headers?.get("host")
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

    private fun pullProxyProviders(
        tree: Map<*, *>,
        fetch: ((String) -> String)?,
        proxies: MutableList<Map<*, *>>,
        shareNodes: JSONArray,
        providerMembers: MutableMap<String, List<String>>,
        notes: MutableList<String>,
    ) {
        val sources = asMap(
            mapIgnoreCase(tree, "proxy-providers") ?: mapIgnoreCase(tree, "proxy_providers"),
        ) ?: return
        var pulled = 0
        for ((key, value) in sources) {
            val spec = asMap(value) ?: continue
            val name = key?.toString()?.trim().orEmpty()
            if (name.isEmpty()) continue
            val kind = str(spec["type"]).lowercase()
            val (clashNodes, linkNodes) = if (kind == "inline") {
                // mihomo `type: inline`: the nodes are in `payload`, nothing to download.
                asMapList(spec["payload"]) to emptyList()
            } else {
                if (fetch == null) continue
                if (pulled >= 8) break
                val url = str(spec["url"])
                if (!url.startsWith("https://", ignoreCase = true)) {
                    notes += if (kind == "file" && url.isEmpty()) {
                        "节点源「$name」是本机文件，导入时读不到"
                    } else {
                        "节点源「$name」不是 HTTPS，没有拉取"
                    }
                    continue
                }
                val body = try {
                    fetch(url)
                } catch (_: Exception) {
                    notes += "节点源「$name」没拉下来"
                    continue
                }
                pulled++
                readProviderBody(body)
            }
            val filters = clashFilters(spec["filter"])
            val excludes = clashFilters(spec["exclude-filter"] ?: spec["exclude_filter"])
            fun keep(tag: String): Boolean =
                (filters.isEmpty() || filters.any { it.containsMatchIn(tag) }) && excludes.none { it.containsMatchIn(tag) }
            val tags = mutableListOf<String>()
            for (item in clashNodes) {
                val tag = str(item["name"])
                if (tag.isEmpty() || !keep(tag)) continue
                tags += tag
                proxies += item
            }
            for (node in linkNodes) {
                val tag = node.optString("tag")
                if (tag.isBlank() || !keep(tag)) continue
                tags += tag
                shareNodes.put(node)
            }
            if (tags.isNotEmpty()) providerMembers[name] = tags
            else notes += "节点源「$name」里没有能识别的节点"
        }
    }

    private fun readProviderBody(body: String): Pair<List<Map<*, *>>, List<JSONObject>> {
        val trimmed = stripBom(body).trim()
        decodeClashPayload(trimmed)?.let { return readProviderBody(it) }
        decodeSharePayload(trimmed)?.let { return emptyList<Map<*, *>>() to shareNodesOf(it) }
        if (looksLikeClash(trimmed)) {
            val tree = MiniYaml.parse(trimmed) as? Map<*, *> ?: return emptyList<Map<*, *>>() to emptyList()
            return asMapList(mapIgnoreCase(tree, "proxies")) to emptyList()
        }
        if (SHARE_LINE.containsMatchIn(trimmed)) {
            return emptyList<Map<*, *>>() to shareNodesOf(trimmed)
        }
        return emptyList<Map<*, *>>() to emptyList()
    }

    private fun shareNodesOf(text: String): List<JSONObject> {
        val nodes = mutableListOf<JSONObject>()
        for (line in expandShareText(text)) {
            val node = convertShareLine(line) ?: continue
            nodes += node
        }
        return nodes
    }

    private fun convertClashGroup(
        raw: Map<*, *>,
        known: Set<String>,
        pool: GroupPool,
    ): JSONObject? {
        val name = str(raw["name"]).ifBlank { return null }
        val type = str(raw["type"]).lowercase()
        val members = asStringList(raw["proxies"]).map { mapSpecialTag(it) }
            .filter { it.isNotEmpty() && (it in known || it == "direct") }
            .toMutableList()
        // mihomo: `use` / `include-all*` pull nodes in, then `filter` / `exclude-filter` / `exclude-type` narrow them.
        val pulled = LinkedHashSet<String>()
        for (used in asStringList(raw["use"])) pulled.addAll(pool.providers[used].orEmpty())
        val includeAll = boolVal(raw["include-all"] ?: raw["include_all"]) == true
        if (includeAll || boolVal(raw["include-all-providers"] ?: raw["include_all_providers"]) == true) {
            pool.providers.values.forEach { pulled.addAll(it) }
        }
        if (includeAll || boolVal(raw["include-all-proxies"] ?: raw["include_all_proxies"]) == true) {
            pulled.addAll(pool.inline)
        }
        val filters = clashFilters(raw["filter"])
        val excludes = clashFilters(raw["exclude-filter"] ?: raw["exclude_filter"])
        val excludeTypes = str(raw["exclude-type"] ?: raw["exclude_type"]).split('|')
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        for (tag in pulled) {
            if (tag in members || !(tag in known || tag == "direct")) continue
            if (filters.isNotEmpty() && filters.none { it.containsMatchIn(tag) }) continue
            if (excludes.any { it.containsMatchIn(tag) }) continue
            if (excludeTypes.isNotEmpty() && clashTypeName(pool.types[tag].orEmpty()) in excludeTypes) continue
            members += tag
        }
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

    /** mihomo filters are Go regexps; several may be joined with a backtick. A broken one matches nothing. */
    private fun clashFilters(raw: Any?): List<Regex> {
        val text = str(raw)
        if (text.isEmpty()) return emptyList()
        return text.split('`').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull {
            try {
                Regex(it)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** `exclude-type` uses mihomo names (Shadowsocks, Vmess, Hysteria2...); share-link nodes carry sing-box types. */
    private fun clashTypeName(type: String): String = when (type.lowercase()) {
        "ss", "shadowsocks" -> "shadowsocks"
        "hy2", "hysteria2" -> "hysteria2"
        "socks", "socks5" -> "socks5"
        else -> type.lowercase()
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
        if (payload.equals("private", true) ||
            payload.equals("lan", true) ||
            payload.equals("lancidr", true)
        ) {
            return JSONObject().put("ip_is_private", true)
        }
        val resolved = resolveRuleProvider(payload, providers) ?: return null
        if (resolved.first == "ip-private") return JSONObject().put("ip_is_private", true)
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
        val mapped = mapKnownRuleSet(name.trim().lowercase())
            ?: urlFileStem(url)?.let { mapKnownRuleSet(it) }
        if (mapped == "ip-private") return mapped to null
        if (mapped != null) return mapped to null
        val file = ConfigInboundCompat.officialRuleSetFile(name.trim().lowercase())
        if (file != null) return file.removeSuffix(".srs") to null
        if (name.trim().startsWith("geosite-", true) || name.trim().startsWith("geoip-", true)) {
            return name.trim().lowercase() to null
        }
        return null
    }

    /** Clash rule-provider file names that have a sing-box rule-set, or `ip-private`. */
    private fun mapKnownRuleSet(stem: String): String? {
        val raw = stem.trim().lowercase()
            .removeSuffix(".txt")
            .removeSuffix(".yaml")
            .removeSuffix(".yml")
            .removeSuffix(".list")
            .removeSuffix(".mrs")
        if (raw.isEmpty()) return null
        return when (raw) {
            "reject", "reject-domain", "ad", "ads", "advertising",
            "category-ads-all", "banad", "banads",
            -> "geosite-category-ads-all"
            "proxy", "gfw", "greatfire", "geolocation-!cn", "geosite-geolocation-!cn" ->
                "geosite-geolocation-!cn"
            "direct", "cn", "china", "geolocation-cn", "geosite-cn", "geosite-geolocation-cn" ->
                "geosite-cn"
            "cncidr", "cnip", "cn-ip", "china-ip", "geoip-cn" -> "geoip-cn"
            "private", "lancidr", "lan", "private-ip" -> "ip-private"
            "apple" -> "geosite-apple"
            "google" -> "geosite-google"
            "youtube" -> "geosite-youtube"
            "telegram", "telegramcidr" -> "geosite-telegram"
            "microsoft" -> "geosite-microsoft"
            "netflix" -> "geosite-netflix"
            "spotify" -> "geosite-spotify"
            "steam" -> "geosite-steam"
            "tiktok" -> "geosite-tiktok"
            "twitter" -> "geosite-twitter"
            "facebook", "meta" -> "geosite-facebook"
            "instagram" -> "geosite-instagram"
            "github" -> "geosite-github"
            "openai" -> "geosite-openai"
            "discord" -> "geosite-discord"
            "bilibili" -> "geosite-bilibili"
            "icloud" -> "geosite-icloud"
            else -> ConfigInboundCompat.officialRuleSetFile(raw)?.removeSuffix(".srs")
        }
    }

    private fun urlFileStem(url: String): String? {
        if (url.isBlank()) return null
        val path = url.substringBefore('?').substringBefore('#')
        val file = path.substringAfterLast('/').trim().lowercase()
        if (file.isEmpty() || file == path.lowercase()) return null
        return file
    }

    private fun matchField(kind: String, payload: String): JSONObject? {
        val rule = JSONObject()
        when (kind) {
            "DOMAIN" -> rule.put("domain", payload)
            "DOMAIN-SUFFIX" -> rule.put("domain_suffix", payload)
            "DOMAIN-KEYWORD" -> rule.put("domain_keyword", payload)
            "DOMAIN-REGEX" -> rule.put("domain_regex", payload)
            "DOMAIN-WILDCARD" -> {
                val wild = payload.trim()
                when {
                    wild.startsWith("+.") -> rule.put("domain_suffix", wild.removePrefix("+."))
                    wild.startsWith("*.") -> rule.put("domain_suffix", wild.removePrefix("*."))
                    wild.contains('*') -> rule.put(
                        "domain_regex",
                        "^" + Regex.escape(wild).replace("\\*", ".*") + "$",
                    )
                    else -> rule.put("domain", wild)
                }
            }
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
        val nodes = mutableListOf<JSONObject>()
        var skipped = 0
        for (line in lines) {
            val converted = convertShareLine(line)
            if (converted == null) {
                skipped++
                continue
            }
            nodes += converted
        }
        if (nodes.isEmpty()) {
            val schemes = lines.map { it.substringBefore("://").lowercase() }.filter { it.isNotEmpty() }.distinct()
            val removed = schemes.filter { it == "ssr" }
            val rest = schemes.filter { it != "ssr" }
            val parts = mutableListOf<String>()
            if (removed.isNotEmpty()) parts += "ssr 已从内核移除"
            if (rest.isNotEmpty()) parts += "${rest.joinToString("、")} 没能转成节点"
            val named = parts.joinToString("；").ifBlank { "这些协议" }
            val message = "没有可用节点（$named）。没有改成直连。"
            return Result("", listOf(message), Format.ShareLinks, message)
        }
        val notes = mutableListOf(
            "已将节点链接转为 sing-box 配置",
            "节点链接没有分流。建议开启默认脚本，应用不会自动开启。",
        )
        if (skipped > 0) notes += "跳过 $skipped 条无法识别的链接"
        return wrapNodes(nodes, notes, Format.ShareLinks)
    }

    /**
     * Standalone nodes (links, Xray outbounds, bare sing-box outbounds) → a startable profile:
     * unique tags, ss + shadow-tls helpers, WireGuard as endpoints, selector + url-test, final.
     */
    private fun wrapNodes(nodes: List<JSONObject>, notes: List<String>, format: Format): Result {
        val outbounds = JSONArray()
        val endpoints = JSONArray()
        val tags = LinkedHashSet<String>()
        val leaves = mutableListOf<String>()
        val helpers = mutableListOf<Pair<JSONObject, JSONObject>>()
        for (source in nodes) {
            val converted = if (source.optString("type") == "wireguard" && isLegacyWireguard(source)) {
                legacyWireguardEndpoint(source)
            } else {
                source
            }
            var tag = converted.optString("tag").ifBlank { "node-${tags.size + 1}" }
            var n = 2
            val base = tag
            while (tag == AUTO_SELECT || tag == AUTO_TEST || !tags.add(tag)) {
                tag = "$base-$n"
                n++
            }
            converted.put("tag", tag)
            val helper = takeChain(converted)
            if (helper != null) helpers += converted to helper
            val type = converted.optString("type")
            if (type == "direct" || type == "block" || type == "dns" || type == "selector" || type == "urltest") {
                tags.remove(tag)
                continue
            }
            leaves += tag
            if (type == "wireguard") endpoints.put(converted) else outbounds.put(converted)
        }
        if (leaves.isEmpty()) {
            val message = "没有可用节点。没有改成直连。"
            return Result("", listOf(message), format, message)
        }
        for ((node, helper) in helpers) {
            val owner = node.optString("tag")
            var tag = "$owner-shadowtls"
            var n = 2
            while (tag in tags) tag = "$owner-shadowtls-${n++}"
            tags.add(tag)
            helper.put("tag", tag)
            node.put("detour", tag)
            outbounds.put(helper)
        }
        ensureDirect(outbounds, tags)
        autoGroups(leaves, tags).forEach { outbounds.put(it) }
        dropDanglingDetours(outbounds, endpoints, tags)
        val root = JSONObject()
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("final", AUTO_SELECT))
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        return Result(root.toString(), notes, format)
    }

    /** One malformed link (e.g. vmess base64 that is not JSON) is skipped, not fatal for the whole list. */
    private fun convertShareLine(line: String): JSONObject? = try {
        convertShareLineUnchecked(line)
    } catch (_: Exception) {
        null
    }

    private fun convertShareLineUnchecked(line: String): JSONObject? {
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
            "hysteria" -> parseHysteria(body)
            "anytls" -> parseAnyTls(body)
            "tuic" -> parseTuic(body)
            "ssh" -> parseSsh(body)
            "naive", "naive+https", "naive+quic" -> parseNaive(body, scheme)
            "shadowtls" -> parseShadowTls(body)
            "snell" -> parseSnell(body)
            "ssr" -> null
            "socks", "socks5", "socks5h" -> parseUserHost(body, "socks")
            "socks4", "socks4a" -> parseUserHost(body, "socks")?.put("version", scheme.removePrefix("socks"))
            "http", "https" -> parseHttpProxy(body, scheme == "https")
            "wireguard", "wg" -> parseWireGuard(body)
            else -> null
        }
    }

    private fun parseSs(body: String): JSONObject? {
        val (main, fragment) = splitFragment(body)
        val query = parseQuery(main.substringAfter('?', ""))
        val bare = main.substringBefore('?')
        val decoded = if ('@' in bare && !bare.substringBefore('@').contains(':')) {
            bare
        } else if ('@' in bare) {
            bare
        } else {
            val inner = decodeB64(bare)?.toString(Charsets.UTF_8) ?: return null
            if ('@' in inner) inner else "$inner@placeholder"
        }
        val userHost = if ('@' in decoded) decoded else return null
        // Legacy base64 `method:pass@host:port` may carry a raw '@' in the password; the host never does.
        val user = userHost.substringBeforeLast('@')
        val hostPort = userHost.substringAfterLast('@').substringBefore('?').trimEnd('/')
        // SIP002: userinfo is base64url(method:password) or percent-encoded `method:password`
        // (required for 2022-blake3 keys, e.g. `...:abc%3D`). Either may arrive percent-encoded.
        val userText = if ('@' in bare) urlDecodeKeepPlus(user) else user
        val methodPass = if (':' in userText) {
            userText
        } else {
            decodeB64(userText)?.toString(Charsets.UTF_8)?.takeIf { ':' in it } ?: userText
        }
        val method = methodPass.substringBefore(':')
        val password = methodPass.substringAfter(':', "")
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = intVal(hostPort.substringAfterLast(':')) ?: return null
        val tag = fragment.ifBlank { host }
        val out = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", urlDecode(tag))
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)
        val plugin = query["plugin"].orEmpty()
        if (plugin.isNotEmpty()) {
            val rawName = plugin.substringBefore(';').trim()
            val opts = plugin.substringAfter(';', "")
            when (rawName.lowercase(Locale.US)) {
                "obfs", "simple-obfs", "obfs-local", "v2ray-plugin" -> {
                    out.put("plugin", ssPluginName(rawName))
                    if (opts.isNotEmpty()) out.put("plugin_opts", opts)
                }
                "shadow-tls", "shadowtls" -> {
                    val map = opts.split(';').filter { '=' in it }
                        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                    val helper = shadowTlsHelper(map, host, port, query["fp"].orEmpty()) ?: return null
                    attachChain(out, helper)
                }
                "" -> Unit
                // kcptun / restls / gost-plugin have no sing-box equivalent.
                else -> return null
            }
        } else if (!query["obfs"].isNullOrEmpty() && !query["obfs"].equals("none", true)) {
            // Shadowrocket: `?obfs=http&obfsParam=host` (also `obfs-host`).
            val mode = query["obfs"].orEmpty()
            if (mode != "http" && mode != "tls") return null
            val obfsHost = query["obfsParam"] ?: query["obfs-host"] ?: query["obfs_host"]
            out.put("plugin", "obfs-local")
            out.put("plugin_opts", if (obfsHost.isNullOrEmpty()) "obfs=$mode" else "obfs=$mode;obfs-host=$obfsHost")
        }
        if (query["uot"] == "1" || query["udp-over-tcp"] == "true") out.put("udp_over_tcp", true)
        return out
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
            val sni = obj.optString("sni").ifBlank { obj.optString("host").substringBefore(',') }
            if (sni.isNotEmpty()) tls.put("server_name", sni)
            val insecure = obj.optString("allowInsecure").ifBlank { obj.optString("insecure") }
            if (insecure == "1" || insecure.equals("true", true)) tls.put("insecure", true)
            putAlpn(tls, obj.optString("alpn"))
            obj.optString("fp").takeIf { it.isNotBlank() }
                ?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
            out.put("tls", tls)
        }
        val net = obj.optString("net").lowercase()
        if (net == "xhttp" || net == "splithttp") return null
        // v2rayN `net: tcp, type: http` is V2Ray's HTTP header obfuscation; sing-box has no such transport.
        if ((net.isEmpty() || net == "tcp" || net == "raw") && obj.optString("type").equals("http", true)) return null
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
                if (mapped == "grpc") {
                    transport.put("service_name", it)
                } else if (mapped != "quic") {
                    applyWsPath(transport, it)
                }
            }
            obj.optString("host").takeIf { it.isNotEmpty() }?.let { putTransportHost(transport, it) }
            out.put("transport", transport)
        }
        return out
    }

    private fun parseVless(body: String): JSONObject? = parseUserHostQuery(body, "vless") { out, query, host ->
        val uuid = urlDecode(body.substringBefore('@'))
        out.put("uuid", uuid)
        val encryption = query["encryption"].orEmpty()
        // Xray's post-quantum VLESS encryption is not in sing-box.
        if (encryption.isNotEmpty() && !encryption.equals("none", true)) return@parseUserHostQuery false
        val flow = vlessFlow(query["flow"].orEmpty()) ?: return@parseUserHostQuery false
        if (flow.isNotEmpty()) out.put("flow", flow)
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
            query["fp"]?.takeIf { it.isNotBlank() }?.let { fp ->
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            }
            ensureRealityUtls(tls)
        }
        true
    }

    /** sing-box: "uTLS is required by reality client". Xray / mihomo default to chrome when fp is empty. */
    private fun ensureRealityUtls(tls: JSONObject) {
        val utls = tls.optJSONObject("utls")
        if (utls != null && utls.optBoolean("enabled", false)) return
        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
    }

    private fun parseTrojan(body: String): JSONObject? = parseUserHostQuery(body, "trojan") { out, query, host ->
        out.put("password", urlDecodeKeepPlus(body.substringBefore('@')))
        putQueryTls(out, query, host, defaultOn = true)
        putQueryTransport(out, query)
    }

    private fun parseHysteria(body: String): JSONObject? {
        val (main, fragment) = splitFragment(body)
        val query = parseQuery(main.substringAfter('?', ""))
        val beforeQuery = main.substringBefore('?')
        val hostPort = (if ('@' in beforeQuery) beforeQuery.substringAfter('@') else beforeQuery).substringBefore('/')
        val user = if ('@' in beforeQuery) urlDecode(beforeQuery.substringBefore('@')) else ""
        if (hostPort.isEmpty() || ':' !in hostPort) return null
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = intVal(hostPort.substringAfterLast(':')) ?: return null
        val out = JSONObject()
            .put("type", "hysteria")
            .put("tag", urlDecode(fragment).ifBlank { host })
            .put("server", host)
            .put("server_port", port)
        val auth = user.ifBlank { query["auth"] ?: query["auth_str"].orEmpty() }
        if (auth.isNotEmpty()) out.put("auth_str", auth)
        intVal(query["upmbps"] ?: query["up"])?.let { out.put("up_mbps", it) }
        intVal(query["downmbps"] ?: query["down"])?.let { out.put("down_mbps", it) }
        val obfs = query["obfsParam"]?.takeIf { it.isNotEmpty() }
            ?: query["obfs"]?.takeIf { it.isNotEmpty() && !it.equals("xplus", true) && !it.equals("none", true) }
        if (!obfs.isNullOrEmpty()) out.put("obfs", obfs)
        val tls = JSONObject().put("enabled", true)
        val peer = query["peer"] ?: query["sni"] ?: host
        if (peer.isNotEmpty()) tls.put("server_name", peer)
        if (query["insecure"] == "1" || query["allowInsecure"] == "1") tls.put("insecure", true)
        query["alpn"]?.takeIf { it.isNotEmpty() }?.let { tls.put("alpn", JSONArray().put(it)) }
        out.put("tls", tls)
        return out
    }

    /**
     * `hysteria2://auth@host:443,20000-30000/?sni=…` — the URI spec allows a port list (port
     * hopping) and a missing port (443). v2rayN writes the range as `mport=`.
     */
    private fun parseHysteria2(body: String): JSONObject? {
        val (main, fragment) = splitFragment(body)
        val at = main.lastIndexOf('@', main.indexOf('?').takeIf { it >= 0 } ?: main.length)
        val user = if (at >= 0) main.substring(0, at) else ""
        val rest = main.substring(at + 1)
        val authority = rest.substringBefore('?').substringBefore('/')
        val query = parseQuery(rest.substringAfter('?', ""))
        val (host, portText) = splitHostPort(authority)
        if (host.isEmpty()) return null
        val out = JSONObject()
            .put("type", "hysteria2")
            .put("tag", urlDecode(fragment).ifBlank { host })
            .put("server", host)
        val ports = portText.ifEmpty { "443" }
        val single = ports.toIntOrNull()
        out.put("server_port", single ?: firstPort(ports) ?: return null)
        if (single == null) putPortList(out, ports)
        query["mport"]?.takeIf { it.isNotBlank() }?.let { putPortList(out, it) }
        out.put("password", urlDecodeKeepPlus(user))
        putQueryTls(out, query, host, defaultOn = true)
        val obfsType = query["obfs"].orEmpty()
        query["obfs-password"]?.takeIf { it.isNotEmpty() }?.let { pwd ->
            val type = if (obfsType.isEmpty() || obfsType.equals("none", true)) "salamander" else obfsType
            out.put("obfs", JSONObject().put("type", type).put("password", pwd))
        }
        mbps(query["upmbps"] ?: query["up"])?.let { out.put("up_mbps", it) }
        mbps(query["downmbps"] ?: query["down"])?.let { out.put("down_mbps", it) }
        return out
    }

    /** `host:port`, `[v6]:port`, `host` (no port) and `host:443,2000-3000` → host and port text. */
    private fun splitHostPort(authority: String): Pair<String, String> {
        val text = authority.trim()
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return "" to ""
            return text.substring(1, close) to text.substring(close + 1).removePrefix(":")
        }
        val colons = text.count { it == ':' }
        if (colons == 0) return text to ""
        if (colons > 1 && text.substringAfterLast(':').any { !it.isDigit() && it != ',' && it != '-' }) return text to ""
        return text.substringBeforeLast(':') to text.substringAfterLast(':')
    }

    private fun parseAnyTls(body: String): JSONObject? = parseUserHostQuery(body, "anytls") { out, query, host ->
        out.put("password", urlDecode(body.substringBefore('@')))
        putQueryTls(out, query, host, defaultOn = true)
        true
    }

    private fun parseSsh(body: String): JSONObject? = parseUserHostQuery(body, "ssh") { out, query, _ ->
        val user = urlDecode(body.substringBefore('@'))
        val name = urlDecode(user.substringBefore(':'))
        val pass = urlDecode(user.substringAfter(':', ""))
        val key = query["private_key"] ?: query["pk"]
        if (name.isBlank() || (pass.isBlank() && key.isNullOrBlank())) return@parseUserHostQuery false
        out.put("user", name)
        if (pass.isNotEmpty()) out.put("password", pass)
        if (!key.isNullOrBlank()) out.put("private_key", urlDecode(key))
        true
    }

    private fun parseNaive(body: String, scheme: String): JSONObject? = parseUserHostQuery(body, "naive") { out, query, host ->
        val user = urlDecode(body.substringBefore('@'))
        val name = urlDecode(user.substringBefore(':'))
        val pass = urlDecode(user.substringAfter(':', ""))
        if (name.isNotEmpty()) out.put("username", name)
        if (pass.isNotEmpty()) out.put("password", pass)
        putQueryTls(out, query, host, defaultOn = true)
        if (scheme.contains("quic")) out.put("quic", true)
        query["congestion_control"]?.let { out.put("quic_congestion_control", it) }
        true
    }

    private fun parseShadowTls(body: String): JSONObject? = parseUserHostQuery(body, "shadowtls") { out, query, host ->
        out.put("password", urlDecode(body.substringBefore('@')))
        out.put("version", intVal(query["version"]) ?: 3)
        putQueryTls(out, query, host, defaultOn = true)
        true
    }

    private fun parseSnell(body: String): JSONObject? = parseUserHostQuery(body, "snell") { out, query, _ ->
        val version = intVal(query["version"]) ?: 4
        val psk = urlDecode(body.substringBefore('@'))
        if ((version != 4 && version != 6) || psk.isBlank()) return@parseUserHostQuery false
        out.put("version", version)
        out.put("psk", psk)
        if (version == 4) {
            val mode = query["obfs"] ?: query["obfs_mode"]
            if (!mode.isNullOrBlank() && !mode.equals("none", true)) out.put("obfs_mode", mode)
            val host = query["obfs-host"] ?: query["host"]
            if (!host.isNullOrBlank()) out.put("obfs_host", host)
        } else {
            query["mode"]?.let { out.put("mode", it) }
        }
        true
    }

    private fun parseTuic(body: String): JSONObject? = parseUserHostQuery(body, "tuic") { out, query, host ->
        val user = body.substringBefore('@')
        val uuid = urlDecodeKeepPlus(user.substringBefore(':'))
        val password = urlDecodeKeepPlus(user.substringAfter(':', ""))
        out.put("uuid", uuid)
        if (password.isNotEmpty()) out.put("password", password)
        putQueryTls(out, query, host, defaultOn = true)
        (query["congestion_control"] ?: query["congestion-control"])?.let { out.put("congestion_control", it) }
        (query["udp_relay_mode"] ?: query["udp-relay-mode"])?.lowercase()
            ?.takeIf { it == "native" || it == "quic" }?.let { out.put("udp_relay_mode", it) }
        if (query["disable_sni"] == "1" || query["disable_sni"] == "true") out.optJSONObject("tls")?.remove("server_name")
        true
    }

    private fun parseUserHost(body: String, type: String): JSONObject? {
        val (main, fragment) = splitFragment(body)
        val userHost = main
        val hostPort = userHost.substringAfter('@', userHost).substringBefore('?').substringBefore('/')
        val userPass = if ('@' in userHost) userHost.substringBefore('@') else ""
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = intVal(hostPort.substringAfterLast(':')) ?: return null
        val out = JSONObject()
            .put("type", type)
            .put("tag", urlDecode(fragment).ifBlank { host })
            .put("server", host)
            .put("server_port", port)
        if (userPass.isNotEmpty()) {
            // v2rayN writes `socks://base64(user:pass)@host:port`.
            val plain = urlDecodeKeepPlus(userPass)
            val pair = if (':' in plain) {
                plain
            } else {
                decodeB64(plain)?.toString(Charsets.UTF_8)?.takeIf { ':' in it && it.all { c -> c >= ' ' } } ?: plain
            }
            out.put("username", urlDecode(pair.substringBefore(':')))
            val password = pair.substringAfter(':', "")
            if (password.isNotEmpty()) out.put("password", urlDecode(password))
        }
        return out
    }

    /**
     * `http(s)://user:pass@host:port#name` proxy links. A URL with a path or query is a
     * subscription address, not a node, so it is not read as one.
     */
    private fun parseHttpProxy(body: String, tls: Boolean): JSONObject? {
        val (main, _) = splitFragment(body)
        val afterUser = main.substringAfterLast('@')
        val path = afterUser.substringAfter('/', "")
        if (path.isNotEmpty() || '?' in afterUser) return null
        if (splitHostPort(afterUser.trimEnd('/')).second.toIntOrNull() == null) return null
        val out = parseUserHost(body, "http") ?: return null
        if (tls) out.put("tls", JSONObject().put("enabled", true).put("server_name", out.optString("server")))
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
        // `host:443/?sni=...` (Hysteria2 URI spec, sing-box / Hiddify exports): drop the path.
        val hostPort = hostPortQuery.substringBefore('?').substringBefore('/')
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
        if (query["allowInsecure"] == "1" || query["insecure"] == "1" ||
            query["allowInsecure"].equals("true", true) || query["insecure"].equals("true", true)
        ) {
            tls.put("insecure", true)
        }
        query["fp"]?.takeIf { it.isNotBlank() && !it.equals("none", true) }
            ?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
        putAlpn(tls, query["alpn"].orEmpty())
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

    private fun putAlpn(tls: JSONObject, raw: String) {
        val arr = JSONArray()
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
        if (arr.length() > 0) tls.put("alpn", arr)
    }

    private fun putQueryTransport(out: JSONObject, query: Map<String, String>): Boolean {
        val rawType = (query["type"] ?: query["network"]).orEmpty().lowercase()
        if (rawType.isEmpty() || rawType == "tcp" || rawType == "raw") {
            // `headerType=http` is V2Ray's HTTP header obfuscation; sing-box cannot speak it.
            return !query["headerType"].equals("http", true)
        }
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
            if (type == "grpc") {
                transport.put("service_name", value)
            } else if (type != "quic") {
                applyWsPath(transport, value)
            }
        }
        query["host"]?.let { putTransportHost(transport, it) }
        out.put("transport", transport)
        return true
    }

    /**
     * sing-box rejects unknown fields: grpc / quic have no `headers`.
     * http / httpupgrade take the Host from `host`; a `Host` header is ignored there.
     */
    private fun putTransportHost(transport: JSONObject, rawHost: String) {
        val host = rawHost.trim()
        if (host.isEmpty()) return
        when (transport.optString("type")) {
            "ws" -> transport.put("headers", JSONObject().put("Host", host))
            "httpupgrade" -> transport.put("host", host)
            "http" -> {
                val hosts = JSONArray()
                host.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { hosts.put(it) }
                if (hosts.length() > 0) transport.put("host", hosts)
            }
        }
    }

    private fun applyWsPath(transport: JSONObject, rawPath: String) {
        val split = splitEarlyData(rawPath)
        transport.put("path", split.first)
        val ed = split.second
        // Only the websocket transport has early-data fields; others reject them.
        if (ed != null && ed > 0 && transport.optString("type") == "ws") {
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
                    .put("domain_resolver", ensureLocalDns(servers))
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
     * ECH HTTPS lookups must not use a DNS server that detours through the
     * same proxy. That proxy is waiting on this lookup (`fetch ECH config
     * list: context deadline exceeded`). Dial AliDNS directly.
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
        val bypass = ensureDirectBypass(root)
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        var changed = bypass.changed
        var tag = firstDnsTag(servers) { it.startsWith("ech-") }
        if (tag == null) {
            tag = "ech-dns"
            if (!dnsServerHas(servers, tag)) {
                servers.put(
                    JSONObject()
                        .put("type", "https")
                        .put("tag", tag)
                        .put("server", "223.5.5.5")
                        .put("server_port", 443)
                        .put("path", "/dns-query")
                        .put(
                            "tls",
                            JSONObject().put("enabled", true).put("server_name", "dns.alidns.com"),
                        )
                        .put("detour", bypass.tag),
                )
                changed = true
            }
        }
        if (forceEchDetour(servers, tag, bypass.tag)) changed = true
        val missing = names.filter { !dnsRuleRoutesTo(dns.optJSONArray("rules"), it, tag) }
        if (missing.isEmpty()) return changed
        return prependEchRule(dns, missing, tag) || changed
    }

    private class DirectBypass(val tag: String, val changed: Boolean)

    /** Empty `direct` cannot be a DNS detour (kernel rejects it). A timeout makes it usable. */
    private fun ensureDirectBypass(root: JSONObject): DirectBypass {
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        var fallback = -1
        for (i in 0 until outs.length()) {
            val outbound = outs.optJSONObject(i) ?: continue
            if (!outbound.optString("type").equals("direct", true)) continue
            val tag = outbound.optString("tag").trim()
            if (tag.isEmpty()) continue
            if (tag.equals("direct", true)) {
                return DirectBypass(tag, stampDirectBypass(outbound))
            }
            if (fallback < 0) fallback = i
        }
        if (fallback >= 0) {
            val outbound = outs.getJSONObject(fallback)
            return DirectBypass(outbound.optString("tag"), stampDirectBypass(outbound))
        }
        outs.put(
            JSONObject()
                .put("type", "direct")
                .put("tag", "direct")
                .put("connect_timeout", "8s"),
        )
        return DirectBypass("direct", true)
    }

    private fun stampDirectBypass(outbound: JSONObject): Boolean {
        if (outbound.optString("connect_timeout").isNotBlank()) return false
        outbound.put("connect_timeout", "8s")
        return true
    }

    private fun forceEchDetour(servers: JSONArray, tag: String, detour: String): Boolean {
        var changed = false
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (server.optString("tag") != tag) continue
            if (server.optString("server").equals("dns.alidns.com", true)) {
                server.put("server", "223.5.5.5")
                if (server.optInt("server_port", 0) == 0) server.put("server_port", 443)
                val tls = server.optJSONObject("tls") ?: JSONObject().also { server.put("tls", it) }
                tls.put("enabled", true)
                if (tls.optString("server_name").isBlank()) tls.put("server_name", "dns.alidns.com")
                server.remove("domain_resolver")
                changed = true
            }
            if (server.optString("detour") != detour) {
                server.put("detour", detour)
                changed = true
            }
        }
        return changed
    }

    private fun dnsRuleRoutesTo(rules: JSONArray?, name: String, tag: String): Boolean {
        if (rules == null) return false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("server") != tag) continue
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

    /**
     * The DoH server for ECH names its host, which has to be resolved by something that does
     * not need the proxy: a `local` DNS server. Reuse one, or add `{type: local, tag: local}`.
     */
    private fun ensureLocalDns(servers: JSONArray): String {
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val tag = server.optString("tag").trim()
            if (tag.isNotEmpty() && server.optString("type").equals("local", true)) return tag
        }
        if (dnsServerHas(servers, "local")) return "local"
        servers.put(JSONObject().put("type", "local").put("tag", "local"))
        return "local"
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
        // sing-box wants prefixes; mihomo / Xray often write a bare address.
        (addresses.ifEmpty { listOf("172.16.0.2/32") }).forEach { address ->
            locals.put(if ('/' in address) address else if (':' in address) "$address/128" else "$address/32")
        }
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

    /** sing-box registers only `obfs-local` and `v2ray-plugin`. Clash writes `obfs`, SIP002 links `simple-obfs`. */
    private fun ssPluginName(name: String): String {
        val trimmed = name.trim()
        return when (trimmed.lowercase(Locale.US)) {
            "obfs", "simple-obfs" -> "obfs-local"
            else -> trimmed
        }
    }

    private fun pluginOpts(plugin: String, raw: Any?): String {
        val map = raw as? Map<*, *> ?: return raw?.toString().orEmpty()
        val obj = JSONObject()
        map.forEach { (key, value) ->
            if (key is String && value != null) obj.put(key, value)
        }
        return ConfigCompat.objectToPluginOpts(plugin, obj)
    }

    private fun expandShareText(text: String): List<String> {
        val direct = shareLinesOf(text)
        if (direct.isNotEmpty()) return direct
        val decoded = decodeSharePayload(text) ?: return emptyList()
        return shareLinesOf(decoded)
    }

    /**
     * Links one per line, or several on a line separated by spaces / commas. A YAML list item
     * (`- vless://…`) or a quoted link counts too; comments and other text are ignored.
     */
    /** `https://host/path?token=…` is a subscription address, not an HTTP proxy node. */
    private fun isSubscriptionUrl(link: String): Boolean {
        val scheme = link.substringBefore("://").lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        return parseHttpProxy(link.substringAfter("://"), scheme == "https") == null
    }

    private fun shareLinesOf(text: String): List<String> {
        val out = ArrayList<String>()
        for (rawLine in text.lineSequence()) {
            var line = rawLine.trim()
            if (line.startsWith("- ")) line = line.substring(2).trim()
            line = line.trim('"', '\'', '`').trim()
            if (!SHARE_LINE.containsMatchIn(line)) continue
            line.split(SHARE_SPLIT).map { it.trim().trimEnd(',', ';') }
                .filter { SHARE_LINE.containsMatchIn(it) && !isSubscriptionUrl(it) }
                .forEach { out += it }
        }
        return out
    }

    private fun decodeSharePayload(text: String): String? {
        val compact = text.trim().replace("\\s".toRegex(), "")
        if (compact.length < 16 || compact.any { it !in B64_CHARS }) return null
        val bytes = decodeB64(compact) ?: return null
        val decoded = stripBom(bytes.toString(Charsets.UTF_8))
        return decoded.takeIf { SHARE_LINE.containsMatchIn(it) }
    }

    private fun decodeClashPayload(text: String): String? {
        val compact = text.trim().replace("\\s".toRegex(), "")
        if (compact.length < 16 || compact.any { it !in B64_CHARS }) return null
        val decoded = stripBom(decodeB64(compact)?.toString(Charsets.UTF_8) ?: return null)
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
        is Number -> when (value.toInt()) {
            1 -> true
            0 -> false
            else -> null
        }
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

    private const val SHARE_SCHEMES = "ss|ssr|vmess|vless|trojan|hysteria2?|hy2|tuic|anytls|socks[45]?a?|https?|wireguard|wg|ssh|naive(?:\\+https|\\+quic)?|shadowtls|snell"
    private val SHARE_LINE = Regex("(?i)^($SHARE_SCHEMES)://")
    /** A second link on the same line, after whitespace or a comma. */
    private val SHARE_SPLIT = Regex("(?i)(?:\\s+|\\s*,\\s*)(?=(?:$SHARE_SCHEMES)://)")
    private const val CHAIN_KEY = "__angela_chain"
    private const val AUTO_SELECT = "节点选择"
    private const val AUTO_TEST = "自动选择"
    private val ECH_PEM = Regex("-----BEGIN ECH CONFIGS-----([\\s\\S]*?)-----END ECH CONFIGS-----")
    private const val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
}

/**
 * Small YAML reader for Clash / mihomo files. Not a full YAML parser, but it covers what real
 * subscriptions use: block and compact sequences, flow maps / lists (also across lines),
 * block scalars (`|` / `>` with chomping), anchors, aliases and `<<` merge keys.
 * Scalars follow YAML 1.2 / mihomo: only `true` / `false` are booleans, so a node named `NO`
 * or a password `yes` stays text.
 */
internal object MiniYaml {
    private val anchors = ThreadLocal.withInitial { HashMap<String, Any?>() }

    fun parse(text: String): Any? {
        val lines = tokenize(text)
        if (lines.isEmpty()) return null
        anchors.get().clear()
        return try {
            val index = intArrayOf(0)
            parseNode(lines, index, 0)
        } finally {
            anchors.get().clear()
        }
    }

    private data class YLine(val indent: Int, val raw: String)

    /** Lines are trimmed, so an item whose value sits on the next lines (`-` alone) has no trailing space. */
    private fun isItem(raw: String): Boolean = raw == "-" || raw.startsWith("- ")

    private fun isFlowStart(value: String): Boolean = value.startsWith("{") || value.startsWith("[")

    /** `key: |-`, `- >`, `key: |2+` → the text before the indicator, the style and the chomping. */
    private val BLOCK_HEADER = Regex("^(.*?)([|>])([1-9]?)([+-]?)([1-9]?)$")

    private fun tokenize(text: String): List<YLine> {
        val out = ArrayList<YLine>()
        val source = text.lines()
        var i = 0
        while (i < source.size) {
            val original = source[i]
            i++
            val noComment = stripComment(original)
            if (noComment.isBlank()) continue
            var indent = 0
            while (indent < noComment.length && noComment[indent] == ' ') indent++
            var content = noComment.trim()
            if (content.isEmpty() || content == "---" || content == "...") continue
            val block = blockHeader(content)
            if (block != null) {
                val (prefix, style, chomp) = block
                val explicit = BLOCK_HEADER.find(content)?.let { m ->
                    (m.groupValues[3] + m.groupValues[5]).toIntOrNull()
                }
                val body = ArrayList<String>()
                while (i < source.size) {
                    val next = source[i].replace("\t", "  ")
                    if (next.isNotBlank()) {
                        var ni = 0
                        while (ni < next.length && next[ni] == ' ') ni++
                        if (ni <= indent) break
                    }
                    body += next
                    i++
                }
                val value = foldBlock(body, style, chomp, explicit?.let { indent + it })
                out += YLine(indent, prefix + quote(value))
                continue
            }
            val valueStart = flowValueStart(content)
            if (valueStart >= 0 && flowDepth(content.substring(valueStart)) > 0) {
                val joined = StringBuilder(content)
                while (i < source.size && flowDepth(joined.substring(valueStart)) > 0) {
                    val more = stripComment(source[i]).trim()
                    i++
                    if (more.isEmpty()) continue
                    joined.append(' ').append(more)
                }
                content = joined.toString()
            }
            out += YLine(indent, content)
        }
        return out
    }

    /** Header of a block scalar: (text before the indicator, '|' or '>', chomping '+', '-' or ""). */
    private fun blockHeader(content: String): Triple<String, Char, String>? {
        val m = BLOCK_HEADER.find(content) ?: return null
        val prefix = m.groupValues[1]
        if (m.groupValues[3].isNotEmpty() && m.groupValues[5].isNotEmpty()) return null
        val ok = prefix.isEmpty() ||
            prefix == "- " ||
            (prefix.endsWith(": ") && pairColon(prefix.trimEnd()) == prefix.trimEnd().length - 1) ||
            (prefix.endsWith(" ") && prefix.trimEnd().let { it.endsWith(":") || it == "-" || it.startsWith("&") || it.contains(" &") })
        if (!ok) return null
        return Triple(prefix, m.groupValues[2][0], m.groupValues[4])
    }

    private fun foldBlock(body: List<String>, style: Char, chomp: String, fixedIndent: Int?): String {
        val indent = fixedIndent ?: body.firstOrNull { it.isNotBlank() }?.let { line ->
            var n = 0
            while (n < line.length && line[n] == ' ') n++
            n
        } ?: 0
        val lines = body.map { if (it.length >= indent) it.substring(indent) else it.trim() }
        var end = lines.size
        while (end > 0 && lines[end - 1].isBlank()) end--
        val content = lines.subList(0, end)
        val trailingBlank = lines.size - end
        val text = if (style == '|') {
            content.joinToString("\n")
        } else {
            val sb = StringBuilder()
            var prevText = false
            for ((n, line) in content.withIndex()) {
                val moreIndented = line.startsWith(" ")
                when {
                    line.isEmpty() -> {
                        sb.append('\n')
                        prevText = false
                    }
                    n == 0 -> {
                        sb.append(line)
                        prevText = !moreIndented
                    }
                    prevText && !moreIndented -> {
                        sb.append(' ').append(line)
                    }
                    else -> {
                        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                        sb.append(line)
                        prevText = !moreIndented
                    }
                }
            }
            sb.toString()
        }
        if (content.isEmpty()) return ""
        return when (chomp) {
            "-" -> text
            "+" -> text + "\n" + "\n".repeat(trailingBlank)
            else -> text + "\n"
        }
    }

    /** Double-quoted YAML scalar, read back by [parseScalar]. */
    private fun quote(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Where a flow value (`{` / `[`) starts on this line, or -1. */
    private fun flowValueStart(content: String): Int {
        if (isFlowStart(content)) return 0
        if (isItem(content)) {
            val rest = content.substring(1).trimStart()
            val at = content.length - rest.length
            if (isFlowStart(rest)) return at
            val colon = pairColon(rest)
            if (colon < 0) return -1
            val value = rest.substring(colon + 1).trimStart()
            return if (isFlowStart(value)) content.length - value.length else -1
        }
        val colon = pairColon(content)
        if (colon < 0) return -1
        val value = content.substring(colon + 1).trimStart()
        return if (isFlowStart(value)) content.length - value.length else -1
    }

    private fun flowDepth(text: String): Int {
        var depth = 0
        var inSingle = false
        var inDouble = false
        var escaped = false
        for ((i, c) in text.withIndex()) {
            if (inDouble) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inDouble = false
                }
                continue
            }
            if (inSingle) {
                if (c == '\'') inSingle = false
                continue
            }
            when {
                c == '"' && quoteOpens(text, i) -> inDouble = true
                c == '\'' && quoteOpens(text, i) -> inSingle = true
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
        }
        return depth
    }

    /** A quote starts a quoted scalar only where a value starts (`it's` in a plain value is text). */
    private fun quoteOpens(text: String, at: Int): Boolean {
        var j = at - 1
        while (j >= 0 && text[j] == ' ') j--
        if (j < 0) return true
        return text[j] in ":-[{,?&!" || (text[j] == '*')
    }

    private fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (i in line.indices) {
            val c = line[i]
            if (inDouble) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inDouble = false
                }
                continue
            }
            if (inSingle) {
                if (c == '\'') inSingle = false
                continue
            }
            when {
                c == '\'' && quoteOpens(line, i) -> inSingle = true
                c == '"' && quoteOpens(line, i) -> inDouble = true
                // YAML: '#' starts a comment only at line start or after whitespace (`password: a#b` is a value).
                c == '#' && (i == 0 || line[i - 1].isWhitespace()) ->
                    return line.substring(0, i).replace("\t", "  ")
            }
        }
        return line.replace("\t", "  ")
    }

    private fun parseNode(lines: List<YLine>, index: IntArray, minIndent: Int): Any? {
        if (index[0] >= lines.size) return null
        val line = lines[index[0]]
        if (line.indent < minIndent) return null
        if (isFlowStart(line.raw) && flowDepth(line.raw) == 0 && pairColon(line.raw) < 0) {
            index[0]++
            return parseFlow(line.raw)
        }
        if (line.raw.startsWith("&") || line.raw.startsWith("*")) {
            index[0]++
            return parseScalarOrChild(line.raw, lines, index, line.indent)
        }
        return if (isItem(line.raw)) {
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
            if (!isItem(line.raw)) break
            var rest = if (line.raw == "-") "" else line.raw.substring(2).trim()
            index[0]++
            var anchor: String? = null
            if (rest.startsWith("&")) {
                anchor = rest.substring(1).substringBefore(' ')
                rest = rest.substring(1 + anchor.length).trim()
            }
            // Items of a `- ` line sit at the dash indent plus two.
            val itemIndent = indent + 2
            val value: Any? = when {
                rest.isEmpty() -> parseNode(lines, index, indent + 1)
                rest.startsWith("*") -> alias(rest)
                isFlowStart(rest) && pairColon(rest) < 0 -> parseFlow(rest)
                isItem(rest) -> {
                    // `- - a` nested sequence on one line: rare, keep the inner item text.
                    listOf(parseScalar(rest.substring(1).trim()))
                }
                pairColon(rest) >= 0 -> {
                    val map = LinkedHashMap<String, Any?>()
                    val (k, v) = splitPair(rest)
                    val next = lines.getOrNull(index[0])
                    map[k] = if (v.isEmpty() && next != null && next.indent == itemIndent && isItem(next.raw)) {
                        parseList(lines, index, itemIndent)
                    } else {
                        parseScalarOrChild(v, lines, index, itemIndent + 1)
                    }
                    while (index[0] < lines.size) {
                        val child = lines[index[0]]
                        if (child.indent <= indent) break
                        if (isItem(child.raw)) {
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
                            val after = lines.getOrNull(index[0])
                            map[ck] = if (cv.isEmpty() && after != null && after.indent == child.indent && isItem(after.raw)) {
                                parseList(lines, index, child.indent)
                            } else {
                                parseScalarOrChild(cv, lines, index, child.indent + 1)
                            }
                        }
                    }
                    applyMerge(map)
                }
                else -> parseScalar(rest)
            }
            if (anchor != null) anchors.get()[anchor] = value
            list += value
        }
        return list
    }

    private fun parseMap(lines: List<YLine>, index: IntArray, indent: Int): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (index[0] < lines.size) {
            val line = lines[index[0]]
            if (line.indent < indent) break
            if (isItem(line.raw)) break
            if (line.indent > indent) break
            val (k, v) = splitPair(line.raw)
            index[0]++
            val next = lines.getOrNull(index[0])
            map[k] = if (v.isEmpty() && next != null && next.indent == indent && isItem(next.raw)) {
                // Compact sequence (yaml.v2 / PyYAML default): `proxies:` then `- name: a` at the same indent.
                parseList(lines, index, indent)
            } else {
                parseScalarOrChild(v, lines, index, indent + 1)
            }
        }
        return applyMerge(map)
    }

    /** `<<: *base` / `<<: [*a, *b]`: keys written in the map win over merged ones. */
    private fun applyMerge(map: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
        if (!map.containsKey("<<")) return map
        val sources = when (val merge = map.remove("<<")) {
            is Map<*, *> -> listOf(merge)
            is List<*> -> merge.filterIsInstance<Map<*, *>>()
            else -> emptyList()
        }
        for (source in sources) {
            for ((k, v) in source) {
                val key = k?.toString() ?: continue
                if (!map.containsKey(key)) map[key] = v
            }
        }
        return map
    }

    private fun alias(raw: String): Any? {
        val name = raw.trim().removePrefix("*").substringBefore(' ').trim()
        return anchors.get()[name]
    }

    private fun parseScalarOrChild(
        value: String,
        lines: List<YLine>,
        index: IntArray,
        childIndent: Int,
    ): Any? {
        var v = value
        var anchor: String? = null
        if (v.startsWith("&")) {
            anchor = v.substring(1).substringBefore(' ')
            v = v.substring(1 + anchor.length).trim()
        }
        val result: Any? = when {
            v.startsWith("*") -> alias(v)
            v.isNotEmpty() -> if (isFlowStart(v)) parseFlow(v) else parseScalar(v)
            index[0] >= lines.size -> emptyMap<String, Any?>()
            else -> {
                val next = lines[index[0]]
                if (next.indent >= childIndent) parseNode(lines, index, next.indent) else emptyMap<String, Any?>()
            }
        }
        if (anchor != null) anchors.get()[anchor] = result
        return result
    }

    private fun splitPair(raw: String): Pair<String, String> {
        val idx = pairColon(raw).takeIf { it >= 0 } ?: raw.indexOf(':')
        if (idx < 0) return raw.trim() to ""
        val key = unquoteKey(raw.substring(0, idx).trim())
        val value = raw.substring(idx + 1).trim()
        return key to value
    }

    private fun unquoteKey(key: String): String {
        if (key.length >= 2 && key.first() == '"' && key.last() == '"') return unescapeDouble(key.substring(1, key.length - 1))
        if (key.length >= 2 && key.first() == '\'' && key.last() == '\'') return key.substring(1, key.length - 1).replace("''", "'")
        return key
    }

    /**
     * Index of the key/value ':' — one followed by whitespace or end of text, outside quotes,
     * or right after a quoted key (`"name":x`). `IP-CIDR6,2001:db8::/32,DIRECT` and
     * `server: 2001:db8::1` keep their inner colons. -1 when the text is a plain scalar.
     */
    private fun pairColon(raw: String): Int {
        var inSingle = false
        var inDouble = false
        var escaped = false
        var depth = 0
        for (i in raw.indices) {
            val c = raw[i]
            if (inDouble) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inDouble = false
                }
                continue
            }
            if (inSingle) {
                if (c == '\'') inSingle = false
                continue
            }
            when {
                c == '\'' && quoteOpens(raw, i) -> inSingle = true
                c == '"' && quoteOpens(raw, i) -> inDouble = true
                (c == '{' || c == '[') && i == 0 -> return -1
                c == ':' && depth == 0 -> {
                    if (i == raw.lastIndex || raw[i + 1].isWhitespace()) return i
                    if (i > 1 && (raw[i - 1] == '"' || raw[i - 1] == '\'')) return i
                }
            }
        }
        return -1
    }

    private fun parseScalar(raw: String): Any? {
        val v = raw.trim()
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return unescapeDouble(v.substring(1, v.length - 1))
        }
        if (v.length >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length - 1).replace("''", "'")
        }
        if (v.startsWith("!!str ")) return v.removePrefix("!!str ").trim().trim('"', '\'')
        // YAML 1.2 core schema (mihomo uses yaml.v3): yes / no / on / off are strings.
        when (v) {
            "true", "True", "TRUE" -> return true
            "false", "False", "FALSE" -> return false
            "null", "Null", "NULL", "~", "" -> return null
        }
        // Only canonical numbers: `short-id: 0123`, `password: 1e5` or `name: 1.10` must stay text.
        v.toIntOrNull()?.let { if (it.toString() == v) return it }
        v.toLongOrNull()?.let { if (it.toString() == v) return it }
        v.toDoubleOrNull()?.let { if (it.toString() == v) return it }
        return v
    }

    private fun unescapeDouble(body: String): String {
        if ('\\' !in body) return body
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i == body.lastIndex) {
                sb.append(c)
                i++
                continue
            }
            val e = body[i + 1]
            i += 2
            when (e) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '0' -> sb.append('\u0000')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                ' ' -> sb.append(' ')
                'x', 'u', 'U' -> {
                    val len = when (e) {
                        'x' -> 2
                        'u' -> 4
                        else -> 8
                    }
                    val hex = body.substring(i, minOf(body.length, i + len))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == len) {
                        sb.appendCodePoint(code)
                        i += len
                    } else {
                        sb.append('\\').append(e)
                    }
                }
                else -> sb.append('\\').append(e)
            }
        }
        return sb.toString()
    }

    private fun parseFlow(raw: String): Any? {
        val s = raw.trim()
        return when {
            s.startsWith("{") -> parseFlowMap(s)
            s.startsWith("[") -> parseFlowList(s)
            else -> flowScalar(s)
        }
    }

    private fun flowScalar(raw: String): Any? {
        val p = raw.trim()
        var v = p
        var anchor: String? = null
        if (v.startsWith("&")) {
            anchor = v.substring(1).substringBefore(' ')
            v = v.substring(1 + anchor.length).trim()
        }
        val result = when {
            v.startsWith("*") -> alias(v)
            isFlowStart(v) -> parseFlow(v)
            else -> parseScalar(v)
        }
        if (anchor != null) anchors.get()[anchor] = result
        return result
    }

    private fun closeFlow(raw: String, open: Char, close: Char): String {
        var s = raw.trim()
        if (s.startsWith(open)) s = s.substring(1)
        s = s.trimEnd()
        if (s.endsWith(close)) s = s.substring(0, s.length - 1)
        return s.trim()
    }

    private fun parseFlowMap(raw: String): Map<String, Any?> {
        val inner = closeFlow(raw, '{', '}')
        val map = LinkedHashMap<String, Any?>()
        splitFlow(inner).forEach { part ->
            if (part.isBlank()) return@forEach
            val colon = flowPairColon(part)
            if (colon < 0) {
                map[unquoteKey(part.trim())] = null
            } else {
                map[unquoteKey(part.substring(0, colon).trim())] = flowScalar(part.substring(colon + 1))
            }
        }
        return applyMerge(map)
    }

    /** In flow context `{a: b}` and JSON-ish `{"a":"b"}` both appear. */
    private fun flowPairColon(part: String): Int {
        val colon = pairColon(part)
        if (colon >= 0) return colon
        val t = part.trimStart()
        if (t.startsWith("\"") || t.startsWith("'")) {
            val q = t[0]
            var i = 1
            while (i < t.length) {
                if (t[i] == '\\' && q == '"') {
                    i += 2
                    continue
                }
                if (t[i] == q) break
                i++
            }
            val after = i + 1
            val offset = part.length - t.length
            if (after < t.length && t.substring(after).trimStart().startsWith(":")) {
                return offset + after + (t.substring(after).length - t.substring(after).trimStart().length)
            }
        }
        return -1
    }

    private fun parseFlowList(raw: String): List<Any?> {
        val inner = closeFlow(raw, '[', ']')
        if (inner.isEmpty()) return emptyList()
        return splitFlow(inner).filter { it.isNotBlank() }.map { part ->
            val p = part.trim()
            if (!isFlowStart(p) && !p.startsWith("\"") && !p.startsWith("'") && pairColon(p) >= 0) {
                // `[a: 1]` single-pair map inside a flow list.
                val (k, v) = splitPair(p)
                mapOf(k to flowScalar(v))
            } else {
                flowScalar(p)
            }
        }
    }

    private fun splitFlow(raw: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var depth = 0
        var inSingle = false
        var inDouble = false
        var escaped = false
        for ((i, c) in raw.withIndex()) {
            if (inDouble) {
                buf.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inDouble = false
                }
                continue
            }
            if (inSingle) {
                buf.append(c)
                if (c == '\'') inSingle = false
                continue
            }
            when {
                c == '\'' && quoteOpens(raw, i) -> {
                    inSingle = true
                    buf.append(c)
                }
                c == '"' && quoteOpens(raw, i) -> {
                    inDouble = true
                    buf.append(c)
                }
                c == '{' || c == '[' -> {
                    depth++
                    buf.append(c)
                }
                c == '}' || c == ']' -> {
                    depth--
                    buf.append(c)
                }
                c == ',' && depth == 0 -> {
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
