package io.nekohasekai.sfa.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BackupOperationGateTest {
    @Test fun returnsValueAndReleases() {
        val gate = BackupOperationGate()
        assertEquals(7, gate.tryRun { 7 }.getOrThrow())
        assertEquals(8, gate.tryRun { 8 }.getOrThrow())
    }
    @Test fun blockFailureReleases() {
        val gate = BackupOperationGate()
        assertTrue(gate.tryRun<Int> { error("injected") }.isFailure)
        assertEquals(9, gate.tryRun { 9 }.getOrThrow())
    }
    @Test fun rejectsNestedCallWithoutExecutingIt() {
        val gate = BackupOperationGate()
        val entered = AtomicBoolean(false)
        val result = gate.tryRun { gate.tryRun { entered.set(true) } }.getOrThrow()
        assertTrue(result.isFailure)
        assertFalse(entered.get())
        assertTrue(gate.tryRun { }.isSuccess)
    }
    @Test fun concurrentOperationFailsWithoutRunningAndCanRetry() {
        val gate = BackupOperationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = AtomicBoolean(false)
        val thread = Thread { gate.tryRun { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(gate.tryRun { executed.set(true) }.isFailure)
            assertFalse(executed.get())
        } finally {
            release.countDown(); thread.join(5000)
        }
        assertFalse(thread.isAlive)
        assertTrue(gate.tryRun { executed.set(true) }.isSuccess)
        assertTrue(executed.get())
    }
}
