package io.nekohasekai.sfa.utils

/** Serializes session transitions with delivery of callbacks for the current session. */
internal class CommandEpochGate {
    val lock = Any()
    private var epoch = 0

    fun advance(): Int = synchronized(lock) { ++epoch }

    fun isCurrent(token: Int): Boolean = synchronized(lock) { token == epoch }

    fun runIfCurrent(token: Int, block: () -> Unit): Boolean = synchronized(lock) {
        if (token != epoch) return@synchronized false
        block()
        true
    }
}
