package io.nekohasekai.sfa.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class PortableCloudBackupTest {
    @Test
    fun manifestIsPortable() {
        val raw = PortableCloudBackup.manifest("android", 1).toString()
        assertTrue(PortableCloudBackup.isPortableManifest(raw))
        val obj = JSONObject(raw)
        assertEquals(PortableCloudBackup.FORMAT, obj.getString("format"))
        assertEquals(PortableCloudBackup.APP, obj.getString("app"))
        assertEquals("omitted", obj.getString("secrets"))
        assertFalse(raw.contains("password"))
    }

    @Test
    fun rejectsUnrelatedManifest() {
        assertFalse(PortableCloudBackup.isPortableManifest("""{"version":1,"app":"other"}"""))
        assertFalse(PortableCloudBackup.isPortableManifest("not-json"))
        assertFalse(PortableCloudBackup.isPortableManifest(""))
    }

    @Test
    fun profilesRoundTripAndRejectTraversal() {
        val profile = PortableCloudBackup.PortableProfile(
            id = "550e8400-e29b-41d4-a716-446655440000",
            name = "机场",
            type = "remote",
            remoteUrl = "https://example.com/sub",
            autoUpdate = true,
            autoUpdateIntervalMinutes = 60,
            lastUpdated = 1710000000000,
            icon = null,
            config = "configs/550e8400-e29b-41d4-a716-446655440000.json",
            order = 0,
        )
        val encoded = PortableCloudBackup.encodeProfiles(profile.id, listOf(profile))
        val (selected, parsed) = PortableCloudBackup.parseProfiles(encoded)
        assertEquals(profile.id, selected)
        assertEquals(1, parsed.size)
        assertEquals(profile.name, parsed[0].name)
        assertEquals("remote", parsed[0].type)
        assertEquals(profile.remoteUrl, parsed[0].remoteUrl)

        val bad = JSONObject(encoded)
        bad.getJSONArray("profiles").getJSONObject(0).put("config", "configs/../secret.json")
        val (_, skipped) = PortableCloudBackup.parseProfiles(bad.toString())
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun stableIdCodec() {
        val encoded = ProfileStableIds.encode(mapOf(3L to "550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(encoded.contains("550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(JSONObject(encoded).getString("3").isNotBlank())
    }
}
