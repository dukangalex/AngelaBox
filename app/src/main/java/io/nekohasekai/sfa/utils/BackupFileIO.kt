package io.nekohasekai.sfa.utils

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Bounds restore input and removes rejected or interrupted copies. */
internal object BackupFileIO {
    private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024

    fun requireWithinLimit(file: File) {
        require(file.length() <= MAX_ARCHIVE_BYTES) { "备份文件过大（上限 128 MiB）" }
    }

    /** Caller owns [input]. Any rejected or interrupted copy removes its partial file. */
    fun copyToFile(
        input: InputStream,
        destination: File,
        maxBytes: Long = MAX_ARCHIVE_BYTES,
        validate: (File) -> Unit = {},
    ) {
        require(maxBytes >= 0)
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "备份文件过大（上限 128 MiB）" }
                    output.write(buffer, 0, read)
                }
            }
            validate(destination)
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }
}
