package io.nekohasekai.sfa.bg

import android.content.Context
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RuleSetProbe
import io.nekohasekai.sfa.utils.RuleSetTriage
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android glue for the per-set start triage (see RuleSetTriage):
 * the real probe and the persisted availability records.
 *
 * The probe is deliberately NOT the subscription HTTPClient: that
 * stack walks a tunnel-aware route ladder with long timeouts and
 * downloads whole bodies — the opposite of a bounded availability
 * check. This is a plain connection on the system network, which is
 * the path the kernel's healed download client (a named client with
 * no detour) also takes at a fresh start: a 1-byte ranged GET, short
 * timeouts, final 2xx after redirects = available, anything else =
 * unavailable. On a reload with the tunnel up, an app-process socket
 * may travel the ambient path instead of the kernel's protected one;
 * that biases the verdict towards AVAILABLE (benefit of the doubt),
 * never towards stripping — and records plus the stall memory carry
 * most reloads anyway.
 *
 * The records live in their own SharedPreferences file, keyed by
 * profile + tag + URL (RuleSetTriage.recordKey), valued "1|<ms>" /
 * "0|<ms>". Housekeeping on save drops the profile's entries that
 * are older than the longest TTL: they would be re-probed anyway.
 */
object HttpRuleSetProbe : RuleSetProbe {
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 2_500

    override fun probe(url: String): Boolean = runCatching {
        val target = URL(url.trim())
        if (target.protocol != "https" && target.protocol != "http") return false
        val conn = target.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("User-Agent", HTTPClient.userAgent)
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            if (code !in 200..299) return false
            // Drain at most a byte or two; the verdict is already in.
            conn.inputStream.use { input -> input.read(ByteArray(64)) }
            true
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}

object RuleSetAvailabilityStore {
    private const val PREFS = "rule_set_availability"

    private fun prefs() = runCatching {
        Application.application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }.getOrNull()

    /** All records for [profileId], keyed by RuleSetTriage.recordKey. */
    fun load(profileId: Long): Map<String, RuleSetTriage.Record> {
        val all = prefs()?.all ?: return emptyMap()
        val prefix = "$profileId|"
        val out = LinkedHashMap<String, RuleSetTriage.Record>()
        for ((key, value) in all) {
            if (!key.startsWith(prefix)) continue
            val text = value as? String ?: continue
            val sep = text.indexOf('|')
            if (sep <= 0) continue
            val at = text.substring(sep + 1).toLongOrNull() ?: continue
            out[key] = RuleSetTriage.Record(text.substring(0, sep) == "1", at)
        }
        return out
    }

    /** Merge [updates] in, and drop this profile's long-stale entries. */
    fun save(profileId: Long, updates: Map<String, RuleSetTriage.Record>, nowMs: Long) {
        if (updates.isEmpty()) return
        val prefs = prefs() ?: return
        val prefix = "$profileId|"
        val editor = prefs.edit()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(prefix) || key in updates) continue
            val text = value as? String ?: continue
            val at = text.substringAfter('|', "").toLongOrNull() ?: continue
            if (nowMs - at > RuleSetTriage.AVAILABLE_TTL_MS) editor.remove(key)
        }
        for ((key, record) in updates) {
            editor.putString(key, "${if (record.available) "1" else "0"}|${record.checkedAtMs}")
        }
        editor.apply()
    }
}
