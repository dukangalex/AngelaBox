package io.nekohasekai.sfa.utils

import java.util.concurrent.locks.ReentrantLock

/** Prevent simultaneous backup/restore entry points from sharing scratch data.
 * Not a database, service, cross-process or crash-consistency lock. */
internal class BackupOperationGate {
    private val lock = ReentrantLock()

    fun <T> tryRun(block: () -> T): Result<T> {
        if (lock.isHeldByCurrentThread || !lock.tryLock()) {
            return Result.failure(IllegalStateException("备份或恢复正在进行，请稍后重试"))
        }
        return try {
            runCatching { block() }
        } finally {
            lock.unlock()
        }
    }
}
