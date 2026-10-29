package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater

/**
 * Subscription rule-set localization (137b). Peer clients never let
 * a start depend on a cold remote fetch; 137a floored the APP-OWNED
 * sets with bundled seeds, and this store generalises the floor to
 * subscription-owned and script-generated sets: a URL-keyed store of
 * last-good copies, stamped onto built definitions as
 * `initial_path`. These tests drive the pure pieces — keying,
 * transactional promote, the stamping decision over a built config,
 * prefetch enumeration, pruning, and the triage precedence — with
 * fake downloads; no network, no Android.
 */
class RuleSetStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("rule-set-store-test").toFile()

    private val subUrl = "https://cdn.example.com/sub-ok.srs"
    private val otherUrl = "https://mirror.example.net/sub-ok.srs"
    private val scriptUrl =
        "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-nvidia.srs"
    private val jsonUrl = "https://cdn.example.com/source-set.json"

    /**
     * Minimal structurally valid SRS bytes: magic + version byte,
     * then a zlib stream whose first uvarint is the top-level rule
     * count. [marker] rides inside the stream so successive
     * "versions" of a set differ byte-for-byte.
     */
    private fun srsBytes(marker: Int): ByteArray {
        val payload = byteArrayOf(2, marker.toByte())
        val deflater = Deflater()
        deflater.setInput(payload)
        deflater.finish()
        val compressed = ByteArray(64)
        val n = deflater.deflate(compressed)
        deflater.end()
        return byteArrayOf(0x53, 0x52, 0x53, 1) + compressed.copyOf(n)
    }

    private fun jsonBytes(): ByteArray = """{"version":1,"rules":[{"domain_suffix":["example.com"]}]}"""
        .toByteArray(Charsets.UTF_8)

    private fun remoteSet(tag: String, url: String, format: String = "binary"): JSONObject = JSONObject()
        .put("tag", tag)
        .put("type", "remote")
        .put("format", format)
        .put("url", url)

    private fun configOf(vararg sets: JSONObject): JSONObject = JSONObject()
        .put(
            "route",
            JSONObject()
                .put("rule_set", JSONArray().also { arr -> sets.forEach { arr.put(it) } })
                .put("final", "proxy"),
        )

    private fun promoteBytes(
        storeDir: File,
        tag: String,
        url: String,
        format: String,
        bytes: ByteArray,
    ): Boolean {
        val tmp = RuleSetStore.tempFile(storeDir, tag, format, url)
        tmp.parentFile?.mkdirs()
        tmp.writeBytes(bytes)
        return RuleSetStore.promote(tmp, storeDir, tag, format, url)
    }

    // Keying: the same tag fetched from two origins is two entries,
    // and a lookup under one origin NEVER sees the other's bytes —
    // the property the tag-keyed warmer cache cannot promise.
    @Test
    fun storeKeyingNeverCrossServes() {
        val storeDir = RuleSetStore.storeDir(tempDir())
        val nameA = RuleSetStore.entryFileName("shared-set", "", subUrl)
        val nameB = RuleSetStore.entryFileName("shared-set", "", otherUrl)
        assertFalse(nameA == nameB)

        assertTrue(promoteBytes(storeDir, "shared-set", subUrl, "", srsBytes(1)))
        assertTrue(RuleSetStore.isStored(storeDir, "shared-set", "", subUrl))
        assertFalse(RuleSetStore.isStored(storeDir, "shared-set", "", otherUrl))
        assertNull(RuleSetStore.initialPathIfStored(storeDir, "shared-set", "", otherUrl))
        assertEquals(
            RuleSetStore.entryFile(storeDir, "shared-set", "", subUrl).absolutePath,
            RuleSetStore.initialPathIfStored(storeDir, "shared-set", "", subUrl),
        )
    }

    // Promote: a valid download replaces; a corrupt or empty one
    // keeps the last-good copy byte-for-byte.
    @Test
    fun promoteValidReplacesCorruptAndEmptyKeepLastGood() {
        val storeDir = RuleSetStore.storeDir(tempDir())
        val dest = RuleSetStore.entryFile(storeDir, "sub-ok", "", subUrl)

        assertTrue(promoteBytes(storeDir, "sub-ok", subUrl, "", srsBytes(1)))
        assertTrue(dest.readBytes().contentEquals(srsBytes(1)))

        assertTrue(promoteBytes(storeDir, "sub-ok", subUrl, "", srsBytes(2)))
        assertTrue(dest.readBytes().contentEquals(srsBytes(2)))

        // Corrupt: no SRS magic / no inflatable header.
        assertFalse(
            promoteBytes(storeDir, "sub-ok", subUrl, "", "NOT A RULE SET".toByteArray()),
        )
        assertTrue(dest.readBytes().contentEquals(srsBytes(2)))

        // Empty download.
        assertFalse(promoteBytes(storeDir, "sub-ok", subUrl, "", ByteArray(0)))
        assertTrue(dest.readBytes().contentEquals(srsBytes(2)))

        // Source format: valid JSON promotes, broken JSON does not.
        val jsonDest = RuleSetStore.entryFile(storeDir, "source-set", "source", jsonUrl)
        assertTrue(promoteBytes(storeDir, "source-set", jsonUrl, "source", jsonBytes()))
        assertTrue(jsonDest.readBytes().contentEquals(jsonBytes()))
        assertFalse(
            promoteBytes(storeDir, "source-set", jsonUrl, "source", "{broken".toByteArray()),
        )
        assertTrue(jsonDest.readBytes().contentEquals(jsonBytes()))
        // JSON that parses but is not a rule-set source (no rules).
        assertFalse(
            promoteBytes(storeDir, "source-set", jsonUrl, "source", "{}".toByteArray()),
        )
        assertTrue(jsonDest.readBytes().contentEquals(jsonBytes()))
    }

    // A failed promote leaves no live copy and no temp litter: the
    // temp file must never become visible as the entry itself.
    @Test
    fun tempFileNeverBecomesTheLiveCopy() {
        val storeDir = RuleSetStore.storeDir(tempDir())
        val dest = RuleSetStore.entryFile(storeDir, "sub-ok", "", subUrl)

        assertFalse(promoteBytes(storeDir, "sub-ok", subUrl, "", "garbage".toByteArray()))
        assertFalse(dest.exists())
        val litter = storeDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("temp litter left behind: $litter", litter.isEmpty())
    }

    // Stamping over a built config: a stored subscription set is
    // stamped with the store path; an unstored one is untouched;
    // the app-owned canonical definition is left to the 137a seed
    // even when a store copy exists; a live manual initial_path is
    // preserved, a dangling one replaced; and a definition the
    // ladder dropped (removed before stamping, exactly the pipeline
    // order) gets nothing.
    @Test
    fun stampingDecisionOverBuiltConfig() {
        val filesDir = tempDir()
        val storeDir = RuleSetStore.storeDir(filesDir)
        val pinnedCnUrl = RuleSetSeeds.seedFor("geosite-cn")!!.url
        val foreignAdsUrl = "https://airport.example.com/my-ads.srs"
        val manualFile = File(tempDir(), "manual.srs").apply { writeBytes(srsBytes(9)) }

        assertTrue(promoteBytes(storeDir, "sub-ok", subUrl, "", srsBytes(1)))
        assertTrue(promoteBytes(storeDir, "geosite-cn", pinnedCnUrl, "", srsBytes(3)))
        assertTrue(promoteBytes(storeDir, "geosite-category-ads-all", foreignAdsUrl, "", srsBytes(4)))
        assertTrue(promoteBytes(storeDir, "manual-set", subUrl, "", srsBytes(5)))
        assertTrue(promoteBytes(storeDir, "dangling-set", otherUrl, "", srsBytes(6)))
        assertTrue(promoteBytes(storeDir, "dropped-set", scriptUrl, "", srsBytes(7)))

        val manualDef = remoteSet("manual-set", subUrl)
            .put("initial_path", manualFile.absolutePath)
        val danglingDef = remoteSet("dangling-set", otherUrl)
            .put("initial_path", File(tempDir(), "gone.srs").absolutePath)
        val root = configOf(
            remoteSet("sub-ok", subUrl),
            remoteSet("sub-missing", "https://cdn.example.com/never-fetched.srs"),
            remoteSet("geosite-cn", pinnedCnUrl),
            remoteSet("geosite-category-ads-all", foreignAdsUrl),
            manualDef,
            danglingDef,
            remoteSet("dropped-set", scriptUrl),
            JSONObject()
                .put("tag", "local-set")
                .put("type", "local")
                .put("path", "/data/local.srs"),
        )

        // The pipeline drops before it stamps (same applyOne stage):
        // a suppressed definition must end up with nothing at all.
        ConfigInboundCompat.dropRemoteRuleSetsByTags(root, setOf("dropped-set"))

        val stamped = RuleSetStore.stampInitialPaths(root, storeDir)
        assertEquals(listOf("sub-ok", "geosite-category-ads-all", "dangling-set"), stamped)

        val sets = root.getJSONObject("route").getJSONArray("rule_set")
        val byTag = (0 until sets.length()).associate {
            val item = sets.getJSONObject(it)
            item.getString("tag") to item
        }
        assertEquals(
            RuleSetStore.entryFile(storeDir, "sub-ok", "", subUrl).absolutePath,
            byTag.getValue("sub-ok").getString("initial_path"),
        )
        assertFalse(byTag.getValue("sub-missing").has("initial_path"))
        // App-owned canonical: the store stamper stays out — the
        // segment stamps the bundled seed for this definition.
        assertFalse(byTag.getValue("geosite-cn").has("initial_path"))
        // A same-tag set from a foreign URL is a subscription set in
        // every way that matters: it gets the store floor.
        assertEquals(
            RuleSetStore.entryFile(storeDir, "geosite-category-ads-all", "", foreignAdsUrl)
                .absolutePath,
            byTag.getValue("geosite-category-ads-all").getString("initial_path"),
        )
        // The user's manual floor (provider screen sync) wins.
        assertEquals(
            manualFile.absolutePath,
            byTag.getValue("manual-set").getString("initial_path"),
        )
        // A dangling manual path is replaced by the real store copy.
        assertEquals(
            RuleSetStore.entryFile(storeDir, "dangling-set", "", otherUrl).absolutePath,
            byTag.getValue("dangling-set").getString("initial_path"),
        )
        assertFalse(byTag.containsKey("dropped-set"))
        assertFalse(byTag.getValue("local-set").has("initial_path"))

        // Idempotent: a second stamp round changes nothing (the
        // freshly stamped paths now read as live existing floors).
        assertEquals(emptyList<String>(), RuleSetStore.stampInitialPaths(root, storeDir))
    }

    // Enumeration: one built config yields both the subscription set
    // and the script-generated set for prefetch — and the canonical
    // app-owned set is excluded (137a owns it). Warming stores both;
    // freshness then gates re-download per set.
    @Test
    fun enumerationCoversSubscriptionAndScriptGeneratedSets() {
        val storeDir = RuleSetStore.storeDir(tempDir())
        val now = 1_700_000_000_000L
        val content = configOf(
            remoteSet("sub-ok", subUrl),
            remoteSet("geosite-nvidia@cn", scriptUrl),
            remoteSet("geosite-cn", RuleSetSeeds.seedFor("geosite-cn")!!.url),
        ).toString()

        val pending = RuleSetStore.pendingDownloads(content, storeDir, now)
        assertEquals(setOf(subUrl, scriptUrl), pending.map { it.url }.toSet())

        val stored = RuleSetStore.warm(content, storeDir, now + 60_000, { now }) { _, tmp ->
            tmp.parentFile?.mkdirs()
            tmp.writeBytes(srsBytes(1))
            true
        }
        assertEquals(2, stored)
        assertTrue(RuleSetStore.isStored(storeDir, "sub-ok", "", subUrl))
        assertTrue(RuleSetStore.isStored(storeDir, "geosite-nvidia@cn", "", scriptUrl))
        assertTrue(RuleSetStore.pendingDownloads(content, storeDir, now).isEmpty())

        // One set goes stale: only it is owed again.
        val scriptFile = RuleSetStore.entryFile(storeDir, "geosite-nvidia@cn", "", scriptUrl)
        assertTrue(scriptFile.setLastModified(now - RuleSetStore.STALE_MS - 1))
        val rePending = RuleSetStore.pendingDownloads(content, storeDir, now)
        assertEquals(listOf(scriptUrl), rePending.map { it.url })
    }

    // Pruning: referenced entries are kept; unreferenced entries
    // survive a grace after their last sighting, then go; the size
    // cap evicts oldest-first, unreferenced before referenced.
    @Test
    fun pruningKeepsReferencedEvictsUnreferencedAndCapEvictsOldestFirst() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        fun entry(name: String, hash: String, size: Long, seen: Long) = RuleSetStore.EntryInfo(name, hash, size, seen, seen)

        val grace = RuleSetStore.planPrune(
            listOf(
                entry("h1-ref.srs", "h1", 100, now),
                entry("h2-old.srs", "h2", 100, now - 15 * day),
                entry("h3-recent.srs", "h3", 100, now - day),
            ),
            referencedHashes = setOf("h1"),
            nowMs = now,
        )
        assertEquals(listOf("h2-old.srs"), grace)

        val mb = 1024L * 1024
        val cap = RuleSetStore.planPrune(
            listOf(
                entry("a.srs", "ha", 20 * mb, now - 3 * day),
                entry("b.srs", "hb", 20 * mb, now - 2 * day),
                entry("c.srs", "hc", 20 * mb, now - day),
            ),
            referencedHashes = setOf("ha", "hb", "hc"),
            nowMs = now,
        )
        assertEquals(listOf("a.srs", "b.srs"), cap)

        // Under the cap valve an unreferenced entry is evicted
        // before a referenced one even when the referenced is older.
        val mixed = RuleSetStore.planPrune(
            listOf(
                entry("ref-old.srs", "hr", 10 * mb, now - 10 * day),
                entry("unref-new.srs", "hu", 40 * mb, now),
            ),
            referencedHashes = setOf("hr"),
            nowMs = now,
        )
        assertEquals(listOf("unref-new.srs"), mixed)
    }

    // Pruning executes: files leave the dir and the index forgets
    // them; kept entries keep their last-seen marks.
    @Test
    fun pruneExecutesAndCleansTheIndex() {
        val storeDir = RuleSetStore.storeDir(tempDir())
        val now = 1_700_000_000_000L
        assertTrue(promoteBytes(storeDir, "keep-set", subUrl, "", srsBytes(1)))
        assertTrue(promoteBytes(storeDir, "drop-set", otherUrl, "", srsBytes(2)))
        val keepName = RuleSetStore.entryFileName("keep-set", "", subUrl)
        val dropName = RuleSetStore.entryFileName("drop-set", "", otherUrl)
        val old = now - RuleSetStore.UNREFERENCED_GRACE_MS - 1
        RuleSetStore.saveIndex(storeDir, mapOf(keepName to now, dropName to old))

        val deleted = RuleSetStore.prune(
            storeDir,
            referencedHashes = setOf(RuleSetStore.urlHash(subUrl)),
            nowMs = now,
        )
        assertEquals(listOf(dropName), deleted)
        assertTrue(RuleSetStore.entryFile(storeDir, "keep-set", "", subUrl).isFile)
        assertFalse(RuleSetStore.entryFile(storeDir, "drop-set", "", otherUrl).exists())
        val index = RuleSetStore.loadIndex(storeDir)
        assertEquals(now, index[keepName])
        assertFalse(index.containsKey(dropName))
    }

    // The index text form round-trips and tolerates junk lines.
    @Test
    fun indexRoundTripsAndToleratesJunk() {
        val index = mapOf("abc123-set.srs" to 42L, "def456-other.json" to 1_700_000_000_000L)
        assertEquals(index, RuleSetStore.parseIndex(RuleSetStore.formatIndex(index)))
        assertEquals(
            index,
            RuleSetStore.parseIndex("junk line\n\n" + RuleSetStore.formatIndex(index) + "x=NaN\n=5\n"),
        )
    }

    // Triage: a stored subscription set is kept WITHOUT probing,
    // even with a fresh UNAVAILABLE record — the store copy is a
    // local floor in the same precedence tier as the warmer cache
    // and the bundled seeds.
    @Test
    fun triageKeepsStoredSetWithoutProbing() {
        val filesDir = tempDir()
        val storeDir = RuleSetStore.storeDir(filesDir)
        assertTrue(promoteBytes(storeDir, "sub-ok", subUrl, "", srsBytes(1)))
        val workingDir = tempDir()
        val subRef = RuleSetTriage.Ref("sub-ok", subUrl)
        val unstoredRef = RuleSetTriage.Ref("sub-missing", otherUrl)
        val profileId = 7L
        val now = 1_700_000_000_000L
        val records = mapOf(
            RuleSetTriage.recordKey(profileId, subRef) to
                RuleSetTriage.Record(available = false, checkedAtMs = now),
        )
        val plan = RuleSetTriage.plan(listOf(subRef, unstoredRef), records, profileId, now) { ref ->
            RuleSetTriage.cacheCopyExists(workingDir, ref) ||
                RuleSetTriage.seedCopyExists(filesDir, ref) ||
                RuleSetTriage.storeCopyExists(filesDir, ref)
        }
        assertTrue(plan.kept.contains(subRef))
        assertFalse(plan.knownUnavailable.contains(subRef))
        assertFalse(plan.toProbe.contains(subRef))
        assertTrue(plan.toProbe.contains(unstoredRef))
        val decision = RuleSetTriage.decide(plan, emptyMap(), profileId, now)
        assertTrue(decision.unavailableTags.isEmpty())
    }
}
