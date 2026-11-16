package io.nekohasekai.sfa.inspection

import io.nekohasekai.sfa.R
import org.junit.Assert.*
import org.junit.Test

class InspectionPresentationTest {
    @Test fun uncheckedAndUnknownNeverShowAnObservedSuccess() {
        val missing = InspectionPresentation.completeFacts(emptyList())
        assertEquals(6, missing.size)
        missing.forEach { assertEquals(R.string.inspection_human_skip_label, InspectionPresentation.explain(it).status) }
        val dns = InspectionPresentation.explain(InspectionPolicy.Fact("DNS", InspectionPolicy.State.OBSERVED, "ok"))
        assertEquals(R.string.inspection_human_unknown_label, dns.status)
        assertEquals(R.string.inspection_human_dns_canary, dns.detail)
        val unexpected = InspectionPresentation.explain(InspectionPolicy.Fact("TUNNEL", InspectionPolicy.State.OBSERVED, "future-value"))
        assertEquals(R.string.inspection_human_unknown_label, unexpected.status)
    }

    @Test fun identifiedProblemsGiveRelevantManualAdvice() {
        val network = InspectionPresentation.explain(InspectionPolicy.Fact("network", InspectionPolicy.State.ATTENTION, "absent"))
        assertEquals(R.string.inspection_human_network_absent_advice, network.advice)
        val subscription = InspectionPresentation.explain(InspectionPolicy.Fact("SUBSCRIPTION", InspectionPolicy.State.ATTENTION, "quota_exhausted"))
        assertEquals(R.string.inspection_human_sub_advice, subscription.advice)
        val tunnel = InspectionPresentation.explain(InspectionPolicy.Fact("TUNNEL", InspectionPolicy.State.ATTENTION, "timeout"))
        assertEquals(R.string.inspection_human_tunnel_advice, tunnel.advice)
    }
}
