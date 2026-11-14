package io.nekohasekai.sfa.utils

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RestoreCoroutineOperationTest {
    private fun blocked(block: () -> Unit) {
        try { block(); fail("Must deny restore or access") } catch (_: IllegalStateException) {}
    }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))
    private fun awaitDrain(gate: RestoreAccessGate) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            try { gate.access {} } catch (_: IllegalStateException) { return }
            Thread.yield()
        }
        fail("Drain did not begin")
    }

    @Test fun rootSpansSuspensionAndDispatcherSwitchAndDrainsBeforeMutation() = runBlocking<Unit> {
        val gate = RestoreAccessGate(true)
        val firstWrite = CompletableDeferred<Unit>()
        val continueWrite = CompletableDeferred<Unit>()
        val written = mutableListOf<String>()
        val error = AtomicReference<Throwable>()
        val mutated = CountDownLatch(1)
        val worker = launch(Dispatchers.Default) {
            gate.operation {
                gate.access { written.add("config") }
                firstWrite.complete(Unit)
                continueWrite.await()
                withContext(Dispatchers.IO) {
                    gate.operation { gate.access { written.add("db") } }
                }
            }
        }
        firstWrite.await()
        val writer = thread {
            try {
                gate.exclusive(5000) {
                    assertEquals(listOf("config", "db"), written)
                    mutated.countDown()
                }
            } catch (t: Throwable) { error.set(t) }
        }
        awaitDrain(gate)
        assertEquals(1L, mutated.count)
        continueWrite.complete(Unit)
        worker.join(); writer.join(5000)
        assertFalse(writer.isAlive)
        error.get()?.let { throw AssertionError(it) }
        assertEquals(0L, mutated.count)
    }

    @Test fun independentOperationCannotJoinOneDrainingOnAnotherCoroutine() = runBlocking<Unit> {
        val gate = RestoreAccessGate(true)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val worker = launch(Dispatchers.Default) { gate.operation { started.complete(Unit); finish.await() } }
        started.await()
        val exclusiveEntered = CountDownLatch(1); val release = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val writer = thread {
            try { gate.exclusive(5000) { exclusiveEntered.countDown(); await(release) } }
            catch (t: Throwable) { error.set(t) }
        }
        awaitDrain(gate)
        try { gate.operation { fail("Independent operation was admitted") }; fail() } catch (_: IllegalStateException) {}
        finish.complete(Unit); worker.join(); await(exclusiveEntered)
        blocked { gate.access {} }; release.countDown(); writer.join(5000)
        error.get()?.let { throw AssertionError(it) }
    }

    @Test fun cancellationReleasesRoot() = runBlocking<Unit> {
        val gate = RestoreAccessGate(true)
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) { gate.operation { started.complete(Unit); awaitCancellation() } }
        started.await(); job.cancelAndJoin()
        gate.exclusive(0) {}
    }

    @Test fun throwingOperationReleasesRootWithoutPoisoningData() = runBlocking<Unit> {
        val gate = RestoreAccessGate(true)
        try { gate.operation { withContext(Dispatchers.Default) { error("failed read") } }; fail() }
        catch (_: IllegalStateException) {}
        gate.exclusive(0) {}
        gate.access {}
    }

    @Test fun structuredChildKeepsOperationUntilItsRealBodyExits() = runBlocking<Unit> {
        val gate = RestoreAccessGate(true)
        val childStarted = CompletableDeferred<Unit>(); val childRelease = CompletableDeferred<Unit>()
        val root = launch(Dispatchers.Default) {
            gate.operation {
                coroutineScope { launch(Dispatchers.IO) { childStarted.complete(Unit); childRelease.await(); gate.access {} } }
            }
        }
        childStarted.await()
        blocked { gate.exclusive(0) { fail("Must not replace") } }
        childRelease.complete(Unit); root.join(); gate.exclusive(0) {}
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test fun detachedChildWithExpiredContextCannotAccessOrOpenNestedOperation() = runBlocking<Unit> {
        val gate = RestoreAccessGate(true)
        val release = CompletableDeferred<Unit>()
        lateinit var detached: Deferred<Boolean>
        gate.operation {
            detached = CoroutineScope(currentCoroutineContext() + SupervisorJob()).async(Dispatchers.Default) {
                release.await()
                blocked { gate.access {} }
                try { gate.operation { fail("Must not enter stale operation") }; false }
                catch (_: IllegalStateException) { true }
            }
        }
        gate.exclusive(0) {}
        release.complete(Unit); assertTrue(detached.await())
    }

    @Test fun exclusiveCannotUpgradeCoroutineRootEvenBetweenSynchronousAccesses() = runBlocking<Unit> {
        val gate = RestoreAccessGate(true)
        gate.operation {
            gate.access {}
            withContext(Dispatchers.IO) { blocked { gate.exclusive(0) {} } }
        }
        gate.exclusive(0) {}
    }

    @Test fun uninitializedAndPoisonedGateDenyOperation() = runBlocking<Unit> {
        val gate = RestoreAccessGate()
        try { gate.operation { fail() }; fail() } catch (_: IllegalStateException) {}
        gate.initialize {}
        blocked { gate.exclusive(0) { error("uncertain transaction") } }
        try { gate.operation { fail() }; fail() } catch (_: IllegalStateException) {}
    }

    @Test fun nestedOperationSurvivesDrainAndReturnsValue() = runBlocking<Unit> {
        val gate = RestoreAccessGate(true)
        assertEquals(42, gate.operation { gate.operation { withContext(Dispatchers.IO) { gate.access { 42 } } } })
        gate.exclusive(0) {}
    }
}
