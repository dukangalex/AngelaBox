package io.nekohasekai.sfa.utils

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Bundled rule-set seeds for the app-owned sets (137a).
 *
 * A sing-box remote rule-set resolves at kernel start in this order
 * (pinned fork, route/rule/rule_set_remote.go StartContext): the
 * cache_file copy, then the `initial_path` local seed (a load failure
 * there is a warning, never fatal), then a synchronous fetch — whose
 * failure IS fatal ("initial rule-set: <tag>"). Until now the app
 * never set initial_path, so a cold start with an empty kernel cache
 * made the four app-owned sets (the CN trio the system-explicit
 * segment injects, plus the ads set) depend on a live download —
 * the exact failure class behind the 133–135 field incidents. Peer
 * clients (NekoBox, Karing, FlClash) never let a start depend on a
 * cold fetch; this is the same fix in sing-box's own idiom.
 *
 * The four compiled `.srs` files ship in the APK under
 * `assets/rule-sets/` (≈268 KB total). [ensureInstalled] copies them
 * to `filesDir/rule-set-seeds/` before the config pipeline runs, and
 * the two app-side injectors stamp the installed absolute path as
 * `initial_path` on the definitions they generate — the definitions
 * stay `type: remote` with their pinned URL, so kernel updates keep
 * flowing exactly as before; the seed is only the cold-start floor.
 * Subscription-owned definitions are never stamped.
 *
 * Which bytes are installed is tracked per tag in a small record
 * file (`seeds.properties` in the seed dir): the SHA-256 of the
 * installed content plus the bundled SHA-256 it was installed or
 * refreshed under. The record is what lets a seed refreshed from a
 * successful warmer download (see [refreshFromDownload]) survive
 * later starts while still yielding to a NEWER bundle the day an
 * APK upgrade ships one: [needsInstall] is the whole policy, pure
 * and unit-tested.
 *
 * Everything here is best-effort: any failure leaves the start
 * exactly as it was without seeds (the 136 behaviour).
 */
object RuleSetSeeds {
    /** One app-owned set and its bundled seed. */
    data class Seed(
        val tag: String,
        /** File name inside `assets/rule-sets/` and the seed dir. */
        val assetName: String,
        /** The pinned URL the set's definition carries (RuleSetPins). */
        val url: String,
        /** SHA-256 of the bundled asset bytes, hex. */
        val bundledSha256: String,
    )

    /** What is currently installed for one tag, per the record file. */
    data class InstallRecord(
        val installedSha256: String,
        val bundledSha256: String,
    )

    const val ASSET_DIR = "rule-sets"
    const val SEED_DIR_NAME = "rule-set-seeds"
    private const val RECORD_FILE = "seeds.properties"

    /**
     * The app-owned seeds, in injector order. URLs are built from
     * [RuleSetPins] with the exact expressions the injectors use;
     * RuleSetSeedsTest pins registry ↔ injector agreement.
     */
    val SEEDS: Map<String, Seed> = linkedMapOf(
        "geosite-cn" to Seed(
            tag = "geosite-cn",
            assetName = "geosite-cn.srs",
            url = RuleSetPins.geositeBase() + "geosite-cn.srs",
            bundledSha256 = "ddbd4b4addb452177b92d2ed4d1b3cfab6f6de39198fdc3fe0326dcca204f1a8",
        ),
        "geoip-cn" to Seed(
            tag = "geoip-cn",
            assetName = "geoip-cn.srs",
            url = RuleSetPins.geoipBase() + "geoip-cn.srs",
            bundledSha256 = "81ada5cafd9d831adc48776f29cdfe55c9713d9287caeef9de925ef30f66cce4",
        ),
        "geosite-geolocation-!cn" to Seed(
            tag = "geosite-geolocation-!cn",
            assetName = "geosite-geolocation-!cn.srs",
            url = RuleSetPins.geositeBase() + "geosite-geolocation-!cn.srs",
            bundledSha256 = "8f05436b89fd7d3fbb1565e21d15e407e64f7e039097036ca0e3725d1174a6f7",
        ),
        "geosite-category-ads-all" to Seed(
            tag = "geosite-category-ads-all",
            assetName = "geosite-category-ads-all.srs",
            url = RuleSetPins.adsRuleSetUrl(),
            bundledSha256 = "54ef3a555666779d0083a3921f4b6534898088b2d63846f3ec57c43a5412a86c",
        ),
    )

    fun seedFor(tag: String): Seed? = SEEDS[tag]

    fun seedDir(filesDir: File): File = File(filesDir, SEED_DIR_NAME)

    /** The installed seed file for [tag], or null when [tag] is not app-owned. */
    fun installedFile(filesDir: File, tag: String): File? =
        SEEDS[tag]?.let { File(seedDir(filesDir), it.assetName) }

    /** True when a non-empty seed file is in place for [tag]. */
    fun isInstalled(filesDir: File, tag: String): Boolean =
        installedFile(filesDir, tag)?.let { it.isFile && it.length() > 0L } == true

    /**
     * Absolute `initial_path` value for [tag]'s definition, or null
     * when no seed is installed (the definition then ships without
     * one — exactly the pre-137 behaviour). Absolute, matching the
     * cache substitute rung's path convention; the kernel resolves it
     * via filemanager.BasePath + filepath.Abs, which an absolute path
     * passes through unchanged.
     */
    fun initialPathIfInstalled(filesDir: File, tag: String): String? =
        if (isInstalled(filesDir, tag)) installedFile(filesDir, tag)?.absolutePath else null

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * The install policy. Install (or reinstall) the bundled bytes
     * when:
     *  - nothing is installed;
     *  - the APK now bundles a different seed than the one the record
     *    was written under (an upgrade's bundle always wins, even
     *    over a refreshed seed);
     *  - the installed bytes match neither the bundle nor the record
     *    (corrupt or tampered).
     * Keep the installed bytes when they ARE the bundle, or when
     * they are the recorded refresh of the current bundle.
     */
    fun needsInstall(installedSha256: String?, record: InstallRecord?, bundledSha256: String): Boolean {
        if (installedSha256 == null) return true
        if (record == null) return !installedSha256.equals(bundledSha256, ignoreCase = true)
        if (!record.bundledSha256.equals(bundledSha256, ignoreCase = true)) return true
        return !installedSha256.equals(record.installedSha256, ignoreCase = true)
    }

    /** Record file form: one `tag=installedSha,bundledSha` line per seed. */
    fun formatRecords(records: Map<String, InstallRecord>): String =
        records.entries.joinToString("\n") { (tag, r) -> "$tag=${r.installedSha256},${r.bundledSha256}" } + "\n"

    /** Parses [formatRecords] output; malformed lines are ignored. */
    fun parseRecords(text: String): Map<String, InstallRecord> {
        val out = LinkedHashMap<String, InstallRecord>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val parts = line.substring(eq + 1).split(',')
            if (parts.size != 2) continue
            val installed = parts[0].trim()
            val bundled = parts[1].trim()
            if (installed.isEmpty() || bundled.isEmpty()) continue
            out[line.substring(0, eq).trim()] = InstallRecord(installed, bundled)
        }
        return out
    }

    fun loadRecords(dir: File): Map<String, InstallRecord> {
        val file = File(dir, RECORD_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching { parseRecords(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyMap())
    }

    /**
     * Bring [dest] in line with the bundled [seed]: applies
     * [needsInstall] against the bytes [readBundled] supplies, and
     * writes them atomically when an install is due. Bundled bytes
     * whose hash is not the registry's are NEVER installed (a
     * mis-built APK degrades to no seed, not a wrong one). Returns
     * the record to persist when an install happened, null when the
     * installed bytes were kept (or the install was refused).
     */
    fun syncSeed(
        dest: File,
        seed: Seed,
        record: InstallRecord?,
        readBundled: () -> ByteArray,
    ): InstallRecord? {
        val installedSha = if (dest.isFile && dest.length() > 0L) {
            runCatching { sha256Hex(dest.readBytes()) }.getOrNull()
        } else {
            null
        }
        if (!needsInstall(installedSha, record, seed.bundledSha256)) return null
        val bytes = readBundled()
        if (!sha256Hex(bytes).equals(seed.bundledSha256, ignoreCase = true)) return null
        writeAtomically(dest, bytes)
        return InstallRecord(seed.bundledSha256, seed.bundledSha256)
    }

    /**
     * Android entry point, called on the service start path before
     * the config pipeline: installs or repairs every bundled seed.
     * Per-seed failure is swallowed — a seed that cannot install
     * simply does not get stamped this start. Returns how many
     * seeds were (re)installed.
     */
    fun ensureInstalled(context: Context): Int {
        val filesDir = runCatching { context.filesDir }.getOrNull() ?: return 0
        val dir = seedDir(filesDir)
        val records = loadRecords(dir).toMutableMap()
        var installed = 0
        var recordsDirty = false
        for (seed in SEEDS.values) {
            val newRecord = runCatching {
                syncSeed(File(dir, seed.assetName), seed, records[seed.tag]) {
                    context.assets.open("$ASSET_DIR/${seed.assetName}").use { it.readBytes() }
                }
            }.getOrNull()
            if (newRecord != null) {
                records[seed.tag] = newRecord
                recordsDirty = true
                installed++
            }
        }
        if (recordsDirty) {
            runCatching {
                writeAtomically(
                    File(dir, RECORD_FILE),
                    formatRecords(records).toByteArray(Charsets.UTF_8),
                )
            }
        }
        return installed
    }

    /**
     * Seed refresh: a warmer download of an app-owned set FROM ITS
     * PINNED URL also becomes the installed seed, so a later cold
     * start (empty kernel cache) floors on the freshest bytes the
     * app has actually fetched, not just the APK's. The pinned-URL
     * match is the trust boundary — a same-tag set a subscription
     * fetched from anywhere else never touches the seeds. Returns
     * true when a seed was refreshed.
     */
    fun refreshFromDownload(filesDir: File, url: String, downloaded: File): Boolean {
        val seed = SEEDS.values.firstOrNull { it.url == url.trim() } ?: return false
        if (downloaded.name != seed.assetName) return false
        if (!downloaded.isFile || downloaded.length() == 0L) return false
        val bytes = downloaded.readBytes()
        val dir = seedDir(filesDir)
        val records = loadRecords(dir).toMutableMap()
        writeAtomically(File(dir, seed.assetName), bytes)
        records[seed.tag] = InstallRecord(sha256Hex(bytes), seed.bundledSha256)
        writeAtomically(
            File(dir, RECORD_FILE),
            formatRecords(records).toByteArray(Charsets.UTF_8),
        )
        return true
    }

    /** Temp-file + rename, so a killed write never leaves a torn seed. */
    internal fun writeAtomically(dest: File, bytes: ByteArray) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
