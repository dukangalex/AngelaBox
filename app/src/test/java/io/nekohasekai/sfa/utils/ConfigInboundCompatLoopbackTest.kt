package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigInboundCompatLoopbackTest {
    @Test
    fun clashApiWildcardReboundToLoopback() {
        val root = JSONObject().put(
            "experimental",
            JSONObject().put(
                "clash_api",
                JSONObject().put("external_controller", "0.0.0.0:9090"),
            ),
        )
        assertTrue(ConfigInboundCompat.bindLoopbackOnly(root))
        val listen = root.getJSONObject("experimental")
            .getJSONObject("clash_api")
            .getString("external_controller")
        assertEquals("127.0.0.1:9090", listen)
    }

    @Test
    fun ipv6AnyRebound() {
        assertEquals("127.0.0.1:9090", ConfigInboundCompat.rebindToLoopback("[::]:9090"))
        assertEquals("127.0.0.1:9090", ConfigInboundCompat.rebindToLoopback("::1:9090"))
    }
}
