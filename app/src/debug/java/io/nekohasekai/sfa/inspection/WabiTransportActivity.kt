package io.nekohasekai.sfa.inspection

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** Non-shipping, one-request transport check. Fixed synthetic data; no private diagnostics or account. */
class WabiTransportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { text = "Anonymous Classic provider: fixed synthetic transport check" }
        setContentView(view)
        val result = File(noBackupFilesDir, "wabi-classic-result.json")
        result.delete()
        Thread {
            val record = JSONObject().put("synthetic_payload", true).put("android_production_transport", true)
                .put("endpoint", "https://text.pollinations.ai/openai").put("model", "openai-fast")
                .put("real_credential", false).put("vpn_tun", false).put("user_consent_ui", false)
            try {
                val answer = runBlocking {
                    InspectionAi.analyze(
                        InspectionStore.AiConfig("https://text.pollinations.ai/openai", "openai-fast", "anonymous-public-api-no-credential"),
                        """{"checks":[{"id":"network","state":"OBSERVED","code":"present"}]}""",
                        { true },
                    )
                }
                check(answer.contains("AI 未选出可复核项目"))
                record.put("passed", true).put("normalized_answer", answer)
            } catch (failure: Throwable) {
                record.put("passed", false).put("failure_class", failure.javaClass.simpleName)
                if (failure is InspectionAi.Failure) record.put("reason", failure.reason.name)
            }
            result.writeText(record.toString())
            runOnUiThread { view.text = record.toString(2) }
        }.start()
    }
}
