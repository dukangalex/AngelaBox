package io.nekohasekai.sfa.inspection

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Exercises the app transport, request serialization, and OpenAI-compatible response parser
 * against a loopback TLS fixture using synthetic data only. The injected connection deliberately
 * bypasses production DNS pinning/RemoteUrlGuard.openPinned; it is not provider or UI-consent proof.
 */
class InspectionAiLocalEndpointTest {
    @Test fun sendsSyntheticRequestToLoopbackTlsFixtureAndParsesOpenAiResponse() = runBlocking {
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", """{"review":[]}""").put("tool_calls", org.json.JSONArray())))).toString()),
            )
            server.start()

            val config = InspectionStore.AiConfig(
                "https://example.com/v1/chat/completions",
                "synthetic-model",
                "synthetic-test-token",
            )
            val payload = """{"checks":[{"id":"network","state":"OBSERVED","code":"present"}]}"""
            var connection: HttpsURLConnection? = null
            val answer = InspectionAi.analyzeUsing(config, payload, { true }) { configuredUrl, headers ->
                // Keep the app's allowlisted config path intact while routing the test seam only to loopback.
                assertEquals(config.endpoint, configuredUrl)
                (URL(server.url("/v1/chat/completions").toString()).openConnection() as HttpsURLConnection)
                    .also { conn ->
                        conn.sslSocketFactory = clientCertificates.sslSocketFactory()
                        headers.forEach(conn::setRequestProperty)
                        connection = conn
                    }
            }

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer synthetic-test-token", request.getHeader("Authorization"))
            assertEquals("application/json", request.getHeader("Content-Type"))
            val requestBody = JSONObject(request.body.readUtf8())
            assertEquals(payload, requestBody.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertFalse(requestBody.toString().contains("user log"))
            assertTrue(answer.contains("AI 未选出可复核项目"))
            assertNotNull(connection)
            assertTrue(connection!!.instanceFollowRedirects.not())
        }
    }

    @Test fun waitsForBoundedModelGenerationBeyondTenSecondsWithoutRetry() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeadersDelay(11, java.util.concurrent.TimeUnit.SECONDS)
                    .setBody(JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", """{"review":[]}""").put("tool_calls", org.json.JSONArray())))).toString()),
            )
            server.start()
            val config = InspectionStore.AiConfig("https://example.com/v1/chat/completions", "synthetic-model", "synthetic-test-token")
            var opens = 0
            val answer = InspectionAi.analyzeUsing(config, """{"checks":[{"id":"network","state":"OBSERVED","code":"present"}]}""", { true }) { _, headers ->
                opens++
                (URL(server.url("/v1/chat/completions").toString()).openConnection() as HttpsURLConnection).also {
                    it.sslSocketFactory = clientTls.sslSocketFactory()
                    headers.forEach(it::setRequestProperty)
                }
            }
            assertTrue(answer.contains("AI 未选出可复核项目"))
            assertEquals(1, opens)
            assertEquals(1, server.requestCount)
        }
    }
}
