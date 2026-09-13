package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class OverlayScript(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val source: String,
    val url: String = "",
    val code: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * User-imported sing-box overlay scripts. Stored locally, applied at
 * start time, never written back into the subscription file.
 *
 * The catalog (list + enabled flag) is global. Each profile can bind a
 * subset: missing key inherits catalog-enabled scripts; `[]` is off;
 * a non-empty list is that profile's selection. Chain mode still runs
 * scripts, but only against the entry (current) profile.
 */
object OverlayScripts {
    const val MAX_SCRIPTS = 12
    const val MAX_CODE_CHARS = 256_000
    const val SAMPLE_ASSET = "scripts/airport-region.js"
    const val SAMPLE_NAME = "机场地区分组（sing-box）"
    const val SOURCE_CODE = "code"
    const val SOURCE_URL = "url"
    const val SOURCE_FILE = "file"
    const val SOURCE_SAMPLE = "sample"

    fun list(): List<OverlayScript> = decode(Settings.overlayScriptsJson)

    fun enabled(): List<OverlayScript> = list().filter { it.enabled && it.code.isNotBlank() }

    fun enabledFor(profileId: Long): List<OverlayScript> {
        val catalog = list()
        val byId = catalog.associateBy { it.id }
        val selected = selectedIds(profileId)
        val ids = selected ?: catalog.filter { it.enabled }.map { it.id }
        return ids.mapNotNull { id -> byId[id] }.filter { it.code.isNotBlank() }
    }

    fun selectedIds(profileId: Long): List<String>? {
        if (profileId < 0L) return emptyList()
        val map = loadBindings()
        return if (map.containsKey(profileId)) map[profileId] else null
    }

    fun isBound(profileId: Long): Boolean = enabledFor(profileId).isNotEmpty()

    @Synchronized
    fun setBinding(profileId: Long, scriptIds: List<String>?) {
        if (profileId < 0L) return
        val next = loadBindings().toMutableMap()
        if (scriptIds == null) {
            next.remove(profileId)
        } else {
            next[profileId] = scriptIds.distinct().filter { it.isNotBlank() }
        }
        saveBindings(next)
    }

    @Synchronized
    fun removeProfile(profileId: Long) {
        if (profileId < 0L) return
        val next = loadBindings().toMutableMap()
        if (next.remove(profileId) != null) saveBindings(next)
    }

    fun save(items: List<OverlayScript>) {
        Settings.overlayScriptsJson = encode(items.take(MAX_SCRIPTS))
    }

    fun upsert(script: OverlayScript) {
        val trimmed = script.copy(code = script.code.take(MAX_CODE_CHARS), name = script.name.trim().ifBlank { "脚本" })
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == trimmed.id }
        if (index >= 0) {
            current[index] = trimmed
        } else {
            if (current.size >= MAX_SCRIPTS) {
                throw IllegalStateException("最多保存 $MAX_SCRIPTS 条脚本")
            }
            current.add(trimmed)
        }
        save(current)
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
        val next = loadBindings().mapValues { (_, ids) -> ids.filterNot { it == id } }
        saveBindings(next)
    }

    fun toggle(id: String, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun newId(): String = UUID.randomUUID().toString()

    internal fun encode(items: List<OverlayScript>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("enabled", item.enabled)
                    .put("source", item.source)
                    .put("url", item.url)
                    .put("code", item.code)
                    .put("updatedAt", item.updatedAt),
            )
        }
        return array.toString()
    }

    internal fun decode(raw: String): List<OverlayScript> {
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val code = obj.optString("code")
                    val id = obj.optString("id").ifBlank { newId() }
                    add(
                        OverlayScript(
                            id = id,
                            name = obj.optString("name").ifBlank { "脚本" },
                            enabled = obj.optBoolean("enabled", false),
                            source = obj.optString("source").ifBlank { SOURCE_CODE },
                            url = obj.optString("url"),
                            code = code,
                            updatedAt = obj.optLong("updatedAt", 0L),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun encodeBindings(map: Map<Long, List<String>>): String {
        val root = JSONObject()
        map.forEach { (id, ids) ->
            if (id < 0L) return@forEach
            val array = JSONArray()
            ids.forEach { array.put(it) }
            root.put(id.toString(), array)
        }
        return root.toString()
    }

    internal fun decodeBindings(raw: String): Map<Long, List<String>> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            val out = linkedMapOf<Long, List<String>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toLongOrNull() ?: continue
                val array = root.optJSONArray(key) ?: continue
                val ids = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optString(i).trim()
                        if (item.isNotEmpty()) add(item)
                    }
                }
                out[id] = ids
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun loadBindings(): Map<Long, List<String>> =
        decodeBindings(Settings.overlayScriptBindingsJson)

    private fun saveBindings(map: Map<Long, List<String>>) {
        Settings.overlayScriptBindingsJson = encodeBindings(map)
    }
}
