package io.nekohasekai.sfa.vendor

import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteUrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

class ApkDownloader : Closeable {
    private val client = HTTPClient()

    suspend fun download(url: String, expectedSha256: String): File = withContext(Dispatchers.IO) {
        val expected = requireSha256(expectedSha256)
        RemoteUrlGuard.requireAllowed(url, RemoteUrlGuard.Kind.UPDATE)

        val cacheDir = File(Application.application.cacheDir, "updates")
        cacheDir.mkdirs()
        val apkFile = File(cacheDir, "update.apk")

        if (apkFile.exists()) apkFile.delete()

        client.downloadToFile(url, RemoteUrlGuard.Kind.UPDATE, apkFile) { progress, total ->
            UpdateState.downloadProgress.value =
                if (total > 0) progress.toFloat() / total.toFloat() else null
        }

        if (!apkFile.exists() || apkFile.length() == 0L) {
            throw Exception("Download failed: empty file")
        }

        verifySha256(apkFile, expected)
        verifyReleaseIdentity(apkFile)
        UpdateState.saveApkPath(apkFile)
        apkFile
    }

    fun verifyCachedApk(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val expected = requireSha256(expectedSha256)
        return runCatching {
            verifySha256(file, expected)
            verifyReleaseIdentity(file)
        }.isSuccess
    }

    private fun verifySha256(file: File, expected: String) {
        val actual = sha256Hex(file)
        if (actual != expected) {
            file.delete()
            throw Exception("APK SHA-256 mismatch (expected $expected, got $actual)")
        }
    }

    private fun verifyReleaseIdentity(file: File) {
        val pm = Application.application.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: throw Exception("APK could not be parsed")

        if (info.packageName != ReleaseTrust.PACKAGE_NAME) {
            file.delete()
            throw Exception("APK package does not match AngelaBox")
        }

        val apkCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (apkCode < BuildConfig.VERSION_CODE.toLong()) {
            file.delete()
            throw Exception("Refusing APK downgrade")
        }

        val signers = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val cert = signers?.firstOrNull()?.toByteArray() ?: run {
            file.delete()
            throw Exception("APK has no signing certificate")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString("") { b -> "%02x".format(b) }
        if (actual == ReleaseTrust.LEAKED_SFA_CERT_SHA256) {
            file.delete()
            throw Exception("APK is signed with the leaked historical key")
        }
        if (actual != ReleaseTrust.CERT_SHA256) {
            file.delete()
            throw Exception("APK signing certificate is not the AngelaBox release key")
        }
    }

    private fun requireSha256(value: String): String {
        val expected = value.trim().lowercase()
        require(expected.matches(SHA256_HEX)) { "Missing or invalid APK SHA-256" }
        return expected
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
