package io.nekohasekai.sfa.utils

import java.io.File

/** Per-operation files prevent an import or upload from replacing another operation's bytes. */
internal object BackupScratchFiles {
    fun create(cacheDir: File, purpose: String): File =
        File.createTempFile("chainbox-$purpose-", ".zip", cacheDir)

    inline fun <T> withFile(cacheDir: File, purpose: String, block: (File) -> T): T {
        val file = create(cacheDir, purpose)
        return try {
            block(file)
        } finally {
            file.delete()
        }
    }
}

/** Keep a password-dialog file until any in-flight restore has really returned. */
internal class BackupScratchRetention {
    private val inUse = mutableSetOf<File>()
    private val pending = mutableSetOf<File>()

    @Synchronized fun begin(file: File) {
        check(inUse.add(file)) { "scratch file is already in use" }
    }

    @Synchronized fun discard(file: File) {
        if (file in inUse) pending.add(file) else file.delete()
    }

    @Synchronized fun finish(file: File) {
        inUse.remove(file)
        if (pending.remove(file)) file.delete()
    }
}
