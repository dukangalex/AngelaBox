package io.nekohasekai.sfa.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Profile::class, RemoteServer::class, InitialImportIntent::class],
    version = ProfileDatabase.VERSION,
    exportSchema = true,
)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun profileDao(): Profile.Dao

    abstract fun remoteServerDao(): RemoteServer.Dao

    abstract fun initialImportDao(): InitialImportIntent.Store

    internal fun insertProfile(profile: Profile, pendingInitialImport: Boolean): Long = runInTransaction(
        java.util.concurrent.Callable {
            val id = profileDao().insert(profile)
            if (pendingInitialImport) initialImportDao().insert(InitialImportIntent(id))
            id
        },
    )

    internal fun updateProfile(profile: Profile, materialized: Boolean = false): Int = runInTransaction(
        java.util.concurrent.Callable {
            val updated = profileDao().update(profile)
            if (updated > 0 && (materialized || profile.typed.type != TypedProfile.Type.Remote)) initialImportDao().clear(profile.id)
            updated
        },
    )

    companion object {
        const val VERSION = 4

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `initial_import_intents` (`profileId` INTEGER NOT NULL, PRIMARY KEY(`profileId`), FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Add icon column to profiles table with default value null
                    database.execSQL("ALTER TABLE profiles ADD COLUMN icon TEXT DEFAULT NULL")
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS `remote_servers` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`userOrder` INTEGER NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`url` TEXT NOT NULL, " +
                            "`secret` TEXT NOT NULL)",
                    )
                }
            }
    }
}
