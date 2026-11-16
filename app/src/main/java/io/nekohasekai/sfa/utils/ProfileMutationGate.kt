package io.nekohasekai.sfa.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes profile writes with pending subscription-import commits. */
internal class ProfileMutationGate {
    private val lock = Mutex()

    @Suppress("UNUSED_PARAMETER")
    suspend fun <T> withLock(profileId: Long, action: suspend () -> T): T = lock.withLock { action() }
}

/** Checks cancellation and the current profile under the same lock used by edit/delete writes. */
internal suspend fun <P> commitPendingProfileImport(
    gate: ProfileMutationGate,
    profileId: Long,
    isStopped: () -> Boolean,
    loadCurrent: suspend () -> P?,
    matchesPending: (P) -> Boolean,
    commit: suspend (P) -> Boolean,
): Boolean = gate.withLock(profileId) {
    if (isStopped()) return@withLock false
    val current = loadCurrent() ?: return@withLock false
    if (!matchesPending(current)) return@withLock false
    commit(current)
}

/** Immutable download-source identity used again while holding the mutation gate. */
internal data class RemoteProfileSource(val url: String, val path: String)

internal fun matchesRemoteProfileSource(isRemote: Boolean, current: RemoteProfileSource, expected: RemoteProfileSource): Boolean = isRemote && current == expected

/** Serializes snapshot validation, file replacement and the callback against profile mutations.
 * The file and callback persistence are not a single crash-atomic transaction.
 */
internal suspend fun <P> commitLocalProfileSnapshot(
    gate: ProfileMutationGate,
    profileId: Long,
    loadCurrent: suspend () -> P?,
    matches: (P) -> Boolean,
    fileFor: (P) -> java.io.File,
    expectedContent: String,
    content: String,
    validateRelated: suspend () -> Boolean,
    afterCommit: () -> Unit,
    commitFile: (java.io.File, String, String) -> Boolean = ProfileFiles::writeIfUnchanged,
): Boolean = commitPendingProfileImport(gate, profileId, { false }, loadCurrent, matches) { current ->
    if (!validateRelated() || !commitFile(fileFor(current), expectedContent, content)) {
        false
    } else {
        afterCommit()
        true
    }
}
