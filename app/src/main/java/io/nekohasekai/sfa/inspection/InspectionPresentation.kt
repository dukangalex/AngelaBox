package io.nekohasekai.sfa.inspection

import io.nekohasekai.sfa.R

/** Local explanations are based only on the recorded fact, never an inferred root cause. */
internal object InspectionPresentation {
    data class Display(val label: Int, val status: Int, val detail: Int, val advice: Int? = null)
    private val labels = mapOf(
        "network" to R.string.inspection_human_network_label,
        "service" to R.string.inspection_human_service_label,
        "TUNNEL" to R.string.inspection_human_tunnel_label,
        "DNS" to R.string.inspection_human_dns_label,
        "LATENCY" to R.string.inspection_human_latency_label,
        "SUBSCRIPTION" to R.string.inspection_human_subscription_label,
    )
    val itemOrder = listOf("network", "service", "TUNNEL", "DNS", "LATENCY", "SUBSCRIPTION")
    fun completeFacts(facts: List<InspectionPolicy.Fact>): List<InspectionPolicy.Fact> = itemOrder.map { id -> facts.firstOrNull { it.id == id } ?: InspectionPolicy.Fact(id, InspectionPolicy.State.NOT_CHECKED, "unknown") }

    fun explain(raw: InspectionPolicy.Fact): Display {
        val fact = InspectionPolicy.normalized(raw)
        val label = labels[fact.id] ?: R.string.inspection_human_other_label
        val displayState = when {
            fact.state == InspectionPolicy.State.NOT_CHECKED -> InspectionPolicy.State.NOT_CHECKED
            fact.id == "SUBSCRIPTION" && fact.code == "unknown" -> InspectionPolicy.State.UNKNOWN
            fact.id == "LATENCY" && fact.code in setOf("no_data", "ok_stale", "slow_stale") -> InspectionPolicy.State.UNKNOWN
            else -> fact.state
        }
        val status = when (displayState) {
            InspectionPolicy.State.OBSERVED -> R.string.inspection_human_obs_label
            InspectionPolicy.State.ATTENTION -> R.string.inspection_human_att_label
            InspectionPolicy.State.UNKNOWN -> R.string.inspection_human_unknown_label
            InspectionPolicy.State.NOT_CHECKED -> R.string.inspection_human_skip_label
        }
        if (fact.state == InspectionPolicy.State.NOT_CHECKED) return Display(label, status, R.string.inspection_human_not_checked)
        if (fact.state == InspectionPolicy.State.UNKNOWN && !(fact.id == "DNS" && fact.code == "canary_not_resolved")) {
            return Display(label, status, R.string.inspection_human_unknown, R.string.inspection_human_unknown_advice)
        }
        val detail = when (fact.id to fact.code) {
            "network" to "present" -> R.string.inspection_human_network_present
            "network" to "absent" -> R.string.inspection_human_network_absent
            "service" to "running" -> R.string.inspection_human_service_running
            "service" to "stopped" -> R.string.inspection_human_service_stopped
            "TUNNEL" to "ok" -> R.string.inspection_human_tunnel_ok
            "TUNNEL" to "timeout", "TUNNEL" to "timeout_downgraded" -> R.string.inspection_human_tunnel_timeout
            "TUNNEL" to "http_error" -> R.string.inspection_human_tunnel_http
            "TUNNEL" to "dns_failure" -> R.string.inspection_human_tunnel_dns
            "TUNNEL" to "io_error" -> R.string.inspection_human_tunnel_io
            "DNS" to "canary_not_resolved" -> R.string.inspection_human_dns_canary
            "DNS" to "fakeip" -> R.string.inspection_human_dns_fakeip
            "DNS" to "error" -> R.string.inspection_human_dns_error
            "DNS" to "hijacked", "DNS" to "hijacked_local", "DNS" to "hijacked_system" -> R.string.inspection_human_dns_unexpected
            "LATENCY" to "ok" -> R.string.inspection_human_latency_ok
            "LATENCY" to "slow" -> R.string.inspection_human_latency_slow
            "LATENCY" to "ok_stale", "LATENCY" to "slow_stale" -> R.string.inspection_human_latency_stale
            "LATENCY" to "no_data" -> R.string.inspection_human_latency_none
            "SUBSCRIPTION" to "ok" -> R.string.inspection_human_sub_ok
            "SUBSCRIPTION" to "expired" -> R.string.inspection_human_sub_expired
            "SUBSCRIPTION" to "quota_exhausted" -> R.string.inspection_human_sub_exhausted
            "SUBSCRIPTION" to "update_failed" -> R.string.inspection_human_sub_update
            "SUBSCRIPTION" to "quota_low" -> R.string.inspection_human_sub_low
            "SUBSCRIPTION" to "import_warnings" -> R.string.inspection_human_sub_warnings
            "SUBSCRIPTION" to "unknown" -> R.string.inspection_human_sub_unknown
            else -> R.string.inspection_human_unknown
        }
        val advice = when (fact.id to fact.code) {
            "network" to "absent" -> R.string.inspection_human_network_absent_advice
            "service" to "stopped" -> R.string.inspection_human_service_stopped_advice
            "TUNNEL" to "timeout", "TUNNEL" to "timeout_downgraded", "TUNNEL" to "http_error", "TUNNEL" to "dns_failure", "TUNNEL" to "io_error" -> R.string.inspection_human_tunnel_advice
            "DNS" to "error", "DNS" to "hijacked", "DNS" to "hijacked_local", "DNS" to "hijacked_system" -> R.string.inspection_human_dns_advice
            "LATENCY" to "slow", "LATENCY" to "slow_stale", "LATENCY" to "no_data" -> R.string.inspection_human_latency_advice
            "SUBSCRIPTION" to "expired", "SUBSCRIPTION" to "quota_exhausted", "SUBSCRIPTION" to "update_failed", "SUBSCRIPTION" to "quota_low", "SUBSCRIPTION" to "import_warnings" -> R.string.inspection_human_sub_advice
            else -> null
        }
        return Display(label, status, detail, advice)
    }
}
