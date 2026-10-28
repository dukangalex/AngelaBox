package io.nekohasekai.sfa.utils

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Per-rule-set availability triage for service start (v1.0.136).
 *
 * Until 135 the start ladder degraded remote rule-sets all-or-nothing:
 * ONE undownloadable set (in the 135 field run, a script-generated
 * `geosite-nvidia@cn` the phone could not fetch directly) failed the
 * kernel attempt, and the recovery rungs then skipped EVERY remote
 * set — the app-injected CN/ads sets and every subscription set,
 * reachable or not. The user's requirement is the opposite: a
 * subscription's sets are a mix — keep the usable ones active, skip
 * only the unusable ones.
 *
 * Triage runs on the fully built runtime config (so subscription,
 * script-generated and app-injected sets are all enumerated) BEFORE
 * the first kernel attempt:
 *
 *  - a set with a usable copy in the app-side rule-set cache
 *    (RuleSetCacheWarmer's files) is kept unconditionally: the
 *    ladder's substitute rung can serve it locally, so stripping it
 *    would only destroy value;
 *  - a set with a fresh availability record is decided by the record;
 *  - anything else is probed — bounded, in parallel, through the
 *    injected [RuleSetProbe] (bg glue does a 1-byte ranged GET on the
 *    same direct path the kernel's healed download client uses).
 *
 * Only a POSITIVE failure (probe answered failure, or a fresh
 * UNAVAILABLE record) strips a set. Unknown state — never probed,
 * probe budget exhausted, triage itself broken — always keeps the
 * set; if it then fails at the kernel, the existing ladder (replace /
 * substitute / drop-all) remains the backstop, unchanged. Triage can
 * therefore never introduce a start failure the old flow did not
 * already have: worst case it degrades to exactly the 135 behaviour.
 *
 * Records persist per profile + tag + URL (a URL change is a new
 * record, so a subscription update that fixes an address is never
 * punished for the old one). AVAILABLE records live [AVAILABLE_TTL_MS];
 * UNAVAILABLE ones only [UNAVAILABLE_TTL_MS] — a dead CDN neighbourhood
 * should be retried soon, a proven one not re-probed every start.
 *
 * Pure JVM (the probe and the clock are injected, the records are
 * passed in and out) so the whole decision is unit-testable without
 * network or Android.
 */
object RuleSetTriage {
    /** A set the kernel would have to fetch remotely. */
    data class Ref(val tag: String, val url: String, val format: String = "")

    /** One persisted availability observation. */
    data class Record(val available: Boolean, val checkedAtMs: Long)

    /** What triage decided for one start, before any rebuild. */
    data class Plan(
        /** Fresh UNAVAILABLE record: strip without probing. */
        val knownUnavailable: List<Ref>,
        /** No usable record and no cache copy: probe now. */
        val toProbe: List<Ref>,
        /** Everything kept (fresh record, cache copy, or beyond the probe cap). */
        val kept: List<Ref>,
    )

    /** Final outcome: exact tags to strip, plus records to persist. */
    data class Decision(
        val unavailableTags: List<String>,
        val newRecords: Map<String, Record>,
    )

    const val AVAILABLE_TTL_MS = 60L * 60 * 1000
    const val UNAVAILABLE_TTL_MS = 10L * 60 * 1000

    /** Overall wall-clock budget for one start's probing. */
    const val PROBE_BUDGET_MS = 6_000L

    /** Probe fan-out; sets past the cap stay UNKNOWN and are kept. */
    const val PROBE_PARALLELISM = 8
    const val MAX_PROBED_SETS = 64

    /** Persistence key: a URL change must not inherit the old verdict. */
    fun recordKey(profileId: Long, ref: Ref): String = "$profileId|${ref.tag}|${ref.url}"

    /** Every remote rule-set of a built (or raw) config, in config order. */
    fun enumerate(content: String): List<Ref> {
        val out = mutableListOf<Ref>()
        for (provider in RuleSetProviders.parse(content)) {
            if (!provider.remote) continue
            val url = provider.url.trim()
            if (url.isEmpty()) continue
            out += Ref(provider.tag, url, provider.format)
        }
        return out
    }

    /**
     * Split [refs] by what is already known. [hasCacheCopy] reports a
     * usable app-side cache file (the substitute rung's ammunition);
     * it outranks records — a stale-but-present copy still serves the
     * set locally, while a record only says the network path failed.
     */
    fun plan(
        refs: List<Ref>,
        records: Map<String, Record>,
        profileId: Long,
        nowMs: Long,
        hasCacheCopy: (Ref) -> Boolean,
    ): Plan {
        val knownUnavailable = mutableListOf<Ref>()
        val toProbe = mutableListOf<Ref>()
        val kept = mutableListOf<Ref>()
        for (ref in refs) {
            if (hasCacheCopy(ref)) {
                kept += ref
                continue
            }
            val record = records[recordKey(profileId, ref)]
            val fresh = record != null && isFresh(record, nowMs)
            when {
                fresh && !record!!.available -> knownUnavailable += ref
                fresh -> kept += ref
                toProbe.size < MAX_PROBED_SETS -> toProbe += ref
                else -> kept += ref
            }
        }
        return Plan(knownUnavailable, toProbe, kept)
    }

    private fun isFresh(record: Record, nowMs: Long): Boolean {
        val age = nowMs - record.checkedAtMs
        if (age < 0) return true // clock stepped back: trust the record over re-probing
        val ttl = if (record.available) AVAILABLE_TTL_MS else UNAVAILABLE_TTL_MS
        return age <= ttl
    }

    /**
     * Probe [refs] in parallel under one overall deadline. Returns a
     * verdict only for probes that finished in time; a probe that
     * throws, or is still running at the deadline, is simply absent
     * (UNKNOWN — the set is kept). Daemon threads: an abandoned probe
     * must never hold the process (or a test JVM) open.
     */
    fun probeAll(
        refs: List<Ref>,
        probe: RuleSetProbe,
        budgetMs: Long = PROBE_BUDGET_MS,
        parallelism: Int = PROBE_PARALLELISM,
    ): Map<Ref, Boolean> {
        if (refs.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "ruleset-probe").apply { isDaemon = true }
        }
        try {
            val futures = refs.map { ref ->
                ref to pool.submit(Callable { probe.probe(ref.url) })
            }
            val deadline = System.nanoTime() + budgetMs * 1_000_000L
            val out = LinkedHashMap<Ref, Boolean>()
            for ((ref, future) in futures) {
                val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (leftMs <= 0) break
                val verdict = try {
                    future.get(leftMs, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    null
                }
                if (verdict != null) out[ref] = verdict
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /** Merge a [plan] with its probe verdicts into the final strip list. */
    fun decide(
        plan: Plan,
        probeResults: Map<Ref, Boolean>,
        profileId: Long,
        nowMs: Long,
    ): Decision {
        val newRecords = LinkedHashMap<String, Record>()
        for ((ref, ok) in probeResults) {
            newRecords[recordKey(profileId, ref)] = Record(ok, nowMs)
        }
        val unavailable = LinkedHashSet<String>()
        plan.knownUnavailable.forEach { unavailable += it.tag }
        probeResults.forEach { (ref, ok) -> if (!ok) unavailable += ref.tag }
        return Decision(unavailable.toList(), newRecords)
    }

    /** The substitute rung's own test for a usable cache copy. */
    fun cacheCopyExists(workingDir: File, ref: Ref): Boolean {
        val file = RuleSetProviders.cacheFile(workingDir, ref.tag, ref.format, ref.url)
        return file.isFile && file.length() > 0L
    }
}

/**
 * One bounded availability probe for a rule-set URL: true when the
 * file answers on the direct path, false on any failure (HTTP error,
 * timeout, reset, DNS). Implementations must self-bound their time
 * (a few seconds at most); [RuleSetTriage.probeAll] additionally
 * enforces the overall budget. Injected so JVM tests never touch the
 * network.
 */
fun interface RuleSetProbe {
    fun probe(url: String): Boolean
}
