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
 * The catalog is a library. A profile only runs scripts after the user
 * binds them on that profile (`[]` or missing key = off). Catalog
 * `enabled` is a master kill: a bound script that is switched off in
 * the list does not run. Chain mode on a profile mutes scripts for
 * that profile; the two are not applied together.
 */
object OverlayScripts {
    const val MAX_SCRIPTS = 12
    const val MAX_CODE_CHARS = 256_000
    const val SAMPLE_ASSET = "scripts/airport-region.js"
    const val SAMPLE_NAME = "默认脚本"
    const val SAMPLE_REVISION = "overlay-revision: 11"
    const val SOURCE_CODE = "code"
    const val SOURCE_URL = "url"
    const val SOURCE_FILE = "file"
    const val SOURCE_SAMPLE = "sample"

    fun list(): List<OverlayScript> {
        val items = decode(Settings.overlayScriptsJson)
        val migrated = items.map { script ->
            if (script.source == SOURCE_SAMPLE && script.name != SAMPLE_NAME) {
                script.copy(name = SAMPLE_NAME)
            } else {
                script
            }
        }
        if (migrated != items) save(migrated)
        return migrated
    }

    fun enabled(): List<OverlayScript> = list().filter { it.enabled && it.code.isNotBlank() }

    fun enabledFor(profileId: Long): List<OverlayScript> {
        val catalog = list()
        val byId = catalog.associateBy { it.id }
        val selected = selectedIds(profileId) ?: return emptyList()
        return selected.mapNotNull { id -> byId[id] }.filter { it.enabled && it.code.isNotBlank() }
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

    fun upsertSample(code: String): OverlayScript {
        val existing = list().firstOrNull { it.source == SOURCE_SAMPLE }
        val item = OverlayScript(
            id = existing?.id ?: newId(),
            name = SAMPLE_NAME,
            enabled = existing?.enabled ?: true,
            source = SOURCE_SAMPLE,
            code = code,
            updatedAt = System.currentTimeMillis(),
        )
        upsert(item)
        return item
    }

    /**
     * Previously imported copies of the bundled sample still contain
     * third-party comments and remote rule-sets that 404. Replace those
     * in place so start and the editor pick up the current asset without
     * a re-import. User duplicates (source != sample) are left alone.
     */
    fun refreshStaleSample(bundled: String? = null) {
        val code = bundled?.takeIf { it.isNotBlank() } ?: bundledSample() ?: return
        val existing = decode(Settings.overlayScriptsJson).firstOrNull { it.source == SOURCE_SAMPLE }
            ?: return
        if (!sampleLooksStale(existing.code, existing.name)) return
        upsertSample(code)
    }

    internal fun sampleLooksStale(code: String, name: String = ""): Boolean {
        if (name.isNotEmpty() && name != SAMPLE_NAME) return true
        if (SAMPLE_REVISION !in code) return true
        return STALE_SAMPLE_MARKERS.any { it in code }
    }

    private fun bundledSample(): String? = runCatching {
        io.nekohasekai.sfa.Application.application.assets
            .open(SAMPLE_ASSET)
            .bufferedReader()
            .use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private val STALE_SAMPLE_MARKERS = arrayOf(
        "Clash Meta",
        "clash:",
        "由 Clash",
        "geoip-fastly",
        "geosite-apple-cn",
        "geosite-biliintl",
        "geoip-private",
        "override_address",
        "机场地区分组",
        "机场订阅覆写",
    )

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
                    val source = obj.optString("source").ifBlank { SOURCE_CODE }
                    val name = if (source == SOURCE_SAMPLE) {
                        SAMPLE_NAME
                    } else {
                        obj.optString("name").ifBlank { "脚本" }
                    }
                    add(
                        OverlayScript(
                            id = id,
                            name = name,
                            enabled = obj.optBoolean("enabled", false),
                            source = source,
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
