package io.nekohasekai.sfa.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RestoreSnapshotTest {
    private fun blocked(block: () -> Unit) {
        try {
            block()
            fail("Expected stale/blocked handle")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun ordinaryReadsAndOperationsKeepSnapshotValid() =
        runBlocking<Unit> {
            val gate = RestoreAccessGate(true)
            val token = gate.snapshot()
            gate.operation { withContext(Dispatchers.Default) { gate.requireSnapshot(token) } }
            gate.requireSnapshot(token)
        }

    @Test fun replacementInvalidatesOldHandlesEvenIfItIsANoOpOrRollback() {
        val gate = RestoreAccessGate(true)
        val before = gate.snapshot()
        gate.exclusive(0) {}
        blocked { gate.requireSnapshot(before) }
        gate.requireSnapshot(gate.snapshot())
    }

    @Test fun foreignGateTokenIsRejected() {
        val first = RestoreAccessGate(true)
        val second = RestoreAccessGate(true)
        blocked { second.requireSnapshot(first.snapshot()) }
    }

    @Test fun failureInsideReplacementNeverReopensOldHandles() {
        val gate = RestoreAccessGate(true)
        val token = gate.snapshot()
        blocked { gate.exclusive(0) { error("replace failed") } }
        blocked { gate.requireSnapshot(token) }
        blocked { gate.snapshot() }
    }

    @Test fun drainTimeoutDoesNotInvalidateUnchangedData() {
        val gate = RestoreAccessGate(true)
        val token = gate.snapshot()
        val lease = gate.beginOperation()
        blocked { gate.exclusive(0) { fail("Not drained") } }
        gate.requireSnapshot(token)
        lease.close()
    }

    @Test fun tokenCannotBeCapturedBeforeStartup() {
        val gate = RestoreAccessGate()
        blocked { gate.snapshot() }
        gate.initialize {}
        gate.requireSnapshot(gate.snapshot())
    }

    @Test fun suspendedOperationKeepsSnapshotUntilItHasFinished() =
        runBlocking<Unit> {
            val gate = RestoreAccessGate(true)
            val token = gate.snapshot()
            val began = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val worker =
                launch(Dispatchers.Default) {
                    gate.operation {
                        gate.requireSnapshot(token)
                        began.complete(Unit)
                        resume.await()
                        withContext(Dispatchers.IO) { gate.requireSnapshot(token) }
                    }
                }
            began.await()
            val replaced = CountDownLatch(1)
            val error = AtomicReference<Throwable>()
            val writer =
                thread {
                    try {
                        gate.exclusive(5000) { replaced.countDown() }
                    } catch (t: Throwable) {
                        error.set(t)
                    }
                }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var draining = false
            while (System.nanoTime() < deadline && !draining) {
                try {
                    gate.snapshot()
                } catch (_: IllegalStateException) {
                    draining = true
                }
                Thread.yield()
            }
            assertTrue(draining)
            assertEquals(1L, replaced.count)
            resume.complete(Unit)
            worker.join()
            writer.join(5000)
            assertFalse(writer.isAlive)
            error.get()?.let { throw AssertionError(it) }
            assertEquals(0L, replaced.count)
            blocked { gate.requireSnapshot(token) }
        }
}
