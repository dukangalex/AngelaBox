package io.nekohasekai.sfa.database

import android.os.Parcelable
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.parcelize.Parcelize
import java.net.URI

@Entity(
    tableName = "remote_servers",
)
@Parcelize
class RemoteServer(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var userOrder: Long = 0L,
    var name: String = "",
    var url: String = "",
    var secret: String = "",
) : Parcelable {
    val displayName: String
        get() = name.ifEmpty { hostPort(url) }

    companion object {
        private val schemePrefix = Regex("^https?://", RegexOption.IGNORE_CASE)
        private val httpPrefix = Regex("^http://", RegexOption.IGNORE_CASE)

        // The stored form: scheme-less for https default; keep explicit https.
        fun normalizeURL(urlString: String): String = urlString.trim().trimEnd('/').replaceFirst(httpPrefix, "")

        // The form passed to libbox: a scheme is required. Default HTTPS.
        fun connectURL(urlString: String): String {
            val value = urlString.trim().trimEnd('/')
            if (value.isEmpty()) {
                return ""
            }
            if (value.contains(schemePrefix)) {
                return value
            }
            return "https://$value"
        }

        fun validateURL(urlString: String): String? {
            val connectURL = connectURL(urlString)
            if (connectURL.isEmpty()) {
                return null
            }
            val uri =
                try {
                    URI(connectURL)
                } catch (_: Exception) {
                    return null
                }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase().orEmpty()
            val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
            if (scheme == "http") {
                if (!loopback) return null
            } else if (scheme != "https") {
                return null
            }
            if (host.isEmpty()) {
                return null
            }
            return normalizeURL(urlString)
        }

        private fun hostPort(urlString: String): String {
            val uri =
                try {
                    URI(connectURL(urlString))
                } catch (_: Exception) {
                    return urlString
                }
            val host = uri.host ?: return urlString
            return if (uri.port != -1) "$host:${uri.port}" else host
        }
    }

    @androidx.room.Dao
    interface Dao {
        @Insert
        fun insert(server: RemoteServer): Long

        @Update
        fun update(server: RemoteServer): Int

        @Update
        fun update(servers: List<RemoteServer>): Int

        @Delete
        fun delete(server: RemoteServer): Int

        @Query("SELECT * FROM remote_servers WHERE id = :serverId")
        fun get(serverId: Long): RemoteServer?

        @Query("SELECT * FROM remote_servers ORDER BY userOrder ASC")
        fun list(): List<RemoteServer>

        @Query("SELECT MAX(userOrder) + 1 FROM remote_servers")
        fun nextOrder(): Long?
    }
}
