"""Review instrumentation only: preserve Android collection and prevent external send."""
from pathlib import Path
p=Path('app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt')
s=p.read_text();old='    LaunchedEffect(changes) {'
new='''    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            InspectionStore.saveConfig(context, InspectionStore.AiConfig("https://example.com/v1/chat/completions", "local-android-test", ""))
        }
    }
    LaunchedEffect(changes) {'''
assert s.count(old)==1;s=s.replace(old,new)
needle='            review = InspectionReview(result, ai, InspectionPolicy.payload(result, BuildConfig.VERSION_NAME, CoreIdentity.libboxOrPin()), InspectionRunner.stamp(context))'
replacement=needle+'\n            android.util.Log.i("WABI_REVIEW", android.util.Base64.encodeToString(review!!.payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))'
assert s.count(needle)==1;s=s.replace(needle,replacement);p.write_text(s)
print('Instrumented synthetic config and pre-send review payload only; collection unchanged')
