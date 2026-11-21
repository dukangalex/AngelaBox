package io.nekohasekai.sfa.compose.screen.connections

/** Serializes session transitions against old cleanup, event and publication work. */
internal class ConnectionSessionStore<T : Any> {
    private val lock = Any()
    private var generation = 0L
    private var connected = false
    private var value: T? = null
    private var valueGeneration = -1L

    fun current(): Long = synchronized(lock) { generation }

    fun open(): Long = synchronized(lock) {
        connected = true
        ++generation
    }

    fun close(): Long = synchronized(lock) {
        connected = false
        ++generation
    }

    fun clear(token: Long): Boolean = synchronized(lock) {
        if (token != generation) return@synchronized false
        value = null
        valueGeneration = token
        true
    }

    fun <R> update(token: Long, create: () -> T, transform: (T) -> R): R? = synchronized(lock) {
        if (token != generation || !connected) return@synchronized null
        if (valueGeneration != token) {
            value = null
            valueGeneration = token
        }
        val store = value ?: create().also { value = it }
        transform(store)
    }

    fun <R> read(token: Long, transform: (T) -> R): R? = synchronized(lock) {
        if (token != generation || !connected || valueGeneration != token) return@synchronized null
        val store = value ?: return@synchronized null
        transform(store)
    }

    fun ifCurrent(token: Long, block: () -> Unit) = synchronized(lock) {
        if (token == generation) block()
    }
}
