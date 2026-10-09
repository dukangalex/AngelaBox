package io.nekohasekai.sfa.bg

import java.io.File

/**
 * Retention policy for on-device diagnostic reports (crash / OOM / power).
 *
 * Reports used to accumulate forever — a single heap profile can be tens of MB.
 * Each manager prunes on refresh(): keep the newest [KEEP_LATEST] reports and
 * drop anything older than [MAX_AGE_DAYS] days.
 *
 * Pure JVM (no android.* imports) so it is unit-testable on the host.
 */
object ReportRetention {
    const val KEEP_LATEST = 10
    const val MAX_AGE_DAYS = 30L
    const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /**
     * Deletes report directories that fall beyond [keepLatest] (input must be
     * sorted newest-first) or are older than [maxAgeMillis].
     *
     * Returns the number of deleted directories. Never throws: a broken cleanup
     * must not break report listing.
     */
    fun prune(
        newestFirst: List<File>,
        keepLatest: Int = KEEP_LATEST,
        maxAgeMillis: Long = MAX_AGE_DAYS * MILLIS_PER_DAY,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        var deleted = 0
        newestFirst.forEachIndexed { index, dir ->
            if (!dir.isDirectory) return@forEachIndexed
            val tooMany = index >= keepLatest
            val tooOld = nowMillis - dir.lastModified() > maxAgeMillis
            if ((tooMany || tooOld) && runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
                deleted++
            }
        }
        return deleted
    }

    /** Total bytes of all files under [dir]; backs the "storage used" line in UI. */
    fun totalBytes(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}
