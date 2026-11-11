package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavAutoProbePolicyTest {
    @Test
    fun directScreenBecomesTunnelRequiredAndStaysRequiredAfterStop() {
        val policy = WebDavAutoProbePolicy()
        assertNull(policy.requiredGeneration(null, 0))
        assertEquals(1L, policy.requiredGeneration(1, 1))
        assertEquals(2L, policy.requiredGeneration(null, 2))
        assertEquals(3L, policy.requiredGeneration(3, 3))
        assertEquals(4L, policy.requiredGeneration(null, 4))
    }

    @Test
    fun reopeningStartsAnIndependentSession() {
        val policy = WebDavAutoProbePolicy()
        policy.requiredGeneration(1, 1)
        assertNull(WebDavAutoProbePolicy().requiredGeneration(null, 2))
    }
}
