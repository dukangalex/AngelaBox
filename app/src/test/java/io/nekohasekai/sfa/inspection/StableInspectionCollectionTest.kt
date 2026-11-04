package io.nekohasekai.sfa.inspection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableInspectionCollectionTest {
    private class Clock {
        var time = 0L
        var epoch = 0
        var calls = 0
        var beforePause: (Long) -> Unit = {}

        suspend fun collect(during: (Int) -> Unit = {}): String =
            collectStableEvidence(
                current = { epoch },
                elapsed = { time },
                pause = { delta ->
                    time += delta
                    beforePause(time)
                },
                collect = { stamp ->
                    calls++
                    during(stamp)
                    "report-$stamp"
                },
            )
    }

    @Test fun waitsBeforeAndAfterCollectionWithoutRepeatingProbes() =
        runBlocking {
            val clock = Clock()
            assertEquals("report-0", clock.collect { assertEquals(3_000L, clock.time) })
            assertEquals(6_000L, clock.time)
            assertEquals(1, clock.calls)
        }

    @Test fun foregroundTransitionBeforeCollectionRestartsQuietWindow() =
        runBlocking {
            val clock = Clock()
            clock.beforePause = { if (it == 1_750L) clock.epoch++ }
            assertEquals("report-1", clock.collect { assertEquals(4_750L, clock.time) })
            assertEquals(7_750L, clock.time)
            assertEquals(1, clock.calls)
        }

    @Test fun transitionDuringCollectionDiscardsInsteadOfRebasing() =
        runBlocking {
            val clock = Clock()
            try {
                clock.collect { clock.epoch++ }
                throw AssertionError("published stale report")
            } catch (_: StaleInspectionEvidence) {
                assertEquals(1, clock.calls)
            }
        }

    @Test fun delayedForegroundLossDiscardsNewEvidence() =
        runBlocking {
            val clock = Clock()
            clock.beforePause = { if (it == 4_750L) clock.epoch++ }
            try {
                clock.collect()
                throw AssertionError("published after callback invalidated evidence")
            } catch (_: StaleInspectionEvidence) {
                assertEquals(4_750L, clock.time)
                assertEquals(1, clock.calls)
            }
        }

    @Test fun disconnectOrRouteChangeDuringPublicationDiscardsEvidence() =
        runBlocking {
            for (transitionAt in listOf(3_250L, 6_000L)) {
                val clock = Clock()
                clock.beforePause = { if (it == transitionAt) clock.epoch++ }
                try {
                    clock.collect()
                    throw AssertionError("published changed route")
                } catch (_: StaleInspectionEvidence) {
                    assertEquals(1, clock.calls)
                }
            }
        }

    @Test fun changingAwayAndBackStillFailsWithMonotonicEpoch() =
        runBlocking {
            val clock = Clock()
            try {
                clock.collect { clock.epoch += 2 }
                throw AssertionError("revived evidence from earlier network")
            } catch (_: StaleInspectionEvidence) {
                assertEquals(1, clock.calls)
            }
        }

    @Test fun sustainedChurnTimesOutWithoutProbes() =
        runBlocking {
            val clock = Clock()
            clock.beforePause = { clock.epoch++ }
            try {
                clock.collect()
                throw AssertionError("unbounded wait")
            } catch (_: StaleInspectionEvidence) {
                assertEquals(10_000L, clock.time)
                assertEquals(0, clock.calls)
            }
        }

    @Test fun cancellationDoesNotPublishOrRetry() =
        runBlocking {
            val clock = Clock()
            clock.beforePause = { if (it == 3_250L) throw CancellationException("cancelled") }
            try {
                clock.collect()
                throw AssertionError("published cancelled collection")
            } catch (expected: CancellationException) {
                assertTrue(expected.message!!.contains("cancelled"))
                assertEquals(1, clock.calls)
            }
        }
}
