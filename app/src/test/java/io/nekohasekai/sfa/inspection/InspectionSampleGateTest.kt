package io.nekohasekai.sfa.inspection

import org.junit.Assert.*
import org.junit.Test

class InspectionSampleGateTest {
    @Test fun laterSampleRejectsOutOfOrderCompletion() {
        val gate = InspectionSampleGate()
        val first = gate.begin(isManual = false)
        val second = gate.begin(isManual = false)
        assertFalse(gate.mayCommit(first))
        assertTrue(gate.mayCommit(second))
    }

    @Test fun passiveCannotSupersedeAnActiveManualSample() {
        val gate = InspectionSampleGate()
        val manual = gate.begin(isManual = true)
        val passive = gate.begin(isManual = false)
        assertFalse(gate.mayCommit(passive))
        assertTrue(gate.mayCommit(manual))
        gate.end(manual)
        assertTrue(gate.mayCommit(gate.begin(isManual = false)))
    }

    @Test fun clearInvalidatesInflightSample() {
        val gate = InspectionSampleGate()
        val pending = gate.begin(isManual = false)
        gate.invalidate()
        assertFalse(gate.mayCommit(pending))
        assertTrue(gate.mayCommit(gate.begin(isManual = false)))
    }
}
