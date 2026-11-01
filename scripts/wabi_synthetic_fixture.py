from pathlib import Path
p = Path("app/src/main/java/io/nekohasekai/sfa/inspection/InspectionRunner.kt")
s = p.read_text()
a = "facts = passive(before).facts + result.items.map {"
b = "facts = listOf(InspectionPolicy.Fact(\"TUNNEL\", InspectionPolicy.State.ATTENTION, \"timeout\"), InspectionPolicy.Fact(\"DNS\", InspectionPolicy.State.UNKNOWN, \"canary_not_resolved\"), InspectionPolicy.Fact(\"LATENCY\", InspectionPolicy.State.NOT_CHECKED, \"skipped\")) + result.items.take(0).map {"
assert s.count(a) == 1
p.write_text(s.replace(a,b))
p = Path("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt")
s = p.read_text()
a = "    LaunchedEffect(changes) {"
b = """    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            InspectionStore.saveConfig(context, InspectionStore.AiConfig(\"https://text.pollinations.ai/openai/chat/completions\", \"gpt-oss-20b\", \"synthetic-test\"))
        }
    }
    LaunchedEffect(changes) {"""
assert s.count(a) == 1
s = s.replace(a,b)
a = "                            val output = InspectionAi.analyze(approved.config, approved.payload, ::current)"
b = """                            android.util.Log.i(\"WABI_TEST\", \"AI_REQUEST_ATTEMPT\")
                            val output = InspectionAi.analyze(approved.config, approved.payload, ::current)"""
assert s.count(a) == 1
p.write_text(s.replace(a,b))
print("Applied synthetic fixture only in ephemeral CI checkout; no production changes")
