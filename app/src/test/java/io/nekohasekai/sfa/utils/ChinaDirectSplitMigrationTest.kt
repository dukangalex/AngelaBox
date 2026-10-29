package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chinaDirect split seeding (design v2 §2.2, NF-5): the three split
 * switches inherit the user's STORED chinaDirect value. The off case
 * is the one that matters — a fill-with-defaults migration would
 * silently re-open switches a user had deliberately turned off.
 */
class ChinaDirectSplitMigrationTest {

    private fun seedWrites(
        setKeys: Set<String>,
        storedChinaDirect: Boolean,
    ): Map<String, Boolean> = Settings.chinaDirectSplitSeedMigration({ it in setKeys }, storedChinaDirect).toMap()

    @Test
    fun seedInheritsStoredOnForAllThree() {
        val writes = seedWrites(emptySet(), storedChinaDirect = true)
        assertEquals(
            mapOf(
                SettingsKey.CHINA_IP_DOMAIN_DIRECT to true,
                SettingsKey.CHINA_PUBLIC_DNS_DIRECT to true,
                SettingsKey.LAN_DIRECT to true,
            ),
            writes,
        )
    }

    @Test
    fun seedInheritsStoredOffForAllThree() {
        // Deliberate chinaDirect=off must produce three offs, never the
        // declared on-defaults.
        val writes = seedWrites(emptySet(), storedChinaDirect = false)
        assertEquals(
            mapOf(
                SettingsKey.CHINA_IP_DOMAIN_DIRECT to false,
                SettingsKey.CHINA_PUBLIC_DNS_DIRECT to false,
                SettingsKey.LAN_DIRECT to false,
            ),
            writes,
        )
    }

    @Test
    fun seedSkipsAlreadyWrittenKeys() {
        // A split key the user (or a previous seed run) already set is
        // a deliberate value and stays untouched; the rest still seed.
        val writes = seedWrites(setOf(SettingsKey.LAN_DIRECT), storedChinaDirect = false)
        assertFalse(writes.containsKey(SettingsKey.LAN_DIRECT))
        assertEquals(false, writes[SettingsKey.CHINA_IP_DOMAIN_DIRECT])
        assertEquals(false, writes[SettingsKey.CHINA_PUBLIC_DNS_DIRECT])
    }

    @Test
    fun seedWritesNothingWhenAllWritten() {
        val writes = seedWrites(Settings.CHINA_DIRECT_SPLIT_KEYS.toSet(), storedChinaDirect = true)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun defaultOverlayFlagsCarrySplitKeysAndDeclaredDefaults() {
        val flags = ConfigScriptOverride.defaultOverlayFlags()
        // Legacy umbrella stays present and on by default.
        assertTrue(flags.getBoolean("chinaDirect"))
        // Split keys ship with their declared defaults.
        assertTrue(flags.getBoolean("chinaIpDomainDirect"))
        assertTrue(flags.getBoolean("chinaPublicDnsDirect"))
        assertTrue(flags.getBoolean("lanDirect"))
        assertTrue(flags.getBoolean("vpnLeakProtect"))
        assertFalse(flags.getBoolean("privacyProtect"))
        // Pre-existing defaults are unchanged (strictRoute historically
        // defaults on in the no-overlay fallback).
        assertTrue(flags.getBoolean("strictRoute"))
        assertTrue(flags.getBoolean("dnsProtect"))
    }
}
