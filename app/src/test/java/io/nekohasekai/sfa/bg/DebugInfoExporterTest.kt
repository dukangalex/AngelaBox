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
}
