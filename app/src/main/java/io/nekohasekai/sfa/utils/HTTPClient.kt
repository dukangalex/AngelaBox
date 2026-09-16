package io.nekohasekai.sfa.utils

import android.net.SSLCertificateSocketFactory
import io.nekohasekai.libbox.Libbox
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * App-layer HTTPS client. Does **not** use libbox's Go HTTP stack, which
 * follows redirects and ignores Android cleartext policy.
 *
 * Every hop is re-checked with [RemoteUrlGuard]. The TCP peer is the
 * already-validated IP ([ValidatedEndpoint]); TLS SNI / hostname
 * verification still use the original hostname. Authorization is dropped
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

        internal fun openPinned(
            url: String,
            kind: RemoteUrlGuard.Kind,
            headers: Map<String, String> = emptyMap(),
        ): HttpsURLConnection {
            val endpoint = RemoteUrlGuard.validate(url, kind)
            val addr = endpoint.addresses.firstOrNull()
                ?: throw IllegalArgumentException("无法解析主机，已拒绝")
            val pinned = requestUrlOnIp(url, addr, endpoint.port)
            val conn = URL(pinned).openConnection()
            require(conn is HttpsURLConnection) { "仅允许 HTTPS" }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Host", endpoint.host)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Connection", "close")
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            conn.sslSocketFactory = PinnedSniSslSocketFactory(endpoint.host, addr)
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, session)
            }
            return conn
        }

        /**
         * Dial the already-checked IP but keep the original percent-encoding.
         * GitHub release assets redirect to Azure SAS URLs; the 7-arg [URI]
         * constructor would re-encode `%3A` as `%253A` and Azure returns
         * HTTP 403 AuthenticationFailed.
         */
        internal fun requestUrlOnIp(url: String, addr: InetAddress, port: Int): String {
            val uri = URI(url)
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            val ip = literalIp(addr)
            val hostLiteral = if (':' in ip) "[$ip]" else ip
            val portPart = if (port != 443) ":$port" else ""
            val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
            return "https://$hostLiteral$portPart$path$query"
        }

        internal fun headersForHop(
            url: String,
            kind: RemoteUrlGuard.Kind,
            headers: Map<String, String>,
        ): Map<String, String> {
            val host = try {
                URI(url).host?.lowercase(Locale.US).orEmpty()
            } catch (_: Exception) {
                return headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }
            }
            // GitHub Bearer is only valid on api.github.com. Azure SAS
            // URLs treat it as a malformed Authorization header.
            if (kind == RemoteUrlGuard.Kind.UPDATE && host != "api.github.com") {
                return headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }
            }
            return headers
        }

        internal fun httpFailureMessage(kind: RemoteUrlGuard.Kind, code: Int): String {
            return when {
                kind == RemoteUrlGuard.Kind.UPDATE && code == 403 ->
                    "GitHub 发行包暂时无法下载。请点「查看发布」用浏览器安装，或稍后重试。"
                kind == RemoteUrlGuard.Kind.UPDATE && code == 404 ->
                    "未找到发行包。请点「查看发布」确认。"
                kind == RemoteUrlGuard.Kind.UPDATE ->
                    "GitHub 下载失败 HTTP $code。请点「查看发布」用浏览器安装。"
                else -> "下载失败 HTTP $code"
            }
        }

        internal fun literalIp(addr: InetAddress): String {
            var host = addr.hostAddress ?: throw IllegalArgumentException("无地址")
            val zone = host.indexOf('%')
            if (zone >= 0) host = host.substring(0, zone)
            return host
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
            hdrs = headersForHop(current, kind, hdrs)
            val conn = openPinned(current, kind, hdrs)
            conn.requestMethod = "GET"
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
                    throw IllegalStateException(httpFailureMessage(kind, code))
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

@Suppress("DEPRECATION")
private class PinnedSniSslSocketFactory(
    private val hostname: String,
    private val peer: InetAddress,
) : SSLSocketFactory() {
    private val delegate =
        SSLCertificateSocketFactory.getDefault(HTTPClient.CONNECT_TIMEOUT_MS, null)
            as SSLCertificateSocketFactory

    private fun pin(socket: Socket): Socket {
        delegate.setHostname(socket, hostname)
        return socket
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        pin(delegate.createSocket(s, hostname, port, autoClose))
    override fun createSocket(host: String, port: Int): Socket =
        pin(delegate.createSocket(peer, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        pin(delegate.createSocket(peer, port, localHost, localPort))
    override fun createSocket(address: InetAddress, port: Int): Socket =
        pin(delegate.createSocket(peer, port))
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = pin(delegate.createSocket(peer, port, localAddress, localPort))
}
