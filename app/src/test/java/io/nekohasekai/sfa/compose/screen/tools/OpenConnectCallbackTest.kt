package io.nekohasekai.sfa.compose.screen.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenConnectCallbackTest {

    @Test
    fun exactHttpsCallbackMatches() {
        assertTrue(
            matchesOpenConnectCallback(
                "https://trusted.example/callback?code=1",
                "https://trusted.example/callback",
            ),
        )
    }

    @Test
    fun pathPrefixDoesNotMatchSibling() {
        assertFalse(
            matchesOpenConnectCallback(
                "https://trusted.example/callback.attacker",
                "https://trusted.example/callback",
            ),
        )
    }

    @Test
    fun nestedPathUnderPrefixMatches() {
        assertTrue(
            matchesOpenConnectCallback(
                "https://trusted.example/callback/ok",
                "https://trusted.example/callback",
            ),
        )
    }

    @Test
    fun hostMismatchRejected() {
        assertFalse(
            matchesOpenConnectCallback(
                "https://evil.example/callback",
                "https://trusted.example/callback",
            ),
        )
    }

    @Test
    fun httpCallbackRejected() {
        assertFalse(
            matchesOpenConnectCallback(
                "http://trusted.example/callback",
                "https://trusted.example/callback",
            ),
        )
    }
}
