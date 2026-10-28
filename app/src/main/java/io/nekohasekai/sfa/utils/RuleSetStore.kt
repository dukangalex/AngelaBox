package io.nekohasekai.sfa.utils

import org.json.JSONObject
import java.io.File

/**
 * URL-keyed local store of subscription rule-set copies (137b).
 *
 * The peer-client rule (see ~/workspace/angelabox-design/peer-research):
 * a start must never depend on a cold remote fetch. 137a gave the
 * four APP-OWNED sets a bundled floor via `initial_path` seeds. This
 * store generalises the same floor to SUBSCRIPTION-owned and
 * script-generated remote sets: every successful prefetch keeps a
 * last-good copy here, and the build pipeline stamps `initial_path`
 * at it whenever a definition's EXACT URL has a stored copy. The
 * definition stays `type: remote` with its original URL, so kernel
 * updates keep flowing from the origin exactly as before; the stored
 * copy only serves the kernel's seed tier (cache → initial_path →
 * fetch) when the kernel cache is cold and the network is not there.
 *
 * Why not the warmer cache ([RuleSetCacheWarmer]'s files)? That
 * cache is keyed by TAG ONLY — two profiles whose same-tag sets come
 * from different URLs share one file, and the last download wins.
 * The ladder's substitute rung accepts that risk knowingly (it is a
 * degraded rung, visibly announced), but an `initial_path` stamp is
 * a silent assertion "these bytes ARE this URL's content", so the
 * store keys every copy by the SHA-256 of its exact source URL: a
 * copy fetched for one origin can never be served under another
 * (the 137a ads trust-boundary lesson, applied to subscriptions).
 * Re-keying the warmer cache instead was rejected: stored profiles
 * already carry absolute paths into it (the rule-provider screen's
 * sync/upload), and its contract has no validation or last-good
 * semantics to build on.
 *
 * Promote is transactional: a download lands in a temp file, is
 * validated (non-empty + format check), and only then atomically
 * renamed over the previous copy — a failed or corrupt download
 * NEVER replaces the last good copy. And even a corrupt stored copy
 * could not make anything worse than 136: the kernel treats an
 * unloadable initial_path as a warning and falls through to the
 * fetch, never as a fatal error.
 *
 * Everything here is pure JVM (downloads are injected by the bg
 * glue, RuleSetWarmup) and best-effort at every call site: any
 * failure degrades to exactly the 136/137a behaviour.
 */
object RuleSetStore {
    const val STORE_DIR_NAME = "rule-set-store"
    private const val INDEX_FILE = "index.properties"
    private const val TMP_SUFFIX = ".tmp"

    /** Per warm pass download cap, mirroring the warmer's discipline. */
    const val MAX_DOWNLOADS_PER_WARM = 8

    /** A stored copy older than this is refreshed on the next warm. */
    const val STALE_MS = 24L * 60 * 60 * 1000

    /** Total-size valve for the store; eviction is oldest-first. */
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    /**
     * An entry whose URL is referenced by no profile survives this
     * long after its last sighting before pruning: script-generated
     * sets appear in no stored profile, only in built configs, so
     * the grace is what keeps their floors alive between starts.
     */
    const val UNREFERENCED_GRACE_MS = 14L * 24 * 60 * 60 * 1000

    /** One remote set a warm pass still owes the store. */
    data class PendingDownload(val tag: String, val url: String, val format: String, val dest: File)

    /** Bookkeeping view of one stored file, for the prune decision. */
    data class EntryInfo(
        val fileName: String,
        val urlHash: String,
        val sizeBytes: Long,
        val lastSeenMs: Long,
        val modifiedMs: Long,
    )

    fun storeDir(filesDir: File): File = File(filesDir, STORE_DIR_NAME)

    /** Origin key: first 16 hex chars of the URL's SHA-256. */
    fun urlHash(url: String): String =
        RuleSetSeeds.sha256Hex(url.trim().toByteArray(Charsets.UTF_8)).substring(0, 16)

    /**
     * The store file name for one set: origin hash + sanitised tag +
     * extension, with the extension rule mirroring
     * [RuleSetProviders.cacheFile] (source format / `.json` URL →
     * json, else srs) so a set's format is decided in exactly one
     * way across the cache, the store and the substitute rung.
     */
    fun entryFileName(tag: String, format: String, url: String): String {
        val ext = when {
            format.equals("source", true) || url.trim().endsWith(".json", true) -> "json"
            else -> "srs"
        }
        val safe = tag.replace(Regex("[^A-Za-z0-9._\\-@!]"), "_")
        return "${urlHash(url)}-$safe.$ext"
    }

    fun entryFile(storeDir: File, tag: String, format: String, url: String): File =
        File(storeDir, entryFileName(tag, format, url))

    /** True when a usable (non-empty) stored copy exists for this exact URL. */
    fun isStored(storeDir: File, tag: String, format: String, url: String): Boolean =
        entryFile(storeDir, tag, format, url).let { it.isFile && it.length() > 0L }

    /** Absolute `initial_path` value for a stored set, or null. */
    fun initialPathIfStored(storeDir: File, tag: String, format: String, url: String): String? =
        if (isStored(storeDir, tag, format, url)) {
            entryFile(storeDir, tag, format, url).absolutePath
        } else {
            null
        }

    /**
     * Format gate for a would-be stored copy: non-empty, plus for
     * `.srs` the SRS magic and an inflated header the pinned format
     * reader accepts ([RuleSetProviders.srsTopLevelCount] does both),
     * and for `.json` a parseable source object carrying a `rules`
     * array. This is a plausibility gate, not the real validation —
     * the kernel validates at load, and a bad seed there is a
     * warning, not a failure.
     */
    fun isValidCopy(file: File): Boolean = isValidCopy(file, file.name)

    /**
     * As [isValidCopy], but the format branch is chosen by
     * [formatName] rather than the name of [file] itself — a pending
     * download still carries the `.tmp` suffix, so its own name would
     * misroute a `.json` copy into the SRS branch.
     */
    private fun isValidCopy(file: File, formatName: String): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return if (formatName.endsWith(".json", true)) {
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                root.optJSONArray("rules") != null
            } catch (_: Exception) {
                false
            }
        } else {
            file.length() >= 5L && RuleSetProviders.srsTopLevelCount(file) != null
        }
    }

    /**
     * Promote a finished download ([tmp], in the store dir) over the
     * live copy for ([tag], [format], [url]): validate first, then
     * atomic rename. On any failure the temp file is removed and the
     * previous copy — if any — is left byte-for-byte untouched.
     * Returns true when the live copy now holds the new bytes.
     */
    fun promote(tmp: File, storeDir: File, tag: String, format: String, url: String): Boolean {
        val dest = entryFile(storeDir, tag, format, url)
        if (!isValidCopy(tmp, dest.name)) {
            tmp.delete()
            return false
        }
        return try {
            dest.parentFile?.mkdirs()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.isFile && dest.length() > 0L
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    /** A fresh temp file name inside the store dir for one download. */
    fun tempFile(storeDir: File, tag: String, format: String, url: String): File =
        File(storeDir, entryFileName(tag, format, url) + TMP_SUFFIX)

    // ------------------------------------------------------------------
    // Last-seen index: one `fileName=lastSeenMs` line per entry. The
    // file name already carries the origin hash, so the index never
    // needs to store the URL itself. Same text idiom as the 137a
    // seed records (formatRecords/parseRecords).
    // ------------------------------------------------------------------

    fun formatIndex(index: Map<String, Long>): String =
        index.entries.joinToString("\n") { (name, seen) -> "$name=$seen" } + "\n"

    /** Parses [formatIndex] output; malformed lines are ignored. */
    fun parseIndex(text: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (line in text.lineSequence()) {
            val eq = line.lastIndexOf('=')
            if (eq <= 0) continue
            val seen = line.substring(eq + 1).trim().toLongOrNull() ?: continue
            val name = line.substring(0, eq).trim()
            if (name.isEmpty()) continue
            out[name] = seen
        }
        return out
    }

    fun loadIndex(storeDir: File): Map<String, Long> {
        val file = File(storeDir, INDEX_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching { parseIndex(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyMap())
    }

    fun saveIndex(storeDir: File, index: Map<String, Long>) {
        storeDir.mkdirs()
        val tmp = File(storeDir, INDEX_FILE + TMP_SUFFIX)
        tmp.writeText(formatIndex(index), Charsets.UTF_8)
        if (!tmp.renameTo(File(storeDir, INDEX_FILE))) {
            tmp.copyTo(File(storeDir, INDEX_FILE), overwrite = true)
            tmp.delete()
        }
    }

    /** Record [fileNames] as seen at [nowMs] (their floors stay prune-proof for a grace). */
    fun markSeen(storeDir: File, fileNames: Collection<String>, nowMs: Long) {
        if (fileNames.isEmpty()) return
        val index = loadIndex(storeDir).toMutableMap()
        for (name in fileNames) index[name] = nowMs
        runCatching { saveIndex(storeDir, index) }
    }

    // ------------------------------------------------------------------
    // Warm decision + execution (downloads injected, like the warmer).
    // ------------------------------------------------------------------

    /**
     * Remote sets of [content] whose stored copy is missing or stale.
     * Enumeration is [RuleSetTriage.enumerate] — the same one triage
     * runs over built configs — so raw profiles and built configs
     * (script-generated sets included) are covered by one definition.
     * App-owned canonical sets are excluded: the 137a seeds own them
     * and a store copy for them would never be stamped.
     */
    fun pendingDownloads(content: String, storeDir: File, nowMs: Long): List<PendingDownload> {
        val out = mutableListOf<PendingDownload>()
        for (ref in RuleSetTriage.enumerate(content)) {
            if (out.size >= MAX_DOWNLOADS_PER_WARM) break
            if (RuleSetSeeds.seedFor(ref.tag)?.url == ref.url) continue
            val dest = entryFile(storeDir, ref.tag, ref.format, ref.url)
            if (dest.isFile && dest.length() > 0L && nowMs - dest.lastModified() <= STALE_MS) continue
            out += PendingDownload(ref.tag, ref.url, ref.format, dest)
        }
        return out
    }

    /**
     * Blocking store warm: downloads the pending sets of [content]
     * until [deadlineMs], each into a temp file the injected
     * [download] fills, promoted via [promote]. [download] returns
     * true on success; exceptions count as failure. Every stored set
     * the content references gets its last-seen mark refreshed,
     * fetched or not — that sighting is what pruning trusts. Returns
     * how many sets were actually stored.
     */
    fun warm(
        content: String,
        storeDir: File,
        deadlineMs: Long,
        nowMs: () -> Long,
        download: (url: String, tmp: File) -> Boolean,
    ): Int {
        val refs = RuleSetTriage.enumerate(content)
        markSeen(
            storeDir,
            refs.map { entryFileName(it.tag, it.format, it.url) }
                .filter { File(storeDir, it).isFile },
            nowMs(),
        )
        var stored = 0
        for (pending in pendingDownloads(content, storeDir, nowMs())) {
            if (nowMs() > deadlineMs) break
            val tmp = tempFile(storeDir, pending.tag, pending.format, pending.url)
            val ok = try {
                download(pending.url, tmp)
            } catch (_: Exception) {
                false
            }
            if (ok && promote(tmp, storeDir, pending.tag, pending.format, pending.url)) {
                markSeen(storeDir, listOf(pending.dest.name), nowMs())
                stored++
            } else {
                runCatching { tmp.delete() }
            }
        }
        return stored
    }

    // ------------------------------------------------------------------
    // Pruning.
    // ------------------------------------------------------------------

    /** The store's current entries, from the dir listing + index. */
    fun listEntries(storeDir: File): List<EntryInfo> {
        val index = loadIndex(storeDir)
        val files = runCatching { storeDir.listFiles() }.getOrNull() ?: return emptyList()
        val out = mutableListOf<EntryInfo>()
        for (file in files) {
            if (!file.isFile) continue
            if (file.name == INDEX_FILE || file.name.endsWith(TMP_SUFFIX)) continue
            val hash = file.name.substringBefore('-', "")
            if (hash.length != 16) continue
            out += EntryInfo(
                fileName = file.name,
                urlHash = hash,
                sizeBytes = file.length(),
                lastSeenMs = index[file.name] ?: file.lastModified(),
                modifiedMs = file.lastModified(),
            )
        }
        return out
    }

    /**
     * The prune decision, pure: delete an entry when its URL is
     * referenced by nothing ([referencedHashes] holds the origin
     * hashes of every profile's and recent built configs' sets) AND
     * it has not been seen for [graceMs]; then, if the survivors
     * still exceed [maxTotalBytes], evict oldest-first by last-seen
     * — unreferenced entries before referenced ones — until the cap
     * holds. Returns the file names to delete.
     */
    fun planPrune(
        entries: List<EntryInfo>,
        referencedHashes: Set<String>,
        nowMs: Long,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        graceMs: Long = UNREFERENCED_GRACE_MS,
    ): List<String> {
        val doomed = LinkedHashSet<String>()
        var total = entries.sumOf { it.sizeBytes }
        for (entry in entries) {
            if (entry.urlHash !in referencedHashes && nowMs - entry.lastSeenMs > graceMs) {
                doomed += entry.fileName
                total -= entry.sizeBytes
            }
        }
        if (total > maxTotalBytes) {
            val survivors = entries.filter { it.fileName !in doomed }
            val evictionOrder = survivors.sortedWith(
                compareBy<EntryInfo> { it.urlHash in referencedHashes }
                    .thenBy { it.lastSeenMs }
                    .thenBy { it.modifiedMs },
            )
            for (entry in evictionOrder) {
                if (total <= maxTotalBytes) break
                doomed += entry.fileName
                total -= entry.sizeBytes
            }
        }
        return doomed.toList()
    }

    /** Execute [planPrune] against the store dir; returns deleted file names. */
    fun prune(storeDir: File, referencedHashes: Set<String>, nowMs: Long): List<String> {
        val doomed = planPrune(listEntries(storeDir), referencedHashes, nowMs)
        if (doomed.isEmpty()) return emptyList()
        val index = loadIndex(storeDir).toMutableMap()
        for (name in doomed) {
            runCatching { File(storeDir, name).delete() }
            index.remove(name)
        }
        runCatching { saveIndex(storeDir, index) }
        return doomed
    }

    // ------------------------------------------------------------------
    // Build-time stamping.
    // ------------------------------------------------------------------

    /**
     * Stamp `initial_path` on the remote rule-set definitions of a
     * built config ([root] is the pipeline's working object) whose
     * exact URL has a stored copy. Runs inside the pipeline AFTER
     * the drop operations, so a definition the ladder suppressed is
     * already gone and gets nothing; definitions the substitute rung
     * converted to local no longer carry a URL and are skipped by
     * the remote test. Read-only against the store — the start path
     * gains no writes and no network from this.
     *
     * Boundaries:
     *  - an app-owned canonical definition (tag + pinned URL match a
     *    137a seed) is skipped: the segment stamps the bundled seed
     *    later in the same apply, and the seed outranks a store copy
     *    of the same bytes;
     *  - a definition that already carries `initial_path` pointing
     *    at an existing non-empty file (the rule-provider screen's
     *    manual sync writes exactly that into stored profiles) keeps
     *    it — the user's explicit floor wins; a dangling one is
     *    replaced by the store path when a copy exists.
     *
     * Returns the tags stamped, in config order.
     */
    fun stampInitialPaths(root: JSONObject, storeDir: File): List<String> {
        val sets = root.optJSONObject("route")?.optJSONArray("rule_set") ?: return emptyList()
        val stamped = mutableListOf<String>()
        for (i in 0 until sets.length()) {
            val item = sets.optJSONObject(i) ?: continue
            val stampedTag = runCatching { stampOne(item, storeDir) }.getOrNull() ?: continue
            stamped += stampedTag
        }
        return stamped
    }

    private fun stampOne(item: JSONObject, storeDir: File): String? {
        val tag = item.optString("tag").trim()
        if (tag.isEmpty()) return null
        val url = item.optString("url").ifBlank { item.optString("download_url") }.trim()
        if (url.isEmpty()) return null
        val remote = item.optString("type").equals("remote", true) || url.startsWith("http")
        if (!remote) return null
        if (RuleSetSeeds.seedFor(tag)?.url == url) return null
        val existing = item.optString("initial_path").trim()
        if (existing.isNotEmpty()) {
            val existingFile = File(existing)
            if (existingFile.isFile && existingFile.length() > 0L) return null
        }
        val path = initialPathIfStored(storeDir, tag, item.optString("format"), url) ?: return null
        if (existing != path) item.put("initial_path", path)
        return tag
    }
}
