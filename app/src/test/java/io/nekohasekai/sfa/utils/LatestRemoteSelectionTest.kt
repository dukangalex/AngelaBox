package io.nekohasekai.sfa.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRemoteSelectionTest {
    @Test
    fun oldJobStartingLateCannotReplaceNewSelection() = runBlocking {
        val selection = LatestRemoteSelection()
        val old = selection.changed()
        val current = selection.changed()
        var stored = 0L
        assertTrue(selection.writeIfCurrent(current) { stored = 2L })
        assertFalse(selection.writeIfCurrent(old) { stored = 1L })
        assertEquals(2L, stored)
    }

    @Test
    fun newSelectionWinsWhenOldWriteAlreadyRunning() = runBlocking {
        val selection = LatestRemoteSelection()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored = 0L
        val old = selection.changed()
        val first = launch {
            selection.writeIfCurrent(old) {
                entered.complete(Unit)
                release.await()
                stored = 1L
            }
        }
        entered.await()
        val current = selection.changed()
        val second = launch { selection.writeIfCurrent(current) { stored = 0L } }
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(0L, stored)
    }
}
