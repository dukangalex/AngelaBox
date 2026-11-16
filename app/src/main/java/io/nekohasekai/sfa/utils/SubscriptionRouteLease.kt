package io.nekohasekai.sfa.utils

import java.io.Closeable
import java.net.HttpURLConnection

/** A subscription attempt cannot outlive the service generation that chose its route. */
internal class SubscriptionRouteLease : Closeable {
    val snapshot = TunnelGate.snapshot()
    private val connections = mutableSetOf<HttpURLConnection>()
    private var closed = false
    private val observer = TunnelGate.observe(snapshot) { invalidate() }

    @Synchronized
    fun check() {
        check(!closed) { "VPN 状态已变化，订阅更新已停止，请重试" }
        TunnelGate.requireCurrent(snapshot)
    }

    @Synchronized
    fun track(connection: HttpURLConnection) {
        try {
            check()
            connections.add(connection)
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    @Synchronized
    fun untrack(connection: HttpURLConnection) {
        connections.remove(connection)
    }

    private fun invalidate() {
        val pending = synchronized(this) {
            closed = true
            connections.toList().also { connections.clear() }
        }
        pending.forEach { runCatching { it.disconnect() } }
    }

    /** Reject bytes returned by a read that overlapped a service transition. */
    fun checkedStream(input: java.io.InputStream): java.io.InputStream = object : java.io.FilterInputStream(input) {
        override fun read(): Int {
            check()
            return super.read().also { check() }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check()
            return super.read(buffer, offset, length).also { check() }
        }
    }

    override fun close() {
        observer.close()
        invalidate()
    }
}
