package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [RestoreDirectoryOsShadow::class])
class ProfileCreationIntegrationTest {
    @Test fun concurrentCommitsKeepDistinctFilesAndRoomRows() = runBlocking(Dispatchers.IO) {
        val ids = (0 until 40).map { i ->
            async {
                val p = Profile(name = "reservation-test-$i", typed = TypedProfile())
                ProfileManager.createReserved(p) { file -> ProfileFiles.writeAtomically(file, "content-$i") }
            }
        }.awaitAll()
        assertEquals(40, ids.map { it.typed.path }.toSet().size)
        val rows = ProfileManager.list().associateBy { it.id }
        ids.forEachIndexed { i, p ->
            assertEquals(p.typed.path, rows[p.id]!!.typed.path)
            assertEquals("content-$i", File(p.typed.path).readText())
        }
    }

    @Test fun pendingRemoteRemainsUnmaterializedAndDurablyRecorded() = runBlocking(Dispatchers.IO) {
        val p = Profile(name = "pending-reservation", typed = TypedProfile().apply { type = TypedProfile.Type.Remote })
        ProfileManager.createReserved(p, pendingInitialImport = true)
        assertFalse(File(p.typed.path).exists())
        assertEquals(p.typed.path, ProfileManager.get(p.id)!!.typed.path)
        assertTrue(p.id in ProfileManager.pendingInitialImportIds())
        val context = RuntimeEnvironment.getApplication()
        BackupRestoreTransaction.recoverAtStartup(context)
        assertEquals(p.typed.path, ProfileManager.get(p.id)!!.typed.path)
        assertTrue(p.id in ProfileManager.pendingInitialImportIds())
        assertFalse(File(p.typed.path).exists())
    }

    @Test fun failedFileWriteDoesNotInsertRowAndRetainsReservation() = runBlocking(Dispatchers.IO) {
        val p = Profile(name = "injected-write-failure", typed = TypedProfile())
        try {
            ProfileManager.createReserved(p) { throw IOException("injected") }
            fail("expected write failure")
        } catch (_: IOException) {}
        assertTrue(ProfileManager.list().none { it.name == "injected-write-failure" })
        assertTrue(p.typed.path.isNotBlank())
        val target = File(p.typed.path)
        val marker = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "profile-path-reservations/${target.name.removePrefix("profile-").removeSuffix(".json")}.reserved")
        assertTrue(marker.exists())
    }

    @Test fun restoreExclusionCoversFileToRoomCommitGap() = runBlocking(Dispatchers.IO) {
        val inWrite = CountDownLatch(1)
        val release = CountDownLatch(1)
        val profile = Profile(name = "lease-held", typed = TypedProfile())
        val create = async {
            ProfileManager.createReserved(profile) { target ->
                ProfileFiles.writeAtomically(target, "pending-commit")
                inWrite.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(inWrite.await(10, TimeUnit.SECONDS))
            val attempted = runCatching { RestoreStorageAccess.exclusive { "unexpected" } }
            assertTrue("restore cannot start while the file/Room commit lease is held", attempted.isFailure)
        } finally {
            release.countDown()
        }
        create.await()
        assertEquals(profile.typed.path, ProfileManager.get(profile.id)!!.typed.path)
        assertEquals("pending-commit", File(profile.typed.path).readText())
    }
}
