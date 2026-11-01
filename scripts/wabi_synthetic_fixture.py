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

# Synthetic-only diagnostics: never install this instrumentation in production.
p = Path("app/src/main/java/io/nekohasekai/sfa/inspection/InspectionAi.kt")
s = p.read_text()
a = "            require(connection.responseCode in 200..299)"
b = """            val syntheticStatus = connection.responseCode
            android.util.Log.i("WABI_TEST", "AI_HTTP_STATUS=" + syntheticStatus)
            require(syntheticStatus in 200..299)"""
assert s.count(a) == 1
s = s.replace(a, b)
a = "            parseResponse(bytes.toByteArray())"
b = """            android.util.Log.i("WABI_TEST", "AI_RESPONSE_BYTES=" + bytes.size())
            try {
                parseResponse(bytes.toByteArray()).also { android.util.Log.i("WABI_TEST", "AI_PARSE_OK") }
            } catch (e: Exception) {
                android.util.Log.i("WABI_TEST", "AI_PARSE_REJECT=" + e.javaClass.simpleName)
                throw e
            }"""
assert s.count(a) == 1
p.write_text(s.replace(a, b))
p = Path("app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/InspectionScreen.kt")
s = p.read_text()
a = """                        } catch (_: Exception) {
                            message = R.string.inspection_ai_failed"""
b = """                        } catch (e: Exception) {
                            android.util.Log.i("WABI_TEST", "AI_EXCEPTION=" + e.javaClass.simpleName)
                            message = R.string.inspection_ai_failed"""
assert s.count(a) == 1
p.write_text(s.replace(a, b))

# Identify the last completed transport stage using constants only. This is a
# synthetic fixture; never log a real payload, key, URL, IP or exception message.
p = Path("app/src/main/java/io/nekohasekai/sfa/inspection/InspectionAi.kt")
s = p.read_text()
for a,b in [
    ('        val connection = open(', '        android.util.Log.i("WABI_TEST", "AI_STAGE=before_open")\n        val connection = open('),
    ('        try {\n            // Resolve once', '        android.util.Log.i("WABI_TEST", "AI_STAGE=opened")\n        try {\n            // Resolve once'),
    ('            connection.outputStream.use { it.write(body) }', '            android.util.Log.i("WABI_TEST", "AI_STAGE=before_output")\n            connection.outputStream.use { it.write(body) }\n            android.util.Log.i("WABI_TEST", "AI_STAGE=output_sent")'),
    ('            val syntheticStatus = connection.responseCode', '            android.util.Log.i("WABI_TEST", "AI_STAGE=before_response")\n            val syntheticStatus = connection.responseCode'),
]:
    assert s.count(a) == 1, a
    s = s.replace(a,b)
p.write_text(s)
