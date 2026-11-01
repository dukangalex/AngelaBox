package io.nekohasekai.sfa.inspection

import org.json.JSONObject
import org.json.JSONTokener

/** A model may order existing checks; it can never supply displayed prose or actions. */
object InspectionAdvice {
    private val labels = mapOf("network" to "活动网络", "service" to "VPN 服务", "TUNNEL" to "App HTTPS 探测", "DNS" to "DNS canary", "LATENCY" to "节点延迟记录", "SUBSCRIPTION" to "订阅汇总元数据", "TUNNEL_1A" to "HTTPS 第一轮目标 A", "TUNNEL_1B" to "HTTPS 第一轮目标 B", "TUNNEL_2A" to "HTTPS 第二轮目标 A", "TUNNEL_2B" to "HTTPS 第二轮目标 B")
    private val states = mapOf("OBSERVED" to "已观察", "ATTENTION" to "需关注", "UNKNOWN" to "未知", "NOT_CHECKED" to "未检查")

    // Fixed grammar intentionally rejects JSON extensions, escapes, additional fields and prose.
    private val responseGrammar = Regex("""^\s*\{\s*"review"\s*:\s*\[\s*(?:"(?:network|service|TUNNEL|DNS|LATENCY|SUBSCRIPTION|TUNNEL_1A|TUNNEL_1B|TUNNEL_2A|TUNNEL_2B)"(?:\s*,\s*"(?:network|service|TUNNEL|DNS|LATENCY|SUBSCRIPTION|TUNNEL_1A|TUNNEL_1B|TUNNEL_2A|TUNNEL_2B)")*)?\s*]\s*}\s*$""")

    fun render(content: String, payload: String): String {
        require(content.length <= 2048 && responseGrammar.matches(content))
        val tokener = JSONTokener(content)
        val reply = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException("expected object")
        require(tokener.nextClean() == '\u0000')
        require(reply.keys().asSequence().toSet() == setOf("review"))
        val checks = JSONObject(payload).getJSONArray("checks")
        require(checks.length() in 1..10)
        val facts = (0 until checks.length()).map { index ->
            val item = checks.getJSONObject(index)
            val fact = InspectionPolicy.Fact(item.getString("id"), InspectionPolicy.State.valueOf(item.getString("state")), item.getString("code"))
            require(fact.id in labels && InspectionPolicy.normalized(fact) == fact)
            fact
        }
        require(facts.map { it.id }.distinct().size == facts.size)
        val eligible = facts.filter { it.state != InspectionPolicy.State.OBSERVED }.map { it.id }.toSet()
        val review = reply.getJSONArray("review")
        require(review.length() <= eligible.size)
        val selected = (0 until review.length()).map { review.getString(it) }
        require(selected.distinct().size == selected.size && eligible.containsAll(selected))
        require(eligible.isEmpty() || selected.isNotEmpty())
        return buildString {
            appendLine("AI 仅排列可复核项目；以下文字来自本地证据模板，不显示模型自由文本。")
            appendLine("本次本地记录（不是整体健康结论）：")
            facts.forEach { appendLine("- ${labels.getValue(it.id)}：${states.getValue(it.state.name)} / ${it.code}") }
            appendLine("证据边界：活动网络存在不等于 Internet 可达；VPN 服务运行不等于流量可达；App HTTPS 探测不证明其他 App 的 VPN 路由。")
            if (facts.any { it.id == "DNS" }) appendLine("DNS canary 使用故意不存在的 .invalid 名称；不解析或返回 FakeIP 都不能单独证明 DNS 正常或故障。")
            if (facts.any { it.id == "LATENCY" }) appendLine("节点延迟来自已有记录，可能已过期，不代表当前端到端连接。")
            if (facts.any { it.id == "SUBSCRIPTION" }) appendLine("订阅状态只是本地汇总，不是服务端核验。")
            appendLine("根因尚未确定；未知和未检查不能当作正常。")
            if (selected.isEmpty()) {
                appendLine("AI 未选出可复核项目，这不表示所有功能正常。")
            } else {
                appendLine("AI 建议优先复核：${selected.joinToString("、") { labels.getValue(it) }}。")
            }
            append("可选下一步：重新运行相同的本地巡检，对照新旧记录；是否继续由你决定，不会自动修改配置或路由。")
        }
    }
}
