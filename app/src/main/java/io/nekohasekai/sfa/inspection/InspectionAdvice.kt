package io.nekohasekai.sfa.inspection

import org.json.JSONObject
import java.util.Locale

/** Model-selected checks are unranked; displayed prose and evidence are always local. */
object InspectionAdvice {
    private val labels = mapOf("network" to "活动网络", "service" to "VPN 服务", "TUNNEL" to "App HTTPS 探测", "DNS" to "DNS canary", "LATENCY" to "节点延迟记录", "SUBSCRIPTION" to "订阅汇总元数据", "TUNNEL_1A" to "HTTPS 第一轮目标 A", "TUNNEL_1B" to "HTTPS 第一轮目标 B", "TUNNEL_2A" to "HTTPS 第二轮目标 A", "TUNNEL_2B" to "HTTPS 第二轮目标 B")
    enum class Language { ENGLISH, SIMPLIFIED, TRADITIONAL }

    fun languageFor(locale: Locale): Language = when {
        locale.language != "zh" -> Language.ENGLISH
        locale.script.equals("Hant", ignoreCase = true) -> Language.TRADITIONAL
        locale.script.equals("Hans", ignoreCase = true) -> Language.SIMPLIFIED
        locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO") -> Language.TRADITIONAL
        else -> Language.SIMPLIFIED
    }

    private val states = mapOf("OBSERVED" to "已观察", "ATTENTION" to "需关注", "UNKNOWN" to "未知", "NOT_CHECKED" to "未检查")

    private val translations = mapOf(
        "活动网络" to ("Active network" to "活動網路"),
        "VPN 服务" to ("VPN service" to "VPN 服務"),
        "App HTTPS 探测" to ("App HTTPS probe" to "App HTTPS 探測"),
        "节点延迟记录" to ("Node latency record" to "節點延遲紀錄"),
        "订阅汇总元数据" to ("Subscription summary metadata" to "訂閱彙總中繼資料"),
        "HTTPS 第一轮目标 A" to ("HTTPS round 1 target A" to "HTTPS 第一輪目標 A"),
        "HTTPS 第一轮目标 B" to ("HTTPS round 1 target B" to "HTTPS 第一輪目標 B"),
        "HTTPS 第二轮目标 A" to ("HTTPS round 2 target A" to "HTTPS 第二輪目標 A"),
        "HTTPS 第二轮目标 B" to ("HTTPS round 2 target B" to "HTTPS 第二輪目標 B"),
        "已观察" to ("Observed" to "已觀察"),
        "需关注" to ("Needs attention" to "需關注"),
        "未知" to ("Unknown" to "未知"),
        "未检查" to ("Not checked" to "未檢查"),
        "AI 选择仅供参考，不代表优先级或根因，可能遗漏项目；全部本地记录见下方，不显示模型自由文本。" to ("AI selections are for reference only, not a priority order or diagnosis, and may omit items. All local observations appear below; no free-form model text is shown." to "AI 選擇僅供參考，不代表優先順序或根因，可能遺漏項目；全部本地紀錄見下方，不顯示模型自由文字。"),
        "本次本地记录（不是整体健康结论）：" to ("Local observations from this run (not an overall health verdict):" to "本次本地紀錄（不是整體健康結論）："),
        "证据边界：活动网络存在不等于 Internet 可达；VPN 服务运行不等于流量可达；App HTTPS 探测不证明其他 App 的 VPN 路由。" to ("Limits: an active network does not establish Internet reachability; a running VPN does not prove traffic works; an app HTTPS probe does not prove other apps use the VPN." to "證據限制：活動網路存在不等於 Internet 可達；VPN 服務運行不等於流量可達；App HTTPS 探測不證明其他 App 的 VPN 路由。"),
        "DNS canary 使用故意不存在的 .invalid 名称；不解析或返回 FakeIP 都不能单独证明 DNS 正常或故障。" to ("The DNS canary uses a deliberately nonexistent .invalid name. Failure to resolve or a FakeIP response alone proves neither working nor broken DNS." to "DNS canary 使用故意不存在的 .invalid 名稱；未解析或傳回 FakeIP 都不能單獨證明 DNS 正常或故障。"),
        "节点延迟来自已有记录，可能已过期，不代表当前端到端连接。" to ("Node latency comes from an existing record and may be stale; it does not represent the current end-to-end connection." to "節點延遲來自既有紀錄，可能已過期，不代表目前端到端連線。"),
        "订阅状态只是本地汇总，不是服务端核验。" to ("Subscription status is a local summary, not a server verification." to "訂閱狀態只是本地彙總，不是伺服器驗證。"),
        "根因尚未确定；未知和未检查不能当作正常。" to ("The root cause is unknown; unknown and unchecked do not mean healthy." to "根因尚未確定；未知和未檢查不能當作正常。"),
        "本地证据优先复核：活动网络（采集时未发现活动网络）。" to ("Local evidence to review first: active network (none was found at collection time)." to "本地證據優先複核：活動網路（採集時未發現活動網路）。"),
        "本地证据优先复核：VPN 服务（采集时未运行）。" to ("Local evidence to review first: VPN service (not running at collection time)." to "本地證據優先複核：VPN 服務（採集時未運行）。"),
        "本地证据优先复核：DNS canary（金丝雀未解析，结果未知，不能当作正常）。" to ("Local evidence to review first: DNS canary (canary not resolved; result unknown, not healthy)." to "本地證據優先複核：DNS canary（金絲雀未解析，結果未知，不能當作正常）。"),
        "AI 未选出可复核项目，这不表示所有功能正常。" to ("AI selected no items for review; this does not mean everything is working." to "AI 未選出可複核項目，這不表示所有功能正常。"),
        "可选下一步：重新运行相同的本地巡检，对照新旧记录；是否继续由你决定，不会自动修改配置或路由。" to ("Optional next step: rerun the same local inspection and compare the records. You decide whether to continue; configuration and routes are never changed automatically." to "可選的下一步：重新執行相同的本地巡檢，對照新舊紀錄；是否繼續由你決定，不會自動修改設定或路由。"),
    )

    private fun translated(value: String, language: Language): String = when (language) {
        Language.SIMPLIFIED -> value
        Language.ENGLISH -> translations[value]?.first ?: value
        Language.TRADITIONAL -> translations[value]?.second ?: value
    }

    private fun aiItems(items: List<String>, language: Language): String {
        val separator = if (language == Language.ENGLISH) ", " else "、"
        // Stable code order avoids exposing provider response order as diagnostic priority.
        val names = items.sorted().joinToString(separator) { translated(labels.getValue(it), language) }
        return when (language) {
            Language.ENGLISH -> "AI-selected items (listed by ID, not priority): $names."
            Language.SIMPLIFIED -> "AI 选择的项目（按 ID 排列，不分优先级）：$names。"
            Language.TRADITIONAL -> "AI 選擇的項目（按 ID 排列，不分優先順序）：$names。"
        }
    }

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

    fun render(content: String, payload: String, language: Language = Language.SIMPLIFIED): String = renderEvidence(payload, language, parseReview(content))

    /** Deterministic local-only report. No provider request or model response is involved. */
    fun renderLocal(payload: String, language: Language = Language.SIMPLIFIED): String = renderEvidence(payload, language, null)

    private fun renderEvidence(payload: String, language: Language, selected: List<String>?): String {
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
        if (selected != null) {
            require(selected.size <= eligible.size)
            require(selected.distinct().size == selected.size && eligible.containsAll(selected))
            require(eligible.isEmpty() || selected.isNotEmpty())
        }
        // A model's arbitrary ordering must not hide a locally observed absent network.
        // This is a point-in-time observation, not proof of a root cause or Internet outage.
        val networkAbsent = facts.any { it.id == "network" && it.state == InspectionPolicy.State.ATTENTION && it.code == "absent" }
        // A stopped service is also an observed, locally known state, not a model inference.
        val serviceStopped = facts.any { it.id == "service" && it.state == InspectionPolicy.State.OBSERVED && it.code == "stopped" }
        // An unresolved DNS canary is unknown, not a fault: it must stay visible even when the model omits DNS.
        val dnsCanaryUnknown = facts.any { it.id == "DNS" && it.state == InspectionPolicy.State.UNKNOWN && it.code == "canary_not_resolved" }
        return buildString {
            if (selected != null) appendLine(translated("AI 选择仅供参考，不代表优先级或根因，可能遗漏项目；全部本地记录见下方，不显示模型自由文本。", language))
            if (networkAbsent) appendLine(translated("本地证据优先复核：活动网络（采集时未发现活动网络）。", language))
            if (serviceStopped) appendLine(translated("本地证据优先复核：VPN 服务（采集时未运行）。", language))
            if (dnsCanaryUnknown) appendLine(translated("本地证据优先复核：DNS canary（金丝雀未解析，结果未知，不能当作正常）。", language))
            if (selected != null) {
                if (selected.isEmpty()) {
                    appendLine(translated("AI 未选出可复核项目，这不表示所有功能正常。", language))
                } else {
                    appendLine(aiItems(selected, language))
                }
            }
            appendLine(translated("本次本地记录（不是整体健康结论）：", language))
            facts.forEach { appendLine("- ${translated(labels.getValue(it.id), language)}：${translated(states.getValue(it.state.name), language)} / ${it.code}") }
            appendLine(translated("证据边界：活动网络存在不等于 Internet 可达；VPN 服务运行不等于流量可达；App HTTPS 探测不证明其他 App 的 VPN 路由。", language))
            if (facts.any { it.id == "DNS" }) appendLine(translated("DNS canary 使用故意不存在的 .invalid 名称；不解析或返回 FakeIP 都不能单独证明 DNS 正常或故障。", language))
            if (facts.any { it.id == "LATENCY" }) appendLine(translated("节点延迟来自已有记录，可能已过期，不代表当前端到端连接。", language))
            if (facts.any { it.id == "SUBSCRIPTION" }) appendLine(translated("订阅状态只是本地汇总，不是服务端核验。", language))
            appendLine(translated("根因尚未确定；未知和未检查不能当作正常。", language))
            append(translated("可选下一步：重新运行相同的本地巡检，对照新旧记录；是否继续由你决定，不会自动修改配置或路由。", language))
        }
    }
}
