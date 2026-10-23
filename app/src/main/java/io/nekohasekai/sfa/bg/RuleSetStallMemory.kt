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
 * The network key must come from [networkKey]: a stable interface-based key.
 * The old key (`Network.toString()`, i.e. the system netId) changed on every
 * reconnect of the same physical network — exactly the jitter this memory
 * exists for — and pointed at the VPN itself while the tunnel was up
 * (audit E-4).
 *
 * The memory is also content-aware (audit X-1): a stall recorded against one
 * version of a profile must not skip the rule-sets of a newer version, or a
 * subscription update that fixed a rule-set URL still starts with the new
 * rule-sets dropped. Callers that know the profile content pass its hash to
 * both [recordStall] and [shouldSkipRemote]; a hash mismatch forgets the
 * stall. Callers that pass no hash keep the legacy behaviour.
 *
 * Call discipline (AB-019, memory demotion): BoxService consults this
 * memory ONLY for reloads inside the current service run — that is what
 * it is for (quick restarts must not burn the start budget over and
 * over). A fresh service start never pre-skips: the full config gets
 * its first attempt, so a start after the user stopped the service, or
 * after the process died, is not punished for a stall it did not cause.
 * The gate lives in StartRetryPolicy.mayConsultStallMemory.
 *
 * Pure JVM (no Android types) so it can be unit tested; BoxService passes
 * SystemClock.elapsedRealtime and a string key for the default network.
 */
class RuleSetStallMemory(
    private val windowMs: Long = WINDOW_MS,
    private val clock: () -> Long,
) {
    private data class Stall(val atMs: Long, val network: String, val contentHash: String?)

    private val stalls = HashMap<Long, Stall>()

    /**
     * Record a stall. Without a known network there is nothing to compare
     * against later, so nothing is kept — and (audit E-5) an existing entry
     * is left alone: a stall recorded while the network callback had not
     * arrived yet must not erase a still-valid earlier stall.
     */
    @Synchronized
    fun recordStall(profileId: Long, network: String?) {
        recordStall(profileId, network, null)
    }

    @Synchronized
    fun recordStall(profileId: Long, network: String?, contentHash: String?) {
        if (network.isNullOrEmpty()) return
        stalls[profileId] = Stall(clock(), network, contentHash)
    }

    /** True only within the window of a recorded stall and on the same network. Expired entries are dropped. */
    @Synchronized
    fun shouldSkipRemote(profileId: Long, network: String?): Boolean {
        return shouldSkipRemote(profileId, network, null)
    }

    @Synchronized
    fun shouldSkipRemote(profileId: Long, network: String?, contentHash: String?): Boolean {
        val stall = stalls[profileId] ?: return false
        // X-1: the profile content changed since the stall — the memory is
        // about the old content's rule-sets and no longer applies.
        if (stall.contentHash != null && contentHash != null && stall.contentHash != contentHash) {
            stalls.remove(profileId)
            return false
        }
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

        /**
         * Canonical network key (audit E-4), the only format callers may
         * feed this memory:
         *
         *     v2|<transport>|<interfaceName>     e.g. "v2|wifi|wlan0"
         *
         * The interface name is the *underlying physical* interface
         * (wlan0, rmnet_data0, eth0) — stable across reconnects of the same
         * network, unlike the netId, and never the VPN's own tun interface.
         * Returns null when the interface is unknown, which the memory
         * treats as "no key, no skip", as before.
         */
        fun networkKey(interfaceName: String?, transport: String?): String? {
            val name = interfaceName?.trim()?.lowercase().orEmpty()
            if (name.isEmpty()) return null
            val t = transport?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "unknown"
            return "v2|$t|$name"
        }
    }
}
