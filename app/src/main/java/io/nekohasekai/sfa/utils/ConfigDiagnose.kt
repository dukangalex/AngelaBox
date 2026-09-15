package io.nekohasekai.sfa.utils

/**
 * Turns kernel / overlay failures into short Chinese guidance.
 * Does not change the config; BoxService may then heal and retry.
 */
object ConfigDiagnose {
    fun explain(raw: String?, scriptsBound: Boolean = false): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return "启动失败，没有具体原因。请检查这份配置是否完整，或换一份再试。"
        }
        val scriptHint = if (scriptsBound) {
            "该配置开了脚本，已在修正时关掉，避免两套规则打架。"
        } else {
            "没有动你的节点、分组和分流。"
        }
        val mapped = when {
            looksLikeRpcDeath(text) -> {
                "内核刚才没起来，连接已断开。请再点一次启动。"
            }
            looksLike(text, "outbound detour not found") -> {
                val tag = extractAfter(text, "outbound detour not found:")
                "找不到出站「$tag」。规则集下载或 DNS 还指向旧名字。" +
                    if (scriptsBound) "应用会改走直连下载；仍失败会关掉该配置上的脚本。"
                    else "应用会改走直连下载后再试。"
            }
            looksLike(text, "download_detour") && looksLike(text, "not found") -> {
                "规则集的下载出口已失效。官方 1.14 请用 http_clients。" +
                    if (scriptsBound) "应用会改成直连下载；仍失败会关掉脚本。"
                    else "应用会改成直连下载后再试。"
            }
            looksLike(text, "initialize rule-set") || looksLike(text, "initial rule-set") -> {
                "远程规则集下载失败。应用会换成官方规则集后再启动。$scriptHint"
            }
            looksLike(text, "missing rule_set") || looksLike(text, "rule-set not found") -> {
                "路由引用了不存在的规则集。应用会换成官方规则集后再启动。$scriptHint"
            }
            looksLike(text, "outbound not found") || looksLike(text, "unknown outbound") -> {
                val tag = extractAfter(text, "outbound not found:").ifBlank {
                    extractAfter(text, "unknown outbound:")
                }
                "路由指向了不存在的出站「$tag」。" +
                    if (scriptsBound) "脚本覆盖分组后旧规则还在用原来的名字。已关掉该配置上的脚本。"
                    else "应用会清掉无效引用后再试。"
            }
            looksLike(text, "detour to an empty direct") -> {
                "DNS 不能 detour 到空的 direct（官方 1.12+ 会拒绝）。应用会自动去掉这条 detour。"
            }
            looksLike(text, "legacy DNS fakeip") || looksLike(text, "legacy inbound") -> {
                "订阅还在用旧版写法。应用会按官方 1.14 语法修正后再试。"
            }
            looksLike(text, "unknown transport type") -> {
                "DNS 用了内核不再支持的类型。应用会改成官方 predefined 规则后再试。"
            }
            looksLike(text, "decode config") || looksLike(text, "unmarshal") -> {
                "配置格式不符合当前官方 sing-box 语法。应用会修正除节点、分组、分流以外的字段后再试。"
            }
            looksLike(text, "脚本执行超时") -> {
                "覆写脚本运行超过 5 秒已被中止。请简化脚本，或关掉后再开。"
            }
            looksLike(text, "function main") -> {
                "脚本需要 function main(config)，并且返回官方 sing-box JSON。"
            }
            looksLike(text, "404") || (looksLike(text, "not found") && looksLike(text, ".srs")) -> {
                "规则集文件不存在（404）。应用会换成官方规则集后再启动。$scriptHint"
            }
            else -> text.take(400)
        }
        return mapped
    }

    fun rollbackHint(): String =
        "脚本导致启动失败，已回滚到订阅原规则，并保留中国直连、DNS 防泄漏等开关。修好或关掉脚本后再开。"

    fun looksLikeRpcDeath(text: String?): Boolean {
        val t = text.orEmpty()
        return t.contains("EOF", ignoreCase = true) ||
            t.contains("Unavailable", ignoreCase = true) ||
            t.contains("error reading from server", ignoreCase = true) ||
            t.contains("code = Unavailable", ignoreCase = true)
    }

    fun ruleSetNeedles(raw: String?): List<String> {
        val text = raw.orEmpty()
        if (text.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        Regex("""[A-Za-z][\w.\-!]+\.srs""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            val name = it.value.lowercase()
            if (isPlausibleRuleSetName(name)) out += name
        }
        Regex(
            """(?:rule[-_ ]?set|geosite|geoip)[:\s\[\(]+([A-Za-z][A-Za-z0-9_\-!.]{2,})""",
            RegexOption.IGNORE_CASE,
        ).findAll(text).forEach {
            val tag = it.groupValues[1].trim().trimEnd('.', ',', ';', ']', ')')
            if (isPlausibleRuleSetName(tag)) out += tag
        }
        return out.toList()
    }

    internal fun isPlausibleRuleSetName(raw: String): Boolean {
        val stem = raw.trim().lowercase()
            .substringAfterLast('/')
            .substringBefore('?')
            .removeSuffix(".srs")
        if (stem.length < 3) return false
        if (stem.all { it.isDigit() || it == '.' }) return false
        if (!stem.any { it.isLetter() }) return false
        if (stem in NEEDLE_STOPWORDS) return false
        return true
    }

    private val NEEDLE_STOPWORDS = setOf(
        "http", "https", "www", "rule", "set", "srs", "not", "found",
        "error", "initialize", "status", "download", "remote", "file", "get",
        "from", "with", "code", "html",
    )

    private fun looksLike(text: String, needle: String): Boolean =
        text.contains(needle, ignoreCase = true)

    private fun extractAfter(text: String, prefix: String): String {
        val idx = text.indexOf(prefix, ignoreCase = true)
        if (idx < 0) return "?"
        return text.substring(idx + prefix.length)
            .substringBefore('|')
            .substringBefore('\n')
            .trim()
            .trim('"', '\'', ' ', '。', '.')
            .ifBlank { "?" }
    }
}
