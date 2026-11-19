package io.nekohasekai.sfa.chain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestChainLoadTest {
    @Test fun oldCompletionCannotReplaceLatestProfileSelection() {
        val gate = LatestChainLoad()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
