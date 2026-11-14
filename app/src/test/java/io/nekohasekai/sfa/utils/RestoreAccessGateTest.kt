package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RestoreAccessGateTest {
    private fun expectBlocked(block: () -> Unit) {
        try { block(); fail("Access should fail closed") } catch (_: IllegalStateException) {}
    }
    private fun await(latch: CountDownLatch) = assertTrue("Timed out", latch.await(5, TimeUnit.SECONDS))

    @Test fun startupBlocksUntilRecoveryHasReturned() {
        val gate = RestoreAccessGate()
        expectBlocked { gate.access {} }
        val recovering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread { gate.initialize { recovering.countDown(); await(release) } }
        await(recovering)
        expectBlocked { gate.access {} }
        release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
        assertEquals(7, gate.access { 7 })
        expectBlocked { gate.initialize {} }
    }

    @Test fun failedStartupNeverOpensAdmission() {
        val gate = RestoreAccessGate()
        val failure = IllegalStateException("corrupt journal")
        try { gate.initialize { throw failure }; fail() } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        expectBlocked { gate.access {} }
        expectBlocked { gate.initialize {} }
    }

    @Test fun recoveryMayUseSynchronousGuardedAccessOnItsOwnThread() {
        val gate = RestoreAccessGate()
        gate.initialize { assertEquals(3, gate.access { 3 }) }
        gate.access {}
    }

    @Test fun exclusiveDeniesNewCallersAndWaitsForExistingOperation() {
        val gate = RestoreAccessGate(true)
        val admitted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val enteredExclusive = CountDownLatch(1)
        val releaseExclusive = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val reader = thread {
            try { gate.access { admitted.countDown(); await(releaseRead); gate.access {} } }
            catch (t: Throwable) { error.set(t) }
        }
        await(admitted)
        val writer = thread {
            try { gate.exclusive(5000) { enteredExclusive.countDown(); await(releaseExclusive); gate.access {} } }
            catch (t: Throwable) { error.set(t) }
        }
        // Observe admission actually closing instead of assuming writer scheduling.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var denied = false
        while (System.nanoTime() < deadline && !denied) {
            try { gate.access {} } catch (_: IllegalStateException) { denied = true }
            Thread.yield()
        }
        assertTrue(denied)
        assertEquals(1L, enteredExclusive.count)
        releaseRead.countDown(); await(enteredExclusive)
        expectBlocked { gate.access {} }
        expectBlocked { gate.exclusive(0) {} }
        releaseExclusive.countDown()
        reader.join(5000); writer.join(5000)
        assertFalse(reader.isAlive); assertFalse(writer.isAlive)
        error.get()?.let { throw AssertionError(it) }
        gate.access {}
    }

    @Test fun timeoutDoesNotPoisonUnmutatedData() {
        val gate = RestoreAccessGate(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reader = thread { gate.access { entered.countDown(); await(release) } }
        await(entered)
        expectBlocked { gate.exclusive(0) { fail("Must not mutate") } }
        gate.access {}
        release.countDown(); reader.join(5000)
    }

    @Test fun failureInsideExclusivePoisonsAdmission() {
        val gate = RestoreAccessGate(true)
        expectBlocked { gate.exclusive(100) { error("rollback required") } }
        expectBlocked { gate.access {} }
        expectBlocked { gate.exclusive(100) {} }
    }

    @Test fun accessExceptionReleasesAdmission() {
        val gate = RestoreAccessGate(true)
        expectBlocked { gate.access { error("query failed") } }
        gate.exclusive(0) {}
        gate.access {}
    }

    @Test fun cannotUpgradeReadLeaseOrNestExclusive() {
        val gate = RestoreAccessGate(true)
        gate.access { expectBlocked { gate.exclusive(0) {} } }
        gate.exclusive(0) { expectBlocked { gate.exclusive(0) {} } }
        gate.access {}
    }

    @Test fun interruptsWhileDrainingDoNotRunMutation() {
        val gate = RestoreAccessGate(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reader = thread { gate.access { entered.countDown(); await(release) } }
        await(entered)
        val interrupted = AtomicReference<Throwable>()
        val writer = thread {
            Thread.currentThread().interrupt()
            try { gate.exclusive(5000) { fail("Interrupted drain mutated data") } }
            catch (t: Throwable) { interrupted.set(t) }
        }
        writer.join(5000)
        assertTrue(interrupted.get() is InterruptedException)
        gate.access {}
        release.countDown(); reader.join(5000)
    }

    @Test fun readLeasesDrainUnderRepeatedConcurrentAccess() {
        val gate = RestoreAccessGate(true)
        val started = CountDownLatch(8); val release = CountDownLatch(1)
        val errors = AtomicReference<Throwable>()
        val workers = (1..8).map {
            thread {
                try { gate.access { started.countDown(); await(release); repeat(100) { gate.access {} } } }
                catch (t: Throwable) { errors.set(t) }
            }
        }
        await(started); release.countDown()
        gate.exclusive(5000) {}
        workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        errors.get()?.let { throw AssertionError(it) }
    }
}
