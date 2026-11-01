package io.nekohasekai.sfa.inspection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Fixtures exercise transport and consent guards, not real model accuracy or provider billing. */
class InspectionAiTransportTest {
    private val config = InspectionStore.AiConfig("https://example.com/v1/chat/completions", "test-model", "synthetic-test-token")
    private val response = """{"choices":[{"message":{"content":"可能是 DNS 问题，需进一步验证"}}]}""".toByteArray()

    private open class Connection(private val status: Int, private val bytes: ByteArray) : HttpsURLConnection(URL("https://example.com/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getResponseCode() = status
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }

    @Test fun sendsExactReviewedEvidenceOnceAndReturnsOnlyAdvice() = runBlocking {
        val connection = Connection(200, response)
        var opens = 0
        val payload = """{"checks":[{"id":"network","code":"absent"}]}"""
        val answer = InspectionAi.analyzeUsing(config, payload, { true }) { url, headers ->
            opens++
            assertEquals(config.endpoint, url)
            assertEquals("Bearer synthetic-test-token", headers["Authorization"])
            connection
        }
        assertEquals(1, opens)
        val sent = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals(payload, sent.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertFalse(connection.sent.toString("UTF-8").contains(config.key))
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("可能是 DNS 问题，需进一步验证", answer)
        assertTrue(connection.disconnected)
    }

    @Test fun acceptsOpenAiCompatibleEmptyToolCalls() {
        val json = """{"choices":[{"message":{"content":"TEST_OK","tool_calls":[],"reasoning":"synthetic"}}]}"""
        assertEquals("TEST_OK", InspectionAi.parseResponse(json.toByteArray()))
    }

    @Test fun rejectsNonEmptyToolCalls() {
        val json = """{"choices":[{"message":{"content":"unsafe","tool_calls":[{"type":"function","function":{"name":"change_route","arguments":"{}"}}]}}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            InspectionAi.parseResponse(json.toByteArray())
        }
    }

    @Test fun staleEvidenceNeverOpensAConnection() {
        var opens = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                InspectionAi.analyzeUsing(config, "{}", { false }) { _, _ ->
                    opens++
                    Connection(200, response)
                }
            }
        }
        assertEquals(0, opens)
    }

    @Test fun changeAfterOpeningPreventsSending() {
        val connection = Connection(200, response)
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { InspectionAi.analyzeUsing(config, "{}", { ++checks == 1 }) { _, _ -> connection } }
        }
        assertEquals(0, connection.sent.size())
        assertTrue(connection.disconnected)
    }

    @Test fun changeAfterSendingNeverDisplaysStaleAdvice() {
        val connection = Connection(200, response)
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { InspectionAi.analyzeUsing(config, "{}", { ++checks < 3 }) { _, _ -> connection } }
        }
        assertTrue(connection.sent.size() > 0)
        assertTrue(connection.disconnected)
    }

    @Test fun errorsAndRedirectsAreNeverRetried() {
        for (status in listOf(302, 401, 429, 500)) {
            var opens = 0
            val connection = Connection(status, response)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    InspectionAi.analyzeUsing(config, "{}", { true }) { _, _ ->
                        opens++
                        connection
                    }
                }
            }
            assertEquals(1, opens)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun oversizedResponseClosesWithoutReturningAdvice() {
        val connection = Connection(200, ByteArray(65537))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { InspectionAi.analyzeUsing(config, "{}", { true }) { _, _ -> connection } }
        }
        assertTrue(connection.disconnected)
    }
    private fun failure(reason: InspectionAi.FailureReason, block: () -> Unit) {
        val error = assertThrows(InspectionAi.Failure::class.java, block)
        assertEquals(reason, error.reason)
        assertEquals(reason.name, error.message)
        assertNull(error.cause)
    }

    @Test fun responseRejectionsHaveFixedLocalReasons() {
        val fixtures = listOf(
            "not JSON with provider-private-text" to InspectionAi.FailureReason.RESPONSE_FORMAT,
            """{"choices":[{"message":{"content":null}}]}""" to InspectionAi.FailureReason.RESPONSE_FORMAT,
            """{"choices":[{"message":{"content":{"text":"not a text response"}}}]}""" to InspectionAi.FailureReason.RESPONSE_FORMAT,
            """{"choices":[]}""" to InspectionAi.FailureReason.RESPONSE_FORMAT,
            """{"choices":[{"message":{"content":" "}}]}""" to InspectionAi.FailureReason.RESPONSE_FORMAT,
            """{"choices":[{"message":{"content":"请提供日志 provider-private-text"}}]}""" to InspectionAi.FailureReason.SENSITIVE_ADVICE,
            """{"choices":[{"finish_reason":"length","message":{"content":"a plausible but truncated answer"}}]}""" to InspectionAi.FailureReason.UNSUPPORTED_RESPONSE,
            """{"choices":[{"finish_reason":"content_filter","message":{"content":"partial"}}]}""" to InspectionAi.FailureReason.UNSUPPORTED_RESPONSE,
            """{"choices":[{"message":{"content":"text","function_call":{}}}]}""" to InspectionAi.FailureReason.UNSUPPORTED_RESPONSE,
        )
        fixtures.forEach { (body, expected) ->
            failure(expected) { InspectionAi.parseResponse(body.toByteArray()) }
        }
        failure(InspectionAi.FailureReason.RESPONSE_LIMIT) { InspectionAi.parseResponse(ByteArray(65537)) }
        val longContent = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", "x".repeat(6001)))))
        failure(InspectionAi.FailureReason.RESPONSE_LIMIT) { InspectionAi.parseResponse(longContent.toString().toByteArray()) }
    }

    @Test fun acceptsStopOrMissingFinishReasonWithoutWeakeningToolGuard() {
        for (finish in listOf("stop", null)) {
            val choice = JSONObject().put("finish_reason", finish ?: JSONObject.NULL)
                .put("message", JSONObject().put("content", "仅此时点的观察，不代表根因").put("tool_calls", org.json.JSONArray()))
            val body = JSONObject().put("choices", org.json.JSONArray().put(choice)).toString()
            assertEquals("仅此时点的观察，不代表根因", InspectionAi.parseResponse(body.toByteArray()))
        }
    }

    @Test fun failedOpenIsSanitizedAndNeverRetried() {
        for ((exception, expected) in listOf(
            SocketTimeoutException("provider-private-text") to InspectionAi.FailureReason.TIMEOUT,
            IOException("provider-private-text") to InspectionAi.FailureReason.NETWORK_ERROR,
        )) {
            var opens = 0
            failure(expected) {
                runBlocking {
                    InspectionAi.analyzeUsing(config, "{}", { true }) { _, _ ->
                        opens++
                        throw exception
                    }
                }
            }
            assertEquals(1, opens)
        }
    }

    @Test fun headerTimeoutIsNotHttpFailureAndDisconnectsOnceAttempted() {
        var opens = 0
        val connection = object : Connection(200, response) {
            override fun getResponseCode(): Int = throw SocketTimeoutException("provider-private-text")
        }
        failure(InspectionAi.FailureReason.TIMEOUT) {
            runBlocking {
                InspectionAi.analyzeUsing(config, "{}", { true }) { _, _ ->
                    opens++
                    connection
                }
            }
        }
        assertEquals(1, opens)
        assertTrue(connection.sent.size() > 0)
        assertTrue(connection.disconnected)
    }

    @Test fun readErrorIsNotFormatRejectionAndDisconnects() {
        val connection = object : Connection(200, response) {
            override fun getInputStream(): ByteArrayInputStream = throw IOException("provider-private-text")
        }
        failure(InspectionAi.FailureReason.NETWORK_ERROR) {
            runBlocking { InspectionAi.analyzeUsing(config, "{}", { true }) { _, _ -> connection } }
        }
        assertTrue(connection.disconnected)
    }

    @Test fun httpFailureDoesNotReadOrExposeProviderErrorBody() {
        for (status in listOf(302, 401, 402, 429, 500)) {
            val connection = object : Connection(status, response) {
                override fun getErrorStream(): ByteArrayInputStream = throw AssertionError("must not read error body")
                override fun getInputStream(): ByteArrayInputStream = throw AssertionError("must not read response body")
            }
            failure(InspectionAi.FailureReason.HTTP_ERROR) {
                runBlocking { InspectionAi.analyzeUsing(config, "{}", { true }) { _, _ -> connection } }
            }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun staleIsDistinctFromInvalidResponse() {
        failure(InspectionAi.FailureReason.STALE_EVIDENCE) {
            runBlocking { InspectionAi.analyzeUsing(config, "{}", { false }) { _, _ -> throw AssertionError("must not open") } }
        }
    }

    @Test fun cancellationIsNeverReclassifiedAsProviderFailure() {
        val connection = object : Connection(200, response) {
            override fun getResponseCode(): Int = throw CancellationException("cancelled")
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { InspectionAi.analyzeUsing(config, "{}", { true }) { _, _ -> connection } }
        }
        assertTrue(connection.disconnected)
    }

    @Test fun blockedSystemDnsHasBoundedWaitAndNoUnboundedQueue() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            val start = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                InspectionAi.resolveSystem("synthetic.invalid", 100) {
                    calls.incrementAndGet()
                    started.countDown()
                    try {
                        while (release.count > 0) {
                            try {
                                release.await()
                            } catch (_: InterruptedException) { /* emulate native DNS */ }
                        }
                        emptyList()
                    } finally {
                        exited.countDown()
                    }
                }
            }
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
            repeat(5) {
                assertThrows(SocketTimeoutException::class.java) {
                    InspectionAi.resolveSystem("synthetic.invalid", 100) {
                        calls.incrementAndGet()
                        emptyList()
                    }
                }
            }
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            assertTrue(exited.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun openConsumesRequestBudgetAndExpiredOpenNeverSends() {
        val connection = Connection(200, response)
        failure(InspectionAi.FailureReason.TIMEOUT) {
            runBlocking {
                InspectionAi.analyzeUsing(config, "{}", { true }, requestBudgetMillis = 1) { _, _ ->
                    Thread.sleep(25)
                    connection
                }
            }
        }
        assertEquals(0, connection.sent.size())
        assertTrue(connection.disconnected)
    }

    @Test fun guardCannotEraseDnsTimeoutNetworkErrorOrCancellation() {
        for (error in listOf(SocketTimeoutException(), IOException(), CancellationException("cancelled"))) {
            val actual = assertThrows(error.javaClass) {
                InspectionAi.withPreservedDnsFailure<Unit>(resolve = { throw error }) { resolve ->
                    // Mirror RemoteUrlGuard.validate's intentional exception folding.
                    try {
                        resolve("synthetic.invalid")
                    } catch (_: Exception) { }
                    throw IllegalArgumentException("unresolved host")
                }
            }
            assertSame(error, actual)
        }
        val denied = IllegalArgumentException("blocked IP")
        assertSame(
            denied,
            assertThrows(IllegalArgumentException::class.java) {
                InspectionAi.withPreservedDnsFailure<Unit>(resolve = { emptyList() }) { throw denied }
            },
        )
    }
}
