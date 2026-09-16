package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class HTTPClientRedirectTest {

    @Test
    fun githubReleaseRedirectStaysOnPinnedHost() {
        val next = HTTPClient.nextUrl(
            "https://github.com/dukangalex/AngelaBox/releases/download/v1.0.53/AngelaBox-android.apk",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1",
            RemoteUrlGuard.Kind.UPDATE,
        )
        assertTrue(next.startsWith("https://release-assets.githubusercontent.com/"))
    }

    @Test
    fun relativeRedirectOnGithubUpdate() {
        val next = HTTPClient.nextUrl(
            "https://github.com/dukangalex/AngelaBox/releases/download/v1.0.53/AngelaBox-android.apk",
            "/dukangalex/AngelaBox/releases/download/v1.0.53/AngelaBox-android.apk",
            RemoteUrlGuard.Kind.UPDATE,
        )
        assertTrue(next.startsWith("https://github.com/dukangalex/AngelaBox/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateRedirectOffGithubRejected() {
        HTTPClient.nextUrl(
            "https://github.com/dukangalex/AngelaBox/releases/download/v1.0.53/AngelaBox-android.apk",
            "https://evil.example/AngelaBox-android.apk",
            RemoteUrlGuard.Kind.UPDATE,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionRedirectToHttpRejected() {
        HTTPClient.nextUrl(
            "https://example.com/sub.yaml",
            "http://example.com/sub.yaml",
            RemoteUrlGuard.Kind.SUBSCRIPTION,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionRedirectToMetadataRejected() {
        HTTPClient.nextUrl(
            "https://example.com/sub.yaml",
            "https://169.254.169.254/latest/meta-data",
            RemoteUrlGuard.Kind.SUBSCRIPTION,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionRedirectToLoopbackRejected() {
        HTTPClient.nextUrl(
            "https://example.com/sub.yaml",
            "https://127.0.0.1/secret",
            RemoteUrlGuard.Kind.SUBSCRIPTION,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun subscriptionRedirectToRfc1918Rejected() {
        HTTPClient.nextUrl(
            "https://example.com/sub.yaml",
            "https://192.168.1.8/clash.yaml",
            RemoteUrlGuard.Kind.SUBSCRIPTION,
        )
    }

    @Test
    fun authorizationDroppedOnHostChange() {
        assertFalse(
            HTTPClient.sameHost(
                "https://github.com/dukangalex/AngelaBox/releases/download/v1.0.53/AngelaBox-android.apk",
                "https://release-assets.githubusercontent.com/x",
            ),
        )
        assertTrue(
            HTTPClient.sameHost(
                "https://example.com/a",
                "https://example.com/b",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingLocationRejected() {
        HTTPClient.nextUrl("https://example.com/sub.yaml", null, RemoteUrlGuard.Kind.SUBSCRIPTION)
    }

    @Test
    fun azureSasQueryIsNotDoubleEncoded() {
        val location =
            "https://release-assets.githubusercontent.com/github-production-release-asset/1" +
                "?sp=r&sv=2018-11-09&se=2026-09-16T22%3A24%3A03Z&sig=x%3D&rscd=attachment%3B+filename%3Dapp.apk"
        val next = HTTPClient.nextUrl(
            "https://github.com/dukangalex/AngelaBox/releases/download/v1.0.54/AngelaBox-android.apk",
            location,
            RemoteUrlGuard.Kind.UPDATE,
        )
        assertTrue(next.contains("se=2026-09-16T22%3A24%3A03Z"))
        assertTrue(next.contains("sig=x%3D"))
        assertFalse(next.contains("%253A"))
        assertFalse(next.contains("%253D"))
        val pinned = HTTPClient.requestUrlOnIp(
            next,
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
            443,
        )
        assertTrue(pinned.startsWith("https://1.1.1.1/github-production-release-asset/1?"))
        assertTrue(pinned.contains("se=2026-09-16T22%3A24%3A03Z"))
        assertTrue(pinned.contains("filename%3Dapp.apk"))
        assertFalse(pinned.contains("%253A"))
    }

    @Test
    fun githubBearerNotSentToReleaseAssets() {
        val headers = mapOf("Authorization" to "Bearer ghp_test", "Accept" to "application/octet-stream")
        val api = HTTPClient.headersForHop(
            "https://api.github.com/repos/dukangalex/AngelaBox/releases",
            RemoteUrlGuard.Kind.UPDATE,
            headers,
        )
        assertEquals("Bearer ghp_test", api["Authorization"])
        val assets = HTTPClient.headersForHop(
            "https://release-assets.githubusercontent.com/x?sp=r",
            RemoteUrlGuard.Kind.UPDATE,
            headers,
        )
        assertFalse(assets.containsKey("Authorization"))
        val download = HTTPClient.headersForHop(
            "https://github.com/dukangalex/AngelaBox/releases/download/v1.0.54/AngelaBox-android.apk",
            RemoteUrlGuard.Kind.UPDATE,
            headers,
        )
        assertFalse(download.containsKey("Authorization"))
    }

    @Test
    fun updateHttpErrorDoesNotDumpXml() {
        val msg = HTTPClient.httpFailureMessage(RemoteUrlGuard.Kind.UPDATE, 403)
        assertFalse(msg.contains("<?xml"))
        assertFalse(msg.contains("AuthenticationFailed"))
        assertTrue(msg.contains("查看发布"))
    }
}
