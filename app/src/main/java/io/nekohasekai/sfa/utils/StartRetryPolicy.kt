package io.nekohasekai.sfa.utils

/**
 * Pure decisions of the BoxService start ladder that used to be implicit
 * in the ladder's control flow (AB-019). Extracted so the policy itself
 * is unit-testable; BoxService only supplies the facts.
 */
object StartRetryPolicy {
    /**
     * Budget for the one extended full-config retry: double the standard
     * 45 s. Rule-set files total a few hundred KB; a healthy but slow
     * cold start (blackholed first route, carrier jitter to the mirror,
     * slow DNS bootstrap) that cannot finish in 45 s very often finishes
     * well inside 90 s — and only pays the extra wait when the first
     * attempt actually timed out with downloads outstanding.
     */
    const val EXTENDED_START_BUDGET_MS = 90_000L

    /**
     * Memory demotion: the rule-set stall memory may only pre-degrade a
     * start that is a reload inside the current service run (its purpose
     * is to keep quick restarts from burning the start budget again and
     * again). A fresh service start must never be pre-skipped — the full
     * config gets its first attempt, so a network that recovered since
     * the stall is not punished for someone else's bad ten minutes.
     */
    fun mayConsultStallMemory(freshStart: Boolean): Boolean = !freshStart

    /**
     * Budget decoupling: a first-attempt timeout is not proof the rule
     * sets are at fault, so before any rung degrades the config, a
     * profile that still has remote rule sets without a cache copy gets
     * exactly one retry of the full config at the extended budget.
     */
    fun shouldRetryWithExtendedBudget(
        timedOut: Boolean,
        alreadyExtended: Boolean,
        uncachedRemoteRuleSets: Int,
        ladderExpired: Boolean,
    ): Boolean =
        timedOut && !alreadyExtended && uncachedRemoteRuleSets > 0 && !ladderExpired
}
