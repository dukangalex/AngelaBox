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
}
