package io.nekohasekai.sfa.utils

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RestoreConfigFilesTest {
    @Test fun copyFailureIsReportedAndDoesNotPruneOtherFiles() {
        val root = java.nio.file.Files.createTempDirectory("restore-config-").toFile()
        try {
            val source = File(root, "stage").also { it.mkdir() }
            val live = File(root, "live").also { it.mkdir() }
            File(source, "new").writeText("new")
            File(live, "old").writeText("old")
            val result = runCatching { restoreConfigFiles(source, live) { _, _ -> throw IOException("injected") } }
            assertTrue(result.isFailure)
            assertEquals("old", File(live, "old").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun partialCopyFailureLeavesExistingConfigsUntouched() {
        val root = java.nio.file.Files.createTempDirectory("restore-config-").toFile()
        try {
            val source = File(root, "stage").also { it.mkdir() }
            val live = File(root, "live").also { it.mkdir() }
            File(source, "a.json").writeText("new-complete")
            File(live, "a.json").writeText("old-complete")
            File(live, "old.json").writeText("other-old")
            val result = runCatching {
                restoreConfigFiles(source, live) { _, dest ->
                    dest.writeText("new-part")
                    throw IOException("injected partial write")
                }
            }
            assertTrue(result.isFailure)
            assertEquals("old-complete", File(live, "a.json").readText())
            assertEquals("other-old", File(live, "old.json").readText())
            assertEquals(setOf("a.json", "old.json"), live.listFiles()!!.map { it.name }.toSet())
        } finally { root.deleteRecursively() }
    }
    @Test fun lateCopyFailureDoesNotPromoteEarlierPreparedFile() {
        val root = java.nio.file.Files.createTempDirectory("restore-config-").toFile()
        try {
            val source = File(root, "stage").also { it.mkdir() }
            val live = File(root, "live").also { it.mkdir() }
            listOf("a", "b").forEach { File(source, it).writeText("new-$it"); File(live, it).writeText("old-$it") }
            var copies = 0
            assertTrue(runCatching {
                restoreConfigFiles(source, live) { from, to ->
                    to.writeText("partial")
                    if (++copies == 2) throw IOException("injected")
                    from.copyTo(to, overwrite = true)
                }
            }.isFailure)
            listOf("a", "b").forEach { assertEquals("old-$it", File(live, it).readText()) }
            assertEquals(setOf("a", "b"), live.listFiles()!!.map { it.name }.toSet())
        } finally { root.deleteRecursively() }
    }
    @Test fun successfulCopyPrunesOnlyUnmatchedFiles() {
        val root = java.nio.file.Files.createTempDirectory("restore-config-").toFile()
        try {
            val source = File(root, "stage").also { it.mkdir() }
            val live = File(root, "live").also { it.mkdir() }
            File(source, "a").writeText("new")
            File(live, "a").writeText("old")
            File(live, "obsolete").writeText("old")
            restoreConfigFiles(source, live)
            assertEquals("new", File(live, "a").readText())
            assertFalse(File(live, "obsolete").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun unreadableSourceFailsWithoutPruning() {
        val root = java.nio.file.Files.createTempDirectory("restore-config-").toFile()
        try {
            val live = File(root, "live").also { it.mkdir() }
            File(live, "old").writeText("old")
            assertTrue(runCatching { restoreConfigFiles(File(root, "missing"), live) }.isFailure)
            assertTrue(File(live, "old").exists())
        } finally { root.deleteRecursively() }
    }
}
