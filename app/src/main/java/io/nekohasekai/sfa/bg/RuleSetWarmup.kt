package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteUrlGuard
import io.nekohasekai.sfa.utils.RuleSetCacheWarmer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Android glue for [RuleSetCacheWarmer]: the real downloader
 * (HTTPClient under the subscription URL guard) and the two call
 * shapes — fire-and-forget after an import lands a profile, and
 * synchronous inside the subscription worker's round budget.
 *
 * Import-time warming is what gives the startup cache substitute its
 * ammunition for EVERY user (AB-019): before, only a successful remote
 * subscription update ever filled the cache, so file-import users ran
 * with an empty cache forever and every cold-start stall went straight
 * to dropping the remote rule sets.
 */
object RuleSetWarmup {
    private const val TAG = "RuleSetWarmup"

    /** Background warm-ups get a bounded slice of time, then stop. */
    private const val ASYNC_BUDGET_MS = 3L * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialize warm-ups: simultaneous imports must not stampede the cache. */
    private val gate = Mutex()

    /**
     * Fire-and-forget warm for [content] that just landed on disk.
     * Never blocks the caller, never throws, failure is silent by
     * design — the cache is an optimization, not a promise.
     */
    fun schedule(content: String) {
        if (content.isBlank()) return
        scope.launch {
            runCatching {
                gate.withLock { warmNow(content, System.currentTimeMillis() + ASYNC_BUDGET_MS) }
            }.onFailure { Log.w(TAG, "rule-set warm-up failed", it) }
        }
    }

    /**
     * Synchronous warm for the whole on-disk config library, used
     * right after a backup restore lands every profile at once
     * (AB-020): the backup does not carry the rule-set cache (it
     * lives in the external files dir, outside the backed-up
     * configs), so restored profiles would otherwise start cold.
     * This must run synchronously — the restore flow relaunches
     * the app moments after success, which would cut a
     * fire-and-forget warm off mid-flight. One shared deadline
     * bounds the whole scan; best-effort like every warm path.
     */
    fun warmRestored(deadlineMs: Long) {
        val dir = File(Application.application.filesDir, "configs")
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in files) {
            if (System.currentTimeMillis() > deadlineMs) return
            if (!f.isFile || !f.name.endsWith(".json")) continue
            val content = runCatching { f.readText() }.getOrNull() ?: continue
            if (content.isNotBlank()) {
                runCatching { warmNow(content, deadlineMs) }
            }
        }
    }

    /** Synchronous warm, bounded by [deadlineMs] (the worker's round deadline). */
    fun warmNow(content: String, deadlineMs: Long) {
        val working = Application.application.getExternalFilesDir(null)
            ?: Application.application.filesDir
        RuleSetCacheWarmer.warm(
            content,
            working,
            deadlineMs,
            System::currentTimeMillis,
        ) { url, dest -> download(url, dest) }
    }

    private fun download(url: String, dest: File): Boolean = runCatching {
        HTTPClient().use { client ->
            client.downloadToFile(url, RemoteUrlGuard.Kind.SUBSCRIPTION, dest)
        }
        dest.isFile && dest.length() > 0L
    }.getOrDefault(false)
}
