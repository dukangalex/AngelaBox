package io.nekohasekai.sfa.inspection
import java.io.File
import org.json.JSONObject
/** Synthetic-only counterfactual; do not expose the returned content as approved advice. */
fun main(args: Array<String>) {
    val response = JSONObject(File(args.single()).readText()).getJSONObject("response")
    val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    val original = message.getString("content")
    check(original.contains("防火墙日志"))
    var rejected = false
    try { InspectionAi.parseResponse(response.toString().toByteArray()) }
    catch (_: IllegalArgumentException) { rejected = true }
    check(rejected)
    message.put("content", original.replace("日志", "说明"))
    check(InspectionAi.parseResponse(response.toString().toByteArray()).contains("8.8.8.8"))
    println("original synthetic replay: REJECTED")
    println("only log mention replaced: ACCEPTED; prohibited public DNS advice still present")
    println("log-mention guard is a sufficient rejection cause; arbitrary semantics are not validated")
}
