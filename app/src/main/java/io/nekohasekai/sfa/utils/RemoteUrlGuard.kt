package io.nekohasekai.sfa.utils

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

data class ValidatedEndpoint(
    val url: String,
    val host: String,
    val port: Int,
    val addresses: List<InetAddress>,
)

/**
 * SSRF / fetch policy for every URL the app downloads.
 *
 * All kinds require HTTPS. Loopback, RFC1918, ULA, CGNAT, link-local,
 * multicast, and cloud metadata are blocked. [Kind.UPDATE] is additionally
 * pinned to GitHub. DNS results must all be allowed (mixed public+private
 * is rejected). Callers that open a socket must use [ValidatedEndpoint.addresses]
 * so the checked IP is the dialed IP.
 */
object RemoteUrlGuard {
    enum class Kind { SUBSCRIPTION, SCRIPT, UPDATE }

    private val UPDATE_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "codeload.github.com",
    )

    private val BLOCKED_HOSTS = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback",
        "metadata.google.internal",
        "metadata.goog",
        "metadata",
        "instance-data",
    )

    private val PUBLIC_PLACEHOLDER: (String) -> List<InetAddress> = {
        listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))
    }

    fun requireAllowed(url: String, kind: Kind, resolve: (String) -> List<InetAddress> = ::systemResolve) {
        validate(url, kind, resolve, requireResolved = kind != Kind.UPDATE)
    }

    fun validate(
        url: String,
        kind: Kind,
        resolve: (String) -> List<InetAddress> = ::systemResolve,
        requireResolved: Boolean = true,
    ): ValidatedEndpoint {
        val raw = url.trim()
        require(raw.isNotEmpty()) { "URL 为空" }
        require(' ' !in raw && '\n' !in raw && '\r' !in raw && '\t' !in raw) { "URL 含非法空白" }

        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("无法解析 URL", e)
        }
        require(uri.userInfo.isNullOrEmpty()) { "URL 不能包含用户名密码" }

        val scheme = uri.scheme?.lowercase(Locale.US) ?: throw IllegalArgumentException("URL 缺少协议")
        require(scheme == "https") { "仅允许 HTTPS" }

        val host = normalizeHost(uri.host ?: throw IllegalArgumentException("URL 缺少主机"))
        require(host.isNotEmpty()) { "URL 缺少主机" }
        require(host !in BLOCKED_HOSTS) { "禁止访问本地或元数据地址" }

        if (kind == Kind.UPDATE) {
            require(host in UPDATE_HOSTS) { "更新地址必须来自 GitHub" }
        }

        val port = if (uri.port > 0) uri.port else 443

        if (isLiteralIp(host)) {
            val addr = parseLiteral(host) ?: throw IllegalArgumentException("非法 IP 地址")
            require(isAddressAllowed(addr, kind)) { "禁止访问本地、链路本地或云元数据地址" }
            return ValidatedEndpoint(raw, host, port, listOf(addr))
        }

        val resolved = try {
            resolve(host)
        } catch (_: Exception) {
            emptyList()
        }
        if (resolved.isEmpty()) {
            if (!requireResolved) {
                return ValidatedEndpoint(raw, host, port, emptyList())
            }
            throw IllegalArgumentException("无法解析主机，已拒绝")
        }
        require(resolved.all { isAddressAllowed(it, kind) }) {
            "主机解析到禁止地址，已拒绝"
        }
        return ValidatedEndpoint(raw, host, port, resolved)
    }

    /**
     * Syntactic HTTPS + public-host check for URLs the kernel fetches
     * (remote rule-sets). Hostnames skip DNS so offline start still works;
     * literal IPs still use the SCRIPT fail-closed policy.
     */
    fun requireHttpsPublic(url: String) {
        requireAllowed(url, Kind.SCRIPT, PUBLIC_PLACEHOLDER)
    }

    fun isPublicHttpsUrl(url: String): Boolean = try {
        requireHttpsPublic(url)
        true
    } catch (_: Exception) {
        false
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun isAddressAllowed(addr: InetAddress, kind: Kind): Boolean {
        val bytes = addr.address ?: return false
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) {
            return false
        }
        if (isMetadataAddress(bytes)) return false
        val embedded = embeddedIpv4(bytes)
        if (embedded != null) {
            if (isMetadataAddress(embedded) || isLinkLocalV4(embedded) || isLoopbackV4(embedded)) return false
            if (isRfc1918(embedded) || isCgnat(embedded)) return false
        }
        if (isRfc1918(bytes) || isUniqueLocalIpv6(bytes) || isCgnat(bytes)) return false
        return true
    }

    internal fun normalizeHost(host: String): String {
        var h = host.trim().trimStart('[').trimEnd(']').lowercase(Locale.US)
        if (h.endsWith(".")) h = h.dropLast(1)
        return try {
            IDN.toASCII(h)
        } catch (_: Exception) {
            h
        }
    }

    private fun isLiteralIp(host: String): Boolean {
        if (host.any { it.isLetter() }) return false
        return host.contains('.') || host.contains(':')
    }

    private fun parseLiteral(host: String): InetAddress? = try {
        if (host.any { it.isLetter() && it != 'a' && it != 'b' && it != 'c' && it != 'd' && it != 'e' && it != 'f' }) {
            null
        } else {
            InetAddress.getByAddress(
                if (':' in host) parseIpv6(host) else parseIpv4(host),
            )
        }
    } catch (_: Exception) {
        null
    }

    internal fun parseIpv4(host: String): ByteArray {
        val parts = host.split('.')
        require(parts.size == 4) { "非法 IPv4" }
        val out = ByteArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: throw IllegalArgumentException("非法 IPv4")
            require(n in 0..255) { "非法 IPv4" }
            require(parts[i] == n.toString()) { "非法 IPv4" }
            out[i] = n.toByte()
        }
        return out
    }

    private fun parseIpv6(host: String): ByteArray {
        // Literal only; InetAddress.getByName would do DNS.
        val s = host.lowercase(Locale.US)
        require(s.all { it in '0'..'9' || it in 'a'..'f' || it == ':' }) { "非法 IPv6" }
        val headTail = s.split("::", limit = 2)
        require(headTail.size <= 2) { "非法 IPv6" }
        fun groups(part: String): List<Int> {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { g ->
                require(g.length in 1..4) { "非法 IPv6" }
                g.toInt(16)
            }
        }
        val head = groups(headTail[0])
        val tail = if (headTail.size == 2) groups(headTail[1]) else emptyList()
        val fill = 8 - head.size - tail.size
        require(fill >= 0) { "非法 IPv6" }
        require(headTail.size == 2 || fill == 0) { "非法 IPv6" }
        val nums = head + List(fill) { 0 } + tail
        require(nums.size == 8) { "非法 IPv6" }
        val out = ByteArray(16)
        for (i in 0..7) {
            out[i * 2] = (nums[i] ushr 8).toByte()
            out[i * 2 + 1] = (nums[i] and 0xff).toByte()
        }
        return out
    }

    internal fun isMetadataAddress(bytes: ByteArray): Boolean {
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            val d = bytes[3].toInt() and 0xff
            // AWS/GCP/Azure 169.254.169.254, Aliyun 100.100.100.200
            if (a == 169 && b == 254 && c == 169 && d == 254) return true
            if (a == 169 && b == 254 && c == 169 && d == 253) return true
            if (a == 100 && b == 100 && c == 100 && d == 200) return true
            return false
        }
        if (bytes.size == 16) {
            // AWS IMDSv2 IPv6 fd00:ec2::/32
            if (bytes[0] == 0xfd.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0x0e.toByte() && bytes[3] == 0xc2.toByte()) {
                return true
            }
            val mapped = isV4Mapped(bytes)
            if (mapped != null) return isMetadataAddress(mapped)
            val embedded = embeddedIpv4(bytes)
            if (embedded != null) return isMetadataAddress(embedded)
        }
        return false
    }

    internal fun isRfc1918(bytes: ByteArray): Boolean {
        val v4 = if (bytes.size == 16) embeddedIpv4(bytes) ?: isV4Mapped(bytes) ?: return false else bytes
        if (v4.size != 4) return false
        val a = v4[0].toInt() and 0xff
        val b = v4[1].toInt() and 0xff
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    internal fun isCgnat(bytes: ByteArray): Boolean {
        val v4 = if (bytes.size == 16) embeddedIpv4(bytes) ?: isV4Mapped(bytes) ?: return false else bytes
        if (v4.size != 4) return false
        val a = v4[0].toInt() and 0xff
        val b = v4[1].toInt() and 0xff
        return a == 100 && b in 64..127
    }

    internal fun isUniqueLocalIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        return (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private fun isLinkLocalV4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        return (bytes[0].toInt() and 0xff) == 169 && (bytes[1].toInt() and 0xff) == 254
    }

    private fun isLoopbackV4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        return (bytes[0].toInt() and 0xff) == 127
    }

    /**
     * IPv4 embedded in IPv6 via v4-mapped, 6to4 (2002::/16), NAT64 (64:ff9b::/96),
     * or Teredo (2001:0000::/32). Used so metadata/RFC1918 cannot hide behind a
     * "global" IPv6 literal.
     */
    internal fun embeddedIpv4(bytes: ByteArray): ByteArray? {
        if (bytes.size != 16) return null
        val mapped = isV4Mapped(bytes)
        if (mapped != null) return mapped
        // 6to4 2002:AABB:CCDD::/48
        if (bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte()) {
            return byteArrayOf(bytes[2], bytes[3], bytes[4], bytes[5])
        }
        // NAT64 well-known prefix 64:ff9b::/96
        if (
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x64.toByte() &&
            bytes[2] == 0xff.toByte() &&
            bytes[3] == 0x9b.toByte()
        ) {
            for (i in 4..11) if (bytes[i] != 0.toByte()) return null
            return bytes.copyOfRange(12, 16)
        }
        // Teredo 2001:0000::/32 — server IPv4 at bytes 4-7, client IPv4 at 12-15 XOR 0xff
        if (
            bytes[0] == 0x20.toByte() &&
            bytes[1] == 0x01.toByte() &&
            bytes[2] == 0x00.toByte() &&
            bytes[3] == 0x00.toByte()
        ) {
            val server = bytes.copyOfRange(4, 8)
            if (isMetadataAddress(server) || isLinkLocalV4(server) || isLoopbackV4(server) || isRfc1918(server) || isCgnat(server)) {
                return server
            }
            val client = ByteArray(4) { i -> (bytes[12 + i].toInt() xor 0xff).toByte() }
            return client
        }
        return null
    }

    private fun isV4Mapped(bytes: ByteArray): ByteArray? {
        if (bytes.size != 16) return null
        for (i in 0..9) if (bytes[i] != 0.toByte()) return null
        if (bytes[10] != 0xff.toByte() || bytes[11] != 0xff.toByte()) return null
        return bytes.copyOfRange(12, 16)
    }

    private fun systemResolve(host: String): List<InetAddress> {
        return InetAddress.getAllByName(host)?.toList().orEmpty()
    }
}
