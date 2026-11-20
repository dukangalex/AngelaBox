package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ProfileFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun replacesExistingFileWithUtf8() {
        val target = File(temp.root, "sub/profile.json")
        ProfileFiles.writeAtomically(target, "旧内容")
        ProfileFiles.writeAtomically(target, "新的配置")
        assertEquals("新的配置", target.readText(StandardCharsets.UTF_8))
        assertEquals(listOf("profile.json"), target.parentFile.list()!!.sorted())
    }

    @Test fun staleUpdateDoesNotOverwriteInterveningWrite() {
        val target = File(temp.root, "profile.json")
        val original = "{\"a\":0,\"b\":0}"
        val changed = "{\"a\":1,\"b\":0}"
        ProfileFiles.writeAtomically(target, original)
        val snapshot = target.readText()
        ProfileFiles.writeAtomically(target, changed)
        val failure = runCatching {
            ProfileFiles.writeIfUnchanged(target, snapshot, "{\"a\":0,\"b\":1}")
        }.exceptionOrNull()
        assertTrue("stale update must fail", failure is java.io.IOException)
        assertEquals(changed, target.readText())
        ProfileFiles.writeIfUnchanged(target, changed, "{\"a\":1,\"b\":1}")
        assertEquals("{\"a\":1,\"b\":1}", target.readText())
    }

    @Test fun concurrentWritersNeverExposePartialContent() {
        val target = File(temp.root, "profile.json")
        val payloads = (0 until 4).map { n -> "profile-$n:" + (('A' + n).toString().repeat(131072)) }
        val valid = payloads.toSet()
        target.writeText(payloads[0])
        val start = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val errors = AtomicInteger(0)
        val invalidReads = AtomicInteger(0)
        val readCount = AtomicInteger(0)
        val reader = thread {
            start.await()
            while (!done.get()) {
                try {
                    if (target.readText() !in valid) invalidReads.incrementAndGet()
                    readCount.incrementAndGet()
                } catch (_: Exception) {
                    errors.incrementAndGet()
                }
            }
        }
        val writers = payloads.map { value ->
            thread {
                start.await()
                repeat(100) {
                    try {
                        ProfileFiles.writeAtomically(target, value)
                    } catch (_: Exception) {
                        errors.incrementAndGet()
                    }
                }
            }
        }
        start.countDown()
        writers.forEach { it.join() }
        done.set(true)
        reader.join()
        assertTrue("reader must run", readCount.get() > 0)
        assertEquals("writes and reads should succeed", 0, errors.get())
        assertEquals("reader saw partial or mixed data", 0, invalidReads.get())
        assertTrue(target.readText() in valid)
        assertEquals(listOf("profile.json"), target.parentFile.list()!!.sorted())
    }
}
