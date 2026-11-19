package io.nekohasekai.sfa.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Explicit download intent, committed with the profile, never inferred from a missing file. */
@Entity(tableName = "initial_import_intents", foreignKeys = [ForeignKey(entity = Profile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)])
data class InitialImportIntent(@PrimaryKey val profileId: Long) {
    @Dao
    interface Store {
        @Insert(onConflict = OnConflictStrategy.IGNORE)
        fun insert(intent: InitialImportIntent)

        @Query("SELECT profileId FROM initial_import_intents ORDER BY profileId")
        fun pendingIds(): List<Long>

        @Query("DELETE FROM initial_import_intents WHERE profileId = :profileId")
        fun clear(profileId: Long)
    }
}
