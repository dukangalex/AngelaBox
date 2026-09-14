package io.nekohasekai.sfa.utils

/**
 * Turns kernel / overlay failures into short Chinese guidance.
 * Does not change the config; BoxService may then retry without scripts.
 */
object ConfigDiagnose {
    fun explain(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return "启动失败，没有具体原因。请先关掉脚本再试；仍不行就换一份订阅。"
        }
        val mapped = when {
            looksLike(text, "outbound detour not found") -> {
                val tag = extractAfter(text, "outbound detour not found:")
                "找不到出站「$tag」。脚本改写了分组，但规则集下载或 DNS 仍指向旧名字。" +
                    "应用会自动改走直连下载规则集；若仍失败，请关掉该配置上的脚本后重试。"
            }
            looksLike(text, "download_detour") && looksLike(text, "not found") -> {
                "规则集的下载出口已失效。官方 1.14 请用 http_clients 而不是旧的 download_detour。" +
                    "应用会自动改成直连下载；仍失败就关掉脚本。"
            }
            looksLike(text, "initialize rule-set") || looksLike(text, "initial rule-set") -> {
                "远程规则集下载失败。常见原因：下载出口指向已删除的分组、或规则集地址 404。" +
                    "国内镜像应直连，不要走代理。关掉脚本或更新默认脚本后再开。"
            }
            looksLike(text, "missing rule_set") || looksLike(text, "rule-set not found") -> {
                "路由引用了不存在的规则集。脚本和订阅的规则集名字不一致。" +
                    "关掉脚本可回到订阅自带规则。"
            }
            looksLike(text, "outbound not found") || looksLike(text, "unknown outbound") -> {
                val tag = extractAfter(text, "outbound not found:").ifBlank {
                    extractAfter(text, "unknown outbound:")
                }
                "路由指向了不存在的出站「$tag」。脚本覆盖分组后，旧规则还在用原来的名字。" +
                    "关掉脚本，或把脚本改成使用当前配置里真实存在的 tag。"
            }
            looksLike(text, "detour to an empty direct") -> {
                "DNS 不能 detour 到空的 direct（官方 1.12+ 会拒绝）。应用会自动去掉这条 detour。"
            }
            looksLike(text, "legacy DNS fakeip") || looksLike(text, "legacy inbound") -> {
                "订阅还在用旧版写法。应用会在启动时按官方 1.14 语法迁移；若仍失败请更新订阅。"
            }
            looksLike(text, "unknown transport type") -> {
                "DNS 用了内核不再支持的类型。应用会改成官方 predefined 规则；仍失败请更新订阅。"
            }
            looksLike(text, "decode config") || looksLike(text, "unmarshal") -> {
                "配置格式不符合当前官方 sing-box 语法。不要混用 Clash 字段。脚本必须输出官方 JSON。"
            }
            looksLike(text, "脚本执行超时") -> {
                "覆写脚本运行超过 5 秒已被中止，以免卡住启动。请简化脚本，或关掉后再开。"
            }
            looksLike(text, "function main") -> {
                "脚本需要 function main(config)，并且返回官方 sing-box JSON。"
            }
            looksLike(text, "404") || looksLike(text, "not found") && looksLike(text, ".srs") -> {
                "规则集文件不存在（404）。应用会丢掉无效的远程规则集；仍缺关键规则就关掉脚本。"
            }
            else -> text.take(400)
        }
        return mapped
    }

    fun rollbackHint(): String =
        "脚本导致启动失败，已回滚到订阅原规则，并保留中国直连、DNS 防泄漏等开关。修好或关掉脚本后再开。"

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
