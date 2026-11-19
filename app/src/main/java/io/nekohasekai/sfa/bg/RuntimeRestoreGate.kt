package io.nekohasekai.sfa.bg

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local lifecycle exclusion. A broadcast or UI status is not a stop acknowledgement.
 * Drain requires teardown API completion and admitted work exiting.
 * Bound Android services need not be destroyed to drain their runtime.
 * Native cleanup guarantees still depend on the linked AAR; its swallowed errors are not proved safe.
 * A timeout refuses the restore before its storage transaction starts.
 */
internal class RuntimeRestoreGate {
    private val lock = Object()
    private data class Runtime(
        val stop: () -> Unit,
        var stopping: Boolean = false,
    )
    private val runtimes = mutableMapOf<Any, Runtime>()
    private var work = 0
    private var preparations = 0
    private var restoring = false
    private var epoch = 0L

    fun startTicket(): Long = synchronized(lock) {
        check(!restoring) { "正在恢复备份，请稍后再启动服务" }
        epoch
    }

    fun prepareStart(): Pair<Long, Closeable>? = synchronized(lock) {
        if (restoring) return null
        epoch to trackLocked(preparation = true)
    }

    fun start(key: Any, ticket: Long?, stop: () -> Unit): Closeable? = synchronized(lock) {
        if (restoring || (ticket != null && ticket != epoch) || runtimes.isNotEmpty() || work != 0) return null
        runtimes[key] = Runtime(stop)
        trackLocked()
    }

    fun operation(key: Any): Closeable? = synchronized(lock) {
        val runtime = runtimes[key]
        if (restoring || runtime == null || runtime.stopping) return null
        trackLocked()
    }

    /** A close callback may drain an existing runtime, never revive one after acknowledgement. */
    fun drainOperation(key: Any): Closeable? = synchronized(lock) {
        if (key !in runtimes) return null
        trackLocked()
    }

    /** Teardown/abandoned native workers must remain counted even after admission closes. */
    fun drainingWork(): Closeable = synchronized(lock) { trackLocked() }

    private fun trackLocked(preparation: Boolean = false): Closeable {
        if (preparation) preparations++ else work++
        val closed = AtomicBoolean()
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    if (preparation) preparations-- else work--
                    lock.notifyAll()
                }
            }
        }
    }

    fun stopping(key: Any) = synchronized(lock) { runtimes[key]?.stopping = true }

    /** Never called from onDestroy, a stop request, or an error-swallowing cleanup path. */
    fun stopped(key: Any) = synchronized(lock) {
        runtimes.remove(key)
        lock.notifyAll()
    }

    fun <T> withStopped(timeoutMs: Long, canWait: Boolean = true, action: () -> T): T {
        require(timeoutMs >= 0)
        val stops = synchronized(lock) {
            check(!restoring) { "已有备份正在恢复" }
            check(canWait || (runtimes.isEmpty() && work == 0 && preparations == 0)) { "请在后台等待服务停止后恢复备份" }
            restoring = true
            epoch++
            runtimes.values.onEach { it.stopping = true }.map { it.stop }
        }
        try {
            stops.forEach { it() }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            synchronized(lock) {
                while (runtimes.isNotEmpty() || work != 0 || preparations != 0) {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { "服务尚未完成停止确认，未恢复任何数据，请稍后重试" }
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                }
            }
            return action()
        } finally {
            synchronized(lock) {
                restoring = false
                lock.notifyAll()
            }
        }
    }
}
