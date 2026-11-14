package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.backup.ProfileStableIds
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.utils.OverlayScripts
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    // Screens register on Main while writes iterate on IO; a plain list can throw
    // ConcurrentModificationException.
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()
    private val dbLock = Any()

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
                .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
                .fallbackToDestructiveMigrationOnDowngrade()
                .enableMultiInstanceInvalidation()
                .build()
            db = built
            return built
        }
    }

    suspend fun nextOrder(): Long = guardedProfileDao.nextOrder() ?: 0

    suspend fun nextFileID(): Long = guardedProfileDao.nextFileID() ?: 1

    suspend fun get(id: Long): Profile? = guardedProfileDao.get(id)

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile =
        io.nekohasekai.sfa.utils.ApplicationDataAccess.operation { createAdmitted(profile, andSelect) }

    private suspend fun createAdmitted(profile: Profile, andSelect: Boolean): Profile {
        profile.id = guardedProfileDao.insert(profile)
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        for (callback in callbacks.toList()) {
            callback()
        }
        return profile
    }

    suspend fun update(profile: Profile): Int =
        io.nekohasekai.sfa.utils.ApplicationDataAccess.operation { updateAdmitted(profile) }

    private suspend fun updateAdmitted(profile: Profile): Int {
        try {
            return guardedProfileDao.update(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun update(profiles: List<Profile>): Int =
        io.nekohasekai.sfa.utils.ApplicationDataAccess.operation { updateAdmitted(profiles) }

    private suspend fun updateAdmitted(profiles: List<Profile>): Int {
        try {
            return guardedProfileDao.update(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
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

    suspend fun delete(profile: Profile): Int =
        io.nekohasekai.sfa.utils.ApplicationDataAccess.operation { deleteAdmitted(profile) }

    private suspend fun deleteAdmitted(profile: Profile): Int {
        try {
            lastDeleteChainInvalidated =
                runCatching { ChainBindings.onProfileDeleted(profile.id) }.getOrDefault(0)
            runCatching { OverlayScripts.removeProfile(profile.id) }
            runCatching { ProfileStableIds.remove(profile.id) }
            return guardedProfileDao.delete(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profiles: List<Profile>): Int =
        io.nekohasekai.sfa.utils.ApplicationDataAccess.operation { deleteAdmitted(profiles) }

    private suspend fun deleteAdmitted(profiles: List<Profile>): Int {
        try {
            var invalidated = 0
            profiles.forEach { p ->
                invalidated += runCatching { ChainBindings.onProfileDeleted(p.id) }.getOrDefault(0)
                runCatching { OverlayScripts.removeProfile(p.id) }
                runCatching { ProfileStableIds.remove(p.id) }
            }
            lastDeleteChainInvalidated = invalidated
            return guardedProfileDao.delete(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun list(): List<Profile> = guardedProfileDao.list()

    private val guardedProfileDao = object : Profile.Dao {
        private fun <T> access(block: (Profile.Dao) -> T): T =
            io.nekohasekai.sfa.utils.ApplicationDataAccess.access { block(database().profileDao()) }
        override fun insert(profile: Profile) = access { it.insert(profile) }
        override fun update(profile: Profile) = access { it.update(profile) }
        override fun update(profile: List<Profile>) = access { it.update(profile) }
        override fun delete(profile: Profile) = access { it.delete(profile) }
        override fun delete(profile: List<Profile>) = access { it.delete(profile) }
        override fun get(profileId: Long) = access { it.get(profileId) }
        override fun list() = access { it.list() }
        override fun clear() = access { it.clear() }
        override fun nextOrder() = access { it.nextOrder() }
        override fun nextFileID() = access { it.nextFileID() }
    }

    private val guardedRemoteDao = object : RemoteServer.Dao {
        private fun <T> access(block: (RemoteServer.Dao) -> T): T =
            io.nekohasekai.sfa.utils.ApplicationDataAccess.access { block(database().remoteServerDao()) }
        override fun insert(server: RemoteServer) = access { it.insert(server) }
        override fun update(server: RemoteServer) = access { it.update(server) }
        override fun update(servers: List<RemoteServer>) = access { it.update(servers) }
        override fun delete(server: RemoteServer) = access { it.delete(server) }
        override fun get(serverId: Long) = access { it.get(serverId) }
        override fun list() = access { it.list() }
        override fun nextOrder() = access { it.nextOrder() }
    }

    fun remoteServerDao(): RemoteServer.Dao = guardedRemoteDao

    fun closeDatabase() {
        synchronized(dbLock) {
            // A failed close must abort restore before any database file is replaced.
            // Keep the reference if close throws; callers can retry or surface the error.
            db?.close()
            db = null
        }
    }
}
