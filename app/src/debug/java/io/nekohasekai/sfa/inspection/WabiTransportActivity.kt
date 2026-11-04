package io.nekohasekai.sfa.inspection

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Non-shipping test entry: synthetic evidence only, production transport unchanged. Not UI-consent proof. */
class WabiTransportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Synthetic controlled HTTPS transport test" })
        val base = intent.getStringExtra("endpoint").orEmpty()
        require(Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").matches(base))
        val mode = intent.getStringExtra("mode") ?: "all"
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
