package io.nekohasekai.sfa.vendor

import io.nekohasekai.libbox.HTTPResponseWriteToProgressHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

class ApkDownloader : Closeable {
    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

    suspend fun download(url: String, expectedSha256: String): File = withContext(Dispatchers.IO) {
        val expected = expectedSha256.trim().lowercase()
        require(expected.matches(SHA256_HEX)) { "Missing or invalid APK SHA-256" }

        val cacheDir = File(Application.application.cacheDir, "updates")
        cacheDir.mkdirs()
        val apkFile = File(cacheDir, "update.apk")

        if (apkFile.exists()) apkFile.delete()

        val request = client.newRequest()
        request.setUserAgent(HTTPClient.userAgent)
        request.setURL(url)

        val response = request.execute()
        response.writeToWithProgress(
            apkFile.absolutePath,
            object : HTTPResponseWriteToProgressHandler {
                override fun update(progress: Long, total: Long) {
                    UpdateState.downloadProgress.value =
                        if (total > 0) progress.toFloat() / total.toFloat() else null
                }
            },
        )

        if (!apkFile.exists() || apkFile.length() == 0L) {
            throw Exception("Download failed: empty file")
        }

        val actual = sha256Hex(apkFile)
        if (actual != expected) {
            apkFile.delete()
            throw Exception("APK SHA-256 mismatch (expected $expected, got $actual)")
        }

        UpdateState.saveApkPath(apkFile)
        apkFile
    }

    override fun close() {
        client.close()
    }

    companion object {
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
}
