"""Ephemeral deterministic UI fixture; never publish or merge this branch.
Synthetic fixed evidence and a non-secret dummy key only; actual production transport is retained.
The real parser, local error types, consent UI and rendering remain in use.
"""
from pathlib import Path
p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionRunner.kt')
s=p.read_text(); a='facts = passive(before).facts + attempts + result.items.map {'
b='facts = listOf(InspectionPolicy.Fact("TUNNEL_1A", InspectionPolicy.State.ATTENTION, "tls_failure"), InspectionPolicy.Fact("TUNNEL_1B", InspectionPolicy.State.OBSERVED, "ok"), InspectionPolicy.Fact("TUNNEL", InspectionPolicy.State.ATTENTION, "timeout"), InspectionPolicy.Fact("DNS", InspectionPolicy.State.UNKNOWN, "canary_not_resolved"), InspectionPolicy.Fact("LATENCY", InspectionPolicy.State.NOT_CHECKED, "skipped")) + result.items.take(0).map {'
assert s.count(a)==1; p.write_text(s.replace(a,b))
p=Path('app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt')
s=p.read_text(); a='    LaunchedEffect(changes) {'
b='''    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            InspectionStore.saveConfig(context, InspectionStore.AiConfig("https://text.pollinations.ai/openai/chat/completions", "gpt-oss-20b", "synthetic-test"))
        }
    }
    LaunchedEffect(changes) {'''
assert s.count(a)==1; p.write_text(s.replace(a,b))
p=Path('app/src/main/java/io/nekohasekai/sfa/inspection/InspectionAi.kt')
s=p.read_text()
a='            connection.outputStream.use { it.write(body) }'
b='''            android.util.Log.i("WABI_LIVE", "POST_BEGIN")
            android.util.Log.i("WABI_BODY", android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP))
            connection.outputStream.use { it.write(body) }'''
assert s.count(a)==1
s=s.replace(a,b)
a='            val status = connection.responseCode'
b='''            val status = connection.responseCode
            android.util.Log.i("WABI_LIVE", "HTTP_STATUS=" + status)'''
assert s.count(a)==1
p.write_text(s.replace(a,b))
print('Synthetic fixed facts and config injected; real guarded transport retained')
