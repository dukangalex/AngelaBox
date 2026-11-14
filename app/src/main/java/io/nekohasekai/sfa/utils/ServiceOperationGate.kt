package io.nekohasekai.sfa.utils

/**
 * Thread-independent service activity accounting. A ticket must be registered BEFORE
 * a coroutine/thread is dispatched and closed only when its real body exits, including
 * abandoned native workers. A timeout is never a cancellation or proof of quiescence.
 */
internal class ServiceOperationGate {
    private val monitor = Object()
    private var paused = false
    private var active = 0
    private var sealed = false
    private var failure: Throwable? = null

    inner class Ticket internal constructor() : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(monitor) {
            if (!closed) { closed = true; active--; monitor.notifyAll() }
        }
    }

    /** New start/reload work; null means the caller must not dispatch it. */
    fun begin(): Ticket? = synchronized(monitor) {
        if (paused) null else Ticket().also { active++ }
    }

    /** Existing work's native/cleanup descendants still count while admission is closed. */
    fun cleanup(): Ticket = synchronized(monitor) {
        if (sealed) {
            val error = IllegalStateException("Unexpected service activity after quiescence acknowledgement")
            if (failure == null) failure = error
            throw error
        }
        Ticket().also { active++ }
    }

    fun recordCloseFailure(error: Throwable) = synchronized(monitor) {
        if (failure == null) failure = error
        monitor.notifyAll()
    }

    inner class Pause internal constructor() {
        private var released = false
        /** Blocking; call on IO, never Main. Timeout/failure leaves admission closed. */
        fun awaitIdle(timeoutMillis: Long) {
            require(timeoutMillis >= 0)
            val start = System.nanoTime()
            synchronized(monitor) {
                check(!released && paused) { "Service pause is no longer active" }
                while (active != 0 && failure == null) {
                    val remaining = timeoutMillis - (System.nanoTime() - start) / 1_000_000
                    check(remaining > 0) { "Service activity did not terminate before restore deadline" }
                    try { monitor.wait(remaining) } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
                failure?.let { throw IllegalStateException("Native service shutdown failed", it) }
            }
        }
        /** Atomically finish acknowledgement after checking all native resource owners. */
        fun seal() = synchronized(monitor) {
            check(!released && paused && active == 0 && failure == null) { "Service state changed during acknowledgement" }
            sealed = true
        }
        /** Only after restore commit/recovery and after ordinary data admission is ready. */
        fun resume() = synchronized(monitor) {
            check(!released && paused && active == 0 && failure == null) { "Cannot resume unsafe service state" }
            released = true
            paused = false
            sealed = false
            monitor.notifyAll()
        }
    }

    fun pause(): Pause = synchronized(monitor) {
        check(!paused && failure == null) { "Service admission is already paused or shutdown failed" }
        paused = true
        Pause()
    }
}
