package io.nekohasekai.sfa.vendor

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.ApkSigningCerts
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteUrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

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
            throw Exception("下载失败：安装包为空")
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
            throw Exception("安装包 SHA-256 不一致（期望 $expected，实际 $actual）")
        }
    }

    /**
     * PackageManager.getPackageArchiveInfo(GET_SIGNING_CERTIFICATES) often
     * returns an empty SigningInfo for a file that is not yet installed
     * (Samsung One UI in particular). SHA-256 of the file already matched
     * the GitHub sidecar; the certificate pin must still be read from the
     * APK Signing Block the same way CI does.
     */
    private fun verifyReleaseIdentity(file: File) {
        val cert = signingCertDer(file) ?: run {
            file.delete()
            throw Exception("安装包没有签名证书")
        }
        val actual = ApkSigningCerts.sha256Hex(cert)
        if (actual == ReleaseTrust.LEAKED_SFA_CERT_SHA256) {
            file.delete()
            throw Exception("安装包使用了已公开的历史密钥签名")
        }
        if (actual != ReleaseTrust.CERT_SHA256) {
            file.delete()
            throw Exception("安装包签名证书不是 AngelaBox 发行密钥")
        }

        val info = packageArchiveInfo(file, wantSigning = false) ?: return
        if (info.packageName != ReleaseTrust.PACKAGE_NAME) {
            file.delete()
            throw Exception("安装包名称与 AngelaBox 不符")
        }
        val apkCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        if (apkCode < BuildConfig.VERSION_CODE.toLong()) {
            file.delete()
            throw Exception("拒绝安装更低版本")
        }
    }

    private fun signingCertDer(file: File): ByteArray? {
        ApkSigningCerts.firstCertDer(file)?.let { return it }
        val info = packageArchiveInfo(file, wantSigning = true) ?: return null
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo
            signing?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { return it }
            signing?.signingCertificateHistory?.firstOrNull()?.toByteArray()?.let { return it }
        }
        @Suppress("DEPRECATION")
        return info.signatures?.firstOrNull()?.toByteArray()
    }

    private fun packageArchiveInfo(file: File, wantSigning: Boolean): PackageInfo? {
        val pm = Application.application.packageManager
        val flags = if (!wantSigning) {
            0
        } else if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return when {
            Build.VERSION.SDK_INT >= 33 -> pm.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
            else -> {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, flags)
            }
        }
    }

    private fun requireSha256(value: String): String {
        val expected = value.trim().lowercase()
        require(expected.matches(SHA256_HEX)) { "安装包缺少有效的 SHA-256 校验和" }
        return expected
    }

    override fun close() {
        client.close()
    }

    companion object {
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun sha256Hex(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
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
