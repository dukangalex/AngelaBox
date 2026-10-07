package io.nekohasekai.sfa.utils

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Reads the first X.509 certificate from an APK Signing Block (v2, then v3).
 *
 * [android.content.pm.PackageManager.getPackageArchiveInfo] with
 * `GET_SIGNING_CERTIFICATES` often returns an empty [android.content.pm.SigningInfo]
 * for a file that is not yet installed, especially on Samsung One UI. The
 * in-app updater therefore cannot trust PackageManager for an unverified
 * download. This parser matches [scripts/verify_apk_signature.py], which the
 * release job already uses.
 */
object ApkSigningCerts {
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.UTF_8)
    private const val V2_ID = 0x7109871A
    // 0xF05368C0 does not fit in a Kotlin Int literal.
    private val V3_ID = 0xF05368C0.toInt()
    private const val EOCD_SCAN = 65557

    fun firstCertDer(file: File): ByteArray? = extractCerts(file).firstOrNull()

    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { b -> "%02x".format(b) }

    fun extractCerts(file: File): List<ByteArray> {
        if (!file.isFile || file.length() < 32L) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val eocd = findEocd(raf) ?: return emptyList()
                raf.seek(eocd + 16)
                val cdOff = raf.readU32le()
                if (cdOff < 32L || cdOff > raf.length()) return emptyList()
                if (cdOff < 24L) return emptyList()
                raf.seek(cdOff - 24)
                val footer = ByteArray(24)
                if (raf.read(footer) != 24) return emptyList()
                if (!footer.copyOfRange(8, 24).contentEquals(MAGIC)) return emptyList()
                val size2 = u64(footer, 0)
                val blockStart = cdOff - 8 - size2
                if (blockStart < 0L || blockStart + 8 > raf.length()) return emptyList()
                raf.seek(blockStart)
                val size1 = raf.readU64le()
                if (size1 != size2) return emptyList()
                val pairsLen = (cdOff - 24 - (blockStart + 8)).toInt()
                if (pairsLen <= 0 || pairsLen > 8 * 1024 * 1024) return emptyList()
                val pairs = ByteArray(pairsLen)
                if (raf.read(pairs) != pairsLen) return emptyList()
                certsFromPairs(pairs)
            }
        }.getOrDefault(emptyList())
    }

    internal fun certsFromPairs(pairs: ByteArray): List<ByteArray> {
        val v2 = mutableListOf<ByteArray>()
        val v3 = mutableListOf<ByteArray>()
        var off = 0
        while (off + 12 <= pairs.size) {
            val pairLen = u64(pairs, off)
            off += 8
            if (pairLen < 4L || off + pairLen > pairs.size) break
            val pairId = u32(pairs, off)
            val value = pairs.copyOfRange(off + 4, off + pairLen.toInt())
            off += pairLen.toInt()
            when (pairId) {
                V2_ID, V3_ID -> {
                    val found = certsFromSigners(value)
                    if (pairId == V2_ID) v2 += found else v3 += found
                }
            }
        }
        return if (v2.isNotEmpty()) v2 else v3
    }

    private fun certsFromSigners(value: ByteArray): List<ByteArray> {
        if (value.size < 4) return emptyList()
        var so = 0
        val signersLen = u32(value, so)
        so += 4
        val signersEnd = (so + signersLen).coerceAtMost(value.size)
        val certs = mutableListOf<ByteArray>()
        while (so + 4 <= signersEnd) {
            val signerLen = u32(value, so)
            so += 4
            if (signerLen < 0 || so + signerLen > signersEnd) break
            val signer = value.copyOfRange(so, so + signerLen)
            so += signerLen
            if (signer.size < 4) continue
            val sdLen = u32(signer, 0)
            if (sdLen < 0 || 4 + sdLen > signer.size) continue
            val signed = signer.copyOfRange(4, 4 + sdLen)
            if (signed.size < 4) continue
            val digestsLen = u32(signed, 0)
            var co = 4 + digestsLen
            if (co + 4 > signed.size) continue
            val certsLen = u32(signed, co)
            co += 4
            val certsEnd = (co + certsLen).coerceAtMost(signed.size)
            while (co + 4 <= certsEnd) {
                val certLen = u32(signed, co)
                co += 4
                if (certLen < 0 || co + certLen > certsEnd) break
                certs += signed.copyOfRange(co, co + certLen)
                co += certLen
            }
        }
        return certs
    }

    internal fun findEocd(raf: RandomAccessFile): Long? {
        val len = raf.length()
        if (len < 22L) return null
        val scan = minOf(len, EOCD_SCAN.toLong()).toInt()
        val buf = ByteArray(scan)
        raf.seek(len - scan)
        if (raf.read(buf) != scan) return null
        for (i in scan - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() &&
                buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
            ) {
                return len - scan + i
            }
        }
        return null
    }

    private fun u32(buf: ByteArray, off: Int): Int {
        if (off + 4 > buf.size) return -1
        return (buf[off].toInt() and 0xff) or
            ((buf[off + 1].toInt() and 0xff) shl 8) or
            ((buf[off + 2].toInt() and 0xff) shl 16) or
            ((buf[off + 3].toInt() and 0xff) shl 24)
    }

    private fun u64(buf: ByteArray, off: Int): Long {
        if (off + 8 > buf.size) return -1L
        val lo = u32(buf, off).toLong() and 0xffffffffL
        val hi = u32(buf, off + 4).toLong() and 0xffffffffL
        return lo or (hi shl 32)
    }

    private fun RandomAccessFile.readU32le(): Long {
        val b = ByteArray(4)
        if (read(b) != 4) return -1L
        return u32(b, 0).toLong() and 0xffffffffL
    }

    private fun RandomAccessFile.readU64le(): Long {
        val b = ByteArray(8)
        if (read(b) != 8) return -1L
        return u64(b, 0)
    }
}
