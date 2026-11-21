package io.nekohasekai.sfa.compose.screen.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedSendJobTest {
    @Test
    fun serviceStartupFailureCancelsLazyJobAndCleansOnce() = runBlocking {
        var bodyRan = false
        var cleanupCount = 0
        var unregisterCount = 0
        var dismissed = false
        val job = launch(start = CoroutineStart.LAZY) { bodyRan = true }
        val cleanup = OwnedSendCleanup { cleanupCount++ }
        registerOwnedSendCleanup(job, cleanup) { unregisterCount++ }
        val failure = IllegalStateException("foreground service denied")
        val result = runCatching { startOwnedSendJob(job, { throw failure }, { dismissed = true }) }
        job.join()
        cleanup.close()
        assertSame(failure, result.exceptionOrNull())
        assertFalse(bodyRan)
        assertTrue(dismissed)
        assertTrue(job.isCancelled)
        assertEquals(1, cleanupCount)
        assertEquals(1, unregisterCount)
    }

    @Test
    fun cancellationBeforeStartCleansWithoutEnteringBody() = runBlocking {
        var bodyRan = false
        var cleanupCount = 0
        var unregisterCount = 0
        val job = launch(start = CoroutineStart.LAZY) { bodyRan = true }
        registerOwnedSendCleanup(job, OwnedSendCleanup { cleanupCount++ }) { unregisterCount++ }
        job.cancel()
        job.join()
        assertFalse(bodyRan)
        assertEquals(1, cleanupCount)
        assertEquals(1, unregisterCount)
    }

    @Test
    fun runningCancellationWaitsForBodyToExitBeforeCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var exited = false
        var cleanedAfterExit = false
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                exited = true
            }
        }
        registerOwnedSendCleanup(job, OwnedSendCleanup { cleanedAfterExit = exited }) { }
        startOwnedSendJob(job, {}, {})
        entered.await()
        job.cancel()
        job.join()
        assertTrue(exited)
        assertTrue(cleanedAfterExit)
    }

    @Test
    fun successfulJobCleansAndUnregisters() = runBlocking {
        var ran = false
        var cleanupCount = 0
        var unregisterCount = 0
        val job = launch(start = CoroutineStart.LAZY) { ran = true }
        registerOwnedSendCleanup(job, OwnedSendCleanup { cleanupCount++ }) { unregisterCount++ }
        startOwnedSendJob(job, {}, {})
        job.join()
        assertTrue(ran)
        assertEquals(1, cleanupCount)
        assertEquals(1, unregisterCount)
    }
}
