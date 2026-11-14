package io.nekohasekai.sfa.utils

import java.util.concurrent.atomic.AtomicBoolean

/** Prevent simultaneous backup/restore entry points from sharing scratch data.
 * Not a database, service, cross-process or crash-consistency lock. */
internal class BackupOperationGate {
    private val busy = AtomicBoolean(false)

    fun <T> tryRun(block: () -> T): Result<T> {
        if (!busy.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("备份或恢复正在进行，请稍后重试"))
        }
        return try {
            runCatching { block() }
        } finally {
            busy.set(false)
        }
    }

    suspend fun <T> tryRunSuspend(block: suspend () -> T): Result<T> {
        if (!busy.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("备份或恢复正在进行，请稍后重试"))
        }
        return try {
            runCatching { block() }
        } finally {
            busy.set(false)
        }
    }
}
