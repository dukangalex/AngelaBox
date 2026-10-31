package io.nekohasekai.sfa.inspection

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Fixtures exercise transport and consent guards, not real model accuracy or provider billing. */
class InspectionAiTransportTest {
    private val config = InspectionStore.AiConfig("https://example.com/v1/chat/completions", "test-model", "synthetic-test-token")
    private val response = """{"choices":[{"message":{"content":"可能是 DNS 问题，需进一步验证"}}]}""".toByteArray()

    private class Connection(private val status: Int, private val bytes: ByteArray) : HttpsURLConnection(URL("https://example.com/v1/chat/completions")) {
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
}
