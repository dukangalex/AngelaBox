package io.nekohasekai.sfa.bg

/**
 * Remembers that a profile's remote rule-sets ran past the start budget, so a
 * start right after it skips them instead of waiting another 45 s.
 *
 * The memory is narrow on purpose: it only holds for [windowMs] after the stall
 * and only on the same default network. A different network, an unknown network
 * or an older stall means the next start tries the rule-sets again. A start that
 * succeeded with the rule-sets kept clears it.
 *
 * Pure JVM (no Android types) so it can be unit tested; BoxService passes
 * SystemClock.elapsedRealtime and a string key for the default network.
 */
class RuleSetStallMemory(
    private val windowMs: Long = WINDOW_MS,
    private val clock: () -> Long,
) {
    private data class Stall(val atMs: Long, val network: String)

    private val stalls = HashMap<Long, Stall>()

    /** Record a stall. Without a known network there is nothing to compare against later, so nothing is kept. */
    @Synchronized
    fun recordStall(profileId: Long, network: String?) {
        if (network.isNullOrEmpty()) {
            stalls.remove(profileId)
            return
        }
        stalls[profileId] = Stall(clock(), network)
    }

    /** True only within the window of a recorded stall and on the same network. Expired entries are dropped. */
    @Synchronized
    fun shouldSkipRemote(profileId: Long, network: String?): Boolean {
        val stall = stalls[profileId] ?: return false
        val age = clock() - stall.atMs
        if (age < 0 || age > windowMs) {
            stalls.remove(profileId)
            return false
        }
        return !network.isNullOrEmpty() && network == stall.network
    }

    /** A start kept the remote rule-sets and came up: forget the stall. */
    @Synchronized
    fun clear(profileId: Long) {
        stalls.remove(profileId)
    }

    companion object {
        const val WINDOW_MS = 10L * 60 * 1000
    }
}
