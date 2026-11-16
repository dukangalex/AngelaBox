package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CommandEpochGateTest {
    @Test
    fun staleCallbackCannotDispatchAfterTransition() {
        val gate = CommandEpochGate()
        val old = gate.advance()
        gate.advance()
        var dispatched = false
        assertFalse(gate.runIfCurrent(old) { dispatched = true })
        assertFalse(dispatched)
    }

    @Test
    fun transitionWaitsForAlreadyAdmittedCallbackThenOldCallbacksAreRejected() {
        val gate = CommandEpochGate()
        val old = gate.advance()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transitioned = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val first = thread {
            gate.runIfCurrent(old) {
                events.add("old callback")
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                events.add("old finished")
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = thread {
            gate.advance()
            events.add("transition")
            transitioned.countDown()
        }
        try {
            assertFalse(transitioned.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        first.join(5000)
        second.join(5000)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertFalse(gate.runIfCurrent(old) { events.add("stale callback") })
        assertEquals(listOf("old callback", "old finished", "transition"), events)
    }

    @Test
    fun delayedOldLogAndRemoteCallbacksCannotReplaceNewSession() {
        val gate = CommandEpochGate()
        val old = gate.advance()
        var log = ""
        var remoteConnected = false
        val next = gate.advance()
        gate.runIfCurrent(next) {
            log = "new log"
            remoteConnected = true
        }
        gate.runIfCurrent(old) {
            log = "old log"
            remoteConnected = false
        }
        assertEquals("new log", log)
        assertTrue(remoteConnected)
    }

    @Test
    fun reentrantTransitionMakesRemainingFanoutStale() {
        val gate = CommandEpochGate()
        val old = gate.advance()
        val events = mutableListOf<String>()
        gate.runIfCurrent(old) {
            for (handler in 1..2) {
                if (!gate.isCurrent(old)) break
                events.add("handler$handler")
                if (handler == 1) gate.advance()
            }
        }
        assertEquals(listOf("handler1"), events)
    }

    @Test
    fun connectedBeforeNativeConnectReturnsIsClearedOnTransition() {
        val gate = CommandEpochGate()
        val old = gate.advance()
        assertTrue(gate.setConnected(old, true))
        // Model disconnect() before the native client is assigned.
        val hadConnectedCallback = gate.hasConnected()
        val next = gate.advance()
        val events = mutableListOf("connected")
        if (hadConnectedCallback) gate.runIfCurrent(next) { events.add("disconnected") }
        assertFalse(gate.setConnected(old, true))
        assertFalse(gate.hasConnected())
        assertEquals(listOf("connected", "disconnected"), events)
    }

    @Test
    fun nativeDisconnectClearsReportedConnectedState() {
        val gate = CommandEpochGate()
        val epoch = gate.advance()
        assertTrue(gate.setConnected(epoch, true))
        assertTrue(gate.setConnected(epoch, false))
        assertFalse(gate.hasConnected())
    }
}
