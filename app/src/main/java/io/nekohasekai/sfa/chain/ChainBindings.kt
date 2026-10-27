package io.nekohasekai.sfa.chain

import io.nekohasekai.sfa.database.Settings
import org.json.JSONObject

data class ChainBinding(
    val profileId: Long,
    val entryTag: String,
    val landingProfileId: Long,
    val landingTag: String,
    /**
     * D3: set when the landing profile was deleted. The binding is KEPT
     * (not silently wiped like the old removeProfile did) so the chain
     * screen can show what broke and startup stays fail-closed with a
     * clear reason instead of the profile silently going out unchained.
     */
    val invalidated: Boolean = false,
    val invalidReason: String = "",
    /**
     * D2: set once this binding has compiled successfully in the startup
     * path at least once. A previously-working binding whose saved entry
     * later disappears fails closed at startup; a never-materialized one
     * keeps the graceful main-group fallback.
     */
    val materialized: Boolean = false,
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
                // Absent in bindings written before these fields existed:
                // opt* defaults keep old JSON parsing as not-invalidated /
                // never-materialized (backward compatible).
                invalidated = item.optBoolean("invalidated", false),
                invalidReason = item.optString("invalidReason").trim(),
                materialized = item.optBoolean("materialized", false),
            )
        }
        return out
    }

    fun encode(bindings: Map<Long, ChainBinding>): String {
        val root = JSONObject()
        bindings.values.forEach { b ->
            if (b.profileId < 0L || b.landingProfileId < 0L || b.landingTag.isBlank()) return@forEach
            val item = JSONObject()
                .put("entryTag", b.entryTag)
                .put("landingProfileId", b.landingProfileId)
                .put("landingTag", b.landingTag)
            // Only written when non-default, so untouched bindings keep
            // their exact pre-existing JSON shape.
            if (b.invalidated) item.put("invalidated", true)
            if (b.invalidReason.isNotBlank()) item.put("invalidReason", b.invalidReason)
            if (b.materialized) item.put("materialized", true)
            root.put(b.profileId.toString(), item)
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

    /**
     * D3 pure transform for "a profile was deleted": the binding OWNED by
     * the deleted profile goes away with it, but bindings that merely
     * REFERENCE it as their landing are marked invalidated (kept, with
     * [reason]) instead of being deleted. Returns the new map and the
     * number of bindings newly invalidated.
     */
    fun onProfileDeleted(
        bindings: Map<Long, ChainBinding>,
        deletedProfileId: Long,
        reason: String,
    ): Pair<Map<Long, ChainBinding>, Int> {
        if (deletedProfileId < 0L) return bindings to 0
        val next = bindings.toMutableMap()
        next.remove(deletedProfileId)
        var invalidated = 0
        for ((ownerId, b) in next) {
            if (b.landingProfileId == deletedProfileId && !b.invalidated) {
                next[ownerId] = b.copy(invalidated = true, invalidReason = reason)
                invalidated++
            }
        }
        return next to invalidated
    }

    /**
     * D2 gate, kept pure so the policy is testable: a disappeared saved
     * entry only fails closed when this binding has actually materialized
     * before. First-run bindings keep the graceful main-group fallback.
     * The policy is evaluated by the startup-pipeline caller only — the
     * shared resolveMainTag helper and its UI/dashboard callers stay
     * forgiving (NF-3).
     */
    fun entryDisappearanceFailsClosed(binding: ChainBinding, entryMissing: Boolean): Boolean =
        entryMissing && binding.materialized

    /**
     * B1 read-only query: bindings owned by OTHER profiles whose landing
     * is [landingProfileId] — exactly the set [onProfileDeleted] would
     * mark invalidated if that profile were deleted. The profile's own
     * binding is excluded: it goes away with the profile and needs no
     * warning. Sorted by owner id so callers render a stable list.
     */
    fun referencing(
        bindings: Map<Long, ChainBinding>,
        landingProfileId: Long,
    ): List<ChainBinding> {
        if (landingProfileId < 0L) return emptyList()
        return bindings.values
            .filter { it.landingProfileId == landingProfileId && it.profileId != landingProfileId }
            .sortedBy { it.profileId }
    }
}

object ChainBindings {
    const val INVALID_REASON_LANDING_DELETED = "落地配置已被删除"

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

    /**
     * D3 replacement for the old removeProfile, which silently deleted
     * OTHER profiles' bindings that used the deleted profile as landing.
     * Now: the deleted profile's own binding is removed with it; bindings
     * referencing it as landing are marked invalidated and kept.
     * Returns how many bindings were newly invalidated, so the delete
     * flow can surface the impact instead of it passing unnoticed.
     */
    @Synchronized
    fun onProfileDeleted(profileId: Long): Int {
        if (profileId < 0L) return 0
        val current = load()
        val (next, invalidated) = ChainBindingCodec.onProfileDeleted(
            current,
            profileId,
            INVALID_REASON_LANDING_DELETED,
        )
        if (next != current) save(next)
        return invalidated
    }

    /** D2: record that this binding compiled successfully at startup. */
    @Synchronized
    fun markMaterialized(profileId: Long) {
        if (profileId < 0L) return
        val next = load().toMutableMap()
        val binding = next[profileId] ?: return
        if (binding.materialized) return
        next[profileId] = binding.copy(materialized = true)
        save(next)
    }

    /**
     * B1: bindings that deleting [profileId] would invalidate (read-only;
     * backs the delete confirmation dialog). Excludes the profile's own
     * binding, matching [onProfileDeleted]'s transform.
     */
    @Synchronized
    fun bindingsLandingOn(profileId: Long): List<ChainBinding> =
        ChainBindingCodec.referencing(load(), profileId)

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
