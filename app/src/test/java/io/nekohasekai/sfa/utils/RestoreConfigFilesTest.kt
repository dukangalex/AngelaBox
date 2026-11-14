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
