package io.nekohasekai.sfa.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Writes a complete profile before atomically replacing its previous contents. */
object ProfileFiles {
    /** Snapshot null means no file; read errors are not treated as absence. */
    @Synchronized
    @Throws(IOException::class)
    fun snapshot(file: File): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    /** Reject stale read-modify-write instead of overwriting an intervening in-process update. */
    @Synchronized
    @Throws(IOException::class)
    fun writeIfUnchanged(file: File, expected: String?, content: String): Boolean {
        if (snapshot(file) != expected) {
            throw IOException("配置已被其他操作修改，请重新加载后再试")
        }
        if (expected == content) return false
        writeAtomically(file, content)
        return true
    }

    @Synchronized
    @Throws(IOException::class)
    fun writeAtomically(file: File, content: String) {
        val target = file.absoluteFile
        val parent = target.parentFile ?: throw IOException("配置目录不存在")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建配置目录")
        }
        // A unique temporary file prevents overlapping writers from modifying
        // or deleting each other's in-progress data.
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
