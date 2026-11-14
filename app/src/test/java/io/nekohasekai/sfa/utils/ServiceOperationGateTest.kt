package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ServiceOperationGateTest {
    private fun blocked(block: () -> Unit) {
        try { block(); fail("Must refuse unsafe acknowledgement") } catch (_: IllegalStateException) {}
    }
    @Test fun pauseRejectsNewStartsAndReloads() {
        val gate = ServiceOperationGate(); val pause = gate.pause()
        assertNull(gate.begin()); pause.awaitIdle(0); pause.resume()
        gate.begin()!!.close()
    }
    @Test fun activeStartCannotBeAcknowledgedByUiStatus() {
        val gate = ServiceOperationGate(); val start = gate.begin()!!; val pause = gate.pause()
        blocked { pause.awaitIdle(0) }; assertNull(gate.begin())
        start.close(); pause.awaitIdle(0); pause.resume()
    }
    @Test fun abandonedWorkerRemainsCountedAfterParentFinishes() {
        val gate = ServiceOperationGate(); val pipeline = gate.begin()!!; val native = gate.cleanup()
        pipeline.close(); val pause = gate.pause()
        blocked { pause.awaitIdle(0) }; native.close(); pause.awaitIdle(0)
    }
    @Test fun delayedCloserPreventsAcknowledgement() {
        val gate = ServiceOperationGate(); val pause = gate.pause(); val closer = gate.cleanup()
        blocked { pause.awaitIdle(0) }; closer.close(); pause.awaitIdle(0)
    }
    @Test fun closeFailureSurvivesSuccessfulLaterClose() {
        val gate = ServiceOperationGate(); val pause = gate.pause(); val close = gate.cleanup()
        val cause = IllegalStateException("native close failed")
        gate.recordCloseFailure(cause); close.close()
        try { pause.awaitIdle(0); fail() } catch (e: IllegalStateException) { assertSame(cause, e.cause) }
        blocked { pause.resume() }; assertNull(gate.begin())
    }
    @Test fun ticketCanFinishOnAnotherThread() {
        val gate = ServiceOperationGate(); val ticket = gate.begin()!!; val pause = gate.pause()
        val worker = thread { ticket.close() }; worker.join(5000)
        assertFalse(worker.isAlive); pause.awaitIdle(0); pause.resume()
    }
    @Test fun ticketCloseIsIdempotentAndDoesNotUndercount() {
        val gate = ServiceOperationGate(); val first = gate.begin()!!; val second = gate.begin()!!
        first.close(); first.close(); val pause = gate.pause()
        blocked { pause.awaitIdle(0) }; second.close(); pause.awaitIdle(0)
    }
    @Test fun waitIsReleasedByRealCompletion() {
        val gate = ServiceOperationGate(); val ticket = gate.begin()!!; val pause = gate.pause()
        val entered = CountDownLatch(1); val completed = CountDownLatch(1)
        val worker = thread { entered.countDown(); pause.awaitIdle(5000); completed.countDown() }
        assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(1L, completed.count)
        ticket.close(); assertTrue(completed.await(5, TimeUnit.SECONDS)); worker.join(5000)
    }
    @Test fun onlyOnePauseOwnerAndNoRepeatedResume() {
        val gate = ServiceOperationGate(); val pause = gate.pause()
        blocked { gate.pause() }; pause.awaitIdle(0); pause.resume(); blocked { pause.resume() }
        blocked { pause.awaitIdle(0) }
    }
    @Test fun resumeCannotIgnoreNewlyRegisteredCleanup() {
        val gate = ServiceOperationGate(); val pause = gate.pause(); pause.awaitIdle(0)
        val cleanup = gate.cleanup(); blocked { pause.resume() }; cleanup.close(); pause.resume()
    }
    @Test fun lateCleanupAfterSealedAckPoisonsResume() {
        val gate = ServiceOperationGate(); val pause = gate.pause()
        pause.awaitIdle(0); pause.seal(); blocked { gate.cleanup() }
        blocked { pause.resume() }; assertNull(gate.begin())
    }
    @Test fun prePauseCloseFailureDoesNotBreakOrdinaryRestartButBlocksRestore() {
        val gate = ServiceOperationGate(); gate.recordCloseFailure(IllegalStateException("old close uncertain"))
        gate.begin()!!.close(); blocked { gate.pause() }
    }
    @Test fun sealCannotIgnoreConcurrentCleanup() {
        val gate = ServiceOperationGate(); val pause = gate.pause(); pause.awaitIdle(0)
        val cleanup = gate.cleanup(); blocked { pause.seal() }; cleanup.close(); pause.seal(); pause.resume()
    }
    @Test fun synchronousCallbackRemainsCountedUntilReturn() {
        val gate = ServiceOperationGate(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = thread {
            gate.trackCleanup { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS)); val pause = gate.pause()
        try { blocked { pause.awaitIdle(0) } } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive); pause.awaitIdle(0); pause.seal(); pause.resume()
    }
    @Test fun callbackExceptionReleasesExactlyItsTicket() {
        val gate = ServiceOperationGate(); val other = gate.cleanup()
        try { gate.trackCleanup { error("callback failed") }; fail() } catch (_: IllegalStateException) {}
        val pause = gate.pause(); blocked { pause.awaitIdle(0) }
        other.close(); pause.awaitIdle(0); pause.seal(); pause.resume()
    }
    @Test fun callbackAfterSealDoesNotEnterItsBody() {
        val gate = ServiceOperationGate(); val pause = gate.pause(); var entered = false
        pause.awaitIdle(0); pause.seal()
        blocked { gate.trackCleanup { entered = true } }
        assertFalse(entered); blocked { pause.resume() }
    }
}
