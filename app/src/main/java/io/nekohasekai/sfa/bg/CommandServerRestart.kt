package io.nekohasekai.sfa.bg

/** Restart only after both native service teardown and command-server close succeed. */
internal fun restartCommandServerSafely(
    closeService: () -> Unit,
    closeServer: () -> Unit,
    startServer: () -> Unit,
) {
    closeService()
    closeServer()
    startServer()
}

/** Never recreate a command server until the abandoned start and teardown have drained. */
internal fun restartCommandServerAfterDrain(
    canRestart: () -> Boolean,
    closeServer: () -> Unit,
    startServer: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    if (!canRestart()) return
    try {
        closeServer()
    } catch (failure: Throwable) {
        onFailure(failure)
        return
    }
    if (canRestart()) startServer()
}
