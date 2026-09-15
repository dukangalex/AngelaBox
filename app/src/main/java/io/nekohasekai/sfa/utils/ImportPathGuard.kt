package io.nekohasekai.sfa.utils

import java.io.File
import java.io.InputStream

object ImportPathGuard {
    const val MAX_BYTES = 8L * 1024 * 1024

    fun requireContentScheme(scheme: String?) {
        require(scheme.equals("content", ignoreCase = true)) {
            "Only content:// profile imports are supported"
        }
    }

    fun requireInside(file: File, sandbox: File) {
        val target = file.canonicalFile
        val root = sandbox.canonicalFile
        val path = target.path
        val prefix = root.path
        require(path == prefix || path.startsWith(prefix + File.separator)) {
            "路径逃逸工作目录"
        }
    }

    fun readLimited(input: InputStream, maxBytes: Long = MAX_BYTES): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) {
                throw IllegalArgumentException("Imported profile exceeds ${maxBytes / (1024 * 1024)} MiB limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
