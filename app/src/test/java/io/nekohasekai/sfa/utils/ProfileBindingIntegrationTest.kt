package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.chain.ChainBinding
import io.nekohasekai.sfa.chain.ChainBindingCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Exercises the real file helper and binding transform with an in-memory profile store. */
class ProfileBindingIntegrationTest {
    private data class Row(val path: String)
    private val binding = ChainBinding(1L, "entry", 2L, "landing")

    @Test
    fun staleLandingOrOwnerContentLeavesBindingAndFileUntouched() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("profile-binding").toFile()
        try {
            val file = File(dir, "profile.json").apply { writeText("original") }
            val gate = ProfileMutationGate()
            val row = Row(file.path)
            var bindingSaved = false
            val staleLanding = commitLocalProfileSnapshot(
                gate, 1, { row }, { true }, { File(it.path) }, "original", "new",
                { false }, { bindingSaved = true },
            )
            assertFalse(staleLanding)
            assertEquals("original", file.readText())
            assertFalse(bindingSaved)
            file.writeText("edited concurrently")
            val staleOwner = commitLocalProfileSnapshot(
                gate, 1, { row }, { true }, { File(it.path) }, "original", "new",
                { true }, { bindingSaved = true },
            )
            assertFalse(staleOwner)
            assertEquals("edited concurrently", file.readText())
            assertFalse(bindingSaved)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileWriteFailureDoesNotSaveBinding() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("profile-binding").toFile()
        try {
            val file = File(dir, "profile.json").apply { writeText("original") }
            var bindingSaved = false
            var writeAttempted = false
            var failed = false
            try {
                commitLocalProfileSnapshot(
                    ProfileMutationGate(), 1, { Row(file.path) }, { true }, { File(it.path) },
                    "original", "new", { true }, { bindingSaved = true },
                    commitFile = { _, _, _ ->
                        writeAttempted = true
                        throw IOException("injected file commit failure")
                    },
                )
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(writeAttempted)
            assertTrue(failed)
            assertEquals("original", file.readText())
            assertFalse(bindingSaved)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun landingDeletionWaitsForFileAndBindingCommitThenInvalidates() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("profile-binding").toFile()
        try {
            val file = File(dir, "profile.json").apply { writeText("original") }
            val gate = ProfileMutationGate()
            var bindings = emptyMap<Long, ChainBinding>()
            var landingExists = true
            val validating = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                commitLocalProfileSnapshot(
                    gate, 1, { Row(file.path) }, { true }, { File(it.path) }, "original", "new",
                    {
                        validating.complete(Unit)
                        proceed.await()
                        landingExists
                    },
                    {
                        assertEquals("new", file.readText())
                        bindings = mapOf(1L to binding)
                        events += "binding"
                    },
                )
            }
            validating.await()
            val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withLock(2) {
                    landingExists = false
                    bindings = ChainBindingCodec.onProfileDeleted(bindings, 2, "deleted").first
                    events += "delete"
                }
            }
            assertTrue(events.isEmpty())
            proceed.complete(Unit)
            assertTrue(commit.await())
            deletion.join()
            assertEquals(listOf("binding", "delete"), events)
            assertEquals("new", file.readText())
            assertTrue(bindings[1L]?.invalidated == true)
            assertEquals("deleted", bindings[1L]?.invalidReason)
        } finally {
            dir.deleteRecursively()
        }
    }
}
