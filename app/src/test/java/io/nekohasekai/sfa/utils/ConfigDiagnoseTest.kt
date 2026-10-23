package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDiagnoseTest {

    @Test
    fun fourOhFourDoesNotBlameScriptsWhenUnbound() {
        val text = ConfigDiagnose.explain(
            "initialize rule-set geosite-cn: get geosite-cn.srs: HTTP 404",
            scriptsBound = false,
        )
        assertTrue(text.contains("404") || text.contains("规则集"))
        assertFalse(text.contains("关掉脚本"))
        assertFalse(text.contains("该配置开了脚本"))
    }

    @Test
    fun fourOhFourMentionsScriptsOnlyWhenBound() {
        val text = ConfigDiagnose.explain(
            "initialize rule-set geosite-cn: HTTP 404",
            scriptsBound = true,
        )
        assertTrue(text.contains("脚本"))
    }

    @Test
    fun ruleSetNeedlesFromKernelError() {
        val needles = ConfigDiagnose.ruleSetNeedles(
            "initialize rule-set[geosite-cn]: get rule-set geosite-cn.srs: 404",
        )
        assertTrue(needles.any { it.contains("geosite-cn") })
        assertTrue(needles.any { it.endsWith(".srs") })
    }

    @Test
    fun rpcDeathIsRecognized() {
        assertTrue(
            ConfigDiagnose.looksLikeRpcDeath(
                "reload service: rpc error: code = Unavailable desc = error reading from server: EOF",
            ),
        )
        val text = ConfigDiagnose.explain(
            "reload service: rpc error: code = Unavailable desc = error reading from server: EOF",
            scriptsBound = false,
        )
        assertFalse(text.contains("EOF"))
        assertFalse(text.contains("脚本"))
    }

    @Test
    fun ruleSetNeedlesRejectDigitsAndKeepRealNames() {
        val needles = ConfigDiagnose.ruleSetNeedles(
            """
            initialize rule-set[telegram]: get telegram.srs: HTTP 404
            ERROR[0003] initialize rule-set[google]: github.srs
            initialize rule-set[12]: get 12.srs
            initialize rule-set[telegram-ip]
            initialize rule-set[category-ai!cn]
            initialize rule-set[gitlab]
            """.trimIndent(),
        )
        assertTrue(needles.any { it.contains("telegram") })
        assertTrue(needles.any { it.contains("google") || it.contains("github") })
        assertTrue(needles.any { it.contains("gitlab") })
        assertTrue(needles.any { it.contains("category-ai") })
        assertFalse(needles.any { it == "1" || it == "3" || it == "12" || it == "6" || it == "7" })
        assertFalse(needles.any { it.matches(Regex("\\d+")) })
    }

    @Test
    fun fourOhFourDoesNotSaySkip() {
        val text = ConfigDiagnose.explain(
            "initialize rule-set telegram: get telegram.srs: HTTP 404",
            scriptsBound = false,
        )
        assertFalse(text.contains("跳过"))
        assertTrue(text.contains("官方") || text.contains("替换") || text.contains("换成"))
    }

    @Test
    fun emptyErrorDoesNotTellUserToCloseScripts() {
        val text = ConfigDiagnose.explain(null, scriptsBound = false)
        assertFalse(text.contains("脚本"))
    }

    @Test
    fun preferKernelErrorKeeps404OverEof() {
        val kept = ConfigDiagnose.preferKernelError(
            "initialize rule-set geosite-cn: HTTP 404",
            "reload service: rpc error: code = Unavailable desc = error reading from server: EOF",
        )
        assertTrue(kept.orEmpty().contains("404"))
        assertFalse(kept.orEmpty().contains("EOF"))
    }

    @Test
    fun scriptFaultRecognizesKeepaliveBoolean() {
        assertTrue(ConfigDiagnose.looksLikeScriptFault("decode config: unmarshal tcp_keep_alive"))
        assertFalse(ConfigDiagnose.looksLikeScriptFault("initialize rule-set geosite-cn: HTTP 404"))
    }

    @Test
    fun decodeDoesNotBlameKeepAliveUnlessNamed() {
        val text = ConfigDiagnose.explain(
            "decode config: outbounds[0].transport: unknown transport type: xhttp",
            scriptsBound = true,
        )
        assertTrue(text.contains("xhttp"))
        assertFalse(text.contains("tcp_keep_alive"))
    }

    @Test
    fun yamlCharacterIsNotAGenericDecode() {
        val text = ConfigDiagnose.explain(
            "decode config: invalid character 'p' looking for beginning of value",
            scriptsBound = false,
        )
        assertTrue(text.contains("Clash"))
        assertFalse(text.contains("tcp_keep_alive"))
    }

    @Test
    fun rpcDeathIgnoresParserEof() {
        // Regression (audit C-低-1): a bare EOF / Unavailable substring in
        // a genuine config error used to trigger a pointless command
        // server restart before the real rung ran.
        assertFalse(ConfigDiagnose.looksLikeRpcDeath("unmarshal config: unexpected EOF"))
        assertFalse(ConfigDiagnose.looksLikeRpcDeath("decode config: stream error: EOF"))
        assertTrue(
            ConfigDiagnose.looksLikeRpcDeath(
                "reload service: rpc error: code = Unavailable desc = error reading from server: EOF",
            ),
        )
    }

    @Test
    fun badEchNeedsConfigContext() {
        // Regression (audit C-低-2): the bare "invalid ech" substring
        // stripped ECH from the whole subscription on a false positive.
        assertFalse(ConfigDiagnose.looksLikeBadEch("invalid ech value on some field"))
        assertTrue(ConfigDiagnose.looksLikeBadEch("invalid ECH configs pem"))
        assertTrue(ConfigDiagnose.looksLikeBadEch("invalid ech config: parse failed"))
    }

    @Test
    fun startTimeoutIsNotDisguisedAsRuleSetFailure() {
        // Regression (audit C-中-4): a start-budget timeout used to be
        // fabricated into an "initialize rule-set" error, which poisoned
        // the stall memory and dropped all remote rule-sets.
        val marker = "内核启动超过 45 秒没有返回"
        assertTrue(ConfigDiagnose.looksLikeStartTimeout(marker))
        assertFalse(ConfigDiagnose.looksLikeRuleSetFailure(marker))
        assertFalse(ConfigDiagnose.looksLikeRuleSetFailure("内核启动没有返回结果"))
        val text = ConfigDiagnose.explain(marker, scriptsBound = false)
        assertTrue(text.contains("超过 45 秒"))
        assertTrue(text.contains("可能"))
    }

    @Test
    fun startTimeoutMarkerAcceptsExtendedBudget() {
        // AB-019: the extended full-config retry enforces a 90 s budget
        // and its marker must still classify as a start timeout — and
        // still not as a rule-set failure.
        val marker = "内核启动超过 90 秒没有返回"
        assertTrue(ConfigDiagnose.looksLikeStartTimeout(marker))
        assertFalse(ConfigDiagnose.looksLikeRuleSetFailure(marker))
        val text = ConfigDiagnose.explain(marker, scriptsBound = false)
        assertTrue(text.contains("超过 90 秒"))
        assertTrue(text.contains("可能"))
    }

    @Test
    fun ruleSetSkippedReasonStatesTheActualCause() {
        // AB-019 (failure must speak): the banner distinguishes a bare
        // start timeout from a real download failure. A timeout must
        // not be worded as a download failure; a kernel error is quoted
        // verbatim when there is one, and not invented when there is not.
        val timeout = ConfigDiagnose.ruleSetSkippedReason(
            ConfigDiagnose.RuleSetSkipCause.START_TIMEOUT, null,
        )
        assertTrue(timeout.contains("启动超时"))
        assertFalse(timeout.contains("下载失败"))

        val kernelError = "initialize rule-set geosite-cn: Get https://example.com/x.srs: i/o timeout"
        val failed = ConfigDiagnose.ruleSetSkippedReason(
            ConfigDiagnose.RuleSetSkipCause.DOWNLOAD_FAILED, kernelError,
        )
        assertTrue(failed.contains("下载失败"))
        assertTrue(failed.contains("内核原话"))
        assertTrue(failed.contains("i/o timeout"))

        val noDetail = ConfigDiagnose.ruleSetSkippedReason(
            ConfigDiagnose.RuleSetSkipCause.DOWNLOAD_FAILED, null,
        )
        assertTrue(noDetail.contains("没能下载下来"))
        assertFalse(noDetail.contains("内核原话"))

        val memory = ConfigDiagnose.ruleSetSkippedReason(
            ConfigDiagnose.RuleSetSkipCause.MEMORY_PRE_SKIP, null,
        )
        assertTrue(memory.contains("卡住过"))
    }
}