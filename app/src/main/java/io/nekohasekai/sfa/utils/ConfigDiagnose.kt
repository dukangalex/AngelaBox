package io.nekohasekai.sfa.utils

/**
 * Turns kernel / overlay failures into short Chinese guidance.
 * Does not change the config; BoxService may then heal and retry.
 */
object ConfigDiagnose {
    fun explain(raw: String?, scriptsBound: Boolean = false, ruleSetRetried: Boolean = false): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return "启动失败，没有具体原因。请检查这份配置是否完整，或换一份再试。"
        }
        val scriptHint = if (scriptsBound) {
            "该配置开了脚本，绑定还在；这次先按订阅原规则启动。"
        } else {
            "没有动你的节点、分组和分流。"
        }
        val mapped = when {
            looksLikeRpcDeath(text) -> {
                "内核刚才没起来，连接已断开。请再点一次启动。"
            }
            looksLikeStartTimeout(text) -> {
                // Keep the budget number the marker actually carries (45 s
                // standard, 90 s for the extended full-config retry) and do
                // not promise a next step: by the time this text is shown,
                // the ladder has already run — and a timeout alone cannot
                // say WHICH part was slow (AB-019).
                val seconds = Regex("""超过\s*(\d+)\s*秒""").find(text)?.groupValues?.get(1) ?: "45"
                "内核启动超过 $seconds 秒没有返回。可能是远程规则集下载慢，也可能是设备或网络慢，仅凭超时断定不了是哪一段出了问题。"
            }
            looksLike(text, "outbound detour not found") -> {
                val tag = extractAfter(text, "outbound detour not found:")
                "找不到出站「$tag」。规则集下载或 DNS 还指向旧名字。" +
                    if (scriptsBound) {
                        "应用会改走直连下载后再试，脚本绑定仍保留。"
                    } else {
                        "应用会改走直连下载后再试。"
                    }
            }
            looksLike(text, "download_detour") && looksLike(text, "not found") -> {
                "规则集的下载出口已失效。官方 1.14 请用 http_clients。" +
                    if (scriptsBound) {
                        "应用会改成直连下载后再试，脚本绑定仍保留。"
                    } else {
                        "应用会改成直连下载后再试。"
                    }
            }
            looksLike(text, "initialize rule-set") || looksLike(text, "initial rule-set") -> {
                if (ruleSetRetried) {
                    "远程规则集下不下来，跳过之后仍然启动失败。节点和分组没有改成直连。$scriptHint"
                } else {
                    "远程规则集下载失败。应用会换成官方规则集后再启动。$scriptHint"
                }
            }
            looksLike(text, "missing rule_set") || looksLike(text, "rule-set not found") -> {
                if (ruleSetRetried) {
                    "路由引用了不存在的规则集，跳过之后仍然启动失败。节点和分组没有改成直连。$scriptHint"
                } else {
                    "路由引用了不存在的规则集。应用会换成官方规则集后再启动。$scriptHint"
                }
            }
            looksLike(text, "outbound not found") || looksLike(text, "unknown outbound") -> {
                val tag = extractAfter(text, "outbound not found:").ifBlank {
                    extractAfter(text, "unknown outbound:")
                }
                "路由指向了不存在的出站「$tag」。" +
                    if (scriptsBound) {
                        "脚本覆盖分组后旧规则还在用原来的名字。绑定还在，修好后再开。"
                    } else {
                        "应用会清掉无效引用后再试。"
                    }
            }
            looksLike(text, "detour to an empty direct") -> {
                "DNS 不能 detour 到空的 direct（官方 1.12+ 会拒绝）。应用会自动去掉这条 detour。"
            }
            looksLike(text, "legacy DNS fakeip") || looksLike(text, "legacy inbound") -> {
                "订阅还在用旧版写法。应用会按官方 1.14 语法修正后再试。"
            }
            looksLike(text, "unknown transport type") &&
                (looksLike(text, "xhttp") || looksLike(text, "splithttp")) -> {
                "当前内核还不支持 xhttp。这种节点会跳过；如果整份都是 xhttp，不会改成直连。节点自带的 ECH 会保留在能用的节点上。"
            }
            looksLike(text, "masque") -> {
                "当前内核还不支持 MASQUE。这种节点会跳过，不会改成直连。"
            }
            looksLike(text, "unknown transport type") -> {
                if (looksLike(text, "rcode") || looksLike(text, "predefined")) {
                    "DNS 用了内核不再支持的类型。应用会改成官方 predefined 规则后再试。"
                } else {
                    "节点用了当前内核不认识的传输。xhttp 和 MASQUE 还不能用，没有改成直连。"
                }
            }
            looksLike(text, "invalid character") && looksLike(text, "'p'") -> {
                "这还是 Clash 原文，没有转成 sing-box。请再保存一次。不会改成直连。"
            }
            looksLike(text, "mixed-port") -> {
                "配置里还有 Clash 的 mixed-port。应用会转成 sing-box 再启动，不会改成直连。"
            }
            looksLike(text, "address already in use") -> {
                "本机端口已被占用。应用会去掉多余的本地混合入站后再试。"
            }
            looksLike(text, "configure tun interface") ||
                (looksLike(text, "tun") && looksLike(text, "invalid argument")) -> {
                "系统没能创建 TUN。请允许 VPN，并关掉其他正在占用的 VPN 后再启动。"
            }
            looksLike(text, "decode config") || looksLike(text, "unmarshal") -> {
                if (looksLike(text, "tcp_keep_alive")) {
                    "tcp_keep_alive 必须是时长（例如 60s），不能写 true。应用会改成 60s 后再试。"
                } else if (scriptsBound) {
                    "脚本写出了内核读不了的字段。绑定还在，修好后再开。"
                } else {
                    "配置格式不符合当前官方 sing-box 语法。应用会修正除节点、分组、分流以外的字段后再试。"
                }
            }
            looksLike(text, "脚本执行超时") -> {
                "覆写脚本运行超过 5 秒已被中止。请简化脚本，或关掉后再开。"
            }
            looksLike(text, "function main") -> {
                "脚本需要 function main(config)，并且返回官方 sing-box JSON。"
            }
            looksLikeDomainResolver(text) -> {
                val name = extractAfter(text, "not found:").ifBlank { "local" }
                "节点用来解析域名的 DNS「$name」不存在。启动时会补上本机 DNS 再试。没有改成直连。"
            }
            looksLike(text, "404") || (looksLike(text, "not found") && looksLike(text, ".srs")) -> {
                if (ruleSetRetried) {
                    "规则集文件跳过之后仍然不存在。节点和分组没有改成直连。$scriptHint"
                } else {
                    "规则集文件不存在（404）。应用会换成官方规则集后再启动。$scriptHint"
                }
            }
            else -> text.take(400)
        }
        return mapped
    }

    fun rollbackHint(): String = "脚本这次没套上，已按订阅原规则启动。绑定还在，修好后再开即可。"

    fun looksLikeRpcDeath(text: String?): Boolean {
        val t = text.orEmpty()
        // Narrow on purpose: a bare "EOF" / "Unavailable" substring also
        // appears inside genuine config errors (a truncated subscription
        // reports "unexpected EOF" from the JSON parser), and restarting
        // the command server for those just wastes one ladder rung and
        // muddies the attribution. Config-format errors are never RPC death.
        if (looksLike(t, "decode config") || looksLike(t, "unmarshal")) return false
        return looksLike(t, "error reading from server") ||
            looksLike(t, "code = Unavailable") ||
            looksLike(t, "unexpected EOF")
    }

    fun looksLikeBadEch(text: String?): Boolean {
        val t = text.orEmpty()
        // Require the config context: a bare "invalid ech" substring can
        // appear in unrelated errors, and a false positive strips ECH from
        // the whole subscription for one retry.
        return looksLike(t, "invalid ECH configs pem") || looksLike(t, "invalid ech config")
    }

    /** BoxService's own start-budget marker (not a kernel rule-set error). */
    fun looksLikeStartTimeout(text: String?): Boolean {
        val t = text.orEmpty()
        // The budget in the marker varies (45 s standard, 90 s extended
        // retry), so match the shape, not one fixed number.
        return (looksLike(t, "内核启动超过") && looksLike(t, "秒没有返回")) ||
            looksLike(t, "内核启动没有返回结果")
    }

    /** Why the start ladder ended up skipping the remote rule sets. */
    enum class RuleSetSkipCause {
        /** A stall inside this service run was remembered; this reload pre-skipped. */
        MEMORY_PRE_SKIP,

        /** The start ran past its budget; nothing proved the downloads themselves failed. */
        START_TIMEOUT,

        /** The kernel reported a rule-set download/initialise failure. */
        DOWNLOAD_FAILED,
    }

    /**
     * Banner text for a start that skipped the remote rule sets. The
     * cause must be stated as what it actually is (AB-019): a bare
     * timeout is not a download failure and must not be worded as one;
     * a real kernel error is quoted verbatim when we have it, and when
     * we do not, the text says no more than what is known.
     */
    fun ruleSetSkippedReason(cause: RuleSetSkipCause, kernelError: String?): String = when (cause) {
        RuleSetSkipCause.MEMORY_PRE_SKIP ->
            "不久前在当前网络上，远程规则集下载卡住过。这次先跳过这些规则集快速启动，避免再等一轮。"
        RuleSetSkipCause.START_TIMEOUT ->
            "这次启动超时（内核在预算时间内没有启动完），已先跳过远程规则集后启动。超时本身不能证明规则集有问题，也可能是设备或网络整体慢。"
        RuleSetSkipCause.DOWNLOAD_FAILED -> {
            val detail = kernelError?.trim()?.takeIf { it.isNotEmpty() }
            if (detail != null) {
                "远程规则集下载失败，已跳过这些规则集后启动。内核原话：${detail.take(180)}"
            } else {
                "远程规则集这次没能下载下来，已跳过这些规则集后启动。"
            }
        }
    }

    fun looksLikeDomainResolver(text: String?): Boolean {
        val t = text.orEmpty()
        return looksLike(t, "domain resolver not found") ||
            looksLike(t, "default domain resolver not found")
    }

    fun looksLikeRuleSetFailure(text: String?): Boolean {
        val t = text.orEmpty()
        return looksLike(t, "initialize rule-set") ||
            looksLike(t, "initial rule-set") ||
            looksLike(t, "missing rule_set") ||
            looksLike(t, "rule-set not found") ||
            (looksLike(t, ".srs") && (looksLike(t, "404") || looksLike(t, "not found")))
    }

    fun looksLikeScriptFault(text: String?): Boolean {
        val t = text.orEmpty()
        return looksLike(t, "decode config") ||
            looksLike(t, "unmarshal") ||
            looksLike(t, "function main") ||
            looksLike(t, "脚本执行超时") ||
            looksLike(t, "tcp_keep_alive")
    }

    /**
     * After closeService the retry often dies with EOF. Keep the first
     * kernel reason (404 / decode) so the dialog is not a raw gRPC dump.
     */
    fun preferKernelError(first: String?, retry: String?): String? {
        val original = first?.trim().orEmpty()
        val next = retry?.trim().orEmpty()
        if (next.isEmpty()) return first
        if (looksLikeRpcDeath(next) && original.isNotEmpty() && !looksLikeRpcDeath(original)) {
            return first
        }
        return retry
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

    private fun looksLike(text: String, needle: String): Boolean = text.contains(needle, ignoreCase = true)

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
