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
    suspend fun analyze(config: InspectionStore.AiConfig, payload: String, stillCurrent: () -> Boolean, language: InspectionAdvice.Language = InspectionAdvice.Language.SIMPLIFIED): String = analyzeUsing(config, payload, stillCurrent, language = language) { url, _ ->
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
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = bytes.size.toLong()
            override fun getInputStream() = java.io.ByteArrayInputStream(bytes)
        }
    }

'''+s[end:]
p.write_text(s)
print('Applied deterministic synthetic UI fixture; no real provider requests')

p=Path('app/src/main/java/io/nekohasekai/sfa/compose/MainActivity.kt')
s=p.read_text(); a='    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)'
b='    override fun onCreate(savedInstanceState: Bundle?) {\n        java.io.File(filesDir, "wabi-test-locale").takeIf { it.exists() }?.readText()?.trim()?.let { androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(it)) }\n        super.onCreate(savedInstanceState)'
assert s.count(a)==1; p.write_text(s.replace(a,b))

# Synthetic-only diagnostics: anonymous per-process network ordinals; never log handles, IPs or profiles.
p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionRunner.kt')
s=p.read_text()
def replace_once(old, new):
    global s
    assert s.count(old) == 1, (old, s.count(old))
    s=s.replace(old,new)
replace_once('    private val epoch = AtomicLong()', """    private val epoch = AtomicLong()
    private val diagNetworks = mutableMapOf<Network, Int>()
    @Synchronized private fun diagId(network: Network?): Int = if (network == null) 0 else diagNetworks.getOrPut(network) { diagNetworks.size + 1 }""")
replace_once('fun networkCallback(): ConnectivityManager.NetworkCallback {', 'fun networkCallback(label: String): ConnectivityManager.NetworkCallback {')
replace_once('val tracker = NetworkEvidenceTracker<Network, Pair<Long, Long>, LinkProperties> { epoch.incrementAndGet() }', 'val tracker = NetworkEvidenceTracker<Network, Pair<Long, Long>, LinkProperties> { android.util.Log.i("WABI_TRACE", "invalidate label=$label epoch=${epoch.incrementAndGet()}") }')
replace_once('override fun onAvailable(network: Network) = tracker.available(network)', 'override fun onAvailable(network: Network) { android.util.Log.i("WABI_TRACE", "available label=$label id=${diagId(network)}"); tracker.available(network) }')
replace_once('override fun onLost(network: Network) = tracker.lost(network)', 'override fun onLost(network: Network) { android.util.Log.i("WABI_TRACE", "lost label=$label id=${diagId(network)}"); tracker.lost(network) }')
replace_once('                    tracker.capabilities(network, evidenceCapabilityBits(capabilityBits) to transportBits)', '                    android.util.Log.i("WABI_TRACE", "capabilities label=$label id=${diagId(network)} caps=$capabilityBits transports=$transportBits")\n                    tracker.capabilities(network, evidenceCapabilityBits(capabilityBits) to transportBits)')
replace_once('                    // Snapshot the mutable object using its public Parcelable API.', '                    android.util.Log.i("WABI_TRACE", "link label=$label id=${diagId(network)}")\n                    // Snapshot the mutable object using its public Parcelable API.')
replace_once('                    tracker.blocked(network, blocked)', '                    android.util.Log.i("WABI_TRACE", "blocked label=$label id=${diagId(network)} blocked=$blocked")\n                    tracker.blocked(network, blocked)')
replace_once('manager.registerDefaultNetworkCallback(networkCallback())','manager.registerDefaultNetworkCallback(networkCallback("default"))')
replace_once('            networkCallback(),','            networkCallback("physical"),')
replace_once('        ProfileManager.registerCallback { epoch.incrementAndGet() }', '        ProfileManager.registerCallback { android.util.Log.i("WABI_TRACE", "profile-change epoch=${epoch.incrementAndGet()}") }')
replace_once('        return ContextStamp(\n', '        android.util.Log.i("WABI_TRACE", "stamp epoch=${epoch.get()} activeId=${diagId(context.getSystemService(ConnectivityManager::class.java)?.activeNetwork)} tunnel=${TunnelGate.up} generation=${TunnelGate.generation}")\n        return ContextStamp(\n')
p.write_text(s)
