package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandServerRestartTest {
    @Test
    fun successfulCloseSequenceStartsNewServerLast() {
        val calls = mutableListOf<String>()
        restartCommandServerSafely(
            closeService = { calls += "service" },
            closeServer = { calls += "server" },
            startServer = { calls += "start" },
        )
        assertEquals(listOf("service", "server", "start"), calls)
    }

    @Test
    fun failedNativeServiceCloseDoesNotStartReplacement() {
        val failure = IllegalStateException("native close failed")
        val calls = mutableListOf<String>()
        val observed = runCatching {
            restartCommandServerSafely(
                closeService = {
                    calls += "service"
                    throw failure
                },
                closeServer = { calls += "server" },
                startServer = { calls += "start" },
            )
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertEquals(listOf("service"), calls)
    }

    @Test
    fun failedCommandServerCloseDoesNotStartReplacement() {
        val failure = IllegalStateException("command close failed")
        val calls = mutableListOf<String>()
        val observed = runCatching {
            restartCommandServerSafely(
                closeService = { calls += "service" },
                closeServer = {
                    calls += "server"
                    throw failure
                },
                startServer = { calls += "start" },
            )
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertTrue(calls == listOf("service", "server"))
    }

    @Test
    fun undrainedStartDoesNotRestartCommandServer() {
        val calls = mutableListOf<String>()
        restartCommandServerAfterDrain(
            canRestart = { false },
            closeServer = { calls += "close" },
            startServer = { calls += "start" },
            onFailure = { calls += "failure" },
        )
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun closeFailureDoesNotRestartCommandServer() {
        val failure = IllegalStateException("close failed")
        val calls = mutableListOf<String>()
        restartCommandServerAfterDrain(
            canRestart = { true },
            closeServer = {
                calls += "close"
                throw failure
            },
            startServer = { calls += "start" },
            onFailure = { calls += "failure" },
        )
        assertEquals(listOf("close", "failure"), calls)
    }

    @Test
    fun lossOfDrainAfterCloseDoesNotRestartCommandServer() {
        var drained = true
        val calls = mutableListOf<String>()
        restartCommandServerAfterDrain(
            canRestart = { drained },
            closeServer = {
                calls += "close"
                drained = false
            },
            startServer = { calls += "start" },
            onFailure = { calls += "failure" },
        )
        assertEquals(listOf("close"), calls)
    }
}
