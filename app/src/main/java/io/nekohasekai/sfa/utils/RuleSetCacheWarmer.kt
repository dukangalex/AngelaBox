package io.nekohasekai.sfa.utils

import java.io.File

/**
 * Ammo supply for the startup cache substitute (1.0.124): decides which
 * remote rule sets of a profile still lack a usable copy in the shared
 * rule-set cache, and fills the gaps through an injected downloader.
 *
 * Until AB-019 the only caller was the subscription worker after a
 * successful remote update, so profiles that arrived any other way
 * (file import, pasted links, QR) never warmed the cache at all — for
 * those users the substitute rung had nothing to substitute and every
 * cold-start stall went straight to dropping the sets. The decision
 * logic lives here, pure and Android-free, so every import/update path
 * shares one definition of "needs warming"; the actual download is
 * injected (RuleSetWarmup in the bg package supplies the HTTPClient
 * one) and unit tests can fake it.
 *
 * Discipline mirrors the original worker implementation: at most
 * [MAX_DOWNLOADS_PER_WARM] downloads per warm, only missing or
 * day-old entries, best-effort — a failed download is skipped, never
 * thrown, and never blocks the import or update that triggered it.
 */
object RuleSetCacheWarmer {
    const val MAX_DOWNLOADS_PER_WARM = 8
    const val STALE_MS = 24L * 60 * 60 * 1000

    data class PendingDownload(val tag: String, val url: String, val dest: File)

    /** Remote rule sets of [content] whose cache copy is missing, empty or stale. */
    fun pendingDownloads(content: String, workingDir: File, nowMs: Long): List<PendingDownload> {
        val out = mutableListOf<PendingDownload>()
        for (provider in RuleSetProviders.parse(content)) {
            if (out.size >= MAX_DOWNLOADS_PER_WARM) break
            if (!provider.remote) continue
            val url = provider.url.trim()
            if (url.isEmpty()) continue
            val dest = RuleSetProviders.cacheFile(workingDir, provider.tag, provider.format, url)
            if (isFresh(dest, nowMs)) continue
            out += PendingDownload(provider.tag, url, dest)
        }
        return out
    }

    /**
     * Tags of remote rule sets with NO usable cache copy at all (a stale
     * copy still counts as a hit — it can be substituted). The start
     * ladder uses this for the extended-budget gate: an extra wait only
     * makes sense when at least one set would really have to download.
     */
    fun uncachedRemoteTags(content: String, workingDir: File): List<String> {
        val out = mutableListOf<String>()
        for (provider in RuleSetProviders.parse(content)) {
            if (!provider.remote) continue
            val url = provider.url.trim()
            if (url.isEmpty()) continue
            val dest = RuleSetProviders.cacheFile(workingDir, provider.tag, provider.format, url)
            if (!dest.isFile || dest.length() == 0L) out += provider.tag
        }
        return out
    }

    /**
     * Blocking warm: downloads the pending sets until [deadlineMs].
     * [download] returns true on success; exceptions count as failure.
     * Returns how many sets were actually warmed.
     */
    fun warm(
        content: String,
        workingDir: File,
        deadlineMs: Long,
        nowMs: () -> Long,
        download: (url: String, dest: File) -> Boolean,
    ): Int {
        var warmed = 0
        for (pending in pendingDownloads(content, workingDir, nowMs())) {
            if (nowMs() > deadlineMs) break
            val ok = try {
                download(pending.url, pending.dest)
            } catch (_: Exception) {
                false
            }
            if (ok && pending.dest.isFile && pending.dest.length() > 0L) warmed++
        }
        return warmed
    }

    private fun isFresh(dest: File, nowMs: Long): Boolean = dest.isFile && dest.length() > 0L && nowMs - dest.lastModified() <= STALE_MS
}
