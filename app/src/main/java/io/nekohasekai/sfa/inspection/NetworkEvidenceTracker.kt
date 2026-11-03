package io.nekohasekai.sfa.inspection

// Preserve all capability changes, including FOREGROUND: Android documents it as
// app network availability, not merely an informational background flag.
internal fun evidenceCapabilityBits(bits: Long): Long = bits

/** One tracker per callback: default and underlying networks have independent lifecycles. */
internal class NetworkEvidenceTracker<K, C, L>(private val singleNetwork: Boolean = false, private val invalidate: () -> Unit) {
    private val capabilities = mutableMapOf<K, C>()
    private val links = mutableMapOf<K, L>()
    private val blocked = mutableMapOf<K, Boolean>()

    @Synchronized fun available(network: K) {
        // Default callbacks stop reporting the old best network without onLost.
        // Do not retain its snapshots indefinitely across default-network switches.
        if (singleNetwork) {
            capabilities.clear()
            links.clear()
            blocked.clear()
        }
        capabilities.remove(network)
        links.remove(network)
        blocked.remove(network)
        invalidate()
    }

    @Synchronized fun lost(network: K) {
        capabilities.remove(network)
        links.remove(network)
        blocked.remove(network)
        invalidate()
    }

    // Treat the first observation as a change as well: a snapshot might already
    // have been captured before the asynchronous initial callback arrived.
    @Synchronized fun capabilities(network: K, value: C) {
        if (!capabilities.containsKey(network) || capabilities[network] != value) {
            capabilities[network] = value
            invalidate()
        }
    }

    @Synchronized fun link(network: K, value: L) {
        if (!links.containsKey(network) || links[network] != value) {
            links[network] = value
            invalidate()
        }
    }

    @Synchronized fun blocked(network: K, value: Boolean) {
        if (!blocked.containsKey(network) || blocked[network] != value) {
            blocked[network] = value
            invalidate()
        }
    }
}
