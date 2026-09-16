package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupManagerTest {

    @Test
    fun probeRejectsCleartext() {
        val result = BackupManager.webdavProbe("http://example.com/dav/", "u", "p")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTPS") == true)
    }

    @Test
    fun sslErrorsGetFriendlyHint() {
        val raw = RuntimeException(
            "javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
        )
        val wrapped = BackupManager.friendlySsl(raw)
        val msg = wrapped.message.orEmpty()
        assertTrue(msg.contains("证书校验失败"))
        assertTrue(msg.contains("按分流"))
        assertFalse(msg.contains("绕过 VPN"))
    }

    @Test
    fun zipMagicDetection() {
        val tmp = File.createTempFile("cb-zip", ".bin")
        tmp.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        assertTrue(BackupManager.isZipFile(tmp))
        tmp.writeText("<html>error</html>")
        assertFalse(BackupManager.isZipFile(tmp))
        tmp.delete()
    }

    @Test
    fun probeTreats401AsAuthFailure() {
        assertEquals(BackupManager.ProbeClass.AUTH, BackupManager.classifyProbe(401))
        assertEquals(BackupManager.ProbeClass.AUTH, BackupManager.classifyProbe(403))
        assertEquals(BackupManager.ProbeClass.OK, BackupManager.classifyProbe(207))
        assertEquals(BackupManager.ProbeClass.OK, BackupManager.classifyProbe(200))
        assertEquals(BackupManager.ProbeClass.MISS, BackupManager.classifyProbe(404))
        assertEquals(BackupManager.ProbeClass.MISS, BackupManager.classifyProbe(405))
        val msg = BackupManager.authFailedMessage("https://app.koofr.net/dav/Koofr", 401, "Unauthorized")
        assertTrue(msg.contains("认证失败"))
        assertTrue(msg.contains("Koofr"))
        val hidden = BackupManager.friendlyProbeDetail(
            Exception("Expected one of [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH] but was PROPFIND"),
        )
        assertFalse(hidden.contains("PROPFIND"))
        assertTrue(hidden.contains("不受支持"))
    }

    @Test
    fun probeRejectsMetadataHost() {
        val result = BackupManager.webdavProbe("https://169.254.169.254/latest/meta-data", "u", "p")
        assertTrue(result.isFailure)
    }

    @Test
    fun probeRejectsLoopbackHttps() {
        val result = BackupManager.webdavProbe("https://127.0.0.1/dav/", "u", "p")
        assertTrue(result.isFailure)
    }

    @Test
    fun probeRejectsRfc1918Https() {
        val result = BackupManager.webdavProbe("https://192.168.1.8/dav/", "u", "p")
        assertTrue(result.isFailure)
    }
}

