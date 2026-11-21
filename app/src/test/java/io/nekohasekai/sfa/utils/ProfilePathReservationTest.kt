package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProfilePathReservationTest {
    @get:Rule val temp = TemporaryFolder()
    private val first = "1".repeat(32)
    private val second = "2".repeat(32)
    private fun reservation(ids: () -> String, sync: (File) -> Unit = {}, referenced: (File) -> Boolean = { false }) = ProfilePathReservation(File(temp.root, "configs"), File(temp.root, "markers"), sync, referenced, ids)

    @Test fun avoidsExistingAndPendingPathsWithoutMaterializing() {
        val configs = File(temp.root, "configs").apply { mkdir() }
        File(configs, "profile-$first.json").writeText("old")
        val ids = listOf(first, second, "3".repeat(32)).iterator()
        val target = reservation({ ids.next() }, referenced = { it.name == "profile-$second.json" }).reserve()
        assertEquals("profile-${"3".repeat(32)}.json", target.name)
        assertFalse(target.exists())
        assertEquals("old", File(configs, "profile-$first.json").readText())
    }

    @Test fun retainedMarkerPreventsReuseAfterFailure() {
        val markers = File(temp.root, "markers")
        val failing = reservation({ first }, sync = { if (it == markers) throw IOException("sync failed") })
        try {
            failing.reserve()
            fail("must propagate")
        } catch (_: IOException) {}
        assertTrue(File(markers, "$first.reserved").exists())
        val ids = listOf(first, second).iterator()
        assertEquals("profile-$second.json", reservation({ ids.next() }).reserve().name)
    }

    @Test fun simultaneousSameNameHasOneWinnerEvenOnFirstDirectoryCreation() {
        val n = 32
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until n).map {
                pool.submit<File> {
                    val initial = AtomicBoolean(true)
                    ready.countDown()
                    start.await()
                    reservation({ if (initial.getAndSet(false)) first else java.util.UUID.randomUUID().toString().replace("-", "") }).reserve()
                }
            }
            ready.await()
            start.countDown()
            val paths = tasks.map { it.get() }
            assertEquals(n, paths.toSet().size)
            assertEquals(1, paths.count { it.name == "profile-$first.json" })
            assertTrue(paths.none(File::exists))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun collectsOnlyUnreferencedAndAbsentMarkers() {
        val configs = File(temp.root, "configs").apply { mkdir() }
        val markers = File(temp.root, "markers").apply { mkdir() }
        val orphan = "a".repeat(32)
        val existing = "b".repeat(32)
        val pending = "c".repeat(32)
        listOf(orphan, existing, pending).forEach { File(markers, "$it.reserved").writeText("") }
        File(configs, "profile-$existing.json").writeText("data")
        var synced = 0
        val subject = reservation(
            { first },
            sync = { if (it == markers) synced++ },
            referenced = { it.name == "profile-$pending.json" },
        )
        subject.collectUnreferencedMarkers()
        val target = subject.reserve()
        assertFalse(File(markers, "$orphan.reserved").exists())
        assertTrue(File(markers, "$existing.reserved").exists())
        assertTrue(File(markers, "$pending.reserved").exists())
        assertEquals("profile-$first.json", target.name)
        assertTrue("cleanup and new reservation each sync the directory", synced >= 2)
    }

    @Test fun cleanupSyncFailureStopsNewReservation() {
        val configs = File(temp.root, "configs").apply { mkdir() }
        val markers = File(temp.root, "markers").apply { mkdir() }
        File(markers, "${"a".repeat(32)}.reserved").writeText("")
        try {
            reservation({ first }, sync = { if (it == markers) throw IOException("sync failure") }).collectUnreferencedMarkers()
            fail("cleanup barrier must fail closed")
        } catch (_: IOException) {}
        assertFalse(File(markers, "$first.reserved").exists())
        assertFalse(File(configs, "profile-$first.json").exists())
    }
}
