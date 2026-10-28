package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Bundled rule-set seeds (137a). The four app-owned sets ship as
 * compiled `.srs` files in the APK and are stamped onto the injected
 * definitions as the kernel's `initial_path`, so a cold start with
 * an empty kernel cache no longer depends on downloading them (the
 * 133–135 failure class). These tests drive the pure pieces: the
 * segment under production-default toggles, the install policy, the
 * triage precedence, and the seed registry itself.
 */
class RuleSetSeedsTest {

    private fun tempDir(): File = Files.createTempDirectory("rule-set-seeds-test").toFile()

    /** Settings defaults on a fresh install (database/Settings.kt). */
    private fun productionConfig() = SystemExplicitSegment.Config(
        chinaIpDomainDirect = true,
        chinaPublicDnsDirect = true,
        lanDirect = true,
        privacyProtect = false,
        vpnLeakProtect = true,
        disableQuic = true,
        excludeCnQuic = true,
        dnsProtect = true,
        dnsProtectLegacyFillOnly = false,
        disableIpv6 = true,
        webrtcProtect = true,
        adsBlock = true,
    )

    private fun baseRoot(): JSONObject = JSONObject(
        """
        {
          "outbounds": [
            {"type": "selector", "tag": "proxy", "outbounds": ["node"]},
            {"type": "vless", "tag": "node", "server": "a.example"},
            {"type": "direct", "tag": "direct"}
          ],
          "route": {
            "final": "proxy",
            "rules": [
              {"domain_suffix": ["example.org"], "outbound": "proxy"}
            ],
            "rule_set": [
              {"tag": "sub-set", "type": "remote", "format": "binary",
               "url": "https://example.com/sub.srs"}
            ]
          },
          "dns": {
            "servers": [
              {"type": "https", "tag": "remote", "server": "dns.example.com", "detour": "proxy"}
            ],
            "final": "remote",
            "rules": []
          },
          "inbounds": [
            {"type": "tun", "tag": "tun-in", "inet6_address": "fdfe:dcba:9876::1/126"}
          ]
        }
        """.trimIndent(),
    )

    /** Runs the segment stages in the same order ConfigQuicOverride.apply does. */
    private fun runSegment(
        root: JSONObject,
        seedPath: (String) -> String? = { null },
    ) {
        val segment = SystemExplicitSegment(root, productionConfig(), null, seedPath = seedPath)
        segment.applyChinaFamily()
        segment.applyVpnLeakProtect()
        segment.applyQuicToggle()
        segment.applyDnsProtectToggle()
        segment.applyDisableIpv6Toggle()
        segment.applyPrivacyToggle()
        segment.applyWebrtcToggle()
        segment.applyAdsToggle()
        segment.assembleRouteRules()
    }

    private fun definitionsByTag(root: JSONObject): Map<String, JSONObject> {
        val sets = root.getJSONObject("route").getJSONArray("rule_set")
        val out = LinkedHashMap<String, JSONObject>()
        for (i in 0 until sets.length()) {
            val item = sets.getJSONObject(i)
            out[item.getString("tag")] = item
        }
        return out
    }

    private fun installFakeSeedFiles(filesDir: File, tags: Collection<String>) {
        val dir = RuleSetSeeds.seedDir(filesDir)
        dir.mkdirs()
        for (tag in tags) {
            val seed = RuleSetSeeds.seedFor(tag)!!
            File(dir, seed.assetName).writeBytes(byteArrayOf(0x53, 0x52, 0x53, 1))
        }
    }

    // (a) Production defaults: exactly the four app definitions carry
    // initial_path; the subscription's own set never does.
    @Test
    fun segmentStampsInitialPathOnExactlyTheAppOwnedSets() {
        val filesDir = tempDir()
        installFakeSeedFiles(filesDir, RuleSetSeeds.SEEDS.keys)
        val root = baseRoot()
        runSegment(root) { tag -> RuleSetSeeds.initialPathIfInstalled(filesDir, tag) }

        val defs = definitionsByTag(root)
        assertEquals(
            setOf("sub-set", "geosite-cn", "geoip-cn", "geosite-geolocation-!cn", "geosite-category-ads-all"),
            defs.keys,
        )
        for ((tag, seed) in RuleSetSeeds.SEEDS) {
            val def = defs.getValue(tag)
            assertEquals("remote", def.getString("type"))
            assertEquals(seed.url, def.getString("url"))
            assertEquals(
                RuleSetSeeds.installedFile(filesDir, tag)!!.absolutePath,
                def.getString("initial_path"),
            )
        }
        assertFalse(defs.getValue("sub-set").has("initial_path"))
    }

    // (a) A seed that is not installed is not stamped (installer
    // degraded → exactly the pre-137 definitions).
    @Test
    fun segmentStampsNothingWhenSeedsAreNotInstalled() {
        val filesDir = tempDir()
        val root = baseRoot()
        runSegment(root) { tag -> RuleSetSeeds.initialPathIfInstalled(filesDir, tag) }
        val defs = definitionsByTag(root)
        for (tag in RuleSetSeeds.SEEDS.keys) {
            assertFalse("seed stamped without an installed file: $tag", defs.getValue(tag).has("initial_path"))
        }
    }

    // (a) Partial install: only the installed seeds are stamped.
    @Test
    fun segmentStampsOnlyTheInstalledSeeds() {
        val filesDir = tempDir()
        installFakeSeedFiles(filesDir, ConfigChinaDirect.CN_RULE_SET_TAGS)
        val root = baseRoot()
        runSegment(root) { tag -> RuleSetSeeds.initialPathIfInstalled(filesDir, tag) }
        val defs = definitionsByTag(root)
        for (tag in ConfigChinaDirect.CN_RULE_SET_TAGS) {
            assertTrue(defs.getValue(tag).has("initial_path"))
        }
        assertFalse(defs.getValue(ConfigAdBlock.RULESET_TAG).has("initial_path"))
    }

    // (b) The install policy, as a pure decision over hashes.
    @Test
    fun needsInstallPolicy() {
        val bundled = "a".repeat(64)
        val other = "b".repeat(64)
        val refreshed = "c".repeat(64)
        // Nothing installed → install.
        assertTrue(RuleSetSeeds.needsInstall(null, null, bundled))
        // Installed bytes are the bundle → keep, record or not.
        assertFalse(RuleSetSeeds.needsInstall(bundled, null, bundled))
        assertFalse(RuleSetSeeds.needsInstall(bundled, RuleSetSeeds.InstallRecord(bundled, bundled), bundled))
        // No record and bytes are not the bundle → reinstall (untrusted).
        assertTrue(RuleSetSeeds.needsInstall(other, null, bundled))
        // A recorded refresh of the current bundle survives.
        assertFalse(RuleSetSeeds.needsInstall(refreshed, RuleSetSeeds.InstallRecord(refreshed, bundled), bundled))
        // Bytes matching neither bundle nor record → corrupt: reinstall.
        assertTrue(RuleSetSeeds.needsInstall(other, RuleSetSeeds.InstallRecord(refreshed, bundled), bundled))
        // A new bundle wins even over a recorded refresh of the old one.
        assertTrue(RuleSetSeeds.needsInstall(refreshed, RuleSetSeeds.InstallRecord(refreshed, bundled), other))
        assertTrue(RuleSetSeeds.needsInstall(bundled, RuleSetSeeds.InstallRecord(bundled, bundled), other))
    }

    // (b) syncSeed performs the decision: install once, keep after,
    // repair corruption, refuse mis-built bundled bytes.
    @Test
    fun syncSeedInstallsKeepsAndRepairs() {
        val bundledBytes = byteArrayOf(0x53, 0x52, 0x53, 9, 9, 9)
        val seed = RuleSetSeeds.Seed(
            tag = "fake-set",
            assetName = "fake-set.srs",
            url = "https://example.com/fake-set.srs",
            bundledSha256 = RuleSetSeeds.sha256Hex(bundledBytes),
        )
        val dest = File(tempDir(), seed.assetName)

        val first = RuleSetSeeds.syncSeed(dest, seed, null) { bundledBytes }
        assertEquals(
            RuleSetSeeds.InstallRecord(seed.bundledSha256, seed.bundledSha256),
            first,
        )
        assertTrue(dest.readBytes().contentEquals(bundledBytes))

        // Kept: no rewrite, no record change.
        assertNull(RuleSetSeeds.syncSeed(dest, seed, first) { bundledBytes })

        // A recorded refresh is kept, not overwritten by the bundle.
        val refreshedBytes = byteArrayOf(0x53, 0x52, 0x53, 7)
        dest.writeBytes(refreshedBytes)
        val refreshRecord = RuleSetSeeds.InstallRecord(
            RuleSetSeeds.sha256Hex(refreshedBytes),
            seed.bundledSha256,
        )
        assertNull(RuleSetSeeds.syncSeed(dest, seed, refreshRecord) { bundledBytes })
        assertTrue(dest.readBytes().contentEquals(refreshedBytes))

        // Corruption (matches neither record nor bundle) → repaired.
        dest.writeBytes(byteArrayOf(1, 2, 3))
        val repaired = RuleSetSeeds.syncSeed(dest, seed, refreshRecord) { bundledBytes }
        assertEquals(RuleSetSeeds.InstallRecord(seed.bundledSha256, seed.bundledSha256), repaired)
        assertTrue(dest.readBytes().contentEquals(bundledBytes))

        // Mis-built bundle (bytes do not hash to the registry value)
        // is refused: nothing installed, nothing recorded.
        val absent = File(tempDir(), seed.assetName)
        assertNull(RuleSetSeeds.syncSeed(absent, seed, null) { byteArrayOf(4, 5, 6) })
        assertFalse(absent.exists())
    }

    // (b) The record file round-trips and tolerates junk lines.
    @Test
    fun recordFileRoundTrips() {
        val records = mapOf(
            "geosite-cn" to RuleSetSeeds.InstallRecord("a".repeat(64), "a".repeat(64)),
            "geoip-cn" to RuleSetSeeds.InstallRecord("b".repeat(64), "a".repeat(64)),
        )
        assertEquals(records, RuleSetSeeds.parseRecords(RuleSetSeeds.formatRecords(records)))
        assertEquals(
            records,
            RuleSetSeeds.parseRecords("junk line\n\n" + RuleSetSeeds.formatRecords(records) + "x=onlyone\n"),
        )
    }

    // (c) Triage: a seeded app set is kept WITHOUT probing, even
    // with a fresh UNAVAILABLE record — the seed is a local copy in
    // the cache tier, outranking records.
    @Test
    fun triageKeepsSeededSetWithoutProbing() {
        val filesDir = tempDir()
        installFakeSeedFiles(filesDir, setOf("geosite-cn"))
        val workingDir = tempDir()
        val cnRef = RuleSetTriage.Ref("geosite-cn", RuleSetSeeds.seedFor("geosite-cn")!!.url)
        val subRef = RuleSetTriage.Ref("sub-set", "https://example.com/sub.srs")
        val profileId = 7L
        val now = 1_700_000_000_000L
        val records = mapOf(
            RuleSetTriage.recordKey(profileId, cnRef) to
                RuleSetTriage.Record(available = false, checkedAtMs = now),
        )
        val plan = RuleSetTriage.plan(listOf(cnRef, subRef), records, profileId, now) { ref ->
            RuleSetTriage.cacheCopyExists(workingDir, ref) ||
                RuleSetTriage.seedCopyExists(filesDir, ref)
        }
        assertTrue(plan.kept.contains(cnRef))
        assertFalse(plan.knownUnavailable.contains(cnRef))
        assertFalse(plan.toProbe.contains(cnRef))
        assertTrue(plan.toProbe.contains(subRef))
        val decision = RuleSetTriage.decide(plan, emptyMap(), profileId, now)
        assertTrue(decision.unavailableTags.isEmpty())
    }

    // Seed refresh (step 6): only a download from the pinned URL,
    // under the seed's own file name, replaces the installed seed —
    // and the refreshed bytes then survive the install policy.
    @Test
    fun refreshFromDownloadHonoursThePinnedUrlBoundary() {
        val filesDir = tempDir()
        val seed = RuleSetSeeds.seedFor("geosite-cn")!!
        val downloaded = File(tempDir(), seed.assetName)
        val freshBytes = byteArrayOf(0x53, 0x52, 0x53, 42)
        downloaded.writeBytes(freshBytes)

        assertTrue(RuleSetSeeds.refreshFromDownload(filesDir, seed.url, downloaded))
        val installed = RuleSetSeeds.installedFile(filesDir, seed.tag)!!
        assertTrue(installed.readBytes().contentEquals(freshBytes))
        val record = RuleSetSeeds.loadRecords(RuleSetSeeds.seedDir(filesDir))[seed.tag]
        assertFalse(
            RuleSetSeeds.needsInstall(
                RuleSetSeeds.sha256Hex(freshBytes),
                record,
                seed.bundledSha256,
            ),
        )

        // Same file name, some other URL → refused, seed untouched.
        downloaded.writeBytes(byteArrayOf(1, 1, 1))
        assertFalse(
            RuleSetSeeds.refreshFromDownload(filesDir, "https://evil.example/geosite-cn.srs", downloaded),
        )
        assertTrue(installed.readBytes().contentEquals(freshBytes))
    }

    // (d) The registry covers exactly the four app-owned tags, its
    // URLs are the ones the injectors emit, and the shipped asset
    // bytes hash to the registry values.
    @Test
    fun registryCoversExactlyTheAppOwnedSets() {
        assertEquals(
            setOf("geosite-cn", "geoip-cn", "geosite-geolocation-!cn", "geosite-category-ads-all"),
            RuleSetSeeds.SEEDS.keys,
        )
        assertEquals(
            ConfigChinaDirect.CN_RULE_SET_TAGS.toSet() + ConfigAdBlock.RULESET_TAG,
            RuleSetSeeds.SEEDS.keys,
        )
        val shaShape = Regex("^[0-9a-f]{64}$")
        for ((tag, seed) in RuleSetSeeds.SEEDS) {
            assertEquals(tag, seed.tag)
            assertEquals(seed.url.substringAfterLast('/'), seed.assetName)
            assertTrue("bundled sha shape: $tag", shaShape.matches(seed.bundledSha256))
            val asset = File("src/main/assets/${RuleSetSeeds.ASSET_DIR}/${seed.assetName}")
            assertTrue("asset shipped: ${seed.assetName}", asset.isFile)
            assertEquals(
                "asset bytes must hash to the registry value: $tag",
                seed.bundledSha256,
                RuleSetSeeds.sha256Hex(asset.readBytes()),
            )
        }
        // Injector agreement: the definitions the injectors emit carry
        // the registry URLs verbatim.
        val cnRoot = JSONObject().put("route", JSONObject())
        ConfigChinaDirect.ensureCnRuleSets(cnRoot)
        val cnSets = cnRoot.getJSONObject("route").getJSONArray("rule_set")
        for (i in 0 until cnSets.length()) {
            val item = cnSets.getJSONObject(i)
            assertEquals(
                RuleSetSeeds.seedFor(item.getString("tag"))!!.url,
                item.getString("url"),
            )
        }
        val adsRoot = JSONObject()
        ConfigAdBlock.apply(adsRoot)
        val adsSets = adsRoot.getJSONObject("route").getJSONArray("rule_set")
        assertEquals(1, adsSets.length())
        assertEquals(
            RuleSetSeeds.seedFor(ConfigAdBlock.RULESET_TAG)!!.url,
            adsSets.getJSONObject(0).getString("url"),
        )
    }
}
