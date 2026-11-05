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
        val raw =
            RuntimeException(
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
        val hidden =
            BackupManager.friendlyProbeDetail(
                Exception("Expected one of [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH] but was PROPFIND"),
            )
        assertFalse(hidden.contains("PROPFIND"))
        assertTrue(hidden.contains("不受支持"))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun pbkdf2MatchesPublishedVectors() {
        val password = "password".toByteArray(Charsets.UTF_8)
        val salt = "salt".toByteArray(Charsets.UTF_8)
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(BackupCrypto.pbkdf2(password, salt, 1, 32)),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(BackupCrypto.pbkdf2(password, salt, 2, 32)),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(BackupCrypto.pbkdf2(password, salt, 4096, 32)),
        )
    }

    @Test
    fun encryptedBackupRoundTripsByteIdentical() {
        val plain = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "fake-zip-body".toByteArray()
        val sealed = BackupCrypto.encrypt(plain, "correct horse".toCharArray(), iterations = 1_000)
        assertTrue(BackupCrypto.isEncrypted(sealed))
        assertFalse(BackupCrypto.isEncrypted(plain))
        val restored = BackupCrypto.decrypt(sealed, "correct horse".toCharArray())
        assertTrue(plain.contentEquals(restored))
    }

    @Test
    fun encryptedBackupRejectsWrongPassword() {
        val sealed = BackupCrypto.encrypt("payload".toByteArray(), "right".toCharArray(), iterations = 1_000)
        val failure =
            try {
                BackupCrypto.decrypt(sealed, "wrong".toCharArray())
                null
            } catch (e: BackupCrypto.BackupCryptoException) {
                e.failure
            }
        assertEquals(BackupCrypto.Failure.DECRYPT_FAILED, failure)
    }

    @Test
    fun encryptedBackupRejectsTampering() {
        val sealed = BackupCrypto.encrypt("payload".toByteArray(), "pw".toCharArray(), iterations = 1_000)
        val flippedCipher = sealed.copyOf()
        flippedCipher[flippedCipher.lastIndex] =
            (flippedCipher[flippedCipher.lastIndex].toInt() xor 0x01).toByte()
        val flippedSalt = sealed.copyOf()
        flippedSalt[12] = (flippedSalt[12].toInt() xor 0x01).toByte()
        for (bad in listOf(flippedCipher, flippedSalt)) {
            val failure =
                try {
                    BackupCrypto.decrypt(bad, "pw".toCharArray())
                    null
                } catch (e: BackupCrypto.BackupCryptoException) {
                    e.failure
                }
            assertEquals(BackupCrypto.Failure.DECRYPT_FAILED, failure)
        }
    }

    @Test
    fun decryptRejectsPlainZipAsNotEncrypted() {
        val plain = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
        val failure =
            try {
                BackupCrypto.decrypt(plain, "pw".toCharArray())
                null
            } catch (e: BackupCrypto.BackupCryptoException) {
                e.failure
            }
        assertEquals(BackupCrypto.Failure.NOT_ENCRYPTED, failure)
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

    @Test
    fun localBackupCopyAcceptsExactLimit() {
        val file = File.createTempFile("backup-copy", ".zip")
        try {
            val bytes = ByteArray(16) { it.toByte() }
            BackupFileIO.copyToFile(bytes.inputStream(), file, 16)
            assertTrue(bytes.contentEquals(file.readBytes()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun localBackupCopyRejectsOverflowAndDeletesPartial() {
        val file = File.createTempFile("backup-copy", ".zip")
        try {
            val result =
                runCatching {
                    BackupFileIO.copyToFile(ByteArray(17).inputStream(), file, 16)
                }
            assertTrue(result.isFailure)
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun localBackupCopyDeletesPartialAfterReadFailure() {
        val file = File.createTempFile("backup-copy", ".zip")
        val input =
            object : java.io.InputStream() {
                var calls = 0

                override fun read(): Int = error("unused")

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (calls++ > 0) throw java.io.IOException("document provider failed")
                    bytes[offset] = 1
                    return 1
                }
            }
        try {
            assertTrue(runCatching { BackupFileIO.copyToFile(input, file, 16) }.isFailure)
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun backupInputLengthRejectsOversize() {
        val file = File.createTempFile("backup-limit", ".bin")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(128L * 1024 * 1024 + 1) }
            assertTrue(runCatching { BackupFileIO.requireWithinLimit(file) }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun backupCopyDeletesRejectedContent() {
        val file = File.createTempFile("backup-invalid", ".zip")
        try {
            val result =
                runCatching {
                    BackupFileIO.copyToFile("not a backup".byteInputStream(), file) {
                        error("invalid archive")
                    }
                }
            assertTrue(result.isFailure)
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun backupCopyValidatesCompleteFileAndRetainsAcceptedContent() {
        val file = File.createTempFile("backup-valid", ".zip")
        try {
            val bytes = "synthetic archive".toByteArray()
            var validated = false
            BackupFileIO.copyToFile(bytes.inputStream(), file) { copied ->
                assertTrue(bytes.contentEquals(copied.readBytes()))
                validated = true
            }
            assertTrue(validated)
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }
}
