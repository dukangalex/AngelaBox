package io.nekohasekai.sfa.utils

import android.content.Context
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.database.ProfileBindingStorage
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.syncProfileDirectory
import java.io.File

/**
 * Fail-closed exclusion for synchronous storage operations. Other threads fail rather than wait
 * behind a restore while holding a profile/stable-ID monitor, which would invert lock order.
 * Acquiring exclusivity itself fails before any mutation if a storage operation is in flight.
 */
internal class RestoreStorageUnavailable(message: String) : IllegalStateException(message)

internal object RestoreStorageAccess {
    private val lock = Any()
    private var active = 0
    private var generation = 0L
    private val observers = mutableSetOf<() -> Unit>()
    private var owner: Thread? = null
    private var pending: () -> Boolean = { false }
    private var effects = linkedMapOf<Any, () -> Unit>()

    fun configure(pendingRecovery: () -> Boolean) = synchronized(lock) { pending = pendingRecovery }

    /** Capture before asynchronous work; equal row IDs/URLs do not survive a restore as identity. */
    fun snapshot(): Long = synchronized(lock) {
        if (owner != null || pending()) throw RestoreStorageUnavailable("备份恢复尚未完成，请稍后重试")
        generation
    }

    fun isCurrent(snapshot: Long): Boolean = synchronized(lock) {
        owner == null && !pending() && generation == snapshot
    }

    fun requireCurrent(snapshot: Long) {
        if (!isCurrent(snapshot)) throw RestoreStorageUnavailable("备份恢复已改变配置，本次操作已停止，请重试")
    }

    fun observe(snapshot: Long, changed: () -> Unit): java.io.Closeable = synchronized(lock) {
        requireCurrent(snapshot)
        observers.add(changed)
        java.io.Closeable { synchronized(lock) { observers.remove(changed) } }
    }

    fun <T> access(action: () -> T): T {
        synchronized(lock) {
            if (owner != null && owner !== Thread.currentThread()) throw RestoreStorageUnavailable("备份正在恢复，请稍后重试")
            if (owner !== Thread.currentThread() && pending()) throw RestoreStorageUnavailable("备份恢复尚未完成，请保留应用数据并重启重试")
            active++
        }
        try {
            return action()
        } finally {
            synchronized(lock) { active-- }
        }
    }

    /** Keep a suspending profile commit admitted across its file/DAO gaps. */
    suspend fun <T> lease(action: suspend () -> T): T {
        synchronized(lock) {
            if (owner != null && owner !== Thread.currentThread()) throw RestoreStorageUnavailable("备份正在恢复，请稍后重试")
            if (owner !== Thread.currentThread() && pending()) throw RestoreStorageUnavailable("备份恢复尚未完成，请保留应用数据并重启重试")
            active++
        }
        try {
            return action()
        } finally {
            synchronized(lock) { active-- }
        }
    }

    fun <T> exclusive(action: () -> T): T {
        val invalidated = synchronized(lock) {
            check(owner == null && active == 0) { "配置正在读写，请稍后重试恢复" }
            generation++
            owner = Thread.currentThread()
            observers.toList().also { observers.clear() }
        }
        // Do not hold the storage lock while disconnecting blocked network reads.
        invalidated.forEach { runCatching(it) }
        var committed = false
        var ready: List<() -> Unit> = emptyList()
        try {
            return action().also { committed = true }
        } finally {
            synchronized(lock) {
                if (committed) ready = effects.values.toList()
                effects.clear()
                owner = null
            }
            // The durable commit already happened. A notification/scheduler failure cannot
            // turn it into a reported rollback or prevent the remaining effects from running.
            ready.forEach { effect ->
                runCatching(effect).onFailure {
                    android.util.Log.w("BackupRestore", "post-commit effect failed", it)
                }
            }
        }
    }

    /** Coalesce provisional notifications and non-transactional scheduler actions.
     * They never escape a failed restore and run only after storage is available again.
     * Room initial-import intents remain the durable authority if the process dies first.
     */
    fun afterCommit(key: Any, effect: () -> Unit) {
        val deferred = synchronized(lock) {
            if (owner != null && owner !== Thread.currentThread()) throw RestoreStorageUnavailable("备份正在恢复，请稍后重试")
            if (owner != null) {
                effects[key] = effect
                true
            } else {
                false
            }
        }
        if (!deferred) effect()
    }

    fun requireOwner() = synchronized(lock) { check(owner === Thread.currentThread()) }
}

internal object BackupRestoreTransaction {
    private fun journal(context: Context): RestoreJournal {
        val targets = linkedMapOf<String, File>("configs" to File(context.filesDir, "configs"))
        listOf("settings" to Path.SETTINGS_DATABASE_PATH, "profiles" to Path.PROFILES_DATABASE_PATH).forEach { (key, name) ->
            val database = context.getDatabasePath(name)
            targets[key] = database
            listOf("wal", "shm", "journal").forEach { targets["${key}_$it"] = File(database.path + "-$it") }
        }
        return RestoreJournal(File(context.noBackupFilesDir, "backup-restore-v1"), targets, ::syncProfileDirectory)
    }

    /** Before providers, background tasks, Settings or Room can observe an interrupted restore. */
    fun recoverAtStartup(context: Context) {
        val journal = journal(context)
        RestoreStorageAccess.configure(journal::needsRecovery)
        RestoreStorageAccess.exclusive {
            if (journal.needsRecovery()) closeDatabases()
            journal.recover()
        }
    }

    fun <T> run(context: Context, action: () -> T): T = RestoreStorageAccess.exclusive {
        val journal = journal(context)
        RestoreStorageAccess.configure(journal::needsRecovery)
        check(!journal.needsRecovery()) { "上次恢复尚未完成，请重启后重试" }
        // Finish an older file/binding commit before taking the rollback snapshot.
        ProfileBindingStorage.access { Unit }
        closeDatabases()
        runJournaled(journal, ::closeDatabases, action)
    }

    /** A post-marker failure is not a rollback. Recover the visible commit and only report
     * success after its directory barriers and cleanup have succeeded. Until then leave the
     * storage fence in place and explicitly report that the outcome is uncertain. */
    internal fun <T> runJournaled(journal: RestoreJournal, close: () -> Unit, action: () -> T): T {
        journal.begin()
        fun recoverOrThrow(failure: Exception, committed: Boolean) {
            try {
                close()
                journal.recover()
            } catch (recovery: Exception) {
                failure.addSuppressed(recovery)
                val message = if (committed) {
                    "恢复可能已经提交，但持久化尚未确认；请保留应用数据并重启重试"
                } else {
                    "恢复失败且回滚尚未完成，请保留应用数据并重启重试"
                }
                throw IllegalStateException(message, failure)
            }
        }
        val result = try {
            action().also { close() }
        } catch (failure: Exception) {
            recoverOrThrow(failure, false)
            throw failure
        }
        try {
            journal.commit()
        } catch (failure: Exception) {
            val committed = journal.commitMarkerRenamed
            recoverOrThrow(failure, committed)
            if (!committed) throw failure
            // Recovery successfully synchronized the commit; preserve post-commit effects.
        }
        return result
        // Errors (including simulated process termination) deliberately leave the journal.
    }

    private fun closeDatabases() {
        Settings.closeDatabaseForRestore()
        ProfileManager.closeDatabaseForRestore()
    }
}
