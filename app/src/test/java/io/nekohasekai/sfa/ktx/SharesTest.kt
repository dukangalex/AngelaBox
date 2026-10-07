package io.nekohasekai.sfa.ktx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SharesTest {

    @Test
    fun traversalNameIsFlattened() {
        val name = shareBasename("../secret")
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
        assertEquals("secret", name)
    }

    @Test
    fun blankFallsBackToProfile() {
        assertEquals("profile", shareBasename("   "))
        assertEquals("profile", shareBasename("///"))
    }

    @Test
    fun keepsLettersAndDots() {
        assertEquals("My.Node-1", shareBasename("My.Node-1"))
    }
}
