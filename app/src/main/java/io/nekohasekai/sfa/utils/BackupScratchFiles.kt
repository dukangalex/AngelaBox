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
