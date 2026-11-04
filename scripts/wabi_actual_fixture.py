"""Review instrumentation only: preserve Android collection and prevent external send."""
from pathlib import Path
p=Path('app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt')
s=p.read_text();old='    LaunchedEffect(changes) {'
new='''    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            InspectionStore.saveConfig(context, InspectionStore.AiConfig("https://example.com/v1/chat/completions", "local-android-test", "synthetic-not-a-real-key"))
        }
    }
    LaunchedEffect(changes) {'''
assert s.count(old)==1;s=s.replace(old,new)
needle='            review = InspectionReview(result, ai, InspectionPolicy.payload(result, BuildConfig.VERSION_NAME, CoreIdentity.libboxOrPin()), InspectionRunner.stamp(context))'
replacement=needle+'\n            android.util.Log.i("WABI_REVIEW", android.util.Base64.encodeToString(review!!.payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))'
assert s.count(needle)==1;s=s.replace(needle,replacement);p.write_text(s)
print('Instrumented synthetic config and pre-send review payload only; collection unchanged')

p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionAi.kt')
s=p.read_text()
start=s.index('    suspend fun analyze(config: InspectionStore.AiConfig, payload: String, stillCurrent: () -> Boolean, language:')
end=s.index('    // RemoteUrlGuard intentionally folds',start)
replacement='''    suspend fun analyze(config: InspectionStore.AiConfig, payload: String, stillCurrent: () -> Boolean, language: InspectionAdvice.Language = InspectionAdvice.Language.SIMPLIFIED): String {
        android.util.Log.i("WABI_TEST", "AI_REQUEST_ATTEMPT")
        return analyzeUsing(config, payload, stillCurrent, language = language) { _, _ ->
            android.util.Log.i("WABI_TEST", "AI_TRANSPORT_OPEN")
            // UI-only resolver rejection fixture. Validate a fixed loopback answer;
            // no connection can be opened and no provider data can be sent.
            InspectionAi.withPreservedDnsFailure(resolve = {
                InspectionAi.checkedSystemAnswers(listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
            }) { resolve ->
                io.nekohasekai.sfa.utils.RemoteUrlGuard.validate(config.endpoint, io.nekohasekai.sfa.utils.RemoteUrlGuard.Kind.SCRIPT, resolve)
                error("Synthetic test must stop before connecting")
            }
        }
    }

'''
s=s[:start]+replacement+s[end:];p.write_text(s)
print('Instrumented call counters; transport opener fails locally, never sends externally')

# Diagnostic-only freshness trace: no IDs, addresses, profile contents or data.
# Keep every production guard and tracker transition unchanged.
p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionRunner.kt')
s=p.read_text()
a='val tracker = NetworkEvidenceTracker<Network, Pair<Long, Long>, LinkProperties>(singleNetwork) { epoch.incrementAndGet() }'
b='''var cause = "initial"
            val tracker = NetworkEvidenceTracker<Network, Pair<Long, Long>, LinkProperties>(singleNetwork) {
                val next = epoch.incrementAndGet()
                android.util.Log.i("WABI_FRESH", "invalidate callback=${if (singleNetwork) "default" else "underlying"} cause=$cause epoch=$next")
            }'''
assert s.count(a)==1;s=s.replace(a,b)
s=s.replace('override fun onAvailable(network: Network) = tracker.available(network)', 'override fun onAvailable(network: Network) { cause = "available"; tracker.available(network) }')
s=s.replace('override fun onLost(network: Network) = tracker.lost(network)', 'override fun onLost(network: Network) { cause = "lost"; tracker.lost(network) }')
s=s.replace('tracker.capabilities(network, evidenceCapabilityBits(capabilityBits) to transportBits)', 'cause = "capabilities_${capabilityBits.toString(16)}_${transportBits.toString(16)}"\n                    tracker.capabilities(network, evidenceCapabilityBits(capabilityBits) to transportBits)')
s=s.replace('tracker.link(network, LinkProperties.CREATOR.createFromParcel(parcel))', 'cause = "link"\n                        tracker.link(network, LinkProperties.CREATOR.createFromParcel(parcel))')
s=s.replace('tracker.blocked(network, blocked)', 'cause = "blocked_$blocked"\n                    tracker.blocked(network, blocked)')
a='                    if (before != stamp(context)) throw StaleInspectionEvidence()'
b='                    val afterDiagnostic = stamp(context)\n                    android.util.Log.i("WABI_FRESH", "collection_finish equal=${before == afterDiagnostic} token_equal=${before.token == afterDiagnostic.token} network_equal=${before.network == afterDiagnostic.network} service_equal=${before.tunnelUp == afterDiagnostic.tunnelUp}")\n                    if (before != stamp(context)) throw StaleInspectionEvidence()'
assert s.count(a)==1;s=s.replace(a,b);p.write_text(s)
p=Path('app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt')
s=p.read_text()
a='            now = System.currentTimeMillis()\n            if (answerStamp'
b='''            now = System.currentTimeMillis()
            snapshot?.let { saved ->
                android.util.Log.i("WABI_FRESH", "ui age_ms=${now - saved.collectedAt} token_equal=${saved.contextToken == stamp.token} network_equal=${saved.networkId == stamp.network} service_equal=${saved.serviceUp == stamp.tunnelUp} profile_equal=${saved.profileId == stamp.profile} running=$running config=${config != null}")
            }\n            if (answerStamp'''
assert s.count(a)==1;s=s.replace(a,b);p.write_text(s)
print('Added anonymous freshness causes only; all production freshness and networking decisions retained')
