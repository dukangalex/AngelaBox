package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.Inflater

data class RuleSetProvider(
    val tag: String,
    val type: String,
    val format: String,
    val url: String,
    val path: String,
    val raw: JSONObject,
) {
    val remote: Boolean get() = type.equals("remote", true) || url.startsWith("http")
    val local: Boolean get() = type.equals("local", true)
    val inline: Boolean get() = type.equals("inline", true) || type.isBlank()
}

data class ProxyProviderItem(
    val tag: String,
    val type: String,
    val url: String,
    val entries: List<String>,
    val remote: Boolean,
)

object RuleSetProviders {
    fun parse(content: String): List<RuleSetProvider> {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed[0] != '{') return emptyList()
        if (trimmed.length > ConfigCompat.MAX_CONFIG_CHARS) return emptyList()
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return emptyList()
        }
        val sets = root.optJSONObject("route")?.optJSONArray("rule_set") ?: return emptyList()
        val out = mutableListOf<RuleSetProvider>()
        val seen = mutableSetOf<String>()
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val tag = item.optString("tag").trim()
            if (tag.isEmpty() || !seen.add(tag)) continue
            val url = item.optString("url").ifBlank { item.optString("download_url") }
            val path = item.optString("path").ifBlank { item.optString("initial_path") }
            out += RuleSetProvider(
                tag = tag,
                type = item.optString("type").ifBlank { if (url.startsWith("http")) "remote" else "inline" },
                format = item.optString("format"),
                url = url.trim(),
                path = path.trim(),
                raw = item,
            )
        }
        return out
    }

    fun cacheFile(workingDir: File, tag: String, format: String = "", url: String = ""): File {
        val ext = when {
            format.equals("source", true) || url.endsWith(".json", true) -> "json"
            else -> "srs"
        }
        val safe = tag.replace(Regex("[^A-Za-z0-9._\\-@!]"), "_")
        return File(File(workingDir, "rule-sets"), "$safe.$ext")
    }

    fun sourceCacheFile(workingDir: File, tag: String): File {
        val safe = tag.replace(Regex("[^A-Za-z0-9._\\-@!]"), "_")
        return File(File(workingDir, "rule-sets"), "$safe.json")
    }

    fun sourceUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.endsWith(".srs", true)) return trimmed.dropLast(4) + ".json"
        return null
    }

    fun updateItem(root: JSONObject, tag: String, mutate: (JSONObject) -> Unit): Boolean {
        val sets = root.optJSONObject("route")?.optJSONArray("rule_set") ?: return false
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            if (item.optString("tag") != tag) continue
            mutate(item)
            return true
        }
        return false
    }

    fun listEntries(file: File?, inline: JSONObject? = null): List<String> {
        val fromFile = file?.let { listEntriesFromFile(it) }.orEmpty()
        if (fromFile.isNotEmpty()) return fromFile
        val rules = inline?.optJSONArray("rules") ?: inline?.optJSONObject("headless")?.optJSONArray("rules")
        if (rules != null) return flattenRules(rules)
        return emptyList()
    }

    fun sourceRuleCount(file: File): Int? {
        val n = listEntriesFromFile(file).size
        return n.takeIf { it > 0 } ?: srsTopLevelCount(file)
    }

    fun pretty(item: JSONObject): String = item.toString(2)

    fun numbered(lines: List<String>): String =
        lines.mapIndexed { index, line -> "${index + 1} $line" }.joinToString("\n")

    fun flattenRules(rules: JSONArray): List<String> {
        val out = mutableListOf<String>()
        flattenRulesInto(rules, out)
        return out
    }

    private fun flattenRulesInto(rules: JSONArray, out: MutableList<String>) {
        for (i in 0 until rules.length()) {
            if (out.size >= 20_000) return
            val item = rules.optJSONObject(i) ?: continue
            out += flattenRule(item)
            if (out.size > 20_000) {
                while (out.size > 20_000) out.removeAt(out.lastIndex)
                return
            }
        }
    }

    internal fun flattenRule(rule: JSONObject): List<String> {
        val out = mutableListOf<String>()
        fun add(prefix: String, key: String) {
            jsonStrings(rule, key).forEach { value ->
                out += if (prefix.isEmpty()) value else prefix + value.removePrefix(".")
            }
        }
        add("+.", "domain_suffix")
        add("", "domain")
        add("*", "domain_keyword")
        add("regexp:", "domain_regex")
        add("", "ip_cidr")
        add("", "source_ip_cidr")
        add("process:", "process_name")
        add("package:", "package_name")
        val nested = rule.optJSONArray("rules")
        if (nested != null) out += flattenRules(nested)
        return out
    }

    internal fun jsonStrings(obj: JSONObject, key: String): List<String> {
        if (!obj.has(key) || obj.isNull(key)) return emptyList()
        return when (val raw = obj.opt(key)) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it).trim() }.filter { it.isNotEmpty() }
            is String -> listOf(raw.trim()).filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun listEntriesFromFile(file: File): List<String> {
        if (!file.isFile || file.length() == 0L) return emptyList()
        if (!file.name.endsWith(".json", true)) return emptyList()
        return try {
            val text = file.readText()
            val root = JSONObject(text)
            val rules = root.optJSONArray("rules") ?: return emptyList()
            flattenRules(rules)
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun srsTopLevelCount(file: File): Int? {
        if (!file.isFile || file.length() < 5L) return null
        val bytes = file.readBytes()
        if (bytes.size < 5 || bytes[0] != 0x53.toByte() || bytes[1] != 0x52.toByte() || bytes[2] != 0x53.toByte()) {
            return null
        }
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes, 4, bytes.size - 4)
            val buf = ByteArray(16)
            val n = inflater.inflate(buf)
            if (n <= 0) null else readUvarint(buf, n)
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    internal fun readUvarint(buf: ByteArray, limit: Int): Int? {
        var x = 0L
        var s = 0
        val n = minOf(limit, buf.size)
        for (i in 0 until n) {
            val b = buf[i].toInt() and 0xFF
            if (b < 0x80) {
                if (i > 9) return null
                return (x or (b.toLong() shl s)).toInt()
            }
            x = x or ((b and 0x7F).toLong() shl s)
            s += 7
        }
        return null
    }
}

object ProxyProviders {
    private val LEAF_TYPES = setOf(
        "shadowsocks", "shadowsocksr", "vmess", "vless", "trojan", "hysteria",
        "hysteria2", "tuic", "socks", "http", "naive", "shadowtls", "wireguard",
        "ssh", "anytls", "tor", "mieru",
    )

    fun leafTags(content: String): List<String> {
        val root = parseRoot(content) ?: return emptyList()
        val outs = root.optJSONArray("outbounds") ?: return emptyList()
        val tags = mutableListOf<String>()
        for (i in 0 until outs.length()) {
            val item = outs.optJSONObject(i) ?: continue
            val type = item.optString("type").lowercase()
            if (type !in LEAF_TYPES) continue
            val tag = item.optString("tag").trim()
            if (tag.isNotEmpty()) tags += tag
        }
        return tags
    }

    fun parse(content: String, profileName: String, remoteUrl: String): List<ProxyProviderItem> {
        val root = parseRoot(content) ?: return emptyList()
        val named = namedProviders(root)
        if (named.isNotEmpty()) return named
        val leaves = leafTags(content)
        if (leaves.isEmpty()) return emptyList()
        val name = profileName.trim().ifBlank { "subscription" }
        return listOf(
            ProxyProviderItem(
                tag = name,
                type = if (remoteUrl.isNotBlank()) "remote" else "local",
                url = remoteUrl.trim(),
                entries = leaves,
                remote = remoteUrl.isNotBlank(),
            ),
        )
    }

    private fun namedProviders(root: JSONObject): List<ProxyProviderItem> {
        val obj = root.optJSONObject("proxy-providers")
            ?: root.optJSONObject("proxy_providers")
            ?: return emptyList()
        val names = obj.keys().asSequence().toList()
        if (names.isEmpty()) return emptyList()
        return names.mapNotNull { key ->
            val item = obj.optJSONObject(key) ?: return@mapNotNull null
            val url = item.optString("url").trim()
            ProxyProviderItem(
                tag = key,
                type = item.optString("type").ifBlank { if (url.startsWith("http")) "http" else "file" },
                url = url,
                entries = emptyList(),
                remote = url.startsWith("http"),
            )
        }
    }

    private fun parseRoot(content: String): JSONObject? {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed[0] != '{') return null
        if (trimmed.length > ConfigCompat.MAX_CONFIG_CHARS) return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }
}
