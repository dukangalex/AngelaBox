package io.nekohasekai.sfa.compose.screen.profileoverride

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppProxyClassifierTest {

    @Test
    fun overseasAppsAreNotChina() {
        val overseas = listOf(
            "ai.x.grok",
            "notion.id",
            "com.google.vr.vrcore",
            "com.google.android.syncadapters.calendar",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.tts",
            "com.microsoft.skydrive",
            "com.microsoft.office.outlook",
            "org.telegram.messenger",
            "com.android.chrome",
            "com.android.vending",
            "com.zhiliaoapp.musically",
            "com.spotify.music",
            "com.discord",
        )
        for (pkg in overseas) {
            assertFalse(pkg, PerAppProxyClassifier.isChinaApp(pkg))
            assertTrue(pkg, PerAppProxyClassifier.isForeignPackage(pkg))
        }
    }

    @Test
    fun chinaAppsAreDetectedByPackage() {
        val china = listOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.ss.android.ugc.aweme",
            "com.taobao.taobao",
            "com.android.bankabc",
            "com.MobileTicket",
            "cn.gov.tax.its",
            "tv.danmaku.bili",
            "com.xingin.xhs",
            "me.ele",
        )
        for (pkg in china) {
            assertTrue(pkg, PerAppProxyClassifier.isChinaApp(pkg))
            assertFalse(pkg, PerAppProxyClassifier.isForeignPackage(pkg))
        }
    }

    @Test
    fun chineseLabelMarksUnknownPackage() {
        assertTrue(
            PerAppProxyClassifier.isChinaApp(
                "com.example.obscurepay",
                installer = null,
                label = "某某支付",
            ),
        )
        assertFalse(
            PerAppProxyClassifier.isChinaApp(
                "ai.x.grok",
                installer = null,
                label = "Grok 助手",
            ),
        )
    }

    @Test
    fun chineseStoreInstallerMarksUnknownPackage() {
        assertTrue(
            PerAppProxyClassifier.isChinaApp(
                "com.random.tool",
                installer = "com.xiaomi.market",
                label = "Random",
            ),
        )
        assertTrue(
            PerAppProxyClassifier.isChinaApp(
                "com.example.obscurepay",
                installer = "com.android.vending",
                label = "某某支付",
            ),
        )
        assertFalse(
            PerAppProxyClassifier.isChinaApp(
                "com.random.tool",
                installer = "com.android.vending",
                label = "Random",
            ),
        )
    }

    @Test
    fun japaneseAndKoreanLabelsAreNotChina() {
        assertFalse(
            PerAppProxyClassifier.hasChineseLabel("日本語アプリ"),
        )
        assertFalse(
            PerAppProxyClassifier.hasChineseLabel("한글앱"),
        )
        assertTrue(
            PerAppProxyClassifier.hasChineseLabel("微信"),
        )
    }

    @Test
    fun sdkPresenceDoesNotMatterForNameClassifier() {
        // A foreign package must stay foreign even if the old dex scanner
        // would have seen Tencent/Umeng classes.
        assertFalse(
            PerAppProxyClassifier.isChinaApp("com.google.android.youtube"),
        )
    }
}
