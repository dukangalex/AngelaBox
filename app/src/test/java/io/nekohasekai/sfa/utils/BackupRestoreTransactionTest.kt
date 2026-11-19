package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [RestoreDirectoryOsShadow::class])
class BackupRestoreTransactionTest {
    private class Killed : Error("simulated process death")
    private fun exercise(crash: Boolean) = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val configs = File(context.filesDir, "configs").apply { mkdirs() }
        val old = File(configs, "old.json").apply { writeText("old-config") }
        Settings.themeMode = "old-theme"
        val profile = Profile(name = "old-profile", typed = TypedProfile().apply { path = old.path })
        ProfileManager.create(profile)
        try {
            BackupRestoreTransaction.run(context) {
                Settings.themeMode = "new-theme"
                ProfileManager.clearForRestore()
                old.delete()
                File(configs, "new.json").writeText("new-config")
                if (crash) throw Killed() else throw IOException("injected copy failure")
            }
            fail("fault must propagate")
        } catch (failure: Throwable) {
            if (crash) assertTrue(failure is Killed) else assertTrue(failure is IOException)
        }
        if (crash) {
            // A retained DAO cannot read a partially restored set before startup recovery.
            assertTrue(runCatching { Settings.themeMode }.isFailure)
            BackupRestoreTransaction.recoverAtStartup(context)
        }
        assertEquals("old-theme", Settings.themeMode)
        assertEquals(listOf("old-profile"), ProfileManager.list().map { it.name })
        assertEquals("old-config", old.readText())
        assertFalse(File(configs, "new.json").exists())
        // Recovery is repeatable and does not roll a completed rollback back again.
        BackupRestoreTransaction.recoverAtStartup(context)
        assertEquals("old-theme", Settings.themeMode)
        assertEquals("old-config", old.readText())
    }

    @Test fun exceptionRollsBackBothDatabasesAndConfigurationTogether() = exercise(false)

    @Test fun startupRecoversBothDatabasesAndFilesAfterSimulatedDeath() = exercise(true)

    @Test fun otherThreadCannotUseRetainedDaoDuringRestore() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val retained = ProfileManager.remoteServerDao()
        var denied = false
        BackupRestoreTransaction.run(context) {
            val reader = Thread { denied = runCatching { retained.list() }.isFailure }
            reader.start()
            reader.join(3000)
            assertFalse("reader must fail promptly rather than deadlock", reader.isAlive)
            assertTrue(denied)
        }
    }
}
