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
start=s.index('    suspend fun analyze(config: InspectionStore.AiConfig, payload: String, stillCurrent: () -> Boolean): String = analyzeUsing')
end=s.index('    // RemoteUrlGuard intentionally folds',start)
replacement='''    suspend fun analyze(config: InspectionStore.AiConfig, payload: String, stillCurrent: () -> Boolean): String {
        android.util.Log.i("WABI_TEST", "AI_REQUEST_ATTEMPT")
        return analyzeUsing(config, payload, stillCurrent) { _, _ ->
            android.util.Log.i("WABI_TEST", "AI_TRANSPORT_OPEN")
            throw java.io.IOException("Synthetic acceptance blocks all external transport")
        }
    }

'''
s=s[:start]+replacement+s[end:];p.write_text(s)
print('Instrumented call counters; transport opener fails locally, never sends externally')
