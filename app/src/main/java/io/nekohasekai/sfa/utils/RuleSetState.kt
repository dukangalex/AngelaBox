package io.nekohasekai.sfa.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-rule-set state aggregation for the 「规则集状态」 view (137c).
 *
 * The notice discipline (136) made degradation signals transient —
 * a flash on screen, the full text in the notice log. The durable
 * half peers (FlClash / CMFA provider pages) have and we lacked is
 * a STATE view: for every rule-set, what floor it has, when it was
 * last seen working, and what the last availability verdict was.
 * This object is the pure aggregation behind that view: the glue
 * (RuleSetStateViewModel) snapshots every app-side source into the
 * input facts below, and [aggregate] merges them into one row per
 * set. Nothing here touches Android, files, or the kernel — and
 * nothing here can fabricate kernel state: the kernel's rule-set
 * cache is an opaque bbolt DB, so the view only ever shows facts
 * the APP itself recorded (seeds it installed, copies it stored,
 * verdicts it probed or learned).
 *
 * Sources, strongest evidence first:
 *  - the 137a seed registry (app-owned sets and their pinned URLs);
 *  - stored profiles' remote definitions (subscription sets), with
 *    the rule-provider screen's manual `initial_path`/`path` sync
 *    detectable from the same parse;
 *  - the 137b URL-keyed store and the warmer's tag-keyed cache
 *    (copies whose owner no profile names any more still surface,
 *    as orphan rows — script-generated sets live exactly there);
 *  - the 136 availability records (probe verdicts and learned
 *    kernel-failure needles share one store and one display).
 *
 * Floor precedence mirrors the triage tiers (all three local tiers
 * keep a set unconditionally), ordered for display by how strongly
 * the copy is bound to THIS set's identity: the bundled seed is
 * pinned bytes for a pinned URL; a store copy is keyed by the exact
 * URL; a manual path is an explicit per-definition assertion; the
 * warmer cache is keyed by tag only, so its bytes may belong to a
 * same-tag set from another origin — shown, but ranked last.
 *
 * Availability freshness mirrors RuleSetTriage's record TTLs
 * exactly (AVAILABLE 60 min, UNAVAILABLE 10 min, a clock stepped
 * back still counts as fresh): a record past its TTL degrades to
 * [Availability.STALE] — displayed as unknown/expired, never as a
 * current verdict — because triage itself would re-probe rather
 * than trust it.
 */
object RuleSetState {
    /** Where the set's definition comes from, as far as the app can tell. */
    enum class SourceKind { APP_OWNED, MANUAL, SUBSCRIPTION }

    /** The strongest local floor serving this set at start, if any. */
    enum class Floor { SEED, STORE, MANUAL_FILE, WARMER, NONE }

    /** The last availability verdict, TTL-aware. */
    enum class Availability { AVAILABLE, UNAVAILABLE, STALE, UNKNOWN }

    /** One local copy's facts: size, when it was written/seen, decoded entry count. */
    data class CopyFact(
        val sizeBytes: Long,
        val atMs: Long,
        val entryCount: Int? = null,
    )

    /** One availability observation for a (tag, url), from any profile. */
    data class RecordFact(
        val available: Boolean,
        val checkedAtMs: Long,
    )

    /**
     * Everything the glue knows about one remote set referenced by
     * a stored profile, the seed registry, or the record store.
     * Copy slots are per-tier; [aggregate] picks the floor.
     */
    data class RefInput(
        val tag: String,
        val url: String,
        val profileNames: List<String> = emptyList(),
        val appOwned: Boolean = false,
        val manual: Boolean = false,
        val seedCopy: CopyFact? = null,
        val storeCopy: CopyFact? = null,
        val manualCopy: CopyFact? = null,
        val warmerCopy: CopyFact? = null,
        val records: List<RecordFact> = emptyList(),
    )

    /** A store copy whose URL no profile or record could identify. */
    data class OrphanStoreInput(
        val fileName: String,
        val urlHash: String,
        val tag: String,
        val copy: CopyFact,
    )

    /** A warmer copy whose tag no profile references any more. */
    data class OrphanWarmerInput(
        val tag: String,
        val copy: CopyFact,
    )

    /** One display row: a set and the strongest facts known about it. */
    data class Row(
        val tag: String,
        /** The set's origin URL, or null when only the store's hash survives. */
        val url: String?,
        /** Origin hash label for [url]-less rows (a store orphan). */
        val urlHash: String?,
        val sourceKind: SourceKind,
        val floor: Floor,
        /** Size / entry count of the winning floor's copy, when there is one. */
        val sizeBytes: Long?,
        val entryCount: Int?,
        /** Floor copy time, else the latest record time, else null. */
        val lastActivityMs: Long?,
        val availability: Availability,
        /** When the displayed verdict was recorded (also for STALE). */
        val availabilityAtMs: Long?,
        val profileNames: List<String>,
    )

    fun aggregate(
        refs: List<RefInput>,
        orphanStore: List<OrphanStoreInput> = emptyList(),
        orphanWarmer: List<OrphanWarmerInput> = emptyList(),
        nowMs: Long,
    ): List<Row> {
        val rows = mutableListOf<Row>()
        for (ref in refs) {
            val floorCopy = listOf(
                Floor.SEED to ref.seedCopy,
                Floor.STORE to ref.storeCopy,
                Floor.MANUAL_FILE to ref.manualCopy,
                Floor.WARMER to ref.warmerCopy,
            ).firstOrNull { it.second != null }
            val record = latestRecord(ref.records)
            rows += Row(
                tag = ref.tag,
                url = ref.url,
                urlHash = null,
                sourceKind = when {
                    ref.appOwned -> SourceKind.APP_OWNED
                    ref.manual -> SourceKind.MANUAL
                    else -> SourceKind.SUBSCRIPTION
                },
                floor = floorCopy?.first ?: Floor.NONE,
                sizeBytes = floorCopy?.second?.sizeBytes,
                entryCount = floorCopy?.second?.entryCount,
                lastActivityMs = floorCopy?.second?.atMs ?: record?.checkedAtMs,
                availability = availabilityOf(record, nowMs),
                availabilityAtMs = record?.checkedAtMs,
                profileNames = ref.profileNames.distinct(),
            )
        }
        for (orphan in orphanStore) {
            rows += Row(
                tag = orphan.tag,
                url = null,
                urlHash = orphan.urlHash,
                sourceKind = SourceKind.SUBSCRIPTION,
                floor = Floor.STORE,
                sizeBytes = orphan.copy.sizeBytes,
                entryCount = orphan.copy.entryCount,
                lastActivityMs = orphan.copy.atMs,
                availability = Availability.UNKNOWN,
                availabilityAtMs = null,
                profileNames = emptyList(),
            )
        }
        for (orphan in orphanWarmer) {
            rows += Row(
                tag = orphan.tag,
                url = null,
                urlHash = null,
                sourceKind = SourceKind.SUBSCRIPTION,
                floor = Floor.WARMER,
                sizeBytes = orphan.copy.sizeBytes,
                entryCount = orphan.copy.entryCount,
                lastActivityMs = orphan.copy.atMs,
                availability = Availability.UNKNOWN,
                availabilityAtMs = null,
                profileNames = emptyList(),
            )
        }
        return rows.sortedWith(
            compareBy<Row> { it.sourceKind != SourceKind.APP_OWNED }
                .thenBy { it.tag.lowercase(Locale.ROOT) }
                .thenBy { it.url == null }
                .thenBy { it.url ?: "" },
        )
    }

    /**
     * The record a row should display: the newest observation; on a
     * timestamp tie the UNAVAILABLE one wins (it is the verdict that
     * changes behaviour, and the one a user diagnosing a start wants
     * to see).
     */
    fun latestRecord(records: List<RecordFact>): RecordFact? = records.maxWithOrNull(compareBy<RecordFact> { it.checkedAtMs }.thenBy { !it.available })

    /**
     * TTL-aware verdict for [record] at [nowMs], mirroring
     * RuleSetTriage's freshness rule (including its clock-stepped-
     * back case). Past TTL → [Availability.STALE], never a verdict.
     */
    fun availabilityOf(record: RecordFact?, nowMs: Long): Availability {
        if (record == null) return Availability.UNKNOWN
        val age = nowMs - record.checkedAtMs
        val ttl = if (record.available) {
            RuleSetTriage.AVAILABLE_TTL_MS
        } else {
            RuleSetTriage.UNAVAILABLE_TTL_MS
        }
        if (age >= 0 && age > ttl) return Availability.STALE
        return if (record.available) Availability.AVAILABLE else Availability.UNAVAILABLE
    }

    /**
     * Splits a RuleSetTriage persistence key
     * (`profileId|tag|url`, see RuleSetTriage.recordKey) back into
     * its parts. The URL is taken after the LAST `|` so a tag that
     * itself contains one still parses; returns null on any
     * malformed key.
     */
    fun parseRecordKey(key: String): Triple<Long, String, String>? {
        val first = key.indexOf('|')
        if (first <= 0) return null
        val last = key.lastIndexOf('|')
        if (last <= first) return null
        val profileId = key.substring(0, first).toLongOrNull() ?: return null
        val tag = key.substring(first + 1, last)
        val url = key.substring(last + 1)
        if (tag.isEmpty() || url.isEmpty()) return null
        return Triple(profileId, tag, url)
    }

    /** Compact byte size for row display: `512 B`, `56.3 KB`, `1.2 MB`. */
    fun formatSize(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb)
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
    }

    /** Absolute timestamp for row display: `2026-10-28 09:41`. */
    fun formatDateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))
}
