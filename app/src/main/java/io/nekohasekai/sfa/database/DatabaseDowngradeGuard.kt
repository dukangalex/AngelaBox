package io.nekohasekai.sfa.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Room is built with destructive fallback on downgrade, so opening a database
 * written by a newer app version (sideloaded older build, or a restored backup)
 * would silently wipe it. Before that happens, copy the files aside so the data
 * can be recovered by reinstalling the newer build.
 */
internal object DatabaseDowngradeGuard {
    private const val TAG = "DatabaseDowngradeGuard"

    fun backupIfNewer(context: Context, name: String, currentVersion: Int) {
        val file = context.getDatabasePath(name)
        if (!file.isFile) return
        val onDisk = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.getOrElse {
            Log.w(TAG, "read version of $name", it)
            return
        }
        if (onDisk <= currentVersion) return
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val src = File(file.path + suffix)
            if (!src.isFile) continue
            runCatching {
                src.copyTo(File(file.path + ".v$onDisk.bak$suffix"), overwrite = true)
            }.onFailure { Log.w(TAG, "back up ${src.name}", it) }
        }
        Log.w(TAG, "$name is schema v$onDisk, newer than v$currentVersion; backed up before downgrade")
    }
}
