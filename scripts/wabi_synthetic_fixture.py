"""Ephemeral deterministic UI fixture; never publish or merge this branch.
No external provider call: production transport seam returns in-memory HTTPS responses.
The real parser, local error types, consent UI and rendering remain in use.
"""
from pathlib import Path
p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionRunner.kt')
s=p.read_text(); a='facts = passive(before).facts + attempts + result.items.map {'
b='facts = listOf(InspectionPolicy.Fact("network", InspectionPolicy.State.OBSERVED, "present"), InspectionPolicy.Fact("service", InspectionPolicy.State.OBSERVED, "stopped"), InspectionPolicy.Fact("TUNNEL", InspectionPolicy.State.NOT_CHECKED, "not_started"), InspectionPolicy.Fact("DNS", InspectionPolicy.State.UNKNOWN, "canary_not_resolved")) + result.items.take(0).map {'
assert s.count(a)==1; p.write_text(s.replace(a,b))
p=Path('app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt')
s=p.read_text(); a='    LaunchedEffect(changes) {'
b='''    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            InspectionStore.saveConfig(context, InspectionStore.AiConfig("https://example.com/v1/chat/completions", "synthetic-ui", "synthetic-test"))
        }
    }
    LaunchedEffect(changes) {'''
assert s.count(a)==1; p.write_text(s.replace(a,b))
p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionAi.kt')
s=p.read_text(); start=s.index('    suspend fun analyze(config:'); end=s.index('    // RemoteUrlGuard intentionally folds',start)
s=s[:start]+'''    private val syntheticAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    suspend fun analyze(config: InspectionStore.AiConfig, payload: String, stillCurrent: () -> Boolean): String = analyzeUsing(config, payload, stillCurrent) { url, _ ->
        val attempt = syntheticAttempt.incrementAndGet()
        android.util.Log.i("WABI_TEST", "AI_REQUEST_ATTEMPT")
        val bytes = when (attempt) {
            2 -> """{"choices":[{"finish_reason":"stop","message":{"content":"切换 DNS 模式以解决故障"}}]}"""
            else -> org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject().put("finish_reason", "stop").put("message", org.json.JSONObject().put("content", """{"review":["TUNNEL","DNS"]}""")))).toString()
        }.toByteArray()
        object : HttpsURLConnection(java.net.URL(url)) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getCipherSuite() = "SYNTHETIC"
            override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
            override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
            override fun getPeerPrincipal(): java.security.Principal? = null
            override fun getLocalPrincipal(): java.security.Principal? = null
            override fun getOutputStream() = object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    android.util.Log.i("WABI_BODY", android.util.Base64.encodeToString(toByteArray(), android.util.Base64.NO_WRAP))
                }
            }
            override fun getResponseCode() = if (attempt == 1) 429 else 200
            override fun getContentLengthLong() = bytes.size.toLong()
            override fun getInputStream() = java.io.ByteArrayInputStream(bytes)
        }
    }

'''+s[end:]
p.write_text(s)
print('Applied deterministic synthetic UI fixture; no real provider requests')
