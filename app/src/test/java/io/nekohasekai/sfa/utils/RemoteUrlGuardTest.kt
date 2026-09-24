package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RemoteUrlGuardTest {

    private val noResolve: (String) -> List<InetAddress> = { emptyList() }
    private val publicResolve: (String) -> List<InetAddress> = {
        listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))
    }

    @Test
    fun publicDnsFallbackStaysOffWhileTunnelIsUp() {
        TunnelGate.setUp(true)
        try {
            assertFalse(RemoteUrlGuard.allowPublicDnsFallback())
        } finally {
            TunnelGate.setUp(false)
        }
        assertTrue(RemoteUrlGuard.allowPublicDnsFallback())
    }

    @Test
    fun subscriptionAllowsHttpsPublicHost() {
        RemoteUrlGuard.requireAllowed("https://example.com/sub.yaml", RemoteUrlGuard.Kind.SUBSCRIPTION, publicResolve)
    }

    @Test
    fun validateReturnsCheckedAddresses() {
        val endpoint = RemoteUrlGuard.validate(
            "https://example.com/sub.yaml",
            RemoteUrlGuard.Kind.SUBSCRIPTION,
            publicResolve,
        )
        assertEquals("example.com", endpoint.host)
        assertEquals(443, endpoint.port)
        assertEquals(1, endpoint.addresses.size)
        assertTrue(endpoint.addresses[0].address.contentEquals(byteArrayOf(1, 1, 1, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionRejectsUnresolvedHost() {
        RemoteUrlGuard.requireAllowed("https://example.com/sub.yaml", RemoteUrlGuard.Kind.SUBSCRIPTION, noResolve)
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionRejectsHttp() {
        RemoteUrlGuard.requireAllowed("http://example.com/sub.yaml", RemoteUrlGuard.Kind.SUBSCRIPTION, noResolve)
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionRejectsLanHttp() {
        RemoteUrlGuard.requireAllowed("http://192.168.1.8:8080/clash.yaml", RemoteUrlGuard.Kind.SUBSCRIPTION, noResolve)
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionBlocksLoopback() {
        RemoteUrlGuard.requireAllowed("http://127.0.0.1/secret", RemoteUrlGuard.Kind.SUBSCRIPTION, noResolve)
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionBlocksMetadata() {
        RemoteUrlGuard.requireAllowed("http://169.254.169.254/latest/meta-data", RemoteUrlGuard.Kind.SUBSCRIPTION, noResolve)
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionBlocksAliyunMetadata() {
        RemoteUrlGuard.requireAllowed("http://100.100.100.200/latest/meta-data", RemoteUrlGuard.Kind.SUBSCRIPTION, noResolve)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scriptRejectsHttp() {
        RemoteUrlGuard.requireAllowed("http://example.com/script.js", RemoteUrlGuard.Kind.SCRIPT, noResolve)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateRejectsNonGithub() {
        RemoteUrlGuard.requireAllowed(
            "https://evil.example/AngelaBox-android.apk",
            RemoteUrlGuard.Kind.UPDATE,
            noResolve,
        )
    }

    @Test
    fun updateAllowsGithubReleaseAsset() {
        RemoteUrlGuard.requireAllowed(
            "https://github.com/dukangalex/AngelaBox/releases/download/v1.0.53/AngelaBox-android.apk",
            RemoteUrlGuard.Kind.UPDATE,
            noResolve,
        )
    }

    @Test
    fun loopbackAddressRejected() {
        val addr = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SUBSCRIPTION))
    }

    @Test
    fun rfc1918RejectedForAllKinds() {
        val addr = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1.toByte()))
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SUBSCRIPTION))
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SCRIPT))
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.UPDATE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionRejectsHttpsLanLiteral() {
        RemoteUrlGuard.requireAllowed(
            "https://192.168.1.8/clash.yaml",
            RemoteUrlGuard.Kind.SUBSCRIPTION,
            noResolve,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun scriptRejectsHttpsLanLiteral() {
        RemoteUrlGuard.requireAllowed(
            "https://192.168.1.8/script.js",
            RemoteUrlGuard.Kind.SCRIPT,
            noResolve,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun mixedPublicPrivateDnsRejected() {
        val mixed: (String) -> List<InetAddress> = {
            listOf(
                InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)),
            )
        }
        RemoteUrlGuard.requireAllowed("https://example.com/sub.yaml", RemoteUrlGuard.Kind.SUBSCRIPTION, mixed)
    }

    @Test
    fun uniqueLocalIpv6Rejected() {
        val bytes = ByteArray(16)
        bytes[0] = 0xfd.toByte()
        bytes[15] = 1
        val addr = InetAddress.getByAddress(bytes)
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SUBSCRIPTION))
    }

    @Test
    fun cgnatRejected() {
        val addr = InetAddress.getByAddress(byteArrayOf(100, 64, 0, 1))
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SUBSCRIPTION))
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionBlocksHttpsMetadata() {
        RemoteUrlGuard.requireAllowed(
            "https://169.254.169.254/latest/meta-data",
            RemoteUrlGuard.Kind.SUBSCRIPTION,
            noResolve,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun publicHttpsRejectsHttp() {
        RemoteUrlGuard.requireHttpsPublic("http://example.com/geoip-cn.srs")
    }

    @Test(expected = IllegalArgumentException::class)
    fun publicHttpsRejectsLan() {
        RemoteUrlGuard.requireHttpsPublic("https://192.168.1.8/geoip-cn.srs")
    }

    @Test
    fun publicHttpsAllowsGithubMirror() {
        RemoteUrlGuard.requireHttpsPublic(
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs",
        )
    }

    @Test
    fun sixToFourMetadataRejected() {
        val bytes = ByteArray(16)
        bytes[0] = 0x20.toByte()
        bytes[1] = 0x02.toByte()
        bytes[2] = 0xa9.toByte()
        bytes[3] = 0xfe.toByte()
        bytes[4] = 0xa9.toByte()
        bytes[5] = 0xfe.toByte()
        val addr = InetAddress.getByAddress(bytes)
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SUBSCRIPTION))
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SCRIPT))
    }

    @Test
    fun nat64Rfc1918RejectedForScripts() {
        val bytes = ByteArray(16)
        bytes[1] = 0x64
        bytes[2] = 0xff.toByte()
        bytes[3] = 0x9b.toByte()
        bytes[12] = 10
        bytes[15] = 1
        val addr = InetAddress.getByAddress(bytes)
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SCRIPT))
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SUBSCRIPTION))
    }
}
