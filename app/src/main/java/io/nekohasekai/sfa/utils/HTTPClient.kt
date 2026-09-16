package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * App-layer HTTPS client. Does **not** use libbox's Go HTTP stack, which
 * follows redirects and ignores Android cleartext policy.
 *
 * Every hop is re-checked with [RemoteUrlGuard]. Authorization is dropped
 * when the host changes. Size and redirect counts are capped.
 */
class HTTPClient : Closeable {
    companion object {
        const val MAX_SUBSCRIPTION_CHARS = 8 * 1024 * 1024
        const val MAX_UPDATE_CHARS = 2 * 1024 * 1024
        const val MAX_UPDATE_FILE_BYTES = 200L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        val userAgent by lazy {
            var userAgent = "SFA (sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }

        internal fun nextUrl(current: String, location: String?, kind: RemoteUrlGuard.Kind): String {
            require(!location.isNullOrBlank()) { "重定向缺少 Location" }
            val next = try {
                URI(current).resolve(location.trim()).toASCIIString()
            } catch (e: Exception) {
                throw IllegalArgumentException("非法重定向", e)
            }
            RemoteUrlGuard.requireAllowed(next, kind)
            return next
        }

        internal fun sameHost(a: String, b: String): Boolean {
            return try {
                val ha = URI(a).host?.lowercase(Locale.US)
                val hb = URI(b).host?.lowercase(Locale.US)
                !ha.isNullOrEmpty() && ha == hb
            } catch (_: Exception) {
                false
            }
        }

        internal fun maxChars(kind: RemoteUrlGuard.Kind): Int = when (kind) {
            RemoteUrlGuard.Kind.SCRIPT -> OverlayScripts.MAX_CODE_CHARS
            RemoteUrlGuard.Kind.UPDATE -> MAX_UPDATE_CHARS
            RemoteUrlGuard.Kind.SUBSCRIPTION -> MAX_SUBSCRIPTION_CHARS
        }
    }

    fun getString(
        url: String,
        kind: RemoteUrlGuard.Kind,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val max = maxChars(kind).toLong()
        return fetch(url, kind, headers, max) { input, _ ->
            val bytes = readLimited(input, max)
            String(bytes, Charsets.UTF_8)
        }
    }

    fun downloadToFile(
        url: String,
        kind: RemoteUrlGuard.Kind,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        val max = if (kind == RemoteUrlGuard.Kind.UPDATE) MAX_UPDATE_FILE_BYTES else maxChars(kind).toLong()
        fetch(url, kind, headers, max) { input, conn ->
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    written += n
                    if (written > max) {
                        dest.delete()
                        throw IllegalStateException("下载内容过大（>${max} 字节）")
                    }
                    output.write(buf, 0, n)
                    onProgress?.invoke(written, total)
                }
            }
            if (!dest.exists() || dest.length() == 0L) {
                dest.delete()
                throw IllegalStateException("下载失败：空文件")
            }
            dest
        }
    }

    private fun <T> fetch(
        startUrl: String,
        kind: RemoteUrlGuard.Kind,
        headers: Map<String, String>,
        maxBytes: Long,
        reader: (InputStream, HttpsURLConnection) -> T,
    ): T {
        var current = startUrl.trim()
        RemoteUrlGuard.requireAllowed(current, kind)
        var hdrs = headers
        val seen = linkedSetOf<String>()
        repeat(MAX_REDIRECTS + 1) {
            require(seen.add(current)) { "重定向循环" }
            val conn = open(current, hdrs)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = nextUrl(current, conn.getHeaderField("Location"), kind)
                    if (!sameHost(current, next)) {
                        hdrs = hdrs.filterKeys { !it.equals("Authorization", ignoreCase = true) }
                    }
                    current = next
                    return@repeat
                }
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                    throw IllegalStateException("下载失败 HTTP $code${err?.let { ": $it" } ?: ""}")
                }
                val declared = conn.contentLengthLong
                if (declared > maxBytes) {
                    throw IllegalStateException("下载内容过大（>${maxBytes} 字节）")
                }
                return conn.inputStream.use { input -> reader(input, conn) }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("重定向次数过多")
    }

    private fun open(url: String, headers: Map<String, String>): HttpsURLConnection {
        val conn = URL(url).openConnection()
        require(conn is HttpsURLConnection) { "仅允许 HTTPS" }
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Connection", "close")
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        conn.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        return conn
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalStateException("下载内容过大（>${maxBytes} 字符）")
            }
            output.write(buf, 0, n)
        }
        return output.toByteArray()
    }

    override fun close() {
        // Per-request connections are disconnected in fetch().
    }
}
