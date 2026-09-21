package io.nekohasekai.sfa.utils

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SubscriptionInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expireAt: Long? = null,
    val fetchedAt: Long = 0L,
) {
    val used: Long get() = upload + download
    val hasQuota: Boolean get() = total > 0L
    val unlimited: Boolean get() = total <= 0L && expireAt == null
    val progress: Float
        get() = if (total > 0L) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    fun usedLabel(): String = formatBytes(used)
    fun totalLabel(): String = if (hasQuota) formatBytes(total) else ""
    fun expireLabel(): String? {
        val at = expireAt ?: return null
        if (at <= 0L) return null
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at * 1000L))
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
            var value = bytes.coerceAtLeast(0L).toDouble()
            var index = 0
            while (value >= 1024.0 && index < units.lastIndex) {
                value /= 1024.0
                index++
            }
            return String.format(Locale.US, "%.2f %s", value, units[index])
        }

        fun parse(raw: String, fetchedAt: Long = System.currentTimeMillis()): SubscriptionInfo? {
            val parts = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire: Long? = null
            for (part in parts) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).trim().lowercase(Locale.US)
                val value = part.substring(eq + 1).trim().toLongOrNull() ?: continue
                when (key) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = if (value > 10_000_000_000L) value / 1000L else value
                }
            }
            if (upload == 0L && download == 0L && total == 0L && expire == null) return null
            return SubscriptionInfo(upload, download, total, expire, fetchedAt)
        }
    }
}

object SubscriptionInfoStore {
    private const val PREFS = "subscription_info"
    private const val KEY_PREFIX = "info_"

    fun put(context: Context, profileId: Long, raw: String) {
        if (profileId < 0L || raw.isBlank()) return
        val payload = JSONObject()
            .put("raw", raw)
            .put("fetchedAt", System.currentTimeMillis())
            .toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("$KEY_PREFIX$profileId", payload)
            .apply()
    }

    fun get(context: Context, profileId: Long): SubscriptionInfo? {
        if (profileId < 0L) return null
        val payload = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$KEY_PREFIX$profileId", null)
            ?: return null
        return try {
            val obj = JSONObject(payload)
            SubscriptionInfo.parse(obj.optString("raw"), obj.optLong("fetchedAt"))
        } catch (_: Exception) {
            SubscriptionInfo.parse(payload)
        }
    }

    fun capture(client: HTTPClient, profileId: Long, context: Context) {
        val raw = client.lastUserinfo ?: return
        put(context, profileId, raw)
    }

    fun fetchRemote(url: String, profileId: Long, context: Context): String {
        return HTTPClient().use { client ->
            val body = client.getString(url, RemoteUrlGuard.Kind.SUBSCRIPTION)
            if (profileId > 0L) capture(client, profileId, context)
            body
        }
    }
}
