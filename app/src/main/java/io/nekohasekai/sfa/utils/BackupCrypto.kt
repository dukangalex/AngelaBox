package io.nekohasekai.sfa.utils

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption for whole backup archives (AB-021).
 *
 * The plain backup zip is encrypted as a single byte stream, so node
 * passwords and UUIDs inside configs stop travelling in clear text.
 * Pure JVM (javax.crypto only, no Android imports) so the round trip is
 * unit-testable on a desktop JVM.
 *
 * Container layout, version 1:
 *   bytes 0..3   magic "ABKE"
 *   byte  4      format version (1)
 *   bytes 5..8   PBKDF2 iteration count, unsigned big-endian
 *   byte  9      salt length (16), followed by the salt
 *   next byte    IV length (12), followed by the IV
 *   rest         AES-256-GCM ciphertext with the 128-bit tag appended
 * Every header byte before the ciphertext is fed to GCM as AAD, so
 * tampering with the magic, version, iteration count, salt or IV fails
 * authentication exactly like a wrong password does: decryption refuses
 * with no partial plaintext ever produced.
 *
 * PBKDF2 is implemented by hand over HmacSHA256. The JCA factory name
 * PBKDF2WithHmacSHA256 only exists on API 26+, while the app supports
 * API 24; HmacSHA256 itself is available everywhere. Password characters
 * are encoded as UTF-8 before key derivation; both sides always use this
 * implementation, so derivation is identical on every device.
 */
object BackupCrypto {

    const val DEFAULT_ITERATIONS: Int = 210_000

    /**
     * Highest iteration count [decrypt] will honour from a container
     * header: 10x [DEFAULT_ITERATIONS], far above anything [encrypt]
     * writes, so a crafted header cannot make decryption burn unbounded
     * CPU in PBKDF2 before GCM authentication gets to reject it.
     */
    private const val MAX_ITERATIONS: Int = 2_100_000

    private val MAGIC = byteArrayOf(0x41, 0x42, 0x4B, 0x45) // "ABKE"
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val FIXED_HEADER = 10 // magic + version + iterations + salt length byte
    private const val MIN_CONTAINER = FIXED_HEADER + SALT_BYTES + 1 + IV_BYTES + TAG_BITS / 8

    enum class Failure { NOT_ENCRYPTED, UNSUPPORTED_VERSION, DECRYPT_FAILED }

    class BackupCryptoException(val failure: Failure) : Exception("backup crypto failure: $failure")

    /** True when [data] starts with the container magic and version. */
    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < MAGIC.size + 1) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return data[MAGIC.size] == VERSION
    }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val header = ByteArrayOutputStream()
        header.write(MAGIC)
        header.write(VERSION.toInt())
        header.write(
            byteArrayOf(
                (iterations ushr 24).toByte(),
                (iterations ushr 16).toByte(),
                (iterations ushr 8).toByte(),
                iterations.toByte(),
            ),
        )
        header.write(SALT_BYTES)
        header.write(salt)
        header.write(IV_BYTES)
        header.write(iv)
        val headerBytes = header.toByteArray()

        val passwordBytes = utf8(password)
        val key = pbkdf2(passwordBytes, salt, iterations, KEY_BYTES)
        passwordBytes.fill(0)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(headerBytes)
            val sealed = cipher.doFinal(plain)
            return headerBytes + sealed
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts a container produced by [encrypt]. Any authentication
     * problem — wrong password, flipped bit, truncated file — throws
     * [BackupCryptoException] with [Failure.DECRYPT_FAILED]; GCM cannot
     * (and must not) tell a wrong password apart from corruption, and no
     * partial plaintext is ever returned.
     */
    fun decrypt(container: ByteArray, password: CharArray): ByteArray {
        if (container.size < MAGIC.size + 1) {
            throw BackupCryptoException(Failure.NOT_ENCRYPTED)
        }
        for (i in MAGIC.indices) {
            if (container[i] != MAGIC[i]) throw BackupCryptoException(Failure.NOT_ENCRYPTED)
        }
        if (container[MAGIC.size] != VERSION) {
            throw BackupCryptoException(Failure.UNSUPPORTED_VERSION)
        }
        if (container.size < MIN_CONTAINER) {
            throw BackupCryptoException(Failure.DECRYPT_FAILED)
        }
        var offset = MAGIC.size + 1
        val iterations = ((container[offset].toInt() and 0xFF) shl 24) or
            ((container[offset + 1].toInt() and 0xFF) shl 16) or
            ((container[offset + 2].toInt() and 0xFF) shl 8) or
            (container[offset + 3].toInt() and 0xFF)
        offset += 4
        if (iterations <= 0) throw BackupCryptoException(Failure.DECRYPT_FAILED)
        if (iterations > MAX_ITERATIONS) throw BackupCryptoException(Failure.DECRYPT_FAILED)
        val saltLen = container[offset].toInt() and 0xFF
        offset += 1
        if (saltLen <= 0 || offset + saltLen + 1 > container.size) {
            throw BackupCryptoException(Failure.DECRYPT_FAILED)
        }
        val salt = container.copyOfRange(offset, offset + saltLen)
        offset += saltLen
        val ivLen = container[offset].toInt() and 0xFF
        offset += 1
        if (ivLen <= 0 || offset + ivLen > container.size) {
            throw BackupCryptoException(Failure.DECRYPT_FAILED)
        }
        val iv = container.copyOfRange(offset, offset + ivLen)
        offset += ivLen
        val headerBytes = container.copyOfRange(0, offset)
        val sealed = container.copyOfRange(offset, container.size)

        val passwordBytes = utf8(password)
        val key = pbkdf2(passwordBytes, salt, iterations, KEY_BYTES)
        passwordBytes.fill(0)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(headerBytes)
            return cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw BackupCryptoException(Failure.DECRYPT_FAILED)
        } finally {
            key.fill(0)
        }
    }

    /** RFC 2898 PBKDF2 with HmacSHA256 as the PRF. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations > 0 && dkLen > 0) { "invalid PBKDF2 parameters" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val derived = ByteArray(dkLen)
        var produced = 0
        for (block in 1..blocks) {
            mac.update(salt)
            var u = mac.doFinal(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte(),
                ),
            )
            val t = u.copyOf()
            var round = 1
            while (round < iterations) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                round++
            }
            val take = minOf(hLen, dkLen - produced)
            System.arraycopy(t, 0, derived, produced, take)
            produced += take
            t.fill(0)
            u.fill(0)
        }
        return derived
    }

    private fun utf8(chars: CharArray): ByteArray = String(chars).toByteArray(Charsets.UTF_8)
}
