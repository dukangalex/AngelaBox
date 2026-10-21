package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import java.io.File
import org.json.JSONObject

/**
 * Compatibility entry point for existing update/startup flows.
 * All chain materialization is delegated to ChainRuntimeCompiler so save,
 * subscription refresh and runtime reload use exactly the same topology.
 * Bindings are per-profile: only the currently selected configuration is chained.
 */
object ConfigChainReapply {
    @Volatile
    var lastLandingNotes: List<String> = emptyList()
        private set

    /** True when the chain compiled by the latest [apply] has TLS on both hops. */
    @Volatile
    var lastBothHopsTls: Boolean = false
        private set

    suspend fun apply(content: String, profileId: Long = Settings.selectedProfile): String {
        lastLandingNotes = emptyList()
        lastBothHopsTls = false
        val currentProfileId = profileId
        val binding = ChainBindings.get(currentProfileId) ?: return content
        val landingId = binding.landingProfileId
        val landingTag = binding.landingTag.trim()
        require(landingId >= 0L && landingTag.isNotEmpty()) { "未选择链式落地出口" }

        val landingContent = if (landingId == currentProfileId) {
            if (ChainRuntimeCompiler.savedEntryMatches(content, landingTag) == null) {
                error("链式落地「$landingTag」在当前配置中找不到对应的 outbound，请到「工具 → 链式代理」重新选择落地")
            }
            null
        } else {
            val landingProfile = ProfileManager.get(landingId) ?: error("落地配置不存在或已被删除")
            val text = File(landingProfile.typed.path).readText()
            require(text.isNotBlank()) { "落地配置文件为空，无法跨配置组链" }
            val prepared = prepareLandingContent(text, landingId)
            if (ChainRuntimeCompiler.savedEntryMatches(prepared, landingTag) == null) {
                error("链式落地「$landingTag」在落地配置中找不到对应的 outbound（可能已被订阅更新或脚本改名），请到「工具 → 链式代理」重新选择落地")
            }
            prepared
        }
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = content,
                currentProfileId = currentProfileId,
                entryTag = binding.entryTag.trim().ifEmpty { null },
                landingProfileId = landingId,
                landingTag = landingTag,
                landingContent = landingContent,
            ),
        )
        // Re-check the both-hops-TLS warning against the actually compiled
        // graph (bind-time checks go stale after subscription updates).
        val chain = ChainRuntimeCompiler.compiledChain(compiled)
        lastBothHopsTls = chain != null &&
            ChainRuntimeCompiler.bothHopsAreTls(compiled, chain.entryTag, compiled, chain.landingTag)
        return compiled
    }

    /**
     * The landing profile gets the same treatment as the entry profile
     * before chaining: heal/sanitize, then the landing profile's OWN bound
     * overlay scripts, then a final sanitize of the script output —
     * mirroring ConfigQuicOverride's order for the entry profile and the
     * OverlayScripts contract ("when a profile is used as a chain entry,
     * its scripts run first and the native chain is compiled from that
     * result"). A failing landing script aborts the chain (fail-closed,
     * surfaced as a chain failure) instead of silently chaining the
     * unscripted topology.
     */
    internal fun prepareLandingContent(text: String, landingProfileId: Long): String {
        var out = if (Settings.configNormalize) {
            val healed = ConfigNormalize.heal(text)
            lastLandingNotes = healed.notes
            healed.content
        } else {
            ConfigCompat.sanitize(text)
        }
        if (OverlayScripts.isBound(landingProfileId)) {
            val root = JSONObject(out)
            ConfigScriptOverride.apply(root, landingProfileId)
            out = ConfigCompat.sanitize(root.toString())
            val names = OverlayScripts.enabledFor(landingProfileId)
                .map { it.name.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            lastLandingNotes = lastLandingNotes +
                "落地配置脚本已生效：${names.joinToString("、").ifBlank { "脚本" }}"
        }
        return out
    }
}
