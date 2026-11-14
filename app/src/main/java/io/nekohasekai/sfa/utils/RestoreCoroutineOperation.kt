package io.nekohasekai.sfa.utils

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One root admission spans suspension and dispatcher changes. Structured children must
 * finish before release. A detached child retaining the context cannot access data after
 * release. New independent operations fail promptly while restore drains existing ones.
 * This excludes restore, not concurrent ordinary edits; callers still own edit conflicts.
 */
internal suspend fun <T> RestoreAccessGate.operation(block: suspend () -> T): T {
    val inherited = currentCoroutineContext()[RestoreCoroutineOperation]
    if (inherited?.gate === this) {
        inherited.requireActive()
        return block()
    }
    val lease = beginOperation()
    try {
        return withContext(RestoreCoroutineOperation(this, lease)) { block() }
    } finally {
        lease.close()
    }
}

internal class RestoreCoroutineOperation(
    val gate: RestoreAccessGate,
    private val lease: RestoreAccessGate.Operation,
) : ThreadContextElement<RestoreAccessGate.Operation?>,
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RestoreCoroutineOperation>

    fun requireActive() = gate.validateOperation(lease)

    override fun updateThreadContext(context: CoroutineContext): RestoreAccessGate.Operation? =
        gate.installOperation(lease)

    override fun restoreThreadContext(context: CoroutineContext, oldState: RestoreAccessGate.Operation?) {
        gate.installOperation(oldState)
    }
}

/** Exclusive coroutine transaction lease: no unrelated app-data access can enter while it drains. */
internal suspend fun <T> RestoreAccessGate.exclusiveOperation(
    timeoutMillis: Long,
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()
    check(context[RestoreCoroutineOperation] == null) { "Cannot upgrade a data access lease" }
    val lease = beginExclusiveOperation(timeoutMillis)
    try {
        return withContext(RestoreCoroutineOperation(this, lease)) { block() }
    } catch (error: Throwable) {
        failExclusiveOperation(error)
        throw error
    } finally {
        lease.close()
        endExclusiveOperation()
    }
}
