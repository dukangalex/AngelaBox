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
}