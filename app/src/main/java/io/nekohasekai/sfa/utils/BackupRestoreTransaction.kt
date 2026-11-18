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
internal object RestoreStorageAccess {
    private val lock = Any()
    private var active = 0
    private var owner: Thread? = null
    private var pending: () -> Boolean = { false }

    fun configure(pendingRecovery: () -> Boolean) = synchronized(lock) { pending = pendingRecovery }

    fun <T> access(action: () -> T): T {
        synchronized(lock) {
            check(owner == null || owner === Thread.currentThread()) { "备份正在恢复，请稍后重试" }
            check(owner === Thread.currentThread() || !pending()) { "备份恢复尚未完成，请保留应用数据并重启重试" }
            active++
        }
        try { return action() } finally { synchronized(lock) { active-- } }
    }

    fun <T> exclusive(action: () -> T): T {
        synchronized(lock) {
            check(owner == null && active == 0) { "配置正在读写，请稍后重试恢复" }
            owner = Thread.currentThread()
        }
        try { return action() } finally { synchronized(lock) { owner = null } }
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
        journal.begin()
        try {
            val result = action()
            closeDatabases()
            journal.commit()
            result
        } catch (failure: Exception) {
            try {
                closeDatabases()
                journal.recover()
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
                throw IllegalStateException("恢复失败且回滚尚未完成，请保留应用数据并重启重试", failure)
            }
            throw failure
        }
        // Errors (including simulated process termination) deliberately leave READY for startup.
    }

    private fun closeDatabases() {
        Settings.closeDatabaseForRestore()
        ProfileManager.closeDatabaseForRestore()
    }
}
