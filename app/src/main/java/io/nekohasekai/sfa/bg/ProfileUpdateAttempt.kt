package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Do not convert a stopped worker into a failed subscription or start another fetch. */
internal suspend fun ensureProfileUpdateActive(isStopped: () -> Boolean) {
    currentCoroutineContext().ensureActive()
    if (isStopped()) throw CancellationException("订阅更新任务已停止")
}

/** Shared failure boundary for one background profile update. */
internal suspend fun runProfileUpdateAttempt(
    isStopped: () -> Boolean,
    onFailure: (Exception) -> Unit,
    update: suspend () -> Unit,
) {
    ensureProfileUpdateActive(isStopped)
    try {
        update()
        ensureProfileUpdateActive(isStopped)
    } catch (error: Exception) {
        // HTTP wrappers can retain cancellation as their cause. Preserve it,
        // instead of persisting a misleading failure and fetching the next profile.
        generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<CancellationException>()
            .firstOrNull()?.let { throw it }
        generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<io.nekohasekai.sfa.utils.RestoreStorageUnavailable>()
            .firstOrNull()?.let { throw it }
        ensureProfileUpdateActive(isStopped)
        onFailure(error)
    }
}
