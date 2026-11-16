package io.nekohasekai.sfa.utils

/**
 * Same-process flag: true only while the VPN/proxy service is Started.
 * Update downloads use it to follow the tunnel instead of dialing a
 * pre-resolved IP that the tunnel cannot route by name.
 */
object TunnelGate {
    @Volatile
    var up: Boolean = false
        private set

    @Volatile
    var generation: Long = 0
        private set

    internal data class Snapshot(val up: Boolean, val generation: Long)

    private val observers = mutableSetOf<() -> Unit>()
    private var reconfigurations = 0

    @Synchronized
    internal fun snapshot() = Snapshot(up, generation)

    @Synchronized
    internal fun requireCurrent(snapshot: Snapshot) {
        check(reconfigurations == 0) { "VPN 正在重载，订阅更新已停止，请稍后重试" }
        check(snapshot == Snapshot(up, generation)) { "VPN 状态已变化，订阅更新已停止，请重试" }
    }

    @Synchronized
    internal fun observe(snapshot: Snapshot, changed: () -> Unit): java.io.Closeable {
        requireCurrent(snapshot)
        observers.add(changed)
        return java.io.Closeable { synchronized(this) { observers.remove(changed) } }
    }

    /**
     * A kernel reload can replace its VPN without changing Started. Invalidate
     * existing requests and refuse new ones until the native attempt returns.
     * Keep [up] unchanged: publishing false here would authorize direct retries.
     */
    internal fun reconfiguring(): java.io.Closeable {
        val changed = synchronized(this) {
            reconfigurations++
            generation++
            observers.toList().also { observers.clear() }
        }
        changed.forEach { runCatching { it() } }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return java.io.Closeable {
            if (closed.compareAndSet(false, true)) {
                synchronized(this) {
                    reconfigurations--
                    generation++
                }
            }
        }
    }

    fun setUp(value: Boolean) {
        val changed = synchronized(this) {
            if (up == value) return
            generation++
            up = value
            observers.toList().also { observers.clear() }
        }
        // Never hold the service-state lock while disconnecting a network request.
        changed.forEach { runCatching { it() } }
    }
}
