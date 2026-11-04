package io.nekohasekai.sfa.inspection

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.TextView
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Non-shipping test entry: synthetic evidence only, production transport unchanged. Not UI-consent proof. */
class WabiTransportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Synthetic controlled HTTPS transport test" })
        val base = intent.getStringExtra("endpoint").orEmpty()
        require(Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").matches(base))
        val mode = intent.getStringExtra("mode") ?: "all"
        if (mode == "active-network-probe") {
            val resultFile = File(noBackupFilesDir, "wabi-active-network-result.json")
            resultFile.delete()
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            Thread {
                val result = JSONObject()
                    .put("probe", "ConnectivityManager.activeNetwork.openConnection")
                    .put("network_present", network != null)
                    .put("network_handle", network?.toString() ?: JSONObject.NULL)
                    .put("transport_vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false)
                var connection: HttpURLConnection? = null
                try {
                    requireNotNull(network) { "activeNetwork is null" }
                    connection = network.openConnection(URL("$base/route-probe")) as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.instanceFollowRedirects = false
                    val status = connection.responseCode
                    result.put("http_code", status)
                    val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText().take(256) }.orEmpty()
                    result.put("response_body", body)
                    result.put("passed", status == 200)
                    if (status != 200) result.put("failure", "unexpected_http_status")
                } catch (failure: Throwable) {
                    result.put("passed", false)
                    result.put("failure", failure.javaClass.simpleName)
                } finally {
                    connection?.disconnect()
                    resultFile.writeText(result.toString())
                    runOnUiThread { finish() }
                }
            }.start()
            return
        }
        // Test-device choice of existing user setting, not a resolver/guard bypass.
        // Default FakeIP mode is tested separately and is expected to be rejected.
        if (mode == "controlled-real-dns") {
            Settings.dnsProtect = false
            File(noBackupFilesDir, "wabi-real-dns-setting.txt").writeText("dnsProtect=false; test emulator only")
            finish()
            return
        }
        val resultFile = File(noBackupFilesDir, "wabi-https-result.json")
        resultFile.delete()
        Thread {
            val results = JSONArray()
            val payload = """{"checks":[{"id":"network","state":"OBSERVED","code":"present"}]}"""
            try {
                val cases = listOf(
                    "stale-before" to "STALE_EVIDENCE", "success" to "success", "rate-limit" to "HTTP_ERROR",
                    "redirect" to "HTTP_ERROR", "malformed" to "RESPONSE_FORMAT", "oversize" to "RESPONSE_LIMIT",
                    "stale-response" to "STALE_EVIDENCE",
                )
                for ((name, expected) in if (mode == "all") cases else listOf("success" to if (mode == "reject") "rejected" else "success")) {
                    val freshnessChecks = AtomicInteger()
                    val outcome = try {
                        val answer = runBlocking {
                            InspectionAi.analyze(
                                InspectionStore.AiConfig("$base/$name/v1/chat/completions", "synthetic-model", "synthetic-only-not-a-secret"),
                                payload, { name != "stale-before" && (name != "stale-response" || freshnessChecks.incrementAndGet() <= 2) },
                            )
                        }
                        check(answer.contains("AI 未选出可复核项目"))
                        "success"
                    } catch (failure: InspectionAi.Failure) {
                        failure.reason.name
                    }
                    results.put(JSONObject().put("case", name).put("expected", expected).put("actual", outcome))
                    check(if (expected == "rejected") outcome in listOf("NETWORK_ERROR", "TIMEOUT") else outcome == expected) { "case failed: $name" }
                }
                resultFile.writeText(JSONObject().put("passed", true).put("synthetic_payload", true).put("production_transport_unchanged", true).put("cases", results).toString())
            } catch (failure: Throwable) {
                resultFile.writeText(JSONObject().put("passed", false).put("failure_class", failure.javaClass.simpleName).put("cases", results).toString())
            }
        }.start()
    }
}
