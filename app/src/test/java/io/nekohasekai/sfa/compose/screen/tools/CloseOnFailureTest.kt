package io.nekohasekai.sfa.compose.screen.tools

import java.io.Closeable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseOnFailureTest {
    private class Handle(private val failClose: Boolean = false) : Closeable {
        var closed = false

        override fun close() {
            closed = true
            if (failClose) throw IllegalStateException("close")
        }
    }

    @Test
    fun failureClosesHandleAndPreservesOriginalError() {
        val handle = Handle(failClose = true)
        val original = IllegalArgumentException("provider")
        val result = runCatching { closeOnFailure(handle) { throw original } }
        assertTrue(handle.closed)
        assertSame(original, result.exceptionOrNull())
    }

    @Test
    fun successTransfersOwnershipWithoutClosing() {
        val handle = Handle()
        assertSame(handle, closeOnFailure(handle) { it })
        assertFalse(handle.closed)
    }
}
