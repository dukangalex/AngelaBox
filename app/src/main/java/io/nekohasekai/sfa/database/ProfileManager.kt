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

    suspend fun nextOrder(): Long = database().profileDao().nextOrder() ?: 0

    suspend fun nextFileID(): Long = database().profileDao().nextFileID() ?: 1

    suspend fun get(id: Long): Profile? = database().profileDao().get(id)

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        profile.id = database().profileDao().insert(profile)
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
            return database().profileDao().update(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return database().profileDao().update(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profile: Profile): Int {
        try {
            runCatching { ChainBindings.removeProfile(profile.id) }
            runCatching { OverlayScripts.removeProfile(profile.id) }
            runCatching { ProfileStableIds.remove(profile.id) }
            return database().profileDao().delete(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            profiles.forEach { p ->
                runCatching { ChainBindings.removeProfile(p.id) }
                runCatching { OverlayScripts.removeProfile(p.id) }
                runCatching { ProfileStableIds.remove(p.id) }
            }
            return database().profileDao().delete(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun list(): List<Profile> = database().profileDao().list()

    fun remoteServerDao(): RemoteServer.Dao = database().remoteServerDao()

    fun closeDatabase() {
        synchronized(dbLock) {
            runCatching { db?.close() }
            db = null
        }
    }
}
