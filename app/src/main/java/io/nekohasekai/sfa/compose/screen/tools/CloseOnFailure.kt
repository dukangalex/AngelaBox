package io.nekohasekai.sfa.compose.screen.tools

import java.io.Closeable

/** Transfers ownership only if the operation succeeds. */
internal inline fun <T : Closeable, R> closeOnFailure(resource: T, operation: (T) -> R): R = try {
    operation(resource)
} catch (failure: Throwable) {
    runCatching { resource.close() }
    throw failure
}
