package io.nekohasekai.sfa.inspection

import org.json.JSONObject

/** A model may order existing checks; it can never supply displayed prose or actions. */
object InspectionAdvice {
    private val labels = mapOf("network" to "活动网络", "service" to "VPN 服务", "TUNNEL" to "App HTTPS 探测", "DNS" to "DNS canary", "LATENCY" to "节点延迟记录", "SUBSCRIPTION" to "订阅汇总元数据", "TUNNEL_1A" to "HTTPS 第一轮目标 A", "TUNNEL_1B" to "HTTPS 第一轮目标 B", "TUNNEL_2A" to "HTTPS 第二轮目标 A", "TUNNEL_2B" to "HTTPS 第二轮目标 B")
    private val states = mapOf("OBSERVED" to "已观察", "ATTENTION" to "需关注", "UNKNOWN" to "未知", "NOT_CHECKED" to "未检查")

    // Parse the tiny strict JSON grammar without platform-dependent regex or lenient JSON extensions.
    private fun parseReview(content: String): List<String> {
        require(content.length <= 2048)
        var pos = 0
        fun whitespace() {
            while (pos < content.length && content[pos] in " \t\n\r") pos++
        }
        fun token(value: String) {
            whitespace()
            require(content.startsWith(value, pos))
            pos += value.length
        }
        token("{")
        token("\"review\"")
        token(":")
        token("[")
        val selected = mutableListOf<String>()
        whitespace()
        if (pos < content.length && content[pos] != ']') {
            while (true) {
                token("\"")
                val start = pos
                while (pos < content.length && content[pos] != '"') {
                    require(content[pos].isLetterOrDigit() || content[pos] == '_')
                    pos++
                }
                require(pos < content.length)
                val id = content.substring(start, pos)
                require(id in labels)
                pos++
                selected.add(id)
                require(selected.size <= labels.size)
                whitespace()
                if (pos >= content.length || content[pos] != ',') break
                pos++
            }
        }
        token("]")
        token("}")
        whitespace()
        require(pos == content.length)
        return selected
    }

    fun render(content: String, payload: String): String {
        val selected = parseReview(content)
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
        require(selected.size <= eligible.size)
        require(selected.distinct().size == selected.size && eligible.containsAll(selected))
        require(eligible.isEmpty() || selected.isNotEmpty())
        // A model's arbitrary ordering must not hide a locally observed absent network.
        // This is a point-in-time observation, not proof of a root cause or Internet outage.
        val networkAbsent = facts.any { it.id == "network" && it.state == InspectionPolicy.State.ATTENTION && it.code == "absent" }
        val reviewOrder = if (networkAbsent) listOf("network") + selected.filterNot { it == "network" } else selected
        return buildString {
            appendLine("AI 仅排列可复核项目；以下文字来自本地证据模板，不显示模型自由文本。")
            appendLine("本次本地记录（不是整体健康结论）：")
            facts.forEach { appendLine("- ${labels.getValue(it.id)}：${states.getValue(it.state.name)} / ${it.code}") }
            appendLine("证据边界：活动网络存在不等于 Internet 可达；VPN 服务运行不等于流量可达；App HTTPS 探测不证明其他 App 的 VPN 路由。")
            if (facts.any { it.id == "DNS" }) appendLine("DNS canary 使用故意不存在的 .invalid 名称；不解析或返回 FakeIP 都不能单独证明 DNS 正常或故障。")
            if (facts.any { it.id == "LATENCY" }) appendLine("节点延迟来自已有记录，可能已过期，不代表当前端到端连接。")
            if (facts.any { it.id == "SUBSCRIPTION" }) appendLine("订阅状态只是本地汇总，不是服务端核验。")
            appendLine("根因尚未确定；未知和未检查不能当作正常。")
            if (networkAbsent) {
                appendLine("本地证据优先复核：活动网络（采集时未发现活动网络）；其余项目按模型选择列出。")
                if (reviewOrder.size > 1) appendLine("模型选择的其他项目：${reviewOrder.drop(1).joinToString("、") { labels.getValue(it) }}。")
            } else if (selected.isEmpty()) {
                appendLine("AI 未选出可复核项目，这不表示所有功能正常。")
            } else {
                appendLine("AI 建议优先复核：${selected.joinToString("、") { labels.getValue(it) }}。")
            }
            append("可选下一步：重新运行相同的本地巡检，对照新旧记录；是否继续由你决定，不会自动修改配置或路由。")
        }
    }
}
