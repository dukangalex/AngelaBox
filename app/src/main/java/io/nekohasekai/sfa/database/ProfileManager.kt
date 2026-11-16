package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.backup.ProfileStableIds
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.chain.ChainBinding
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.utils.OverlayScripts
import io.nekohasekai.sfa.utils.ProfileMutationGate
import io.nekohasekai.sfa.utils.RemoteProfileSource
import io.nekohasekai.sfa.utils.SubscriptionInfoStore
import io.nekohasekai.sfa.utils.commitLocalProfileSnapshot
import io.nekohasekai.sfa.utils.commitPendingProfileImport
import io.nekohasekai.sfa.utils.matchesRemoteProfileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    // Screens register on Main while writes iterate on IO; a plain list can throw
    // ConcurrentModificationException.
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()
    private val dbLock = Any()
    private val profileMutationGate = ProfileMutationGate()

    @Volatile
    private var db: ProfileDatabase? = null

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    private fun database(): ProfileDatabase {
        db?.takeIf { it.isOpen }?.let { return it }
        synchronized(dbLock) {
            db?.takeIf { it.isOpen }?.let { return it }
            Application.application.getDatabasePath(Path.PROFILES_DATABASE_PATH).parentFile?.mkdirs()
            DatabaseDowngradeGuard.backupIfNewer(Application.application, Path.PROFILES_DATABASE_PATH, ProfileDatabase.VERSION)
            val built = Room
                .databaseBuilder(
                    Application.application,
                    ProfileDatabase::class.java,
                    Path.PROFILES_DATABASE_PATH,
                )
                .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3, ProfileDatabase.MIGRATION_3_4)
                .fallbackToDestructiveMigrationOnDowngrade()
                .enableMultiInstanceInvalidation()
                .build()
            db = built
            return built
        }
    }

    suspend fun nextOrder(): Long = database().profileDao().nextOrder() ?: 0

    suspend fun nextFileID(): Long = database().profileDao().nextFileID() ?: 1

    // Recovery can be requested by a synchronous binding reader on Main. Only
    // this raw DAO lookup hops to IO; it must never acquire the storage monitor.
    internal fun pathForBindingRecovery(id: Long): String? = runBlocking(Dispatchers.IO) {
        database().profileDao().get(id)?.typed?.path
    }

    suspend fun get(id: Long): Profile? = ProfileBindingStorage.access { database().profileDao().get(id) }

    suspend fun create(profile: Profile, andSelect: Boolean = false, pendingInitialImport: Boolean = false): Profile {
        profile.id = ProfileBindingStorage.mutate { database().insertProfile(profile, pendingInitialImport) }
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        for (callback in callbacks.toList()) {
            callback()
        }
        return profile
    }

    suspend fun update(profile: Profile): Int {
        try {
            return profileMutationGate.withLock(profile.id) {
                ProfileBindingStorage.mutate { database().updateProfile(profile) }
            }
        } finally {
            notifyChanged()
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return profileMutationGate.withLock(0L) {
                ProfileBindingStorage.mutate { database().profileDao().update(profiles) }
            }
        } finally {
            notifyChanged()
        }
    }

    /** Holds the same lock used by edit/delete while a subscription update validates and commits. */
    suspend fun commitPendingImport(
        expected: Profile,
        isStopped: () -> Boolean,
        requireAutoUpdate: Boolean = false,
        requireInitialIntent: Boolean = false,
        commit: suspend (Profile) -> Boolean,
    ): Boolean = commitPendingProfileImport(
        gate = profileMutationGate,
        profileId = expected.id,
        isStopped = isStopped,
        loadCurrent = {
            ProfileBindingStorage.access {
                val currentDatabase = database()
                if (requireInitialIntent && expected.id !in currentDatabase.initialImportDao().pendingIds()) {
                    null
                } else {
                    currentDatabase.profileDao().get(expected.id)
                }
            }
        },
        matchesPending = { current ->
            current.typed.type == TypedProfile.Type.Remote &&
                (!requireAutoUpdate || current.typed.autoUpdate) &&
                current.typed.remoteURL == expected.typed.remoteURL &&
                current.typed.path == expected.typed.path
        },
        commit = commit,
    )

    data class RemoteContentCommit(val changed: Boolean, val updatedAt: Date)

    /** Commits a validated manual download only if its source still identifies the current profile. */
    suspend fun commitRemoteContent(
        profileId: Long,
        expectedUrl: String,
        expectedPath: String,
        content: String,
        userInfo: String?,
        isStopped: () -> Boolean,
    ): RemoteContentCommit? {
        var result: RemoteContentCommit? = null
        val applied = commitPendingProfileImport(
            gate = profileMutationGate,
            profileId = profileId,
            isStopped = isStopped,
            loadCurrent = { get(profileId) },
            matchesPending = { current ->
                matchesRemoteProfileSource(
                    current.typed.type == TypedProfile.Type.Remote,
                    RemoteProfileSource(current.typed.remoteURL, current.typed.path),
                    RemoteProfileSource(expectedUrl, expectedPath),
                )
            },
        ) { current ->
            val file = File(current.typed.path)
            val changed = !file.exists() || ProfileBindingStorage.readText(file) != content
            if (changed) ProfileBindingStorage.writeAtomically(file, content)
            val updatedAt = Date()
            current.typed.lastUpdated = updatedAt
            if (updateWhileLocked(current, materialized = true) == 0) {
                false
            } else {
                userInfo?.let { SubscriptionInfoStore.put(Application.application, current.id, it) }
                result = RemoteContentCommit(changed, updatedAt)
                true
            }
        }
        return if (applied) result else null
    }

    /** Applies a local edit only if the profile and file still match the snapshot edited by the caller. */
    suspend fun commitLocalContent(
        profileId: Long,
        expectedPath: String,
        expectedContent: String,
        content: String,
        validateRelated: suspend () -> Boolean = { true },
    ): Boolean = commitLocalProfileSnapshot(
        gate = profileMutationGate,
        profileId = profileId,
        loadCurrent = { get(profileId) },
        matches = { it.typed.path == expectedPath },
        fileFor = { File(it.typed.path) },
        expectedContent = expectedContent,
        content = content,
        validateRelated = validateRelated,
        afterCommit = {},
        commitFile = ProfileBindingStorage::writeIfUnchanged,
    )

    /** The only API allowed to change a profile file and its chain binding together. */
    suspend fun commitChainContent(
        profileId: Long,
        expectedPath: String,
        expectedContent: String,
        content: String,
        binding: ChainBinding?,
        validateRelated: suspend () -> Boolean = { true },
    ): Boolean = profileMutationGate.withLock(profileId) {
        val current = get(profileId) ?: return@withLock false
        if (current.typed.path != expectedPath || !validateRelated()) return@withLock false
        ProfileBindingStorage.commit(profileId, File(expectedPath), expectedContent, content, binding)
    }

    /** Caller must already hold the profile mutation lock. */
    suspend fun updateWhileLocked(profile: Profile, materialized: Boolean = false): Int {
        val updated = ProfileBindingStorage.mutate {
            database().updateProfile(profile, materialized)
        }
        notifyChanged()
        return updated
    }

    private fun notifyChanged() {
        for (callback in callbacks.toList()) {
            callback()
        }
    }

    /**
     * How many chain bindings the most recent delete() marked invalidated
     * because their landing profile was deleted (D3). The bindings are
     * kept and fail closed at startup; this count lets the delete flow
     * surface the impact. UI surfacing reads this after delete returns.
     */
    @Volatile
    var lastDeleteChainInvalidated: Int = 0
        private set

    suspend fun delete(profile: Profile): Int {
        try {
            return profileMutationGate.withLock(profile.id) {
                ProfileBindingStorage.mutate { deleteWhileLocked(profile) }
            }
        } finally {
            notifyChanged()
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            return profileMutationGate.withLock(0L) {
                ProfileBindingStorage.mutate {
                    var deleted = 0
                    var invalidated = 0
                    for (profile in profiles.distinctBy { it.id }) {
                        runCatching { UpdateProfileWork.cancelInitialImport(profile.id) }
                        invalidated += ChainBindings.onProfileDeleted(profile.id)
                        runCatching { OverlayScripts.removeProfile(profile.id) }
                        runCatching { ProfileStableIds.remove(profile.id) }
                        deleted += ProfileBindingStorage.mutate { database().profileDao().delete(profile) }
                    }
                    lastDeleteChainInvalidated = invalidated
                    deleted
                }
            }
        } finally {
            notifyChanged()
        }
    }

    private fun deleteWhileLocked(profile: Profile): Int {
        runCatching { UpdateProfileWork.cancelInitialImport(profile.id) }
        lastDeleteChainInvalidated =
            ChainBindings.onProfileDeleted(profile.id)
        runCatching { OverlayScripts.removeProfile(profile.id) }
        runCatching { ProfileStableIds.remove(profile.id) }
        return database().profileDao().delete(profile)
    }

    suspend fun list(): List<Profile> = ProfileBindingStorage.access { database().profileDao().list() }

    suspend fun pendingInitialImportIds(): List<Long> = ProfileBindingStorage.access { database().initialImportDao().pendingIds() }

    fun remoteServerDao(): RemoteServer.Dao = database().remoteServerDao()

    fun closeDatabase() {
        synchronized(dbLock) {
            runCatching { db?.close() }
            db = null
        }
    }
}
