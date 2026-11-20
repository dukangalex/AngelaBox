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

    /** Call only while holding the profile mutation gate. */
    fun writeIfUnchanged(file: File, expected: String, content: String): Boolean {
        if (!file.isFile || file.readText() != expected) return false
        if (content != expected) writeAtomically(file, content)
        return true
    }

    @Throws(IOException::class)
    fun writeAtomically(file: File, content: String) {
        val target = file.absoluteFile
        val parent = target.parentFile ?: throw IOException("配置目录不存在")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建配置目录")
        }
        val tmp = File.createTempFile(target.name + ".", ".tmp", parent)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("原子写入配置失败")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
