package io.nekohasekai.sfa.compose.screen.tools

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/** Cleanup belongs to completion, including a lazy job cancelled before its body runs. */
internal class OwnedSendCleanup(private val action: () -> Unit) {
    private val cleaned = AtomicBoolean(false)

    fun close() {
        if (cleaned.compareAndSet(false, true)) action()
    }
}

internal fun registerOwnedSendCleanup(job: Job, cleanup: OwnedSendCleanup, unregister: () -> Unit) {
    job.invokeOnCompletion {
        try {
            unregister()
        } finally {
            cleanup.close()
        }
    }
}

internal fun startOwnedSendJob(job: Job, startService: () -> Unit, onFailure: () -> Unit) {
    try {
        startService()
        job.start()
    } catch (failure: Throwable) {
        job.cancel()
        onFailure()
        throw failure
    }
}
