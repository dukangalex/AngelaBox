package io.nekohasekai.sfa.utils

/** A remote blob may carry a URL without a usable cached configuration. */
internal fun isUnmaterializedRemoteConfig(isRemote: Boolean, content: String): Boolean = isRemote && (content.isBlank() || content.filterNot(Char::isWhitespace) == "{}")

/** Keep placeholder imports unselected and let the normal guarded worker materialize them.
 * Scheduling starts only after create has assigned a durable profile identity. A ready
 * cached configuration keeps the existing import/selection behavior.
 */
internal suspend fun persistImportedProfile(
    pendingRemote: Boolean,
    selectWhenReady: Boolean,
    writeConfig: suspend () -> Unit,
    create: suspend (Boolean) -> Unit,
    enqueueInitial: () -> Unit,
    updateSchedule: suspend () -> Unit,
) {
    if (!pendingRemote) writeConfig()
    create(!pendingRemote && selectWhenReady)
    if (pendingRemote) enqueueInitial()
    updateSchedule()
}
