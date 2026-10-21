package io.nekohasekai.sfa.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * X-3: the one way profile archive files get written.
 *
 * A profile file used to be written two ways: the background worker wrote
 * tmp + fsync + rename, while the foreground update paths used a bare
 * `File.writeText` — a truncate-then-write that leaves a half-written JSON
 * behind if the process dies mid-write, corrupting the profile, and that a
 * concurrent serviceReload can read mid-write. Every writer of a profile's
 * stored config goes through here so the archive is never observable in a
 * partial state.
 */
object ProfileFiles {

    @Throws(IOException::class)
    fun writeAtomically(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, file.name + ".tmp")
        try {
            tmp.writeText(content)
            // fsync so a power loss right after the rename cannot resurrect
            // a zero-length or partial file.
            FileOutputStream(tmp, true).channel.use { it.force(true) }
            if (!tmp.renameTo(file)) {
                throw IOException("原子写入配置失败")
            }
        } finally {
            // A leftover .tmp from a killed run is truncated by the next
            // write, but do not leave one behind on failure paths either.
            if (tmp.exists()) tmp.delete()
        }
    }
}
