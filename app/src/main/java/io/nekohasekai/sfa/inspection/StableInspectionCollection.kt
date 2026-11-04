package io.nekohasekai.sfa.inspection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A context transition is not an IO failure and must never publish a partial report. */
internal class StaleInspectionEvidence : IllegalStateException("stale")

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
        if (elapsed() - completed >= quietMillis) return result
        pause(250)
    }
}
