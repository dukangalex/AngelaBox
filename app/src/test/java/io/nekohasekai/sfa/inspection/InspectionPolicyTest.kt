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

    @Test fun underlyingNetworkEpochInvalidatesEvenWhenVpnHandleIsUnchanged() {
        val s = snapshot()
        // Android's default VPN handle can remain 42 after its physical uplink
        // changes. The runner's independent NOT_VPN callback advances this token.
        assertFalse(InspectionPolicy.fresh(s, 10_001, 77, true, 42, "session:2"))
        val payload = InspectionPolicy.payload(s.copy(contextToken = "private-uplink-epoch"), "1.0", "1.0")
        assertFalse(payload.contains("private-uplink-epoch"))
        assertFalse(JSONObject(payload).has("context"))
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

    @Test fun payloadExplainsWhyDnsCanaryNonresolutionIsAmbiguous() {
        val limitations = JSONObject(InspectionPolicy.payload(snapshot(), "1.0.138-beta", "v1.15.0-chain.11")).getString("limitations")
        assertTrue(limitations.contains("intentionally nonexistent .invalid"))
        assertTrue(limitations.contains("NXDOMAIN or a resolver error"))
        assertTrue(limitations.contains("neither working nor broken DNS"))
        assertTrue(limitations.contains("FakeIP may intentionally return a synthetic address"))
        assertTrue(limitations.contains("do not treat either result as proof of a resolver fault"))
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

    @Test fun aiResponseRejectsKnownSensitiveDataTermsConservatively() {
        fun response(content: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString().toByteArray()
        val examples = listOf("请检查设备日志", "請提供日誌", "look at system logs", "run LOGCAT", "分享密码", "send your API key", "提供订阅链接", "ＬＯＧＳ", "查看运行记录并发给客服", "提供诊断记录", "发送错误报告", "导出调试信息", "上传订阅内容", "提供令牌", "发送 API token")
        for (example in examples) {
            assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(response(example)) }
        }
        // Conservative: even a benign negative statement is rejected.
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(response("不要发送日志")) }
        assertEquals("网络暂不可用，请稍后重试。", InspectionAi.parseResponse(response("网络暂不可用，请稍后重试。")))
    }

    @Test fun aiResponseAllowsEmptyToolCallsButRejectsToolsOrUnboundedContent() {
        fun response(content: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString().toByteArray()
        assertEquals("建议先复测", InspectionAi.parseResponse(response("建议先复测")))
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(response("x".repeat(6001))) }
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(response("")) }
        val emptyTools = """{"choices":[{"message":{"tool_calls":[],"content":"x"}}]}""".toByteArray()
        assertEquals("x", InspectionAi.parseResponse(emptyTools))
        val nonEmptyTools = """{"choices":[{"message":{"tool_calls":[{"type":"function","function":{"name":"change_route","arguments":"{}"}}],"content":"x"}}]}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(nonEmptyTools) }
        val legacyFunctionCall = """{"choices":[{"message":{"function_call":{"name":"change_route","arguments":"{}"},"content":"x"}}]}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(legacyFunctionCall) }
        assertThrows(IllegalArgumentException::class.java) { InspectionAi.parseResponse(ByteArray(65537)) }
    }

    @Test fun requestContainsOnlyExplicitPayloadAndFixedInstruction() {
        val payload = InspectionPolicy.payload(snapshot(), "1.0.138-beta", "v1.15.0-chain.11")
        val body = JSONObject(InspectionAi.requestBody(payload, "example-model"))
        assertEquals("example-model", body.getString("model"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("api_key"))
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("Tunnel status alone does not verify NAT, proxy configuration, or traffic routing"))
        assertTrue(system.contains("Never suggest bypassing the VPN or directly querying public DNS"))
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

    @Test fun probeAttemptsAreBoundedAndNeverContainPrivateStrings() {
        assertNull(InspectionPolicy.tunnelAttempt(2, 0, "ok"))
        assertNull(InspectionPolicy.tunnelAttempt(0, 2, "ok"))
        assertNull(InspectionPolicy.tunnelAttempt(0, 0, "private.example secret"))
        val first = InspectionPolicy.tunnelAttempt(0, 0, "tls_failure")!!
        val second = InspectionPolicy.tunnelAttempt(0, 1, "ok")!!
        assertEquals(InspectionPolicy.Fact("TUNNEL_1A", InspectionPolicy.State.ATTENTION, "tls_failure"), first)
        assertEquals(InspectionPolicy.Fact("TUNNEL_1B", InspectionPolicy.State.OBSERVED, "ok"), second)
        val payload = InspectionPolicy.payload(snapshot().copy(facts = listOf(first, second)), "1", "1")
        assertTrue(payload.contains("tls_failure"))
        assertFalse(payload.contains("private.example"))
    }

    @Test fun safeRendererRejectsRealUnsupportedDnsAdviceAndOutputInjection() {
        val evidence = InspectionPolicy.payload(snapshot(), "1", "1")
        val candidates = listOf(
            "建议切换 DNS 模式",
            """{"review":["DNS"],"advice":"切换 DNS"}""",
            """{"review":["DNS","DNS"]}""",
            """{review:["DNS"]}""",
            """{"review":["DNS"]} extra""",
            """{"review":["network"]}""",
            """{"review":["DNS",]}""",
            """{"review":[,"DNS"]}""",
            """{"review":["DNS" "DNS"]}""",
            """{"review":["DNS"],"review":["DNS"]}""",
            """{"review":["D\u004eS"]}""",
            """{"review":["UNKNOWN"]}""",
            """{'review':['DNS']}""",
            """{"review":["DNS"] // extra
            }""",
        )
        candidates.forEach { bad -> assertThrows(IllegalArgumentException::class.java) { InspectionAdvice.render(bad, evidence) } }
        val rendered = InspectionAdvice.render("""{"review":["DNS"]}""", evidence)
        assertEquals(rendered, InspectionAdvice.render("  { \n \"review\" : [ \"DNS\" ] } \t", evidence))
        assertTrue(rendered.contains("DNS canary"))
        assertTrue(rendered.contains("根因尚未确定"))
        assertFalse(rendered.contains("切换 DNS"))
    }

    @Test fun localAbsentNetworkTakesPriorityOverModelChoicesWithoutClaimingRootCause() {
        val facts = listOf(
            InspectionPolicy.Fact("network", InspectionPolicy.State.ATTENTION, "absent"),
            InspectionPolicy.Fact("service", InspectionPolicy.State.OBSERVED, "running"),
            InspectionPolicy.Fact("DNS", InspectionPolicy.State.UNKNOWN, "canary_not_resolved"),
            InspectionPolicy.Fact("LATENCY", InspectionPolicy.State.NOT_CHECKED, "skipped"),
        )
        val payload = InspectionPolicy.payload(snapshot().copy(facts = facts), "1", "1")
        for (response in listOf("""{"review":["DNS"]}""", """{"review":["LATENCY"]}""", """{"review":["DNS","network"]}""", """{"review":["network"]}""")) {
            val result = InspectionAdvice.render(response, payload)
            assertTrue(result.contains("本地证据优先复核：活动网络（采集时未发现活动网络）"))
            assertTrue(result.indexOf("本地证据优先复核：活动网络") < result.indexOf("本次本地记录"))
            assertFalse(result.contains("AI 建议优先复核："))
            assertTrue(result.contains("根因尚未确定"))
        }
        val result = InspectionAdvice.render("""{"review":["DNS","network"]}""", payload)
        assertTrue(result.contains("AI 选择的项目（按 ID 排列，不分优先级）：DNS canary、活动网络。"))
        assertFalse(result.contains("AI 建议优先复核"))
        val online = InspectionPolicy.payload(
            snapshot().copy(
                facts = listOf(
                    InspectionPolicy.Fact("network", InspectionPolicy.State.OBSERVED, "present"),
                    InspectionPolicy.Fact("DNS", InspectionPolicy.State.UNKNOWN, "canary_not_resolved"),
                ),
            ),
            "1",
            "1",
        )
        assertTrue(InspectionAdvice.render("""{"review":["DNS"]}""", online).contains("AI 选择的项目（按 ID 排列，不分优先级）：DNS canary"))
        assertThrows(IllegalArgumentException::class.java) { InspectionAdvice.render("""{"review":[]}""", payload) }
        assertThrows(IllegalArgumentException::class.java) { InspectionAdvice.render("""{"review":["service"]}""", payload) }
    }

    @Test fun stoppedServiceIsLocallyPrioritizedWithoutAttributingRootCause() {
        val facts = listOf(
            InspectionPolicy.Fact("network", InspectionPolicy.State.OBSERVED, "present"),
            InspectionPolicy.Fact("service", InspectionPolicy.State.OBSERVED, "stopped"),
            InspectionPolicy.Fact("TUNNEL", InspectionPolicy.State.NOT_CHECKED, "not_started"),
            InspectionPolicy.Fact("DNS", InspectionPolicy.State.UNKNOWN, "canary_not_resolved"),
        )
        val payload = InspectionPolicy.payload(snapshot().copy(facts = facts), "1", "1")
        val text = InspectionAdvice.render("""{"review":["TUNNEL","DNS"]}""", payload)
        assertTrue(text.contains("本地证据优先复核：VPN 服务（采集时未运行）"))
        assertTrue(text.indexOf("本地证据优先复核：VPN 服务") < text.indexOf("本次本地记录"))
        assertTrue(text.contains("AI 选择的项目（按 ID 排列，不分优先级）：DNS canary、App HTTPS 探测"))
        assertTrue(text.contains("根因尚未确定"))
        assertFalse(text.contains("AI 建议优先复核："))
        val both = InspectionPolicy.payload(
            snapshot().copy(
                facts = facts.map {
                    if (it.id == "network") InspectionPolicy.Fact("network", InspectionPolicy.State.ATTENTION, "absent") else it
                },
            ),
            "1",
            "1",
        )
        val bothText = InspectionAdvice.render("""{"review":["DNS"]}""", both)
        assertTrue(bothText.contains("本地证据优先复核：活动网络"))
        assertTrue(bothText.contains("本地证据优先复核：VPN 服务"))
    }

    @Test fun modelSelectionsAreUnrankedAndCannotHideLocalEvidence() {
        val facts = listOf(
            InspectionPolicy.Fact("network", InspectionPolicy.State.OBSERVED, "present"),
            InspectionPolicy.Fact("DNS", InspectionPolicy.State.UNKNOWN, "canary_not_resolved"),
            InspectionPolicy.Fact("TUNNEL", InspectionPolicy.State.NOT_CHECKED, "not_started"),
            InspectionPolicy.Fact("SUBSCRIPTION", InspectionPolicy.State.ATTENTION, "expired"),
        )
        val payload = InspectionPolicy.payload(snapshot().copy(facts = facts), "1", "1")
        for (language in InspectionAdvice.Language.values()) {
            val a = InspectionAdvice.render("""{"review":["TUNNEL","DNS"]}""", payload, language)
            val b = InspectionAdvice.render("""{"review":["DNS","TUNNEL"]}""", payload, language)
            assertEquals(a, b)
            assertTrue(a.contains("expired")) // Model omitted subscription, local evidence remains.
            assertTrue(a.contains("not_started"))
            assertTrue(a.contains("canary_not_resolved"))
        }
    }

    @Test
    fun adviceUsesTheSelectedLocaleWithoutChangingEvidenceOrOrdering() {
        val payload = """{"checks":[{"id":"network","state":"ATTENTION","code":"absent"},{"id":"service","state":"OBSERVED","code":"stopped"},{"id":"DNS","state":"UNKNOWN","code":"canary_not_resolved"}]}"""
        val response = """{"review":["DNS"]}"""
        val en = InspectionAdvice.render(response, payload, InspectionAdvice.languageFor(java.util.Locale.forLanguageTag("en-US")))
        val tw = InspectionAdvice.render(response, payload, InspectionAdvice.languageFor(java.util.Locale.forLanguageTag("zh-TW")))
        val cn = InspectionAdvice.render(response, payload, InspectionAdvice.languageFor(java.util.Locale.forLanguageTag("zh-CN")))
        assertTrue(en.contains("Local evidence to review first: active network"))
        assertTrue(en.contains("VPN service (not running at collection time)"))
        assertFalse(en.any { it in '\u4e00'..'\u9fff' })
        assertTrue(tw.contains("活動網路"))
        assertTrue(tw.contains("VPN 服務（採集時未運行）"))
        assertTrue(cn.contains("活动网络"))
        assertTrue(cn.contains("VPN 服务（采集时未运行）"))
        assertTrue(listOf(en, tw, cn).all { it.contains("canary_not_resolved") })
        assertEquals(InspectionAdvice.Language.TRADITIONAL, InspectionAdvice.languageFor(java.util.Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals(InspectionAdvice.Language.SIMPLIFIED, InspectionAdvice.languageFor(java.util.Locale.forLanguageTag("zh-Hans-TW")))
    }

    @Test fun localSummaryRetainsAllEvidenceAndItsLimitsWithoutModel() {
        val payload = """{"checks":[{"id":"network","state":"ATTENTION","code":"absent"},{"id":"service","state":"OBSERVED","code":"stopped"},{"id":"DNS","state":"UNKNOWN","code":"canary_not_resolved"},{"id":"SUBSCRIPTION","state":"ATTENTION","code":"expired"},{"id":"LATENCY","state":"NOT_CHECKED","code":"skipped"}]}"""
        for (language in InspectionAdvice.Language.values()) {
            val output = InspectionAdvice.renderLocal(payload, language)
            assertFalse(output.contains("AI"))
            for (code in listOf("absent", "stopped", "canary_not_resolved", "expired", "skipped")) assertTrue(output.contains(code))
            assertTrue(output.indexOf("absent") < output.indexOf("expired"))
        }
        val en = InspectionAdvice.renderLocal(payload, InspectionAdvice.Language.ENGLISH)
        assertTrue(en.contains("not running at collection time"))
        assertTrue(en.contains("unknown and unchecked do not mean healthy"))
        assertTrue(en.contains("local summary, not a server verification"))
        assertFalse(en.any { it in '\u4e00'..'\u9fff' })
        val bad = """{"checks":[{"id":"DNS","state":"OBSERVED","code":"ok"}]}"""
        assertThrows(IllegalArgumentException::class.java) { InspectionAdvice.renderLocal(bad) }
    }
}
