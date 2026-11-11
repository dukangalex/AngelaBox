package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavAutoProbePolicyTest {
    @Test
    fun initiallyDownDispatchRejectsCompleteCycleBeforeIo() {
        TunnelGate.setUp(false)
        val state = TunnelGate.snapshot()
        val policy = WebDavAutoProbePolicy(state.generation)
        assertNull(policy.requiredGeneration(state.upGeneration, state.generation))
        val dispatch = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val requests =
            java.util.concurrent.atomic
                .AtomicInteger()
        val rejected =
            java.util.concurrent.atomic
                .AtomicBoolean()
        val worker =
            Thread {
                try {
                    check(dispatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    TunnelGate.requireGeneration(state.generation)
                    requests.incrementAndGet()
                } catch (_: IllegalStateException) {
                    rejected.set(true)
                } finally {
                    done.countDown()
                }
            }
        worker.start()
        try {
            TunnelGate.setUp(true)
            TunnelGate.setUp(false)
            dispatch.countDown()
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(true, rejected.get())
            assertEquals(0, requests.get())
        } finally {
            dispatch.countDown()
            worker.join(5_000)
            TunnelGate.setUp(false)
        }
    }

    @Test
    fun unchangedDownEpochAllowsDispatch() {
        TunnelGate.setUp(false)
        val state = TunnelGate.snapshot()
        TunnelGate.requireGeneration(state.generation)
        assertNull(state.upGeneration)
    }

    @Test
    fun epochRejectsTransitionEvenWhenTunnelCurrentlyUp() {
        TunnelGate.setUp(false)
        val state = TunnelGate.snapshot()
        try {
            TunnelGate.setUp(true)
            assertEquals(true, runCatching { TunnelGate.requireGeneration(state.generation) }.isFailure)
        } finally {
            TunnelGate.setUp(false)
        }
    }

    @Test
    fun quickTunnelCycleBetweenPollsStillRequiresTunnel() {
        TunnelGate.setUp(false)
        val before = TunnelGate.generation
        val policy = WebDavAutoProbePolicy(before)
        assertNull(policy.requiredGeneration(null, before))
        TunnelGate.setUp(true)
        TunnelGate.setUp(false)
        assertEquals(TunnelGate.generation, policy.requiredGeneration(null, TunnelGate.generation))
    }

    @Test
    fun directScreenBecomesTunnelRequiredAndStaysRequiredAfterStop() {
        val policy = WebDavAutoProbePolicy(0)
        assertNull(policy.requiredGeneration(null, 0))
        assertEquals(1L, policy.requiredGeneration(1, 1))
        assertEquals(2L, policy.requiredGeneration(null, 2))
        assertEquals(3L, policy.requiredGeneration(3, 3))
        assertEquals(4L, policy.requiredGeneration(null, 4))
    }

    @Test
    fun normalServiceDoesNotAdvertiseVpnTransport() {
        TunnelGate.setUp(false)
        try {
            TunnelGate.setUp(true, vpn = false)
            assertEquals(true, TunnelGate.up)
            assertEquals(false, TunnelGate.vpnUp)
            val normalGeneration = TunnelGate.generation
            TunnelGate.setUp(true, vpn = true)
            assertEquals(true, TunnelGate.vpnUp)
            assertEquals(normalGeneration + 1, TunnelGate.generation)
        } finally {
            TunnelGate.setUp(false)
        }
    }

    @Test
    fun reopeningStartsAnIndependentSession() {
        val policy = WebDavAutoProbePolicy(0)
        policy.requiredGeneration(1, 1)
        assertNull(WebDavAutoProbePolicy(2).requiredGeneration(null, 2))
    }
}
