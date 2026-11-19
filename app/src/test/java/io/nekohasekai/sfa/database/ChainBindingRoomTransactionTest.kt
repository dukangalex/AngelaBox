package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.preference.KeyValueDatabase
import io.nekohasekai.sfa.database.preference.KeyValueEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChainBindingRoomTransactionTest {
    @Test
    fun rollbackOnMidTransactionFailurePreservesEveryPriorField() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), KeyValueDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val keys = listOf(SettingsKey.CHAIN_BINDINGS, SettingsKey.CHAIN_ENABLED, SettingsKey.CHAIN_BOUND_PROFILE_ID)
            val dao = db.keyValuePairDao()
            keys.forEach { dao.put(KeyValueEntity(it).put("old:$it")) }
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_binding_update BEFORE INSERT ON KeyValueEntity " +
                    "WHEN NEW.`key` = '${SettingsKey.CHAIN_BOUND_PROFILE_ID}' BEGIN SELECT RAISE(ABORT, 'injected'); END",
            )
            try {
                writeChainBindingEntries(db, keys.map { KeyValueEntity(it).put("new:$it") })
                fail("the injected write must fail")
            } catch (_: android.database.sqlite.SQLiteException) {
                // A real Room/SQLite transaction must roll back the preceding writes too.
            }
            keys.forEach { assertEquals("old:$it", dao[it]?.string) }
        } finally {
            db.close()
        }
    }

    @Test
    fun committedSnapshotSurvivesDatabaseReopen() {
        val context = RuntimeEnvironment.getApplication()
        val name = "binding-snapshot-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, KeyValueDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val writeDb = open()
            try {
                val db = writeDb
                writeChainBindingEntries(
                    db,
                    listOf(
                        KeyValueEntity(SettingsKey.CHAIN_BINDINGS).put("{}"),
                        KeyValueEntity(SettingsKey.CHAIN_ENABLED).put(false),
                        KeyValueEntity(SettingsKey.CHAIN_BOUND_PROFILE_ID).put(-1L),
                    ),
                )
            } finally {
                writeDb.close()
            }
            val readDb = open()
            try {
                val db = readDb
                val dao = db.keyValuePairDao()
                assertEquals("{}", dao[SettingsKey.CHAIN_BINDINGS]?.string)
                assertEquals(false, dao[SettingsKey.CHAIN_ENABLED]?.boolean)
                assertEquals(-1L, dao[SettingsKey.CHAIN_BOUND_PROFILE_ID]?.long)
            } finally {
                readDb.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
