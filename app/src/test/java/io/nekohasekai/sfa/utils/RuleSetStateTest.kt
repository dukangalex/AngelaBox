package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Per-rule-set state view aggregation (137c). The view is the
 * durable half of the 136 notice discipline: instead of transient
 * flashes, every set's floor / last verdict / last activity is
 * inspectable. These tests drive the pure aggregation over fake
 * snapshots — merging, floor precedence, TTL-aware availability,
 * orphan copies, sorting, formatting — no Android, no files.
 */
class RuleSetStateTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private val cnUrl = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs"
    private val subUrl = "https://cdn.example.com/rules/geosite-netflix.srs"
    private val scriptUrl =
        "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-nvidia.srs"

    @Test
    fun mergesMultiSourceEvidenceIntoOneRowWithStrongestFloor() {
        val rows = RuleSetState.aggregate(
            refs = listOf(
                RuleSetState.RefInput(
                    tag = "geosite-netflix",
                    url = subUrl,
                    profileNames = listOf("机场 A", "机场 B"),
                    storeCopy = RuleSetState.CopyFact(sizeBytes = 2048, atMs = now - 5 * minute, entryCount = 42),
                    warmerCopy = RuleSetState.CopyFact(sizeBytes = 1024, atMs = now - 60 * minute, entryCount = 40),
                    records = listOf(
                        RuleSetState.RecordFact(available = true, checkedAtMs = now - 30 * minute),
                        RuleSetState.RecordFact(available = false, checkedAtMs = now - 50 * minute),
                    ),
                ),
            ),
            nowMs = now,
        )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("geosite-netflix", row.tag)
        assertEquals(subUrl, row.url)
        assertEquals(RuleSetState.SourceKind.SUBSCRIPTION, row.sourceKind)
        // Store (URL-keyed) outranks the warmer's tag-keyed copy.
        assertEquals(RuleSetState.Floor.STORE, row.floor)
        assertEquals(2048L, row.sizeBytes)
        assertEquals(42, row.entryCount)
        assertEquals(now - 5 * minute, row.lastActivityMs)
        // The NEWEST record is the verdict, not the scariest one.
        assertEquals(RuleSetState.Availability.AVAILABLE, row.availability)
        assertEquals(now - 30 * minute, row.availabilityAtMs)
        assertEquals(listOf("机场 A", "机场 B"), row.profileNames)
    }

    @Test
    fun floorPrecedenceSeedOverStoreOverManualOverWarmer() {
        fun ref(
            seed: RuleSetState.CopyFact? = null,
            store: RuleSetState.CopyFact? = null,
            manual: RuleSetState.CopyFact? = null,
            warmer: RuleSetState.CopyFact? = null,
        ) = RuleSetState.RefInput(
            tag = "geosite-cn",
            url = cnUrl,
            appOwned = seed != null,
            manual = manual != null,
            seedCopy = seed,
            storeCopy = store,
            manualCopy = manual,
            warmerCopy = warmer,
        )
        val copy = RuleSetState.CopyFact(sizeBytes = 100, atMs = now)
        assertEquals(
            RuleSetState.Floor.SEED,
            RuleSetState.aggregate(listOf(ref(seed = copy, store = copy, manual = copy, warmer = copy)), nowMs = now)
                .single().floor,
        )
        assertEquals(
            RuleSetState.Floor.STORE,
            RuleSetState.aggregate(listOf(ref(store = copy, manual = copy, warmer = copy)), nowMs = now)
                .single().floor,
        )
        assertEquals(
            RuleSetState.Floor.MANUAL_FILE,
            RuleSetState.aggregate(listOf(ref(manual = copy, warmer = copy)), nowMs = now).single().floor,
        )
        assertEquals(
            RuleSetState.Floor.WARMER,
            RuleSetState.aggregate(listOf(ref(warmer = copy)), nowMs = now).single().floor,
        )
        assertEquals(
            RuleSetState.Floor.NONE,
            RuleSetState.aggregate(listOf(ref()), nowMs = now).single().floor,
        )
    }

    @Test
    fun unavailableNeedleRecordSurfacesAsUnavailableWithItsTime() {
        // A learned kernel-failure needle is an ordinary UNAVAILABLE
        // record (BoxService.learnRuleSetNeedles): while fresh it is
        // the row's verdict, timestamp attached.
        val rows = RuleSetState.aggregate(
            refs = listOf(
                RuleSetState.RefInput(
                    tag = "geosite-nvidia@cn",
                    url = scriptUrl,
                    records = listOf(
                        RuleSetState.RecordFact(available = false, checkedAtMs = now - 3 * minute),
                    ),
                ),
            ),
            nowMs = now,
        )
        val row = rows.single()
        assertEquals(RuleSetState.Availability.UNAVAILABLE, row.availability)
        assertEquals(now - 3 * minute, row.availabilityAtMs)
        // No floor copy: the record time is the last activity there is.
        assertEquals(RuleSetState.Floor.NONE, row.floor)
        assertEquals(now - 3 * minute, row.lastActivityMs)
    }

    @Test
    fun expiredRecordsDegradeToStaleNeverToAVerdict() {
        fun availability(available: Boolean, ageMs: Long) = RuleSetState.availabilityOf(
            RuleSetState.RecordFact(available = available, checkedAtMs = now - ageMs),
            nowMs = now,
        )
        // AVAILABLE lives 60 minutes, UNAVAILABLE only 10 (triage TTLs).
        assertEquals(RuleSetState.Availability.AVAILABLE, availability(true, 59 * minute))
        assertEquals(RuleSetState.Availability.STALE, availability(true, 61 * minute))
        assertEquals(RuleSetState.Availability.UNAVAILABLE, availability(false, 9 * minute))
        assertEquals(RuleSetState.Availability.STALE, availability(false, 11 * minute))
        // A clock stepped back (negative age) still counts as fresh.
        assertEquals(RuleSetState.Availability.AVAILABLE, availability(true, -5 * minute))
        // No record at all is UNKNOWN, distinct from STALE.
        assertEquals(RuleSetState.Availability.UNKNOWN, RuleSetState.availabilityOf(null, now))
    }

    @Test
    fun seedRowsAlwaysShowSeedFloorAndSortFirst() {
        val rows = RuleSetState.aggregate(
            refs = listOf(
                RuleSetState.RefInput(tag = "zzz-sub", url = subUrl),
                RuleSetState.RefInput(
                    tag = "geosite-cn",
                    url = cnUrl,
                    appOwned = true,
                    seedCopy = RuleSetState.CopyFact(sizeBytes = 56_611, atMs = now - minute, entryCount = 9_365),
                    storeCopy = RuleSetState.CopyFact(sizeBytes = 56_611, atMs = now),
                ),
                RuleSetState.RefInput(tag = "aaa-sub", url = subUrl),
            ),
            nowMs = now,
        )
        assertEquals(listOf("geosite-cn", "aaa-sub", "zzz-sub"), rows.map { it.tag })
        val seedRow = rows.first()
        assertEquals(RuleSetState.SourceKind.APP_OWNED, seedRow.sourceKind)
        assertEquals(RuleSetState.Floor.SEED, seedRow.floor)
        assertEquals(56_611L, seedRow.sizeBytes)
        assertEquals(9_365, seedRow.entryCount)
    }

    @Test
    fun storeOrphanWithUnrecoverableUrlListsWithHashLabel() {
        val rows = RuleSetState.aggregate(
            refs = emptyList(),
            orphanStore = listOf(
                RuleSetState.OrphanStoreInput(
                    fileName = "0123456789abcdef-geosite-nvidia.srs",
                    urlHash = "0123456789abcdef",
                    tag = "geosite-nvidia",
                    copy = RuleSetState.CopyFact(sizeBytes = 777, atMs = now - 2 * minute, entryCount = 12),
                ),
            ),
            nowMs = now,
        )
        val row = rows.single()
        assertEquals("geosite-nvidia", row.tag)
        assertNull(row.url)
        assertEquals("0123456789abcdef", row.urlHash)
        assertEquals(RuleSetState.Floor.STORE, row.floor)
        assertEquals(777L, row.sizeBytes)
        assertEquals(RuleSetState.Availability.UNKNOWN, row.availability)
    }

    @Test
    fun warmerOrphanListsWithWarmerFloor() {
        val rows = RuleSetState.aggregate(
            refs = emptyList(),
            orphanWarmer = listOf(
                RuleSetState.OrphanWarmerInput(
                    tag = "geoip-private",
                    copy = RuleSetState.CopyFact(sizeBytes = 100, atMs = now),
                ),
            ),
            nowMs = now,
        )
        val row = rows.single()
        assertEquals("geoip-private", row.tag)
        assertNull(row.url)
        assertNull(row.urlHash)
        assertEquals(RuleSetState.Floor.WARMER, row.floor)
    }

    @Test
    fun emptyInputsProduceEmptyList() {
        assertTrue(RuleSetState.aggregate(emptyList(), nowMs = now).isEmpty())
    }

    @Test
    fun latestRecordPicksNewestAndUnavailableOnTie() {
        val older = RuleSetState.RecordFact(available = false, checkedAtMs = now - 10 * minute)
        val newer = RuleSetState.RecordFact(available = true, checkedAtMs = now - minute)
        assertEquals(newer, RuleSetState.latestRecord(listOf(older, newer)))
        val tieOk = RuleSetState.RecordFact(available = true, checkedAtMs = now)
        val tieBad = RuleSetState.RecordFact(available = false, checkedAtMs = now)
        assertEquals(tieBad, RuleSetState.latestRecord(listOf(tieOk, tieBad)))
        assertNull(RuleSetState.latestRecord(emptyList()))
    }

    @Test
    fun parseRecordKeySplitsProfileTagUrl() {
        assertEquals(
            Triple(7L, "geosite-cn", cnUrl),
            RuleSetState.parseRecordKey("7|geosite-cn|$cnUrl"),
        )
        // A tag containing '|' still parses: the URL sits after the LAST one.
        assertEquals(
            Triple(3L, "a|b", subUrl),
            RuleSetState.parseRecordKey("3|a|b|$subUrl"),
        )
        assertNull(RuleSetState.parseRecordKey("not-a-key"))
        assertNull(RuleSetState.parseRecordKey("x|geosite-cn|$cnUrl"))
        assertNull(RuleSetState.parseRecordKey("7||$cnUrl"))
        assertNull(RuleSetState.parseRecordKey("7|geosite-cn|"))
    }

    @Test
    fun sizeAndDateTimeFormatting() {
        assertEquals("0 B", RuleSetState.formatSize(0))
        assertEquals("512 B", RuleSetState.formatSize(512))
        assertEquals("1023 B", RuleSetState.formatSize(1023))
        assertEquals("1.0 KB", RuleSetState.formatSize(1024))
        assertEquals("56.3 KB", RuleSetState.formatSize(57_651))
        assertEquals("1.0 MB", RuleSetState.formatSize(1024 * 1024))
        assertEquals("0 B", RuleSetState.formatSize(-5))

        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("1970-01-01 00:00", RuleSetState.formatDateTime(0L))
            assertEquals("2023-11-14 22:13", RuleSetState.formatDateTime(now))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
