package io.nekohasekai.sfa.utils

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Android API 21+ primitives; no java.nio.file (API 26) dependency for minSdk 24. */
internal object AndroidRestoreDurability : RestoreRecoveryJournal.Durability {
    override fun isSymbolicLink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }

    override fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Not a restore directory" }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }
}
