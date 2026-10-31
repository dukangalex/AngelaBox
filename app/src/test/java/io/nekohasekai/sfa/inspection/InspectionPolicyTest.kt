package io.nekohasekai.sfa.inspection

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InspectionPolicyTest {
    private fun snapshot() = InspectionPolicy.Snapshot(
        10_000,
        77,
        true,
        listOf(
            InspectionPolicy.Fact("network", InspectionPolicy.State.OBSERVED, "present"),
            InspectionPolicy.Fact("DNS", InspectionPolicy.State.NOT_CHECKED, "error"),
        ),
        42,
        "session:1",
    )

    @Test fun freshnessRejectsExpiryClockRollbackAndEnvironmentChange() {
        val s = snapshot()
        assertTrue(InspectionPolicy.fresh(s, 10_001, 77, true, 42, "session:1"))
        assertFalse(InspectionPolicy.fresh(s, 9_999, 77, true, 42, "session:1"))
        assertFalse(InspectionPolicy.fresh(s, 310_001, 77, true, 42, "session:1"))
        assertFalse(InspectionPolicy.fresh(s, 10_001, 78, true, 42, "session:1"))
        assertFalse(InspectionPolicy.fresh(s, 10_001, 77, false, 42, "session:1"))
        assertFalse(InspectionPolicy.fresh(s, 10_001, 77, true, 43, "session:1"))
        assertFalse(InspectionPolicy.fresh(s, 10_001, 77, true, 42, "session:2"))
        assertFalse(InspectionPolicy.fresh(s, 10_001, 77, true, 42, "new-process:1"))
    }

    @Test fun payloadUsesAnAllowlistNotRawRedaction() {
        val evil = "https://subscription.example/token?key=secret"
        val s = snapshot().copy(facts = snapshot().facts + InspectionPolicy.Fact(evil, InspectionPolicy.State.OBSERVED, evil))
        val payload = InspectionPolicy.payload(s, evil, "v1.15.0-chain.11")
        assertFalse(payload.contains(evil))
        assertFalse(payload.contains("session"))
        val json = JSONObject(payload)
        assertEquals(setOf("schema", "app_version", "kernel_version", "checks", "limitations"), json.keys().asSequence().toSet())
        assertEquals("unknown", json.getString("app_version"))
        val fact = json.getJSONArray("checks").getJSONObject(2)
        assertEquals("UNKNOWN", fact.getString("state"))
        assertEquals("unknown", fact.getString("code"))
        assertEquals("unknown", fact.getString("id"))
    }

    @Test fun dnsCanaryAbsenceDoesNotProveWorkingDns() {
        val fact = InspectionPolicy.normalized(InspectionPolicy.Fact("DNS", InspectionPolicy.State.OBSERVED, "ok"))
        assertEquals(InspectionPolicy.State.UNKNOWN, fact.state)
        assertEquals("canary_not_resolved", fact.code)
        assertEquals(fact, InspectionPolicy.normalized(fact))
    }

    @Test fun unknownNeverBecomesHealthy() {
        val result = InspectionPolicy.normalized(InspectionPolicy.Fact("DNS", InspectionPolicy.State.OBSERVED, "future-code"))
        assertEquals(InspectionPolicy.State.UNKNOWN, result.state)
        assertFalse(InspectionPolicy.attention(listOf(result))) // Not an all-healthy verdict: unknown remains explicit.
    }

    @Test fun stateAndEvidenceAreDeterministic() {
        val s = snapshot()
        assertEquals(InspectionPolicy.evidenceKey(s), InspectionPolicy.evidenceKey(s.copy(facts = s.facts.reversed())))
        assertTrue(InspectionPolicy.attention(listOf(InspectionPolicy.Fact("network", InspectionPolicy.State.ATTENTION, "absent"))))
    }

    @Test fun aiResponseCannotReturnToolsOrUnboundedContent() {
        fun response(content: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString().toByteArray()
        assertEquals("建议先复测", InspectionAi.parseResponse(response("建议先复测")))
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(response("x".repeat(6001))) }
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(response("")) }
        val tools = """{"choices":[{"message":{"tool_calls":[],"content":"x"}}]}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(tools) }
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(ByteArray(65537)) }
    }

    @Test fun requestContainsOnlyExplicitPayloadAndFixedInstruction() {
        val payload = InspectionPolicy.payload(snapshot(), "1.0.138-beta", "v1.15.0-chain.11")
        val body = JSONObject(InspectionAi.requestBody(payload, "example-model"))
        assertEquals("example-model", body.getString("model"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("api_key"))
        assertEquals(payload, body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun endpointConfigRejectsCleartextPrivateHostsQueriesAndHeaderInjection() {
        fun config(endpoint: String = "https://example.com/v1/chat/completions", key: String = "abc") = InspectionStore.AiConfig(endpoint, "model-1", key)
        InspectionAi.validateConfig(config())
        for (url in listOf("http://example.com/v1", "https://localhost/v1", "https://127.0.0.1/v1", "https://192.168.1.2/v1", "https://example.com/v1?key=x", "https://example.com/v1#x", "https://user:password@example.com/v1")) {
            assertThrows(IllegalArgumentException::class.java) { InspectionAi.validateConfig(config(url)) }
        }
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.validateConfig(config(key = "abc\r\nInjected: x")) }
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.validateConfig(config(key = "")) }
    }
}
