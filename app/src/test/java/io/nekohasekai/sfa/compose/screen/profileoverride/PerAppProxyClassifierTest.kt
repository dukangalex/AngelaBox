package io.nekohasekai.sfa.compose.screen.profileoverride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppProxyClassifierTest {

    @Test
    fun overseasAppsAreNotChina() {
        val overseas = listOf(
            "ai.x.grok",
            "notion.id",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.microsoft.skydrive",
            "com.microsoft.office.outlook",
            "org.telegram.messenger",
            "com.android.chrome",
            "com.android.vending",
            "com.zhiliaoapp.musically",
            "com.spotify.music",
            "com.discord",
            "com.google.android.youtube",
            "com.openai.chatgpt",
            "app.revanced.android.gms",
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

    @Test
    fun systemPlumbingIsNeverAutoSelected() {
        val plumbing = listOf(
            "android",
            "com.samsung.android.provider.filterprovider",
            "com.sec.android.app.parser",
            "com.qualcomm.location",
            "com.android.systemui",
            "com.android.providers.settings",
            "com.android.phone",
            "com.trustonic.teeservice",
            "com.gd.mobicore.pa",
        )
        for (pkg in plumbing) {
            assertEquals(pkg, PerAppProxyClassifier.NetworkUse.SKIP, PerAppProxyClassifier.networkUse(pkg, system = true))
            assertFalse(pkg, PerAppProxyClassifier.isChinaApp(pkg, system = true))
            assertFalse(pkg, PerAppProxyClassifier.isForeignPackage(pkg, system = true))
        }
    }

    @Test
    fun overseasScanIsNotEverythingExceptChina() {
        assertTrue(PerAppProxyClassifier.isForeignPackage("org.telegram.messenger"))
        assertTrue(PerAppProxyClassifier.isForeignPackage("com.google.android.youtube"))
        assertTrue(PerAppProxyClassifier.isForeignPackage("app.revanced.android.gms"))
        assertTrue(PerAppProxyClassifier.isForeignPackage("com.openai.chatgpt"))
        assertFalse(PerAppProxyClassifier.isForeignPackage("com.samsung.android.incallui", system = true))
        assertFalse(PerAppProxyClassifier.isForeignPackage("io.nekohasekai.sfa"))
        assertFalse(PerAppProxyClassifier.isForeignPackage("com.v2ray.ang"))
        assertFalse(PerAppProxyClassifier.isChinaApp("com.samsung.android.lool", label = "智能管理器", system = true))
    }

    @Test
    fun preinstalledChinaConsumerAppStillCounts() {
        assertTrue(PerAppProxyClassifier.isChinaApp("com.tencent.mm", system = true))
        assertTrue(PerAppProxyClassifier.isChinaApp("com.android.bankabc", system = true))
        assertFalse(PerAppProxyClassifier.isForeignPackage("com.tencent.mm", system = true))
    }

    @Test
    fun unknownUserAppIsNotGuessedOverseas() {
        assertEquals(
            PerAppProxyClassifier.NetworkUse.SKIP,
            PerAppProxyClassifier.networkUse(
                "com.example.obscuretool",
                installer = "com.android.vending",
                label = "Random",
            ),
        )
    }

    @Test
    fun systemFlagDoesNotGuessByPrefixOrLabel() {
        assertEquals(
            PerAppProxyClassifier.NetworkUse.SKIP,
            PerAppProxyClassifier.networkUse(
                "com.google.android.ext.services",
                system = true,
            ),
        )
        assertEquals(
            PerAppProxyClassifier.NetworkUse.SKIP,
            PerAppProxyClassifier.networkUse(
                "com.google.android.setupwizard",
                system = true,
            ),
        )
        assertEquals(
            PerAppProxyClassifier.NetworkUse.SKIP,
            PerAppProxyClassifier.networkUse(
                "com.samsung.android.lool",
                label = "智能管理器",
                system = true,
            ),
        )
        assertEquals(
            PerAppProxyClassifier.NetworkUse.FOREIGN,
            PerAppProxyClassifier.networkUse("com.google.android.youtube", system = true),
        )
        assertEquals(
            PerAppProxyClassifier.NetworkUse.FOREIGN,
            PerAppProxyClassifier.networkUse("com.google.android.gms", system = true),
        )
        assertEquals(
            PerAppProxyClassifier.NetworkUse.CHINA,
            PerAppProxyClassifier.networkUse("com.tencent.mm", system = true),
        )
        assertFalse(
            PerAppProxyClassifier.isChinaApp("app.revanced.android.gms", system = false),
        )
        assertTrue(
            PerAppProxyClassifier.isForeignPackage("app.revanced.android.gms"),
        )
    }
}
