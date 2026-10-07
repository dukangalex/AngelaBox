package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ImportPathGuardTest {

    @Test(expected = IllegalArgumentException::class)
    fun fileSchemeRejected() {
        ImportPathGuard.requireContentScheme("file")
    }

    @Test
    fun contentSchemeAccepted() {
        ImportPathGuard.requireContentScheme("content")
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathEscapeRejected() {
        val root = File.createTempFile("ab-root", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        try {
            ImportPathGuard.requireInside(File(root, "../escape.json"), root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sizeCapRejected() {
        val huge = ByteArray(64) { 1 }
        try {
            ImportPathGuard.readLimited(ByteArrayInputStream(huge), 16)
            throw AssertionError("expected size cap")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("MiB") || e.message!!.contains("limit"))
        }
    }

    @Test
    fun readsUnderCap() {
        val body = "hello".toByteArray()
        val out = ImportPathGuard.readLimited(ByteArrayInputStream(body), 64)
        assertEquals("hello", out.toString(Charsets.UTF_8))
    }
}
