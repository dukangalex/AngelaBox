package io.nekohasekai.sfa.chain

/** Main-thread generation for screen loads; old IO completions must not replace a later choice. */
internal class LatestChainLoad {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun isCurrent(ticket: Long): Boolean = ticket == generation
}
