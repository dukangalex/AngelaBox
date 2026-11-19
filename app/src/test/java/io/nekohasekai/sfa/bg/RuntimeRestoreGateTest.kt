package io.nekohasekai.sfa.bg

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeRestoreGateTest {
    @Test fun broadcastRequestAloneCannotAuthorizeStorageMutation() {
        val gate = RuntimeRestoreGate()
        val key = Any()
        var stops = 0
        val admission = gate.start(key, gate.startTicket()) { stops++ }!!
        admission.close()
        var mutated = false
        assertThrows(IllegalStateException::class.java) { gate.withStopped(0) { mutated = true } }
        assertEquals(1, stops)
        assertFalse(mutated)
        // Only successful teardown may acknowledge this runtime.
        gate.stopped(key)
        gate.withStopped(0) { mutated = true }
        assertTrue(mutated)
    }

    @Test fun teardownAcknowledgementStillWaitsForNativeWorkerExit() {
        val gate = RuntimeRestoreGate()
        val key = Any()
        val stopRequested = CountDownLatch(1)
        val start = gate.start(key, gate.startTicket()) {
            gate.stopped(key)
            stopRequested.countDown()
        }!!
        val native = gate.operation(key)!!
        start.close()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val restored = executor.submit<Boolean> { gate.withStopped(5000) { true } }
            assertTrue(stopRequested.await(2, TimeUnit.SECONDS))
            assertFalse(restored.isDone)
            assertNull(gate.operation(key))
            assertThrows(IllegalStateException::class.java) { gate.startTicket() }
            native.close()
            assertTrue(restored.get(2, TimeUnit.SECONDS))
        } finally {
            native.close()
            executor.shutdownNow()
        }
    }

    @Test fun queuedStartBeforeRestoreIsRejectedAfterItCompletes() {
        val gate = RuntimeRestoreGate()
        val stale = gate.startTicket()
        gate.withStopped(0) { assertNull(gate.start(Any(), stale) {}) }
        assertNull(gate.start(Any(), stale) {})
        val key = Any()
        gate.start(key, gate.startTicket()) {}!!.close()
        gate.stopped(key)
    }

    @Test fun restoreExceptionReopensAdmissionButDoesNotReviveStaleTicket() {
        val gate = RuntimeRestoreGate()
        val old = gate.startTicket()
        assertThrows(IllegalArgumentException::class.java) { gate.withStopped<Unit>(0) { errorForTest() } }
        assertNull(gate.start(Any(), old) {})
        val key = Any()
        gate.start(key, gate.startTicket()) {}!!.close()
        gate.stopped(key)
    }

    @Test fun failedStopAndTimeoutLeaveRuntimeBlockedForFurtherStarts() {
        val gate = RuntimeRestoreGate()
        val key = Any()
        gate.start(key, gate.startTicket()) { throw IllegalStateException("native stop failed") }!!.close()
        assertThrows(IllegalStateException::class.java) { gate.withStopped(0) { fail("must not restore") } }
        assertNull(gate.start(Any(), gate.startTicket()) {})
        assertNull(gate.operation(key))
    }

    @Test fun mainThreadRefusesWaitingWithoutStoppingOrMutating() {
        val gate = RuntimeRestoreGate()
        val key = Any()
        var stopped = false
        gate.start(key, gate.startTicket()) { stopped = true }!!.close()
        assertThrows(IllegalStateException::class.java) { gate.withStopped(0, false) { fail("must not mutate") } }
        assertFalse(stopped)
        assertNotNull(gate.operation(key)?.also { it.close() })
        gate.stopped(key)
    }

    @Test fun duplicateCloseDoesNotUnderflowAndNestedRestoreIsRejected() {
        val gate = RuntimeRestoreGate()
        val drain = gate.drainingWork()
        drain.close()
        drain.close()
        gate.withStopped(0) {
            assertThrows(IllegalStateException::class.java) { gate.withStopped(0) {} }
            assertNull(gate.start(Any(), null) {})
        }
        gate.withStopped(0) {}
    }

    @Test fun preflightMustDrainAndCannotDispatchItsStaleStartAfterRestore() {
        val gate = RuntimeRestoreGate()
        val preparation = gate.prepareStart()!!
        assertThrows(IllegalStateException::class.java) { gate.withStopped(0) { fail("preflight still reading settings") } }
        preparation.second.close()
        gate.withStopped(0) { assertNull(gate.prepareStart()) }
        assertNull(gate.start(Any(), preparation.first) {})
    }

    @Test fun stoppedCallbackCannotReviveRuntimeAndTeardownMustDrain() {
        val gate = RuntimeRestoreGate()
        val key = Any()
        gate.start(key, gate.startTicket()) {}!!.close()
        val callback = gate.drainOperation(key)!!
        gate.stopped(key)
        assertNull(gate.drainOperation(key))
        assertNull(gate.start(Any(), gate.startTicket()) {})
        assertThrows(IllegalStateException::class.java) { gate.withStopped(0) { fail("callback still active") } }
        callback.close()
        gate.withStopped(0) {}
    }

    @Test fun successfulRuntimeTeardownCanDrainWhileAndroidServiceRemainsBound() {
        val gate = RuntimeRestoreGate()
        val key = Any()
        gate.start(key, gate.startTicket()) { gate.stopped(key) }!!.close()
        gate.withStopped(0) {}
        // No onDestroy signal: a foreground UI may still be bound to this service.
        gate.start(key, gate.startTicket()) {}!!.close()
        gate.stopped(key)
    }

    private fun errorForTest(): Nothing = throw IllegalArgumentException("storage rollback")
}
