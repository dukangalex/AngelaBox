package io.nekohasekai.sfa.backup

import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject
import java.util.UUID

/**
 * Stable profile identifiers for the portable cloud backup.
 * Android keeps numeric Room ids; Windows and the ZIP use UUIDs.
 */
object ProfileStableIds {
    // load-modify-save: concurrent backups/restores must not drop each other's ids.
    @Synchronized
    fun ensure(profileId: Long): String {
        if (profileId < 0L) return UUID.randomUUID().toString()
        val map = load().toMutableMap()
        map[profileId]?.let { return it }
        val id = UUID.randomUUID().toString()
        map[profileId] = id
        save(map)
        return id
    }

    @Synchronized
    fun put(profileId: Long, uuid: String) {
        if (profileId < 0L || uuid.isBlank()) return
        val map = load().toMutableMap()
        map[profileId] = uuid.trim()
        save(map)
    }

    /** A deleted profile must not keep its id, or a restore maps its UUID to a dead row. */
    @Synchronized
    fun remove(profileId: Long) {
        val map = load().toMutableMap()
        if (map.remove(profileId) != null) save(map)
    }

    fun localId(uuid: String): Long? {
        val want = uuid.trim()
        if (want.isEmpty()) return null
        load().forEach { (id, value) ->
            if (value == want) return id
        }
        return null
    }

    fun load(): Map<Long, String> {
        val raw = Settings.profileStableIdsJson
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            val out = linkedMapOf<Long, String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toLongOrNull() ?: continue
                val value = root.optString(key).trim()
                if (id >= 0L && value.isNotEmpty()) out[id] = value
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    internal fun encode(map: Map<Long, String>): String {
        val root = JSONObject()
        map.forEach { (id, uuid) ->
            if (id >= 0L && uuid.isNotBlank()) root.put(id.toString(), uuid)
        }
        return root.toString()
    }

    private fun save(map: Map<Long, String>) {
        Settings.profileStableIdsJson = encode(map)
    }
}
