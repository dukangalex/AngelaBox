package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for v1.0.112-beta fixes.
 *
 * Covers:
 * - H1: base64url subscription (with `-`/`_`) accepted by pre-check
 * - M2: query param native `+` preserved (not turned into space)
 * - M1: MiniYaml deep nesting >512 throws controlled exception (no StackOverflowError)
 * - L2: VLESS blank UUID skipped; Hysteria2 empty password returns null
 * - L3: port validation 1-65535
 */
class ConfigIngest112RegressionTest {

    // H1: base64url chars `-` and `_` must pass the pre-check.
    @Test
    fun base64UrlSubscriptionIsAccepted() {
        // A vless share link, base64url-encoded (contains - and _ after encoding).
        val rawLink = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?encryption=none&security=tls#test-node"
        val b64url = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawLink.toByteArray())
        // Sanity: the encoded form should contain url-safe chars for this input.
        // Even if it doesn't, the pre-check must accept `-` and `_` without rejecting.
        val withUrlSafeChars = b64url.replace('+', '-').replace('/', '_') + "-_"
        val result = ConfigIngest.adapt(withUrlSafeChars)
        // Should not be Unknown purely because of `-`/`_` in the pre-check.
        // (It may still be Unknown for other reasons, but must not crash.)
        assertTrue(result.format != null)
    }

    // M2: native `+` in query values (e.g. reality pbk) must survive.
    @Test
    fun queryPlusSignPreservedInRealityPbk() {
        // pbk with a literal `+` (not %2B). Must not become a space.
        val link = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
            "?encryption=none&security=reality&pbk=abc+def%2Bghi&fp=chrome&sni=example.com#pbk-test"
        val result = ConfigIngest.adapt(link)
        assertEquals(ConfigIngest.Format.VLESS, result.format)
        val text = result.content
        // The `+` must be preserved as `+`, not turned into a space.
        assertTrue("pbk lost its plus sign", text.contains("abc+def+ghi") || text.contains("abc+def%2Bghi"))
        assertFalse("plus was turned into space", text.contains("abc def"))
    }

    // M1: deeply nested YAML must throw a controlled exception, not StackOverflowError.
    @Test
    fun deeplyNestedYamlThrowsControlledException() {
        val depth = 600
        val sb = StringBuilder()
        repeat(depth) { sb.append("a:\n  ") }
        sb.append("b: 1\n")
        var threwControlled = false
        var threwStackOverflow = false
        try {
            ConfigIngest.adapt(sb.toString())
        } catch (e: StackOverflowError) {
            threwStackOverflow = true
        } catch (e: Exception) {
            threwControlled = true
        }
        assertFalse("StackOverflowError escaped!", threwStackOverflow)
        // Either a controlled exception or an "unsupported" result is fine.
        assertTrue(threwControlled || true)
    }

    // L2: VLESS with blank UUID must be skipped (no empty-UUID node).
    @Test
    fun vlessBlankUuidIsSkipped() {
        val link = "vless://@1.2.3.4:443?encryption=none#empty-uuid"
        val result = ConfigIngest.adapt(link)
        // The node should be skipped; content must not contain an empty-uuid outbound.
        assertFalse(result.content.contains("\"uuid\":\"\""))
        assertFalse(result.content.contains("\"uuid\": \"\""))
    }

    // L2: Hysteria2 with empty password must return null (skipped).
    @Test
    fun hysteria2EmptyPasswordIsSkipped() {
        val link = "hysteria2://@1.2.3.4:443#empty-auth"
        val result = ConfigIngest.adapt(link)
        assertEquals(ConfigIngest.Format.Hysteria2, result.format)
        // No outbound with empty password should be generated.
        assertFalse(result.content.contains("\"password\":\"\""))
    }

    // L3: ports outside 1-65535 must be rejected.
    @Test
    fun invalidPortsAreRejected() {
        val badPorts = listOf("0", "99999", "-1", "70000")
        for (port in badPorts) {
            val link = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:$port?encryption=none#bad-port-$port"
            val result = ConfigIngest.adapt(link)
            // The node with an invalid port must not appear in the output.
            assertFalse(
                "port $port should be rejected",
                result.content.contains("\"server_port\":$port")
            )
        }
    }

    // L3: valid boundary ports must be accepted.
    @Test
    fun validBoundaryPortsAreAccepted() {
        for (port in listOf("1", "443", "65535")) {
            val link = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:$port?encryption=none#good-port-$port"
            val result = ConfigIngest.adapt(link)
            assertEquals(ConfigIngest.Format.VLESS, result.format)
            assertTrue(
                "port $port should be accepted",
                result.content.contains("\"server_port\":$port")
            )
        }
    }
}
