package io.nekohasekai.sfa.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Keeps persisted remote selections ordered even when IO jobs start out of order. */
internal class LatestRemoteSelection {
    private val generation = AtomicLong(0)
    private val writeMutex = Mutex()

    fun current(): Long = generation.get()

    fun changed(): Long = generation.incrementAndGet()

    suspend fun writeIfCurrent(token: Long, write: suspend () -> Unit): Boolean = writeMutex.withLock {
        if (token != generation.get()) return@withLock false
        write()
        true
    }
}
