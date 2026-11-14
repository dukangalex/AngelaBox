package io.nekohasekai.sfa.utils

/**
 * Synchronous operation admission and draining, not a filesystem or cross-process lock.
 * A synchronous [access] lease must not escape its block or span a suspension.
 * Use the structured coroutine operation adapter for a multi-step suspending operation. Nested synchronous calls
 * already admitted on the same thread may finish while a drain is pending.
 *
 * New callers fail promptly instead of blocking the Android main thread. An exclusive
 * caller must not already hold an access lease (that would deadlock its own drain).
 * A failed recovery/transaction can poison admission until process restart and recovery.
 */
internal class RestoreAccessGate(
    initiallyReady: Boolean = false,
) {
    private val monitor = Object()
    private val depth =
        object : ThreadLocal<Int>() {
            override fun initialValue(): Int = 0
        }
    private val operation = ThreadLocal<Operation?>()

    /** Thread-independent root lease; coroutine context installs it only while executing. */
    internal inner class Operation internal constructor() : AutoCloseable {
        internal var closed = false // accessed only under monitor

        override fun close() =
            synchronized(monitor) {
                if (!closed) {
                    closed = true
                    active--
                    monitor.notifyAll()
                }
            }
    }

    internal fun beginOperation(): Operation =
        synchronized(monitor) {
            check(failure == null && ready && !draining && owner == null) {
                "Application data is unavailable during restore recovery"
            }
            Operation().also { active++ }
        }

    internal fun validateOperation(lease: Operation) =
        synchronized(monitor) {
            check(!lease.closed && failure == null) { "Application data operation has ended or failed" }
        }

    internal fun installOperation(lease: Operation?): Operation? {
        val previous = operation.get()
        operation.set(lease)
        return previous
    }

    /** Opaque identity of the data image from which a cached UI object was read. */
    internal class Snapshot internal constructor(
        internal val identity: Any,
    )

    private var dataImage = Any()

    fun snapshot(): Snapshot = access { synchronized(monitor) { Snapshot(dataImage) } }

    fun requireSnapshot(snapshot: Snapshot) =
        access {
            synchronized(monitor) {
                check(snapshot.identity === dataImage) {
                    "Application data changed during recovery; reload this screen before saving"
                }
            }
        }

    private var ready = initiallyReady
    private var draining = false
    private var active = 0
    private var owner: Thread? = null
    private var failure: Throwable? = null

    fun <T> access(block: () -> T): T {
        synchronized(monitor) {
            check(failure == null) { "Application data is blocked pending restore recovery" }
            val lease = operation.get()
            check(lease == null || !lease.closed) { "Application data operation has ended" }
            check(owner === Thread.currentThread() || (ready && (!draining || depth.get() > 0 || lease != null))) {
                "Application data is unavailable during restore recovery"
            }
            active++
            depth.set(depth.get() + 1)
        }
        try {
            return block()
        } finally {
            synchronized(monitor) {
                depth.set(depth.get() - 1)
                active--
                monitor.notifyAll()
            }
        }
    }

    /** Must run before providers, workers, services or UI can touch application data. */
    fun initialize(recover: () -> Unit) {
        synchronized(monitor) {
            check(!ready && !draining && owner == null && failure == null && active == 0) {
                "Restore admission already initialized or failed"
            }
            draining = true
            owner = Thread.currentThread()
        }
        try {
            recover()
            synchronized(monitor) { ready = true }
        } catch (error: Throwable) {
            synchronized(monitor) { failure = error }
            throw error
        } finally {
            synchronized(monitor) {
                owner = null
                draining = false
                monitor.notifyAll()
            }
        }
    }

    /** Drain all admitted accesses and issue one coroutine-context lease for a transaction. */
    internal fun beginExclusiveOperation(timeoutMillis: Long): Operation {
        require(timeoutMillis >= 0)
        check(depth.get() == 0 && operation.get() == null) { "Cannot upgrade a data access lease" }
        val start = System.nanoTime()
        synchronized(monitor) {
            check(ready && failure == null && !draining && owner == null) { "Restore admission unavailable" }
            draining = true
            try {
                while (active != 0) {
                    val remaining = timeoutMillis - (System.nanoTime() - start) / 1_000_000
                    check(remaining > 0) { "Timed out draining application data" }
                    monitor.wait(remaining)
                }
                dataImage = Any()
                return Operation().also { active++ }
            } catch (error: Throwable) {
                draining = false
                monitor.notifyAll()
                if (error is InterruptedException) Thread.currentThread().interrupt()
                throw error
            }
        }
    }

    internal fun endExclusiveOperation() = synchronized(monitor) {
        check(draining && owner == null) { "No exclusive application data operation" }
        draining = false
        monitor.notifyAll()
    }

    internal fun failExclusiveOperation(error: Throwable) = synchronized(monitor) {
        check(draining && owner == null) { "No exclusive application data operation" }
        failure = error
    }

    /**
     * Integration primitive only: currently NOT used by BackupManager. The caller must
     * separately quiesce native service activity and every direct config-file accessor.
     * Any failure inside [block] poisons admission; rollback must precede re-opening.
     */
    fun <T> exclusive(
        timeoutMillis: Long,
        block: () -> T,
    ): T {
        require(timeoutMillis >= 0)
        check(depth.get() == 0 && operation.get() == null) { "Cannot upgrade a data access lease" }
        val start = System.nanoTime()
        synchronized(monitor) {
            check(ready && failure == null && !draining && owner == null) { "Restore admission unavailable" }
            draining = true
            try {
                while (active != 0) {
                    val remaining = timeoutMillis - (System.nanoTime() - start) / 1_000_000
                    check(remaining > 0) { "Timed out draining application data" }
                    monitor.wait(remaining)
                }
                owner = Thread.currentThread()
                // Rotation happens only after all readers drain, but before any replacement.
                // Even a successful rollback must invalidate pre-restore UI/cached handles.
                dataImage = Any()
            } catch (error: Throwable) {
                draining = false
                monitor.notifyAll()
                if (error is InterruptedException) Thread.currentThread().interrupt()
                throw error
            }
        }
        try {
            return block()
        } catch (error: Throwable) {
            synchronized(monitor) { failure = error }
            throw error
        } finally {
            synchronized(monitor) {
                owner = null
                draining = false
                monitor.notifyAll()
            }
        }
    }
}

/** One process-wide owner for Settings/ProfileManager admission. */
internal object ApplicationDataAccess {
    val gate = RestoreAccessGate()

    fun <T> access(block: () -> T): T = gate.access(block)

    suspend fun <T> operation(block: suspend () -> T): T = gate.operation(block)

    fun snapshot(): RestoreAccessGate.Snapshot = gate.snapshot()

    fun <T> access(
        snapshot: RestoreAccessGate.Snapshot,
        block: () -> T,
    ): T =
        gate.access {
            gate.requireSnapshot(snapshot)
            block()
        }

    suspend fun <T> operation(
        snapshot: RestoreAccessGate.Snapshot,
        block: suspend () -> T,
    ): T =
        gate.operation {
            gate.requireSnapshot(snapshot)
            block()
        }
}
