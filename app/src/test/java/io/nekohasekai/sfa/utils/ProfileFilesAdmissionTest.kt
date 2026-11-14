package io.nekohasekai.sfa.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ProfileFilesAdmissionTest {
    @Test fun concurrentAtomicWritesKeepWholeContentsAndOwnScratch() {
        val root = Files.createTempDirectory("profile-write").toFile()
        try {
            val dest = File(root, "1.json"); val ready = CountDownLatch(12); val go = CountDownLatch(1)
            val error = AtomicReference<Throwable>()
            val texts = (1..12).map { n -> "{\"payload\":\"${n.toString().repeat(64000)}\"}" }
            val workers = texts.map { text -> thread {
                ready.countDown(); go.await()
                try { repeat(4) { ProfileFiles.writeAtomicallyAdmitted(dest, text) } }
                catch (t: Throwable) { error.compareAndSet(null, t) }
            } }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); go.countDown()
            workers.forEach { it.join(10000); assertFalse(it.isAlive) }
            error.get()?.let { throw AssertionError(it) }
            assertTrue(texts.contains(dest.readText()))
            assertEquals(listOf("1.json"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }
    @Test fun failedRenameDoesNotRemoveTargetOrLeaveScratch() {
        val root = Files.createTempDirectory("profile-failed-write").toFile()
        try {
            val dest = File(root, "occupied").apply { mkdir() }; File(dest, "old").writeText("preserve")
            try { ProfileFiles.writeAtomicallyAdmitted(dest, "new"); fail() } catch (_: java.io.IOException) {}
            assertEquals("preserve", File(dest, "old").readText())
            assertEquals(listOf("occupied"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }
    @Test fun publicReadsWritesAndDeleteRespectPause() {
        val root = Files.createTempDirectory("profile-gate").toFile()
        try {
            val gate = ApplicationDataAccess.gate
            // Isolated test JVM: runtime initializes this in Application.attachBaseContext.
            gate.initialize {}
            val dest = File(root, "1.json"); ProfileFiles.writeAtomically(dest, "before")
            assertEquals("before", ProfileFiles.readText(dest))
            assertArrayEquals("before".toByteArray(), ProfileFiles.readBytes(dest))
            gate.exclusive(1000) {
                // Exclusive owner may execute data operations; concurrent UI may not.
                val failures = mutableListOf<Throwable>()
                val worker = thread {
                    for (operation in listOf<() -> Unit>(
                        { ProfileFiles.readText(dest) }, { ProfileFiles.readBytes(dest) },
                        { ProfileFiles.writeAtomically(dest, "after") },
                        { ProfileFiles.delete(dest) },
                    )) try { operation(); failures.add(AssertionError("Access escaped pause")) }
                    catch (_: IllegalStateException) {}
                }
                worker.join(5000); assertFalse(worker.isAlive); assertTrue(failures.isEmpty())
                assertEquals("before", dest.readText())
            }
            assertTrue(ProfileFiles.delete(dest))
        } finally { root.deleteRecursively() }
    }
}
