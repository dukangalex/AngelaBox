package io.nekohasekai.sfa.inspection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A context transition is not an IO failure and must never publish a partial report. */
internal class StaleInspectionEvidence : IllegalStateException("stale")

/** Monotonic control identity prevents a stop/start or same-profile edit from
 * masquerading as a network-only callback eligible for local recollection. */
data class InspectionCollectionIdentity(
    val profile: Long,
    val profileRevision: Long,
    val tunnelGeneration: Long,
    val network: Long?,
)

internal fun mayRecollectInspection(
    before: InspectionCollectionIdentity,
    after: InspectionCollectionIdentity,
): Boolean = before.network != null && before == after

/**
 * Wait out asynchronous Android network callbacks before taking a snapshot, then
 * require the same context throughout collection and a short publication window.
 * This does not suppress any invalidation, rebase old evidence, repeat probes or
 * retry an AI request. Churn stays a bounded, cancellable failure.
 */
internal suspend fun <S, T> collectStableEvidence(
    current: () -> S,
    pause: suspend (Long) -> Unit,
    elapsed: () -> Long,
    publicationMillis: Long = 3_000L,
    collect: suspend (S) -> T,
): T {
    val quietMillis = 3_000L
    val maxWaitMillis = 10_000L
    val started = elapsed()
    var stableSince = started
    var context = current()
    while (elapsed() - stableSince < quietMillis) {
        if (elapsed() - started >= maxWaitMillis) throw StaleInspectionEvidence()
        pause(250)
        val next = current()
        if (next != context) {
            context = next
            stableSince = elapsed()
        }
    }
    currentCoroutineContext().ensureActive()
    val result = collect(context)
    val completed = elapsed()
    while (true) {
        currentCoroutineContext().ensureActive()
        if (current() != context) throw StaleInspectionEvidence()
        if (elapsed() - completed >= publicationMillis) return result
        pause(250)
    }
}

/**
 * One consented manual operation may discard and repeat local probes once. A
 * delayed callback never rebases a report or retries AI. The second report is
 * collected from scratch, and is still subject to every freshness check.
 */
internal suspend fun <S, T> collectRecoveringEvidence(
    current: () -> S,
    pause: suspend (Long) -> Unit,
    elapsed: () -> Long,
    mayRecollect: (S, S) -> Boolean,
    collect: suspend (S) -> T,
): T {
    val initial = current()
    repeat(2) { attempt ->
        try {
            return collectStableEvidence(current, pause, elapsed, publicationMillis = 10_000L, collect = collect)
        } catch (stale: StaleInspectionEvidence) {
            currentCoroutineContext().ensureActive()
            if (attempt != 0 || !mayRecollect(initial, current())) throw stale
        }
    }
    throw StaleInspectionEvidence()
}
