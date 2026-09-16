package io.nekohasekai.sfa.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
