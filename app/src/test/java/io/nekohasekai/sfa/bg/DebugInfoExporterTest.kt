package io.nekohasekai.sfa.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugInfoExporterTest {

    @Test
    fun redactsProxyUrisAndSecrets() {
        val raw = "node vless://uuid@host:443 password=hunter2 token=abc Authorization: Bearer xyz"
        val out = DebugInfoExporter.redactSecrets(raw)
        assertFalse(out.contains("vless://uuid@host:443"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("Bearer xyz"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun redactsVpnKeyMaterial() {
        val raw = """
            {"private_key": "WGPRIVATEKEY123", "pre_shared_key": "PSK456"}
            psk=hunter2 auth_str: myhy2secret
            Authorization: Bearer realtoken123
            method=aes-256-gcm normal_key=5
        """.trimIndent()
        val out = DebugInfoExporter.redactSecrets(raw)
        assertFalse(out.contains("WGPRIVATEKEY123"))
        assertFalse(out.contains("PSK456"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("myhy2secret"))
        assertFalse(out.contains("realtoken123"))
        // Non-secret keys and values must survive redaction.
        assertTrue(out.contains("aes-256-gcm"))
        assertTrue(out.contains("normal_key=5"))
        assertTrue(out.contains("[redacted]"))
    }
}
