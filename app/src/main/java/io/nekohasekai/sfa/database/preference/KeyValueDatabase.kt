package io.nekohasekai.sfa.database.preference

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [KeyValueEntity::class],
    version = KeyValueDatabase.VERSION,
)
abstract class KeyValueDatabase : RoomDatabase() {
    abstract fun keyValuePairDao(): KeyValueEntity.Dao

    companion object {
        const val VERSION = 1
    }
}
