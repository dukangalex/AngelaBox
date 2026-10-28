package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteUrlGuard
import io.nekohasekai.sfa.utils.RuleSetCacheWarmer
import io.nekohasekai.sfa.utils.RuleSetSeeds
import io.nekohasekai.sfa.utils.RuleSetStore
import io.nekohasekai.sfa.utils.RuleSetTriage
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
 * Since 137b the same triggers also feed the URL-keyed localization
 * store ([RuleSetStore]) and run its pruning: [warmNow] is the one
 * funnel for import / update / restore / built-config warms, so
 * both floors stay filled from exactly the same set of events.
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
     * Fire-and-forget warm for a BUILT config (the post-pipeline
     * content BoxService produced for a start). Script-generated
     * rule sets exist only in that built form, so the import-time
     * warms of the raw profile can never capture them — this is the
     * trigger that gives them a local floor for the next start.
     * Same both-store warm as an import; never blocks the start
     * that produced the config, never throws.
     */
    fun scheduleBuilt(content: String) {
        if (content.isBlank()) return
        scope.launch {
            runCatching {
                gate.withLock { warmNow(content, System.currentTimeMillis() + ASYNC_BUDGET_MS) }
            }.onFailure { Log.w(TAG, "built-config rule-set warm-up failed", it) }
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
        // Localization store (137b): the same trigger points fill
        // the URL-keyed store, inside the same deadline — the warmer
        // cache and the store are two floors with different contracts
        // (tag-keyed substitute ammo vs origin-keyed initial_path
        // floors), and both are at their fullest when every import /
        // update / restore feeds both.
        runCatching { warmStoreNow(content, deadlineMs) }
    }

    private fun warmStoreNow(content: String, deadlineMs: Long) {
        val storeDir = RuleSetStore.storeDir(Application.application.filesDir)
        RuleSetStore.warm(
            content,
            storeDir,
            deadlineMs,
            System::currentTimeMillis,
        ) { url, tmp -> downloadToTemp(url, tmp) }
        pruneStore(storeDir, content)
    }

    private fun downloadToTemp(url: String, tmp: File): Boolean =
        runCatching {
            HTTPClient().use { client ->
                client.downloadToFile(url, RemoteUrlGuard.Kind.SUBSCRIPTION, tmp)
            }
            tmp.isFile && tmp.length() > 0L
        }.getOrDefault(false)

    /**
     * Store maintenance at the warm triggers: prune entries whose
     * URL no existing profile references and which no recent built
     * config has seen within the grace period, under the store's
     * size cap. "Referenced" here = every stored profile's remote
     * sets plus the sets of the content being warmed right now;
     * [RuleSetStore.warm] refreshes the last-seen marks that carry
     * script-generated sets (which live in no stored profile)
     * through the grace.
     */
    private fun pruneStore(storeDir: File, warmedContent: String) {
        runCatching {
            val referenced = HashSet<String>()
            fun collect(content: String) {
                for (ref in RuleSetTriage.enumerate(content)) {
                    referenced += RuleSetStore.urlHash(ref.url)
                }
            }
            collect(warmedContent)
            val dir = File(Application.application.filesDir, "configs")
            for (f in dir.listFiles() ?: emptyArray()) {
                if (!f.isFile || !f.name.endsWith(".json")) continue
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (text.isNotBlank()) collect(text)
            }
            RuleSetStore.prune(storeDir, referenced, System.currentTimeMillis())
        }
    }

    private fun download(url: String, dest: File): Boolean {
        val ok = runCatching {
            HTTPClient().use { client ->
                client.downloadToFile(url, RemoteUrlGuard.Kind.SUBSCRIPTION, dest)
            }
            dest.isFile && dest.length() > 0L
        }.getOrDefault(false)
        if (ok) {
            // Seed refresh (137a): a download of an app-owned set from
            // its pinned URL also refreshes the bundled seed's
            // installed copy, so a later cold start floors on the
            // freshest fetched bytes. RuleSetSeeds enforces the
            // pinned-URL trust boundary; failure is silent like every
            // warm path.
            runCatching {
                RuleSetSeeds.refreshFromDownload(Application.application.filesDir, url, dest)
            }
        }
        return ok
    }
}
