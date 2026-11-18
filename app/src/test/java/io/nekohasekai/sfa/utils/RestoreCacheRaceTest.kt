package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RestoreCacheRaceTest {
    @Test fun completedDownloadCannotPromoteAcrossRestore() {
        val root = Files.createTempDirectory("restore-cache-race").toFile()
        val url = "https://example.invalid/not-requested.json"
        val content = """{"route":{"rule_set":[{"type":"remote","tag":"custom","format":"source","url":"$url"}]}}"""
        try {
            val count = RuleSetStore.warm(content, root, Long.MAX_VALUE, { 1L }) { _, tmp ->
                tmp.parentFile?.mkdirs()
                tmp.writeText("""{"version":1,"rules":[{"domain_suffix":["old.example"]}]}""")
                // Download finished; restore runs before the caller promotes its temporary bytes.
                RestoreStorageAccess.exclusive { }
                true
            }
            assertEquals("stale completed download must not enter the live store", 0, count)
            assertFalse(RuleSetStore.entryFile(root, "custom", "source", url).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun staleQueuedWarmDoesNotBeginDownload() {
        val root = Files.createTempDirectory("queued-cache-race").toFile()
        val epoch = RestoreStorageAccess.snapshot()
        RestoreStorageAccess.exclusive { }
        var requests = 0
        try {
            val result = runCatching {
                RuleSetStore.warm("{}", root, Long.MAX_VALUE, { 1L }, epoch) { _, _ -> requests++; true }
            }
            assertTrue(result.exceptionOrNull() is RestoreStorageUnavailable)
            assertEquals(0, requests)
            // Android glue also rejects a ticket captured before its coroutine/mutex wait.
            io.nekohasekai.sfa.bg.RuleSetWarmup.warmNow("{}", Long.MAX_VALUE, epoch) { requests++; true }
            assertEquals(1, requests) // continuation predicate only; no cache/downloader work
        } finally { root.deleteRecursively() }
    }

    @Test fun tagCachePreservesPriorCopyWhenRestoreFollowsDownload() {
        val root = Files.createTempDirectory("tag-cache-race").toFile()
        val url = "https://example.invalid/not-requested.json"
        val content = """{"route":{"rule_set":[{"type":"remote","tag":"custom","format":"source","url":"$url"}]}}"""
        val dest = RuleSetProviders.cacheFile(root, "custom", "source", url)
        dest.parentFile?.mkdirs()
        dest.writeText("old last good copy")
        dest.setLastModified(1L)
        var effects = 0
        try {
            val count = RuleSetCacheWarmer.warm(content, root, Long.MAX_VALUE, { Long.MAX_VALUE / 2 },
                onStored = { _, _ -> effects++ }) { _, tmp ->
                tmp.writeText("stale downloaded copy")
                RestoreStorageAccess.exclusive { }
                true
            }
            assertEquals(0, count)
            assertEquals("old last good copy", dest.readText())
            assertEquals(0, effects)
            assertFalse(dest.parentFile!!.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { root.deleteRecursively() }
    }
}
