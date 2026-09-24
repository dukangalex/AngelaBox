package io.nekohasekai.sfa.utils

import android.net.SSLCertificateSocketFactory
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
 * Every hop is re-checked with [RemoteUrlGuard]. When the tunnel is down,
 * the TCP peer is the already-validated IP. When the tunnel is up, UPDATE
 * dials the hostname so the running proxy can route it by name.
 * Authorization is dropped when the host changes. Size and redirect counts
 * are capped.
 */
class HTTPClient : Closeable {
    companion object {
        const val MAX_SUBSCRIPTION_CHARS = 8 * 1024 * 1024
        const val MAX_UPDATE_CHARS = 2 * 1024 * 1024
        const val MAX_UPDATE_FILE_BYTES = 200L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val UPDATE_CONNECT_TIMEOUT_MS = 10_000
        const val UPDATE_DIRECT_CONNECT_TIMEOUT_MS = 6_000
        const val UPDATE_READ_TIMEOUT_MS = 60_000
        const val UPDATE_DIRECT_READ_TIMEOUT_MS = 12_000
        const val UPDATE_ATTEMPTS = 2

        val userAgent by lazy {
            var userAgent = "SFA (sing-box "
            userAgent += CoreIdentity.libboxOrPin()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }

        internal fun nextUrl(
            current: String,
            location: String?,
            kind: RemoteUrlGuard.Kind,
            resolveDns: Boolean = true,
            outsideTunnel: Boolean = false,
        ): String {
            require(!location.isNullOrBlank()) { "重定向缺少 Location" }
            val next = try {
                URI(current).resolve(location.trim()).toASCIIString()
            } catch (e: Exception) {
                throw IllegalArgumentException("非法重定向", e)
            }
            if (!resolveDns) {
                RemoteUrlGuard.validateWithoutDns(next, kind)
            } else if (outsideTunnel) {
                RemoteUrlGuard.validate(next, kind, resolve = RemoteUrlGuard::resolveOutsideTunnel)
            } else {
                RemoteUrlGuard.requireAllowed(next, kind)
            }
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
            bypassTunnel: Boolean = false,
        ): HttpsURLConnection {
            val endpoint = if (bypassTunnel) {
                RemoteUrlGuard.validate(url, kind, resolve = RemoteUrlGuard::resolveOutsideTunnel)
            } else {
                RemoteUrlGuard.validate(url, kind)
            }
            val addr = endpoint.addresses.firstOrNull()
                ?: throw IllegalArgumentException("无法解析主机，已拒绝")
            val pinned = requestUrlOnIp(url, addr, endpoint.port)
            val conn = URL(pinned).openConnection()
            require(conn is HttpsURLConnection) { "仅允许 HTTPS" }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeout(kind, viaTunnel = false)
            conn.readTimeout = readTimeout(kind, viaTunnel = false)
            conn.setRequestProperty("Host", endpoint.host)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Connection", "close")
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            conn.sslSocketFactory = PinnedSniSslSocketFactory(endpoint.host, addr, endpoint.port, bypassTunnel)
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, session)
            }
            return conn
        }

        internal fun openNamed(
            url: String,
            endpoint: ValidatedEndpoint,
            kind: RemoteUrlGuard.Kind,
            headers: Map<String, String>,
        ): HttpsURLConnection {
            val conn = URL(url).openConnection()
            require(conn is HttpsURLConnection) { "仅允许 HTTPS" }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeout(kind, viaTunnel = true)
            conn.readTimeout = readTimeout(kind, viaTunnel = true)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Connection", "close")
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, session)
            }
            return conn
        }

        internal fun connectTimeout(kind: RemoteUrlGuard.Kind, viaTunnel: Boolean): Int = when {
            kind == RemoteUrlGuard.Kind.UPDATE && viaTunnel -> UPDATE_CONNECT_TIMEOUT_MS
            kind == RemoteUrlGuard.Kind.UPDATE -> UPDATE_DIRECT_CONNECT_TIMEOUT_MS
            else -> CONNECT_TIMEOUT_MS
        }

        internal fun readTimeout(kind: RemoteUrlGuard.Kind, viaTunnel: Boolean): Int = when {
            kind == RemoteUrlGuard.Kind.UPDATE && viaTunnel -> UPDATE_READ_TIMEOUT_MS
            kind == RemoteUrlGuard.Kind.UPDATE -> UPDATE_DIRECT_READ_TIMEOUT_MS
            else -> READ_TIMEOUT_MS
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

        /** Proxy is up: dial the hostname so the tunnel routes by name, not a pre-resolved IP. */
        internal fun dialByName(kind: RemoteUrlGuard.Kind, tunnelUp: Boolean): Boolean {
            if (!tunnelUp) return false
            return kind == RemoteUrlGuard.Kind.UPDATE ||
                kind == RemoteUrlGuard.Kind.SUBSCRIPTION ||
                kind == RemoteUrlGuard.Kind.SCRIPT
        }

        /** First attempt may use the running node. A reset then retries outside the tunnel. */
        internal enum class Route { PROXY, DIRECT }

        internal fun openFor(
            url: String,
            kind: RemoteUrlGuard.Kind,
            headers: Map<String, String>,
            route: Route,
        ): HttpsURLConnection {
            if (route == Route.PROXY) {
                val endpoint = RemoteUrlGuard.validateWithoutDns(url, kind)
                return openNamed(url, endpoint, kind, headers)
            }
            return openPinned(url, kind, headers, bypassTunnel = TunnelGate.up)
        }

        internal fun explainProfileUpdate(error: Throwable): String =
            explainProfileUpdate(error, TunnelGate.up)

        internal fun explainProfileUpdate(error: Throwable, tunnelUp: Boolean): String {
            val messages = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            val chinese = messages.firstOrNull { msg ->
                msg.any { it.code in 0x4E00..0x9FFF } && !msg.startsWith("Failed to update")
            }
            if (chinese != null) return chinese.take(240)
            return if (tunnelUp) {
                "订阅没更新上。当前节点把连接断开了，不经过节点再试也失败。"
            } else {
                "订阅没更新上。直连被断开，请检查网络后再更新。"
            }
        }

        internal fun explainUpdateFailure(error: Throwable): String =
            explainUpdateFailure(error, TunnelGate.up)

        internal fun explainUpdateFailure(error: Throwable, tunnelUp: Boolean): String {
            val messages = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            val chinese = messages.firstOrNull { msg -> msg.any { it.code in 0x4E00..0x9FFF } }
            if (chinese != null) return chinese.take(240)
            return if (tunnelUp) {
                "更新没下完。当前代理把连接断开了，不经过节点再试也失败。可点「查看发布」。"
            } else {
                "更新没下完。直连更新服务器被断开。请检查网络，或点「查看发布」。"
            }
        }

        internal fun isTransientUpdateFailure(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                when (current) {
                    is java.net.SocketTimeoutException,
                    is java.net.SocketException,
                    is java.net.UnknownHostException,
                    is javax.net.ssl.SSLException,
                    -> return true
                }
                val raw = current.message.orEmpty()
                if (raw.contains("handshake", ignoreCase = true) ||
                    raw.contains("timed out", ignoreCase = true) ||
                    raw.contains("timeout", ignoreCase = true) ||
                    raw.contains("end of stream", ignoreCase = true) ||
                    raw.contains("return exception", ignoreCase = true) ||
                    raw.contains("reset", ignoreCase = true) ||
                    raw.contains("abort", ignoreCase = true) ||
                    raw.contains("unexpected end", ignoreCase = true)
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        internal fun literalIp(addr: InetAddress): String {
            var host = addr.hostAddress ?: throw IllegalArgumentException("无地址")
            val zone = host.indexOf('%')
            if (zone >= 0) host = host.substring(0, zone)
            return host
        }
    }

    var lastUserinfo: String? = null
        private set

    fun getString(
        url: String,
        kind: RemoteUrlGuard.Kind,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val max = maxChars(kind).toLong()
        lastUserinfo = null
        return withUpdateRetry(kind) { route ->
            fetch(url, kind, headers, max, route) { input, conn ->
                lastUserinfo = header(conn, "subscription-userinfo") ?: lastUserinfo
                val bytes = readLimited(input, max)
                String(bytes, Charsets.UTF_8)
            }
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
        withUpdateRetry(kind) { route ->
            fetch(url, kind, headers, max, route) { input, conn ->
                val total = conn.contentLengthLong
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
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
    }

    /**
     * One ladder for subscription, script, and app-update downloads.
     * Tunnel up: try the current node once. A reset, timeout, or handshake
     * failure retries outside the tunnel (protected socket, not fake-ip).
     * Neither path tells the user to switch nodes.
     */
    private fun <T> withUpdateRetry(kind: RemoteUrlGuard.Kind, block: (Route) -> T): T {
        val tunnel = dialByName(kind, TunnelGate.up)
        if (!tunnel) return block(Route.DIRECT)
        return try {
            block(Route.PROXY)
        } catch (e: Exception) {
            if (!isTransientUpdateFailure(e)) throw friendlyFetch(kind, e)
            try {
                block(Route.DIRECT)
            } catch (direct: Exception) {
                throw friendlyFetch(kind, direct)
            }
        }
    }

    private fun friendlyFetch(kind: RemoteUrlGuard.Kind, error: Exception): Exception {
        val message = when (kind) {
            RemoteUrlGuard.Kind.SUBSCRIPTION -> explainProfileUpdate(error)
            RemoteUrlGuard.Kind.UPDATE -> explainUpdateFailure(error)
            else -> return error
        }
        return IllegalStateException(message, error)
    }

    private fun <T> fetch(
        startUrl: String,
        kind: RemoteUrlGuard.Kind,
        headers: Map<String, String>,
        maxBytes: Long,
        route: Route,
        reader: (InputStream, HttpsURLConnection) -> T,
    ): T {
        var current = startUrl.trim()
        val byName = route == Route.PROXY
        if (byName) {
            RemoteUrlGuard.validateWithoutDns(current, kind)
        } else if (TunnelGate.up && dialByName(kind, true)) {
            RemoteUrlGuard.validate(current, kind, resolve = RemoteUrlGuard::resolveOutsideTunnel)
        } else {
            RemoteUrlGuard.requireAllowed(current, kind)
        }
        var hdrs = headers
        val seen = linkedSetOf<String>()
        repeat(MAX_REDIRECTS + 1) {
            require(seen.add(current)) { "重定向循环" }
            hdrs = headersForHop(current, kind, hdrs)
            val conn = openFor(current, kind, hdrs, route)
            conn.requestMethod = "GET"
            try {
                val code = conn.responseCode
                val info = header(conn, "subscription-userinfo")
                if (!info.isNullOrBlank()) lastUserinfo = info
                if (code in 300..399) {
                    val next = nextUrl(
                        current,
                        conn.getHeaderField("Location"),
                        kind,
                        resolveDns = !byName,
                        outsideTunnel = !byName && TunnelGate.up,
                    )
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

    private fun header(conn: HttpsURLConnection, name: String): String? {
        val direct = conn.getHeaderField(name)?.trim().orEmpty()
        if (direct.isNotEmpty()) return direct
        conn.headerFields?.forEach { (key, values) ->
            if (key != null && key.equals(name, ignoreCase = true)) {
                val hit = values?.firstOrNull()?.trim().orEmpty()
                if (hit.isNotEmpty()) return hit
            }
        }
        return null
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
    private val port: Int,
    private val bypassTunnel: Boolean,
) : SSLSocketFactory() {
    private val delegate =
        SSLCertificateSocketFactory.getDefault(HTTPClient.CONNECT_TIMEOUT_MS, null)
            as SSLCertificateSocketFactory

    private fun pin(socket: Socket): Socket {
        delegate.setHostname(socket, hostname)
        return socket
    }

    /** Protect before connect so a dead node cannot reset the direct retry. */
    private fun connectOutside(): Socket {
        val raw = Socket()
        if (bypassTunnel) DirectDial.protect(raw)
        raw.connect(java.net.InetSocketAddress(peer, port), HTTPClient.UPDATE_DIRECT_CONNECT_TIMEOUT_MS)
        val ssl = delegate.createSocket(raw, hostname, port, true)
        return pin(ssl)
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        if (!bypassTunnel) return pin(delegate.createSocket(s, hostname, port, autoClose))
        if (autoClose) runCatching { s.close() }
        return connectOutside()
    }
    override fun createSocket(host: String, port: Int): Socket =
        if (bypassTunnel) connectOutside() else pin(delegate.createSocket(peer, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        if (bypassTunnel) connectOutside() else pin(delegate.createSocket(peer, port, localHost, localPort))
    override fun createSocket(address: InetAddress, port: Int): Socket =
        if (bypassTunnel) connectOutside() else pin(delegate.createSocket(peer, port))
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = if (bypassTunnel) {
        connectOutside()
    } else {
        pin(delegate.createSocket(peer, port, localAddress, localPort))
    }
}
