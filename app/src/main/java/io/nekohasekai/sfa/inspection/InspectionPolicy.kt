package io.nekohasekai.sfa.inspection

import org.json.JSONArray
import org.json.JSONObject

/** Evidence, not an AI verdict. No profile names, URLs, IPs, logs or config enter this model. */
object InspectionPolicy {
    const val MAX_AGE_MS = 5 * 60_000L
    const val INTERVAL_MINUTES = 15L

    enum class State { OBSERVED, ATTENTION, UNKNOWN, NOT_CHECKED }
    data class Fact(val id: String, val state: State, val code: String)
    data class Snapshot(
        val collectedAt: Long,
        val profileId: Long,
        val serviceUp: Boolean,
        val facts: List<Fact>,
        val networkId: Long? = null,
        val contextToken: String = "",
    )

    private val allowedCodes = mapOf(
        "network" to setOf("present", "absent", "unknown"),
        "service" to setOf("running", "stopped"),
        "TUNNEL" to setOf("ok", "http_error", "timeout", "dns_failure", "io_error", "not_started", "timeout_downgraded"),
        "DNS" to setOf("ok", "fakeip", "error", "hijacked", "hijacked_local", "hijacked_system"),
        "LATENCY" to setOf("ok", "slow", "ok_stale", "slow_stale", "no_data", "skipped"),
        "SUBSCRIPTION" to setOf("ok", "expired", "quota_exhausted", "update_failed", "quota_low", "import_warnings", "unknown"),
    )

    fun normalized(fact: Fact): Fact = if (fact.code in allowedCodes[fact.id].orEmpty()) {
        fact
    } else {
        Fact(fact.id.takeIf { it in allowedCodes } ?: "unknown", State.UNKNOWN, "unknown")
    }

    /** Wall-clock rollback is stale too; persisted results never prove current health. */
    fun fresh(snapshot: Snapshot, now: Long, profileId: Long, serviceUp: Boolean, networkId: Long?, contextToken: String): Boolean = now >= snapshot.collectedAt && now - snapshot.collectedAt <= MAX_AGE_MS &&
        snapshot.profileId == profileId && snapshot.serviceUp == serviceUp && snapshot.networkId == networkId && snapshot.contextToken == contextToken && contextToken.isNotEmpty()

    fun attention(facts: List<Fact>): Boolean = facts.any { normalized(it).state == State.ATTENTION }

    /** Allowlist, not regex redaction: arbitrary strings can never cross the network boundary. */
    fun payload(snapshot: Snapshot, appVersion: String, kernelVersion: String): String {
        fun version(value: String) = value.takeIf { it.matches(Regex("[a-zA-Z0-9.+_-]{1,80}")) } ?: "unknown"
        return JSONObject()
            .put("schema", 1)
            .put("app_version", version(appVersion))
            .put("kernel_version", version(kernelVersion))
            .put(
                "checks",
                JSONArray().apply {
                    snapshot.facts.map(::normalized).forEach { fact ->
                        put(JSONObject().put("id", fact.id).put("state", fact.state.name).put("code", fact.code))
                    }
                },
            )
            .put("limitations", "Point-in-time observations only; app traffic is not proof of other apps routing; subscription health is aggregated across local profiles; DNS canary nonresolution is not proof of working DNS; no root cause established; skipped/unknown is not healthy.")
            .toString(2)
    }

    fun evidenceKey(snapshot: Snapshot): String = snapshot.facts.map(::normalized)
        .sortedBy { it.id }.joinToString("|") { "${it.id}:${it.state}:${it.code}" }
}
