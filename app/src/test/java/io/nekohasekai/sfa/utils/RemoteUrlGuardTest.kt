package io.nekohasekai.sfa.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RemoteUrlGuardTest {

    private val noResolve: (String) -> List<InetAddress> = { emptyList() }

    @Test
    fun subscriptionAllowsHttpsPublicHost() {
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
    fun rfc1918AllowedForSubscriptionNotScript() {
        val addr = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1.toByte()))
        assertTrue(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SUBSCRIPTION))
        assertFalse(RemoteUrlGuard.isAddressAllowed(addr, RemoteUrlGuard.Kind.SCRIPT))
    }
}
