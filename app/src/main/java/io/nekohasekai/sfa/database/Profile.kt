package io.nekohasekai.sfa.database

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.parcelize.Parcelize

@Entity(
    tableName = "profiles",
)
@TypeConverters(TypedProfile.Convertor::class)
@Parcelize
class Profile(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var userOrder: Long = 0L,
    var name: String = "",
    @ColumnInfo(defaultValue = "NULL") var icon: String? = null,
    var typed: TypedProfile = TypedProfile(),
) : Parcelable {
    // Process-local provenance; never stored in Room or accepted from a parcel.
    @androidx.room.Ignore
    @kotlinx.parcelize.IgnoredOnParcel
    internal var restoreSnapshot: io.nekohasekai.sfa.utils.RestoreAccessGate.Snapshot? = null

    @androidx.room.Dao
    interface Dao {
        @Insert
        fun insert(profile: Profile): Long

        @Update
        fun update(profile: Profile): Int

        @Update
        fun update(profile: List<Profile>): Int

        @Delete
        fun delete(profile: Profile): Int

        @Delete
        fun delete(profile: List<Profile>): Int

        @Query("SELECT * FROM profiles WHERE id = :profileId")
        fun get(profileId: Long): Profile?

        @Query("select * from profiles order by userOrder asc")
        fun list(): List<Profile>

        @Query("DELETE FROM profiles")
        fun clear()

        @Query("SELECT MAX(userOrder) + 1 FROM profiles")
        fun nextOrder(): Long?

        @Query("SELECT MAX(id) + 1 FROM profiles")
        fun nextFileID(): Long?
    }
}
