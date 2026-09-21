package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
            format.equals("source", true) || url.endsWith(".json") -> "json"
            else -> "srs"
        }
        val safe = tag.replace(Regex("[^A-Za-z0-9._\\-@!]"), "_")
        return File(File(workingDir, "rule-sets"), "$safe.$ext")
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

    fun sourceRuleCount(file: File): Int? {
        if (!file.isFile || file.length() == 0L) return null
        if (!file.name.endsWith(".json", true)) return null
        return try {
            val text = file.readText()
            val root = JSONObject(text)
            val rules = root.optJSONArray("rules") ?: return null
            rules.length()
        } catch (_: Exception) {
            null
        }
    }

    fun pretty(item: JSONObject): String = item.toString(2)
}