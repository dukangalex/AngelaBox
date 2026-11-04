package io.nekohasekai.sfa.inspection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecoveringInspectionCollectionTest {
    private class Clock {
        var time = 0L
        var epoch = 0
        var calls = 0
        var allow = true
        var during: () -> Unit = {}
        var tick: (Long) -> Unit = {}
        suspend fun run(): String = collectRecoveringEvidence(
            current = { epoch },
            elapsed = { time },
            pause = { delta ->
                time += delta
                tick(time)
            },
            mayRecollect = { _, _ -> allow },
        ) { stamp ->
            calls++
            // Match the observed collection finishing six seconds before linger expiry.
            time += 21_000L
            during()
            "report-$stamp"
        }
    }

    @Test fun thirtySecondLingerDiscardsFirstAndPublishesOnlyNewReport() = runBlocking {
        val clock = Clock()
        clock.tick = { if (it == 30_000L) clock.epoch++ }
        assertEquals("report-1", clock.run())
        assertNotEquals("report-0", "report-${clock.epoch}")
        assertEquals(2, clock.calls)
        assertEquals(64_000L, clock.time)
    }

    @Test fun stableNetworkUsesOneRound() = runBlocking {
        val clock = Clock()
        assertEquals("report-0", clock.run())
        assertEquals(1, clock.calls)
        assertEquals(34_000L, clock.time)
    }

    @Test fun changesDuringBothCollectionsFailAfterTwoRounds() = runBlocking {
        val clock = Clock()
        clock.during = { clock.epoch++ }
        try {
            clock.run()
            throw AssertionError("stale report published")
        } catch (_: StaleInspectionEvidence) {
            assertEquals(2, clock.calls)
        }
    }

    @Test fun disconnectProfileOrServiceTransitionDoesNotRecollect() = runBlocking {
        val clock = Clock()
        clock.during = {
            clock.epoch++
            clock.allow = false
        }
        try {
            clock.run()
            throw AssertionError("changed identity accepted")
        } catch (_: StaleInspectionEvidence) {
            assertEquals(1, clock.calls)
        }
    }

    @Test fun cancellationDuringSecondRoundPropagates() = runBlocking {
        val clock = Clock()
        clock.during = {
            if (clock.calls == 1) clock.epoch++ else throw CancellationException("cancel")
        }
        try {
            clock.run()
            throw AssertionError("cancel ignored")
        } catch (_: CancellationException) {
            assertEquals(2, clock.calls)
        }
    }

    @Test fun unstableNetworkRemainsBoundedBeforeProbes() = runBlocking {
        val clock = Clock()
        clock.tick = { clock.epoch++ }
        try {
            clock.run()
            throw AssertionError("unbounded wait")
        } catch (_: StaleInspectionEvidence) {
            assertEquals(0, clock.calls)
            assertEquals(20_000L, clock.time)
        }
    }
}
