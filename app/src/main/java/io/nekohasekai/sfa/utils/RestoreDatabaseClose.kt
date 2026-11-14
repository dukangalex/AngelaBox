package io.nekohasekai.sfa.utils

/** Abort replacement if either database close fails; callers must propagate the failure. */
internal fun closeDatabasesBeforeRestore(closeSettings: () -> Unit, closeProfiles: () -> Unit) {
    closeSettings()
    closeProfiles()
}
