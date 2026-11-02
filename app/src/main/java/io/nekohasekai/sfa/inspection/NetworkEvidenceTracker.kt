package io.nekohasekai.sfa.inspection

// Experimental only: FOREGROUND (platform bit 19) indicates app availability versus a
// background-held network. Ignoring it requires validating that no relevant availability
// change is missed. Other capability bits remain conservative and invalidate on change.
internal fun evidenceCapabilityBits(bits: Long): Long = bits and (1L shl 19).inv()

/** One tracker per callback: default and underlying networks have independent lifecycles. */
internal class NetworkEvidenceTracker<K, C, L>(private val invalidate: () -> Unit) {
    private val capabilities = mutableMapOf<K, C>()
    private val links = mutableMapOf<K, L>()
    private val blocked = mutableMapOf<K, Boolean>()

    @Synchronized fun available(network: K) {
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
