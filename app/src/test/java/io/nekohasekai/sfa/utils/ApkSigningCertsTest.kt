package io.nekohasekai.sfa.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApkSigningCertsTest {
    @Test
    fun certsFromPairsPrefersV2OverV3() {
        val v2 = "v2-cert".toByteArray()
        val v3 = "v3-cert".toByteArray()
        val pairs = concat(
            pair(0x7109871A, signerValue(v2)),
            pair(0xF05368C0.toInt(), signerValue(v3)),
        )
        val found = ApkSigningCerts.certsFromPairs(pairs)
        assertEquals(1, found.size)
        assertArrayEquals(v2, found[0])
    }

    @Test
    fun certsFromPairsFallsBackToV3() {
        val v3 = "only-v3".toByteArray()
        val pairs = pair(0xF05368C0.toInt(), signerValue(v3))
        val found = ApkSigningCerts.certsFromPairs(pairs)
        assertEquals(1, found.size)
        assertArrayEquals(v3, found[0])
    }

    @Test
    fun extractCertFromSyntheticApk() {
        val cert = ByteArray(48) { i -> (i * 3).toByte() }
        val apk = File.createTempFile("angela-sign", ".apk")
        try {
            writeSyntheticApk(apk, cert)
            val found = ApkSigningCerts.firstCertDer(apk)
            assertArrayEquals(cert, found)
            assertEquals(64, ApkSigningCerts.sha256Hex(found!!).length)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun missingMagicYieldsNoCert() {
        val apk = File.createTempFile("angela-nosig", ".apk")
        try {
            apk.writeBytes("PK\u0005\u0006".toByteArray() + ByteArray(18))
            assertTrue(ApkSigningCerts.extractCerts(apk).isEmpty())
            assertNull(ApkSigningCerts.firstCertDer(apk))
        } finally {
            apk.delete()
        }
    }

    private fun writeSyntheticApk(file: File, cert: ByteArray) {
        val pairs = pair(0x7109871A, signerValue(cert))
        val size = pairs.size.toLong() + 24L
        val block = ByteArray(pairs.size + 32)
        putU64(block, 0, size)
        System.arraycopy(pairs, 0, block, 8, pairs.size)
        putU64(block, 8 + pairs.size, size)
        val magic = "APK Sig Block 42".toByteArray()
        System.arraycopy(magic, 0, block, 16 + pairs.size, 16)
        val prefix = ByteArray(16) { 0x11 }
        val cdOff = prefix.size + block.size
        val eocd = ByteArray(22)
        eocd[0] = 0x50
        eocd[1] = 0x4b
        eocd[2] = 0x05
        eocd[3] = 0x06
        putU32(eocd, 16, cdOff.toLong())
        file.writeBytes(prefix + block + eocd)
    }

    private fun signerValue(cert: ByteArray): ByteArray {
        val digests = u32pref(ByteArray(0))
        val certs = u32pref(u32pref(cert))
        val signed = u32pref(digests + certs)
        val signer = u32pref(signed)
        return u32pref(signer)
    }

    private fun pair(id: Int, value: ByteArray): ByteArray {
        val body = ByteArray(4 + value.size)
        putU32(body, 0, id.toLong() and 0xffffffffL)
        System.arraycopy(value, 0, body, 4, value.size)
        val out = ByteArray(8 + body.size)
        putU64(out, 0, body.size.toLong())
        System.arraycopy(body, 0, out, 8, body.size)
        return out
    }

    private fun u32pref(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        putU32(out, 0, payload.size.toLong())
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val size = chunks.sumOf { it.size }
        val out = ByteArray(size)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, off, c.size)
            off += c.size
        }
        return out
    }

    private fun putU32(buf: ByteArray, off: Int, value: Long) {
        ByteBuffer.wrap(buf, off, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
    }

    private fun putU64(buf: ByteArray, off: Int, value: Long) {
        ByteBuffer.wrap(buf, off, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
    }
}
