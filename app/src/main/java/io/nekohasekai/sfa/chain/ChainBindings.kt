package io.nekohasekai.sfa.chain

import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject

data class ChainBinding(
    val profileId: Long,
    val entryTag: String,
    val landingProfileId: Long,
    val landingTag: String,
)

/**
 * Per-profile chain bindings. Each configuration keeps its own entry/landing
 * pair; switching profiles never inherits another profile's chain.
 * Stored in Settings, not inside the subscription JSON, so a remote refresh
 * does not wipe the binding.
 */
object ChainBindingCodec {
    fun parse(json: String): Map<Long, ChainBinding> {
        if (json.isBlank()) return emptyMap()
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return emptyMap()
        }
        val out = linkedMapOf<Long, ChainBinding>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toLongOrNull() ?: continue
            val item = root.optJSONObject(key) ?: continue
            val landingTag = item.optString("landingTag").trim()
            val landingId = item.optLong("landingProfileId", -1L)
            if (id < 0L || landingId < 0L || landingTag.isEmpty()) continue
            out[id] = ChainBinding(
                profileId = id,
                entryTag = item.optString("entryTag").trim(),
                landingProfileId = landingId,
                landingTag = landingTag,
            )
        }
        return out
    }

    fun encode(bindings: Map<Long, ChainBinding>): String {
        val root = JSONObject()
        bindings.values.forEach { b ->
            if (b.profileId < 0L || b.landingProfileId < 0L || b.landingTag.isBlank()) return@forEach
            root.put(
                b.profileId.toString(),
                JSONObject()
                    .put("entryTag", b.entryTag)
                    .put("landingProfileId", b.landingProfileId)
                    .put("landingTag", b.landingTag),
            )
        }
        return root.toString()
    }

    fun mergeLegacy(
        existing: Map<Long, ChainBinding>,
        enabled: Boolean,
        boundId: Long,
        entryTag: String,
        landingId: Long,
        landingTag: String,
    ): Map<Long, ChainBinding> {
        if (existing.isNotEmpty()) return existing
        if (!enabled || boundId < 0L || landingId < 0L || landingTag.isBlank()) return existing
        return mapOf(
            boundId to ChainBinding(
                profileId = boundId,
                entryTag = entryTag.trim(),
                landingProfileId = landingId,
                landingTag = landingTag.trim(),
            ),
        )
    }
}

object ChainBindings {
    @Synchronized
    fun get(profileId: Long): ChainBinding? {
        if (profileId < 0L) return null
        return load()[profileId]
    }

    @Synchronized
    fun put(binding: ChainBinding) {
        require(binding.profileId >= 0L) { "未选择当前配置" }
        require(binding.landingProfileId >= 0L && binding.landingTag.isNotBlank()) { "未选择链式落地出口" }
        val next = load().toMutableMap()
        next[binding.profileId] = binding
        save(next)
    }

    @Synchronized
    fun remove(profileId: Long) {
        if (profileId < 0L) return
        val next = load().toMutableMap()
        if (next.remove(profileId) != null) save(next)
    }

    @Synchronized
    fun removeProfile(profileId: Long) {
        if (profileId < 0L) return
        val next = load().toMutableMap()
        var changed = next.remove(profileId) != null
        val stale = next.filterValues { it.landingProfileId == profileId }.keys
        stale.forEach { next.remove(it) }
        if (changed || stale.isNotEmpty()) save(next)
    }

    @Synchronized
    fun all(): List<ChainBinding> = load().values.toList()

    @Synchronized
    fun hasAny(): Boolean = load().isNotEmpty()

    private fun load(): Map<Long, ChainBinding> {
        val parsed = ChainBindingCodec.parse(Settings.chainBindingsJson)
        val merged = ChainBindingCodec.mergeLegacy(
            parsed,
            Settings.chainEnabled,
            Settings.chainBoundProfileId,
            Settings.chainEntryTag,
            Settings.chainLandingProfileId,
            Settings.chainLandingTag,
        )
        if (merged != parsed) save(merged)
        return merged
    }

    private fun save(bindings: Map<Long, ChainBinding>) {
        Settings.chainBindingsJson = ChainBindingCodec.encode(bindings)
        Settings.chainEnabled = bindings.isNotEmpty()
        val selected = bindings[Settings.selectedProfile]
        if (selected != null) {
            Settings.chainBoundProfileId = selected.profileId
            Settings.chainEntryTag = selected.entryTag
            Settings.chainLandingProfileId = selected.landingProfileId
            Settings.chainLandingTag = selected.landingTag
        } else {
            Settings.chainBoundProfileId = -1L
            Settings.chainEntryTag = ""
            Settings.chainLandingProfileId = -1L
            Settings.chainLandingTag = ""
        }
    }
}
