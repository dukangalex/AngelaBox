package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.sfa.chain.ChainBinding
import io.nekohasekai.sfa.chain.ChainBindingCodec
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.preference.KeyValueDatabase
import io.nekohasekai.sfa.database.preference.KeyValueEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProfileBindingJournalTest {
    private class Cut : Error()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun entries(clear: Boolean = false) = ProfileBindingJournal.entries(
        ChainBindingCodec.snapshot(
            if (clear) emptyMap() else mapOf(1L to ChainBinding(1, "new-entry", 2, "landing", materialized = true)),
            1,
        ),
    )

    private fun scenario(action: (File, () -> KeyValueDatabase, (KeyValueDatabase) -> Unit, () -> Unit) -> Unit) {
        val root = File(context.cacheDir, "journal-${java.util.UUID.randomUUID()}").also { it.mkdirs() }
        val name = "journal-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, KeyValueDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            writeChainBindingEntries(
                db,
                ProfileBindingJournal.entries(
                    ChainBindingCodec.snapshot(
                        mapOf(1L to ChainBinding(1, "old-entry", 2, "old-landing", materialized = true)),
                        1,
                    ),
                ),
            )
            action(root, { db }, { replacement -> db = replacement }, {
                db.close()
                db = open()
            })
        } finally {
            db.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    @Test fun recoverEveryCommitBoundaryAfterRealDatabaseReopen() {
        for (point in listOf("staged", "before_intent_commit", "intent_committed", "file_replaced", "file_durable", "before_clear_commit", "intent_cleared")) {
            scenario { root, db, _, reopen ->
                val target = File(root, "1.json").also { it.writeText("old") }
                fun journal(cut: (String) -> Unit = {}) = ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, {}, cut)
                try {
                    journal { if (it == point) throw Cut() }.commit(1, target, "old", "new", entries())
                    fail("cut $point was not reached")
                } catch (_: Cut) { }
                reopen()
                journal().recover()
                val committed = point !in listOf("staged", "before_intent_commit")
                assertEquals(point, if (committed) "new" else "old", target.readText())
                val bindings = ChainBindingCodec.parse(db().keyValuePairDao()[SettingsKey.CHAIN_BINDINGS]!!.string!!)
                assertEquals(point, if (committed) "new-entry" else "old-entry", bindings[1L]!!.entryTag)
                assertTrue(bindings[1L]!!.materialized)
                assertNull(db().keyValuePairDao()[ProfileBindingJournal.KEY])
                assertFalse(journal().recover())
            }
        }
    }

    @Test fun clearingLastBindingDoesNotResurrectLegacyAfterInterruptedCommit() = scenario { root, db, _, reopen ->
        val target = File(root, "1.json").also { it.writeText("old") }
        val journal = ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, {}, { if (it == "intent_committed") throw Cut() })
        try {
            journal.commit(1, target, "old", "clean", entries(clear = true))
            fail()
        } catch (_: Cut) { }
        reopen()
        ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, {}).recover()
        val dao = db().keyValuePairDao()
        assertEquals("clean", target.readText())
        assertTrue(ChainBindingCodec.parse(dao[SettingsKey.CHAIN_BINDINGS]!!.string!!).isEmpty())
        assertEquals(false, dao[SettingsKey.CHAIN_ENABLED]!!.boolean)
        assertEquals(-1L, dao[SettingsKey.CHAIN_BOUND_PROFILE_ID]!!.long)
        assertEquals(-1L, dao[SettingsKey.CHAIN_LANDING_PROFILE_ID]!!.long)
        assertEquals("", dao[SettingsKey.CHAIN_ENTRY_TAG]!!.string)
        assertEquals("", dao[SettingsKey.CHAIN_LANDING_TAG]!!.string)
    }

    @Test fun conflictsPreservePendingIntentAndNeverOverwrite() {
        for (conflict in listOf("file", "deleted", "path", "bindings", "stage", "malformed")) {
            scenario { root, db, _, reopen ->
                val target = File(root, "1.json").also { it.writeText("old") }
                var path: String? = target.path
                fun journal(cut: (String) -> Unit = {}) = ProfileBindingJournal(db, File(root, "stages"), { path }, root, {}, cut)
                try {
                    journal { if (it == "intent_committed") throw Cut() }.commit(1, target, "old", "new", entries())
                    fail()
                } catch (_: Cut) { }
                reopen()
                when (conflict) {
                    "file" -> target.writeText("independent")
                    "deleted" -> path = null
                    "path" -> path = File(root, "other").path
                    "bindings" -> db().keyValuePairDao().put(KeyValueEntity(SettingsKey.CHAIN_ENTRY_TAG).put("independent"))
                    "stage" -> File(root, "stages").listFiles()!!.single().writeText("damaged")
                    "malformed" -> db().keyValuePairDao().put(KeyValueEntity(ProfileBindingJournal.KEY).put("broken"))
                }
                val actual = target.readText()
                repeat(2) {
                    try {
                        journal().recover()
                        fail("conflict $conflict must block")
                    } catch (_: ProfileBindingRecoveryException) { }
                    assertEquals(actual, target.readText())
                    assertNotNull(db().keyValuePairDao()[ProfileBindingJournal.KEY])
                }
            }
        }
    }

    @Test fun failedDirectorySyncRetainsIntentForLaterRetry() = scenario { root, db, _, reopen ->
        val target = File(root, "1.json").also { it.writeText("old") }
        var syncs = 0
        val journal = ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, { if (++syncs == 3) throw java.io.IOException("disk") })
        try {
            journal.commit(1, target, "old", "new", entries())
            fail()
        } catch (_: java.io.IOException) { }
        assertNotNull(db().keyValuePairDao()[ProfileBindingJournal.KEY])
        reopen()
        ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, {}).recover()
        assertEquals("new", target.readText())
        assertNull(db().keyValuePairDao()[ProfileBindingJournal.KEY])
    }

    @Test fun staleInputDoesNotCommitBindingsOrCreateIntent() = scenario { root, db, _, _ ->
        val target = File(root, "1.json").also { it.writeText("changed") }
        assertFalse(ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, {}).commit(1, target, "old", "new", entries()))
        assertEquals("changed", target.readText())
        assertNull(db().keyValuePairDao()[ProfileBindingJournal.KEY])
        assertEquals("old-entry", ChainBindingCodec.parse(db().keyValuePairDao()[SettingsKey.CHAIN_BINDINGS]!!.string!!)[1L]!!.entryTag)
    }

    @Test fun barrierRecoversBeforeReadAndWriteAndBlocksConflicts() = scenario { root, db, _, reopen ->
        val target = File(root, "1.json").also { it.writeText("old") }
        fun journal(cut: (String) -> Unit = {}) = ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, {}, cut)
        try {
            journal { if (it == "intent_committed") throw Cut() }.commit(1, target, "old", "new", entries())
        } catch (_: Cut) { }
        reopen()
        val barrier = RecoveringProfileStorage(Any()) { journal().recover() }
        barrier.access {
            assertEquals("new", target.readText())
            assertNull(db().keyValuePairDao()[ProfileBindingJournal.KEY])
        }
        try {
            journal { if (it == "intent_committed") throw Cut() }.commit(1, target, "new", "newer", entries())
        } catch (_: Cut) { }
        target.writeText("independent")
        var ran = false
        for (write in listOf(false, true)) {
            try {
                if (write) barrier.mutate { ran = true } else barrier.access { ran = true }
                fail("conflict must block all readers/writers")
            } catch (_: ProfileBindingRecoveryException) { }
        }
        assertFalse(ran)
        assertEquals("independent", target.readText())
    }

    @Test fun barrierSerializesTheIntentWindowAndInvalidatesRuntimeSnapshot() = scenario { root, db, _, _ ->
        val target = File(root, "1.json").also { it.writeText("old") }
        fun journal(cut: (String) -> Unit = {}) = ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, {}, cut)
        val barrier = RecoveringProfileStorage(Any()) { journal().recover() }
        val epoch = barrier.currentEpoch()
        val pending = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val readDone = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val writer = Thread {
            try {
                barrier.mutate {
                    journal {
                        if (it == "intent_committed") {
                            pending.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                    }.commit(1, target, "old", "new", entries())
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }
        writer.start()
        assertTrue(pending.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val reader = Thread {
            try {
                barrier.access {
                    assertEquals("new", target.readText())
                    assertNull(db().keyValuePairDao()[ProfileBindingJournal.KEY])
                }
            } catch (e: Throwable) {
                errors.add(e)
            } finally {
                readDone.countDown()
            }
        }
        try {
            reader.start()
            assertFalse(readDone.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            writer.join(10000)
            reader.join(10000)
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(readDone.await(1, java.util.concurrent.TimeUnit.SECONDS))
        try {
            barrier.requireEpoch(epoch)
            fail("stale startup must abort")
        } catch (_: IllegalStateException) { }
    }

    @Test fun realSqliteAbortCannotPersistIntentWithoutAllBindingFields() = scenario { root, db, _, reopen ->
        val target = File(root, "1.json").also { it.writeText("old") }
        db().openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_intent BEFORE INSERT ON KeyValueEntity WHEN NEW.`key` = '${ProfileBindingJournal.KEY}' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        try {
            ProfileBindingJournal(db, File(root, "stages"), { target.path }, root, {}).commit(1, target, "old", "new", entries())
            fail()
        } catch (_: android.database.sqlite.SQLiteException) { }
        reopen()
        assertEquals("old", target.readText())
        assertNull(db().keyValuePairDao()[ProfileBindingJournal.KEY])
        assertEquals("old-entry", ChainBindingCodec.parse(db().keyValuePairDao()[SettingsKey.CHAIN_BINDINGS]!!.string!!)[1L]!!.entryTag)
    }
}
