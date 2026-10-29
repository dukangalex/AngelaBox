package io.nekohasekai.sfa.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.backup.PortableCloudBackup
import io.nekohasekai.sfa.backup.ProfileStableIds
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.RemoteServer
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.ktx.marshall
import io.nekohasekai.sfa.ktx.unmarshall
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection

object BackupManager {
    private const val MANIFEST = PortableCloudBackup.MANIFEST
    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_SIZE = 32L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 128L * 1024 * 1024

    /** Size and path limits always abort. Compat may skip unknown entries, not these. */
    private class BackupRejected(message: String) : IllegalStateException(message)

    /** A Remote profile a restore created, carried in [RestoreReport] for disclosure. */
    data class RestoredRemoteProfile(val name: String, val url: String)

    /**
     * What a restore did that the user should see afterwards: the Remote
     * subscriptions it created (stored with auto-update off — a restore
     * never confirms them per URL, see restoreZipFile) and the sensitive
     * portable items it skipped (see [PortableCloudBackup.applyPortableSettings]).
     */
    data class RestoreReport(
        val remoteProfiles: List<RestoredRemoteProfile> = emptyList(),
        val skippedScriptNames: List<String> = emptyList(),
        val skippedChainBindingCount: Int = 0,
        val skippedScriptBindingCount: Int = 0,
    )

    /**
     * Builds a backup archive at [dest]. With a non-empty [password] the
     * finished zip is sealed with [BackupCrypto] (AB-021): the zip is
     * written to a cache-dir scratch file first, encrypted as one byte
     * stream, and the scratch plaintext is deleted. The password itself
     * is never stored or logged. Without a password the output is the
     * plain zip, byte-for-byte the pre-encryption behaviour.
     */
    fun createBackupFile(
        context: Context,
        dest: File,
        password: CharArray? = null,
    ): Result<File> = runCatching {
        if (password == null || password.isEmpty()) {
            writeBackupZip(context, dest)
            return@runCatching dest
        }
        val plainTmp = File(context.cacheDir, "chainbox-backup-plain.zip")
        try {
            writeBackupZip(context, plainTmp)
            val sealed = BackupCrypto.encrypt(plainTmp.readBytes(), password)
            dest.parentFile?.mkdirs()
            dest.writeBytes(sealed)
        } finally {
            plainTmp.delete()
        }
        dest
    }

    private fun writeBackupZip(context: Context, dest: File) {
        dest.parentFile?.mkdirs()
        // Checkpoint first so the temp copies below observe the latest committed
        // state. Credentials are blanked in the temp copies only: live Settings
        // are never mutated, so a process killed mid-backup can no longer lose
        // the user's WebDAV password / GitHub token (no sleep window, and no
        // finally-restore that a kill could skip).
        checkpoint(context, Path.SETTINGS_DATABASE_PATH)
        checkpoint(context, Path.PROFILES_DATABASE_PATH)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
            val profiles = runBlocking { ProfileManager.list() }
            val archive = PortableCloudBackup.buildFromAndroid(
                profiles = profiles,
                selectedProfileId = Settings.selectedProfile,
            )
            putEntry(
                zos,
                MANIFEST,
                PortableCloudBackup.manifest(archive.writtenBy, archive.time).toString().toByteArray(),
            )
            copySettingsDbRedacted(zos, context)
            copyProfilesDbRedacted(zos, context)
            val configs = File(context.filesDir, "configs")
            if (configs.isDirectory) {
                configs.listFiles()?.forEach { f ->
                    if (f.isFile) putFile(zos, "configs/${f.name}", f)
                }
            }
            putEntry(
                zos,
                PortableCloudBackup.PROFILES,
                PortableCloudBackup.encodeProfiles(archive.selected, archive.profiles).toByteArray(),
            )
            putEntry(zos, PortableCloudBackup.SETTINGS, archive.settings.toString().toByteArray())
            archive.configs.forEach { (name, bytes) ->
                putEntry(zos, name, bytes)
            }
        }
    }

    /** True when [file] is a password-encrypted backup container (AB-021). */
    fun isEncryptedBackup(file: File): Boolean {
        if (!file.isFile || file.length() < 5L) return false
        return FileInputStream(file).use { ins ->
            val head = ByteArray(16)
            val read = ins.read(head)
            read >= 5 && BackupCrypto.isEncrypted(head.copyOf(read))
        }
    }

    /**
     * Restores a backup file. Encrypted containers (see [isEncryptedBackup])
     * are recognised by content, never by file name, and decrypted with
     * [password] before the regular zip pipeline runs on the plaintext —
     * a wrong password or damaged file fails cleanly before anything is
     * touched. Plain zips from older versions restore exactly as before.
     * [includeSensitiveItems] opts in to the portable backup's sensitive
     * items (overlay scripts / chain bindings); see
     * [PortableCloudBackup.applyPortableSettings]. The returned
     * [RestoreReport] lists what the restore created or skipped so the
     * caller can disclose it.
     */
    fun restoreBackupFile(
        context: Context,
        src: File,
        compat: Boolean = false,
        password: CharArray? = null,
        includeSensitiveItems: Boolean = false,
    ): Result<RestoreReport> {
        if (!isEncryptedBackup(src)) return restoreZipFile(context, src, compat, includeSensitiveItems)
        if (password == null || password.isEmpty()) {
            return Result.failure(
                IllegalStateException(context.getString(R.string.backup_password_required)),
            )
        }
        val plainTmp = File(context.cacheDir, "chainbox-restore-plain.zip")
        return try {
            val plain = try {
                BackupCrypto.decrypt(src.readBytes(), password)
            } catch (e: BackupCrypto.BackupCryptoException) {
                return Result.failure(
                    IllegalStateException(context.getString(R.string.backup_decrypt_failed)),
                )
            }
            plainTmp.writeBytes(plain)
            restoreZipFile(context, plainTmp, compat, includeSensitiveItems)
        } finally {
            plainTmp.delete()
        }
    }

    private fun restoreZipFile(
        context: Context,
        src: File,
        compat: Boolean = false,
        includeSensitiveItems: Boolean = false,
    ): Result<RestoreReport> = runCatching {
        if (!isZipFile(src)) {
            if (!compat) error("不是有效的 ZIP 备份（可能下到了网页错误页）。请重新备份后再恢复。")
        }
        val keepDav = Settings.webdavPassword
        val keepTok = Settings.githubToken
        // M3 snapshot: the pre-restore WebDAV endpoint is the only one the
        // preserved password may ever be paired with (see the repair at the
        // end of the overwrite path).
        val keepDavUrl = Settings.webdavUrl
        val keepDavUser = Settings.webdavUser
        val staging = File(context.cacheDir, "restore-staging").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        var entries = 0
        var total = 0L
        var hasData = false
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (++entries > MAX_ENTRIES) {
                        throw BackupRejected("备份包含过多文件")
                    }
                    val name = entry.name.trimStart('/')
                    if (!entry.isDirectory && name.isNotEmpty() && !name.contains("..") && !name.contains('\\')) {
                        val outFile = when {
                            name == MANIFEST ||
                                name == PortableCloudBackup.PROFILES ||
                                name == PortableCloudBackup.SETTINGS -> File(staging, name)
                            name.startsWith("configs/") -> {
                                val relative = name.removePrefix("configs/")
                                if (relative.isBlank() || relative.contains('/')) {
                                    throw BackupRejected("非法备份路径")
                                } else {
                                    File(staging, "configs").also { it.mkdirs() }.let { File(it, relative) }
                                }
                            }
                            name.endsWith(".db") && !name.contains('/') -> File(staging, name)
                            else -> null
                        }
                        if (outFile != null) {
                            if (name.endsWith(".db") || name.startsWith("configs/") ||
                                name == PortableCloudBackup.PROFILES
                            ) {
                                hasData = true
                            }
                            outFile.parentFile?.mkdirs()
                            var entryBytes = 0L
                            FileOutputStream(outFile).use { fos ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = zis.read(buffer)
                                    if (read < 0) break
                                    entryBytes += read
                                    total += read
                                    if (entryBytes > MAX_ENTRY_SIZE || total > MAX_TOTAL_SIZE) {
                                        throw BackupRejected("备份展开大小超过限制")
                                    }
                                    fos.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: BackupRejected) {
            staging.deleteRecursively()
            throw e
        } catch (e: Exception) {
            if (!compat) throw e
        }
        if (!hasData) error("备份里没有配置或数据库，无法恢复")

        val portableProfiles = File(staging, PortableCloudBackup.PROFILES)
        val portableSettings = File(staging, PortableCloudBackup.SETTINGS)
        val hasPortable = portableProfiles.isFile

        if (compat) {
            val restoredRemote = if (hasPortable) {
                mergePortableProfiles(
                    context,
                    portableProfiles.readText(),
                    File(staging, "configs"),
                )
            } else {
                mergeProfilesFromBackup(
                    context,
                    File(staging, Path.PROFILES_DATABASE_PATH),
                    File(staging, "configs"),
                )
            }
            staging.deleteRecursively()
            return@runCatching RestoreReport(remoteProfiles = restoredRemote)
        }

        runCatching { BoxService.stop() }
        Thread.sleep(350)
        Settings.closeDatabase()
        ProfileManager.closeDatabase()

        val settingsDb = File(staging, Path.SETTINGS_DATABASE_PATH)
        val profilesDb = File(staging, Path.PROFILES_DATABASE_PATH)
        val settingsDbRestored = settingsDb.isFile
        val profilesDbRestored = profilesDb.isFile
        if (settingsDbRestored) {
            val dest = context.getDatabasePath(Path.SETTINGS_DATABASE_PATH)
            dest.parentFile?.mkdirs()
            deleteSidecars(dest)
            settingsDb.copyTo(dest, overwrite = true)
            deleteSidecars(dest)
        }
        if (profilesDbRestored) {
            val dest = context.getDatabasePath(Path.PROFILES_DATABASE_PATH)
            dest.parentFile?.mkdirs()
            deleteSidecars(dest)
            profilesDb.copyTo(dest, overwrite = true)
            deleteSidecars(dest)
            pinProfilePathsToConfigs(dest, File(context.filesDir, "configs"))
            repairRestoredRemoteServers(dest)
        }
        val stagedConfigs = File(staging, "configs")
        val live = File(context.filesDir, "configs").also { it.mkdirs() }
        if (stagedConfigs.isDirectory) {
            val incoming = stagedConfigs.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()
            live.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in incoming) f.delete()
            }
            stagedConfigs.listFiles()?.forEach { f ->
                if (f.isFile) runCatching { f.copyTo(File(live, f.name), overwrite = true) }
            }
        }

        val liveSettings = context.getDatabasePath(Path.SETTINGS_DATABASE_PATH)
        if (keepDav.isNotEmpty()) putSettingString(liveSettings, SettingsKey.WEBDAV_PASSWORD, keepDav)
        if (keepTok.isNotEmpty()) putSettingString(liveSettings, SettingsKey.GITHUB_TOKEN, keepTok)

        var portableOutcome: PortableRestoreOutcome? = null
        if (hasPortable) {
            if (!profilesDbRestored) {
                portableOutcome = restorePortableOverwrite(
                    context,
                    portableProfiles.readText(),
                    if (portableSettings.isFile) portableSettings.readText() else "{}",
                    File(staging, "configs"),
                    includeSensitiveItems,
                )
            } else {
                runCatching {
                    val (selected, profiles) = PortableCloudBackup.parseProfiles(portableProfiles.readText())
                    val existing = runBlocking { ProfileManager.list() }
                    val uuidToLocal = linkedMapOf<String, Long>()
                    profiles.forEach { portable ->
                        val local = existing.firstOrNull { it.name.trim() == portable.name.trim() }
                            ?: existing.firstOrNull {
                                portable.remoteUrl.isNotEmpty() &&
                                    it.typed.remoteURL.trim() == portable.remoteUrl
                            }
                        if (local != null) uuidToLocal[portable.id] = local.id
                    }
                    PortableCloudBackup.importStableIds(profiles, uuidToLocal)
                    if (!selected.isNullOrBlank()) {
                        ProfileStableIds.localId(selected)?.let { Settings.selectedProfile = it }
                    }
                }
            }
        }
        if (keepDav.isNotEmpty()) Settings.webdavPassword = keepDav
        if (keepTok.isNotEmpty()) Settings.githubToken = keepTok
        // M3 (same rule the portable path applies in applyPortableSettings):
        // a backup carries no WebDAV password by design, so the URL/user its
        // settings.db restored must never be paired with the password
        // preserved above — the first probe would send that password to the
        // backup's server as Basic Auth. Keep the pre-restore endpoint
        // whenever the backup's differs. Gated on the DB actually having
        // been replaced: without one, the endpoint is either untouched or
        // came through the portable path, whose M3 gate already ran.
        // (Every WebDAV use still re-validates the URL through
        // RemoteUrlGuard in requireHttps before connecting.)
        if (settingsDbRestored) {
            if (Settings.webdavUrl != keepDavUrl) Settings.webdavUrl = keepDavUrl
            if (Settings.webdavUser != keepDavUser) Settings.webdavUser = keepDavUser
        }
        // A restored activeRemoteServerId points into the backup's server
        // list, whose rows were just re-validated by
        // repairRestoredRemoteServers. Never auto-connect on the next
        // launch: the user re-selects the server in the UI.
        if (settingsDbRestored || profilesDbRestored) Settings.activeRemoteServerId = 0L

        val report = if (portableOutcome != null) {
            RestoreReport(
                remoteProfiles = portableOutcome.remoteProfiles,
                skippedScriptNames = portableOutcome.sensitive.skippedScriptNames,
                skippedChainBindingCount = portableOutcome.sensitive.skippedChainBindingCount,
                skippedScriptBindingCount = portableOutcome.sensitive.skippedScriptBindingCount,
            )
        } else {
            RestoreReport(remoteProfiles = restoredDbRemoteProfiles(profilesDbRestored))
        }
        staging.deleteRecursively()
        report
    }

    /** Remote profiles the wholesale DB restore just installed, read back for the [RestoreReport]. */
    private fun restoredDbRemoteProfiles(profilesDbRestored: Boolean): List<RestoredRemoteProfile> {
        if (!profilesDbRestored) return emptyList()
        return runCatching {
            runBlocking { ProfileManager.list() }
                .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.remoteURL.isNotBlank() }
                .map { RestoredRemoteProfile(it.name, it.typed.remoteURL) }
        }.getOrDefault(emptyList())
    }

    /**
     * A restored profiles.db carries absolute `typed.path` values. A tampered
     * backup (the WebDAV server holds it) could point them at settings.db or
     * any other app file, which subscription updates and the editor then
     * overwrite. Keep only the file name and re-anchor it under configs/.
     *
     * The same pass normalises the update policy of wholesale-restored
     * profiles: a Remote profile installed this way was never confirmed
     * per URL, so it must not fetch unattended — auto-update is stored off
     * (the restore result discloses it) — and the interval gets the same
     * 15-minute floor every other import path applies.
     */
    internal fun pinProfilePathsToConfigs(profilesDb: File, liveConfigs: File) {
        if (!profilesDb.isFile) return
        runCatching {
            val root = liveConfigs.canonicalFile
            val convertor = TypedProfile.Convertor()
            SQLiteDatabase.openDatabase(profilesDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val fixes = mutableListOf<Pair<Long, ByteArray>>()
                db.rawQuery("SELECT id, typed FROM profiles", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val blob = c.getBlob(1) ?: continue
                        val typed = runCatching { convertor.unmarshall(blob) }.getOrNull() ?: continue
                        var changed = false
                        val current = runCatching { File(typed.path).canonicalFile }.getOrNull()
                        if (current == null || current.parentFile != root) {
                            val name = File(typed.path).name
                            val safe = if (name.isBlank() || name.contains("..")) "$id.json" else name
                            typed.path = File(root, safe).path
                            changed = true
                        }
                        if (typed.type == TypedProfile.Type.Remote && typed.autoUpdate) {
                            typed.autoUpdate = false
                            changed = true
                        }
                        if (typed.autoUpdateInterval < 15) {
                            typed.autoUpdateInterval = 15
                            changed = true
                        }
                        if (changed) fixes += id to convertor.marshall(typed)
                    }
                }
                fixes.forEach { (id, bytes) ->
                    val cv = ContentValues()
                    cv.put("typed", bytes)
                    db.update("profiles", cv, "id = ?", arrayOf(id.toString()))
                }
            }
        }
    }

    /**
     * A restored profiles.db can plant `remote_servers` rows the UI would
     * refuse — validateURL is only enforced on the edit screen, while the
     * launch-time RemoteControlManager.restore() connects to whatever
     * Settings.activeRemoteServerId names. Validate every restored row
     * with that same gate, drop the rows that fail, and blank secrets:
     * exports always blank them, so a secret in a backup is planted, not
     * restored data. The caller clears the active server id separately.
     */
    internal fun repairRestoredRemoteServers(profilesDb: File) {
        if (!profilesDb.isFile) return
        runCatching {
            SQLiteDatabase.openDatabase(profilesDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val hasTable = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='remote_servers'",
                    null,
                ).use { it.moveToFirst() }
                if (hasTable) {
                    val rows = mutableListOf<Pair<Long, String>>()
                    db.rawQuery("SELECT id, url FROM remote_servers", null).use { c ->
                        while (c.moveToNext()) rows += c.getLong(0) to (c.getString(1) ?: "")
                    }
                    rows.forEach { (id, url) ->
                        val valid = RemoteServer.validateURL(url)
                        if (valid == null) {
                            db.delete("remote_servers", "id = ?", arrayOf(id.toString()))
                        } else {
                            val cv = ContentValues()
                            cv.put("url", valid)
                            cv.put("secret", "")
                            db.update("remote_servers", cv, "id = ?", arrayOf(id.toString()))
                        }
                    }
                }
            }
        }
    }

    /** What [restorePortableOverwrite] produced, for the [RestoreReport]. */
    private class PortableRestoreOutcome(
        val remoteProfiles: List<RestoredRemoteProfile>,
        val sensitive: PortableCloudBackup.SensitiveItemsOutcome,
    )

    private fun restorePortableOverwrite(
        context: Context,
        profilesJson: String,
        settingsJson: String,
        backupConfigs: File,
        includeSensitiveItems: Boolean,
    ): PortableRestoreOutcome {
        val (selected, profiles) = PortableCloudBackup.parseProfiles(profilesJson)
        val liveConfigs = File(context.filesDir, "configs").also { it.mkdirs() }
        var sensitive = PortableCloudBackup.SensitiveItemsOutcome()
        val remoteProfiles = mutableListOf<RestoredRemoteProfile>()
        runBlocking {
            ProfileManager.list().toList().forEach { existing ->
                runCatching { ProfileManager.delete(existing) }
            }
            val uuidToLocal = linkedMapOf<String, Long>()
            profiles.forEach { portable ->
                val created = importPortableProfile(liveConfigs, backupConfigs, portable) ?: return@forEach
                uuidToLocal[portable.id] = created.id
                if (created.typed.type == TypedProfile.Type.Remote && created.typed.remoteURL.isNotBlank()) {
                    remoteProfiles += RestoredRemoteProfile(created.name, created.typed.remoteURL)
                }
            }
            PortableCloudBackup.importStableIds(profiles, uuidToLocal)
            sensitive = PortableCloudBackup.applyPortableSettings(settingsJson, uuidToLocal, includeSensitiveItems)
            val selectedLocal = selected?.let { ProfileStableIds.localId(it) }
            if (selectedLocal != null) {
                Settings.selectedProfile = selectedLocal
            } else if (uuidToLocal.isNotEmpty()) {
                Settings.selectedProfile = uuidToLocal.values.first()
            }
        }
        return PortableRestoreOutcome(remoteProfiles, sensitive)
    }

    private fun mergePortableProfiles(
        context: Context,
        profilesJson: String,
        backupConfigs: File,
    ): List<RestoredRemoteProfile> {
        val (_, profiles) = PortableCloudBackup.parseProfiles(profilesJson)
        val liveConfigs = File(context.filesDir, "configs").also { it.mkdirs() }
        val remoteProfiles = mutableListOf<RestoredRemoteProfile>()
        runBlocking {
            val existing = ProfileManager.list()
            val names = existing.map { it.name.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            val urls = existing.map { it.typed.remoteURL.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            profiles.forEach { portable ->
                val name = portable.name.trim()
                if (name.isEmpty() || name in names) return@forEach
                val url = portable.remoteUrl.trim()
                if (url.isNotEmpty() && url in urls) return@forEach
                val created = importPortableProfile(liveConfigs, backupConfigs, portable) ?: return@forEach
                ProfileStableIds.put(created.id, portable.id)
                names.add(name)
                if (url.isNotEmpty()) urls.add(url)
                if (created.typed.type == TypedProfile.Type.Remote && created.typed.remoteURL.isNotBlank()) {
                    remoteProfiles += RestoredRemoteProfile(created.name, created.typed.remoteURL)
                }
            }
        }
        return remoteProfiles
    }

    private suspend fun importPortableProfile(
        liveConfigs: File,
        backupConfigs: File,
        portable: PortableCloudBackup.PortableProfile,
    ): Profile? {
        val rawUrl = portable.remoteUrl.trim()
        // A redacted subscription URL carries no credential material by design;
        // leave remoteURL empty so the user re-enters it after restore.
        val url = if (rawUrl.endsWith(PortableCloudBackup.REDACTED_URL_SUFFIX)) "" else rawUrl
        if (url.isNotEmpty()) {
            try {
                RemoteUrlGuard.requireAllowed(url, RemoteUrlGuard.Kind.SUBSCRIPTION)
            } catch (_: Exception) {
                return null
            }
        }
        val srcName = File(portable.config).name
        if (srcName.isBlank() || srcName.contains("..")) return null
        val staged = File(backupConfigs, srcName)
        if (!staged.isFile) return null
        val fileId = ProfileManager.nextFileID()
        val dest = File(liveConfigs, "$fileId.json")
        val copied = runCatching { staged.copyTo(dest, overwrite = true) }.isSuccess
        if (!copied || !dest.isFile) return null
        val typed = TypedProfile().apply {
            path = dest.path
            type = if (portable.type == "remote") TypedProfile.Type.Remote else TypedProfile.Type.Local
            remoteURL = url
            lastUpdated = Date(portable.lastUpdated)
            // A restore never confirms a subscription per URL, so a profile
            // it creates must not fetch unattended: auto-update is stored
            // off (the restore result discloses the profile) and turned
            // back on by the user if wanted. The interval keeps the
            // 15-minute floor every import path applies.
            autoUpdate = false
            autoUpdateInterval = portable.autoUpdateIntervalMinutes.coerceAtLeast(15)
        }
        return ProfileManager.create(
            Profile(name = portable.name.trim(), icon = portable.icon, typed = typed),
            andSelect = false,
        )
    }

    /**
     * 兼容模式：把备份里的配置追加到当前列表。同名或同一远程 URL 的现有配置保留，
     * 不替换 settings / 不覆盖已有 JSON。
     */
    internal fun mergeProfilesFromBackup(
        context: Context,
        backupProfilesDb: File,
        backupConfigs: File,
    ): List<RestoredRemoteProfile> {
        if (!backupProfilesDb.isFile) return emptyList()
        val remoteProfiles = mutableListOf<RestoredRemoteProfile>()
        val liveConfigs = File(context.filesDir, "configs").also { it.mkdirs() }
        val convertor = TypedProfile.Convertor()
        SQLiteDatabase.openDatabase(backupProfilesDb.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val cursor = try {
                db.rawQuery("SELECT name, icon, typed FROM profiles", null)
            } catch (_: Exception) {
                db.rawQuery("SELECT name, typed FROM profiles", null)
            }
            cursor.use { c ->
                val nameIdx = c.getColumnIndex("name")
                val iconIdx = c.getColumnIndex("icon")
                val typedIdx = c.getColumnIndex("typed")
                if (nameIdx < 0 || typedIdx < 0) return emptyList()
                runBlocking {
                    val existing = ProfileManager.list()
                    val names = existing.map { it.name.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                    val urls = existing.map { it.typed.remoteURL.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                    while (c.moveToNext()) {
                        val name = c.getString(nameIdx)?.trim().orEmpty()
                        if (name.isEmpty() || name in names) continue
                        val typedBytes = c.getBlob(typedIdx) ?: continue
                        val typed = try {
                            convertor.unmarshall(typedBytes)
                        } catch (_: Exception) {
                            continue
                        }
                        val url = typed.remoteURL.trim()
                        if (url.isNotEmpty()) {
                            try {
                                RemoteUrlGuard.requireAllowed(url, RemoteUrlGuard.Kind.SUBSCRIPTION)
                            } catch (_: Exception) {
                                continue
                            }
                            if (url in urls) continue
                        }
                        val srcName = File(typed.path).name
                        if (srcName.isBlank() || srcName.contains("..")) continue
                        val staged = File(backupConfigs, srcName)
                        if (!staged.isFile) continue
                        val fileId = ProfileManager.nextFileID()
                        val dest = File(liveConfigs, "$fileId.json")
                        val copied = runCatching { staged.copyTo(dest, overwrite = true) }.isSuccess
                        if (!copied || !dest.isFile) continue
                        val imported = TypedProfile().apply {
                            path = dest.path
                            type = typed.type
                            remoteURL = typed.remoteURL
                            lastUpdated = typed.lastUpdated
                            // Same restore policy as importPortableProfile:
                            // never confirmed per URL, so no unattended
                            // fetching until the user opts in; interval
                            // gets the usual 15-minute floor.
                            autoUpdate = false
                            autoUpdateInterval = typed.autoUpdateInterval.coerceAtLeast(15)
                        }
                        val icon = if (iconIdx >= 0 && !c.isNull(iconIdx)) c.getString(iconIdx) else null
                        ProfileManager.create(
                            Profile(name = name, icon = icon, typed = imported),
                            andSelect = false,
                        )
                        names.add(name)
                        if (url.isNotEmpty()) urls.add(url)
                        if (typed.type == TypedProfile.Type.Remote && url.isNotEmpty()) {
                            remoteProfiles += RestoredRemoteProfile(name, url)
                        }
                    }
                }
            }
        }
        return remoteProfiles
    }

    fun webdavUpload(baseUrl: String, username: String, password: String, remoteName: String, localFile: File): Result<Unit> = runCatching {
        require(username.isNotBlank() && password.isNotBlank()) { "请填写 WebDAV 用户名和密码" }
        wrapSsl {
            val conn = openWebDav(joinUrl(baseUrl, remoteName), username, password).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Overwrite", "T")
                setFixedLengthStreamingMode(localFile.length())
            }
            try {
                FileInputStream(localFile).use { input -> conn.outputStream.use { output -> input.copyTo(output) } }
                val code = conn.responseCode
                val err = conn.errorStream?.bufferedReader()?.readText()
                if (code == 401 || code == 403) error(authFailedMessage(baseUrl, code, err))
                if (code !in 200..299) error("WebDAV 上传失败 HTTP $code${err?.let { ": $it" } ?: ""}")
            } finally {
                conn.disconnect()
            }
        }
    }

    fun webdavDownload(baseUrl: String, username: String, password: String, remoteName: String, localFile: File): Result<File> = runCatching {
        require(username.isNotBlank() && password.isNotBlank()) { "请填写 WebDAV 用户名和密码" }
        wrapSsl {
            val conn = openWebDav(joinUrl(baseUrl, remoteName), username, password).apply { requestMethod = "GET" }
            try {
                val code = conn.responseCode
                val err = conn.errorStream?.bufferedReader()?.readText()
                if (code == 401 || code == 403) error(authFailedMessage(baseUrl, code, err))
                if (code !in 200..299) {
                    error("WebDAV 下载失败 HTTP $code${err?.let { ": $it" } ?: ""}")
                }
                localFile.parentFile?.mkdirs()
                var written = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(localFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_TOTAL_SIZE) {
                                localFile.delete()
                                error("WebDAV 下载过大")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                require(isZipFile(localFile) || isEncryptedBackup(localFile)) {
                    "下载内容不是备份文件（服务器可能返回了错误页）。请确认远程文件名「$remoteName」正确。"
                }
                localFile
            } finally {
                conn.disconnect()
            }
        }
    }

    fun webdavProbe(baseUrl: String, username: String, password: String, remoteName: String = ""): Result<Boolean> = runCatching {
        wrapSsl {
            if (username.isBlank() || password.isBlank()) {
                error("请填写 WebDAV 用户名和密码")
            }
            val base = requireHttps(baseUrl)
            val fileUrl = if (remoteName.isNotBlank()) joinUrl(base, remoteName) else base
            // Android HttpURLConnection only allows OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH.
            // PROPFIND throws ProtocolException and is NOT a real connectivity failure — never use it.
            var lastDetail = "no response"
            var sawAuthFailure = false
            val attempts = listOf(
                fileUrl to "HEAD",
                base to "OPTIONS",
                base to "HEAD",
                base to "GET",
            )
            for ((target, method) in attempts) {
                val conn = try {
                    openWebDav(target, username, password).apply {
                        requestMethod = method
                        instanceFollowRedirects = false
                    }
                } catch (e: Exception) {
                    lastDetail = friendlyProbeDetail(e)
                    continue
                }
                try {
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location").orEmpty()
                    lastDetail = "$method HTTP $code"
                    when (classifyProbe(code)) {
                        ProbeClass.AUTH -> {
                            sawAuthFailure = true
                            lastDetail = authFailedMessage(baseUrl, code, conn.errorStream?.bufferedReader()?.readText())
                        }
                        ProbeClass.REDIRECT -> {
                            if (loc.isNotEmpty()) {
                                val nextUrl = try {
                                    URI(target).resolve(loc.trim()).toASCIIString()
                                } catch (_: Exception) {
                                    ""
                                }
                                val next = try {
                                    URI(nextUrl)
                                } catch (_: Exception) {
                                    null
                                }
                                val cur = try {
                                    URI(target)
                                } catch (_: Exception) {
                                    null
                                }
                                if (
                                    nextUrl.isEmpty() ||
                                    next == null ||
                                    cur == null ||
                                    next.scheme != "https" ||
                                    next.host.isNullOrEmpty() ||
                                    !next.host.equals(cur.host, true)
                                ) {
                                    error("连通性失败：重定向到不安全主机")
                                }
                                try {
                                    RemoteUrlGuard.requireAllowed(nextUrl, RemoteUrlGuard.Kind.SUBSCRIPTION)
                                } catch (_: Exception) {
                                    error("连通性失败：重定向到不安全主机")
                                }
                            }
                            lastDetail = "redirect $code"
                        }
                        ProbeClass.OK, ProbeClass.MISS -> {
                            if (code == 405) continue
                            if (method == "OPTIONS" && code in 200..204) {
                                val dav = conn.getHeaderField("DAV").orEmpty()
                                val allow = conn.getHeaderField("Allow").orEmpty()
                                if (dav.isEmpty() && allow.isEmpty() && code != 204) continue
                            }
                            return@runCatching true
                        }
                        ProbeClass.OTHER -> { }
                    }
                } catch (e: Exception) {
                    if (e is IllegalStateException && (e.message?.contains("认证") == true || e.message?.contains("重定向") == true)) throw e
                    lastDetail = friendlyProbeDetail(e)
                } finally {
                    try {
                        conn.disconnect()
                    } catch (_: Exception) {}
                }
            }
            if (sawAuthFailure) error(lastDetail)
            error("连通性失败：$lastDetail")
        }
    }

    internal fun friendlyProbeDetail(e: Exception): String {
        val text = (e.message ?: e.javaClass.simpleName).trim()
        if (text.contains("PROPFIND", true) || text.contains("Expected one of", true)) {
            return "已跳过不受支持的检测方法"
        }
        return text.take(120)
    }

    internal enum class ProbeClass { OK, AUTH, MISS, REDIRECT, OTHER }

    internal fun classifyProbe(code: Int): ProbeClass = when (code) {
        401, 403 -> ProbeClass.AUTH
        207, 206 -> ProbeClass.OK
        in 200..299 -> ProbeClass.OK
        404, 410, 405 -> ProbeClass.MISS
        in 300..399 -> ProbeClass.REDIRECT
        else -> ProbeClass.OTHER
    }

    internal fun authFailedMessage(baseUrl: String, code: Int, body: String?): String {
        val host = try {
            URI(baseUrl).host ?: baseUrl
        } catch (_: Exception) {
            baseUrl
        }
        val extra = if (host.contains("koofr", true)) {
            " Koofr 请使用账号邮箱 + 在 Koofr 设置里生成的应用密码，不是登录密码。"
        } else {
            " 请核对用户名/密码；部分网盘需要单独的应用密码。"
        }
        val snippet = body?.trim()?.take(80).orEmpty()
        return "认证失败 HTTP $code。$extra" + if (snippet.isNotEmpty()) " 服务器：$snippet" else ""
    }

    internal fun isZipFile(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return FileInputStream(file).use { ins ->
            val magic = ByteArray(4)
            if (ins.read(magic) != 4) return@use false
            magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        }
    }

    private inline fun <T> wrapSsl(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw friendlySsl(e)
    }

    internal fun friendlySsl(e: Exception): Exception {
        val text = buildString {
            append(e.message ?: "")
            var c = e.cause
            var n = 0
            while (c != null && n++ < 6) {
                append(' ')
                append(c.javaClass.simpleName)
                append(':')
                append(c.message ?: "")
                c = c.cause
            }
        }
        if (
            text.contains("Trust anchor", true) ||
            text.contains("CertPath", true) ||
            text.contains("SSLHandshake", true) ||
            text.contains("CertificateException", true)
        ) {
            return IllegalStateException(
                "证书校验失败。WebDAV 按分流走内核路由（海外网盘会走代理）。请确认设备时间正确，以及 WebDAV 站点证书链完整（常见于 TeraCLOUD / 自签证书）。原始错误：${e.message}",
                e,
            )
        }
        return if (e is IllegalStateException) e else IllegalStateException(e.message ?: e.javaClass.simpleName, e)
    }

    private fun openWebDav(url: String, username: String, password: String): HttpsURLConnection {
        val checked = requireHttps(url)
        // When the tunnel is up, system DNS answers fake-ip and direct DNS
        // often fails: dial by name and let the tunnel resolve upstream (same
        // policy as subscription updates: HTTPClient.Route.PROXY). TLS still
        // verifies the hostname. Tunnel down keeps the pinned direct connection.
        val conn = if (TunnelGate.up) {
            HTTPClient.openNamed(
                checked,
                RemoteUrlGuard.validateWithoutDns(checked, RemoteUrlGuard.Kind.SUBSCRIPTION),
                RemoteUrlGuard.Kind.SUBSCRIPTION,
                emptyMap(),
            )
        } else {
            HTTPClient.openPinned(checked, RemoteUrlGuard.Kind.SUBSCRIPTION)
        }
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "AngelaBox-WebDAV")
        conn.setRequestProperty("Connection", "close")
        if (username.isNotEmpty()) {
            val token = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return conn
    }

    /**
     * Copies the settings DB with the WebDAV password and GitHub token blanked.
     * The live Settings are never touched: a process killed mid-backup cannot
     * lose the user's credentials. Mirrors [copyProfilesDbRedacted].
     */
    private fun copySettingsDbRedacted(zos: ZipOutputStream, context: Context) {
        val main = context.getDatabasePath(Path.SETTINGS_DATABASE_PATH)
        if (!main.isFile) return
        val tmp = File(context.cacheDir, "settings-redacted.db")
        try {
            main.copyTo(tmp, overwrite = true)
            SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL(
                    "DELETE FROM KeyValueEntity WHERE `key` IN (?, ?)",
                    arrayOf<Any?>(SettingsKey.WEBDAV_PASSWORD, SettingsKey.GITHUB_TOKEN),
                )
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                // DELETE only marks b-tree pages as free; VACUUM rebuilds the
                // file so credential bytes are not left recoverable in free
                // pages of a backup that will be uploaded to a third party.
                db.execSQL("VACUUM")
            }
            putFile(zos, Path.SETTINGS_DATABASE_PATH, tmp)
        } finally {
            tmp.delete()
            deleteSidecars(tmp)
        }
    }

    private fun copyProfilesDbRedacted(zos: ZipOutputStream, context: Context) {
        val main = context.getDatabasePath(Path.PROFILES_DATABASE_PATH)
        if (!main.isFile) return
        val tmp = File(context.cacheDir, "profiles-redacted.db")
        try {
            main.copyTo(tmp, overwrite = true)
            SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val hasTable = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='remote_servers'",
                    null,
                ).use { it.moveToFirst() }
                if (hasTable) {
                    db.execSQL("UPDATE remote_servers SET secret = ''")
                }
                // Subscription URLs often embed the token in path/query: blank
                // remoteURL inside each profile's serialized typed blob so the
                // backup carries no credential material. Restore leaves the URL
                // empty for the user to re-enter.
                val redacted = mutableListOf<Pair<Long, ByteArray>>()
                db.rawQuery("SELECT id, typed FROM profiles", null).use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val typedIndex = cursor.getColumnIndexOrThrow("typed")
                    while (cursor.moveToNext()) {
                        runCatching {
                            val blob = cursor.getBlob(typedIndex) ?: return@runCatching
                            val typed = blob.unmarshall(::TypedProfile)
                            if (typed.remoteURL.isEmpty()) return@runCatching
                            typed.remoteURL = ""
                            redacted += cursor.getLong(idIndex) to typed.marshall()
                        }
                    }
                }
                for ((id, bytes) in redacted) {
                    db.execSQL("UPDATE profiles SET typed = ? WHERE id = ?", arrayOf<Any?>(bytes, id))
                }
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                // See copySettingsDbRedacted: VACUUM away freed pages.
                db.execSQL("VACUUM")
            }
            putFile(zos, Path.PROFILES_DATABASE_PATH, tmp)
        } finally {
            tmp.delete()
            deleteSidecars(tmp)
        }
    }

    private fun requireHttps(url: String): String {
        val normalized = url.trim()
        require(normalized.startsWith("https://", ignoreCase = true)) { "WebDAV 必须使用 HTTPS" }
        if (TunnelGate.up) {
            // Tunnel DNS answers fake-ip: do not require a direct DNS answer
            // here. The connection below dials by name and the tunnel resolves
            // upstream (same policy as subscription imports: acceptSubscription).
            RemoteUrlGuard.validateWithoutDns(normalized, RemoteUrlGuard.Kind.SUBSCRIPTION)
        } else {
            RemoteUrlGuard.requireAllowed(normalized, RemoteUrlGuard.Kind.SUBSCRIPTION)
        }
        return normalized
    }

    private fun checkpoint(context: Context, dbName: String) {
        val main = context.getDatabasePath(dbName)
        if (!main.isFile) return
        runCatching {
            SQLiteDatabase.openDatabase(main.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
        }
    }

    private fun deleteSidecars(dbFile: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(dbFile.path + suffix).delete()
        }
    }

    private fun putSettingString(dbFile: File, key: String, value: String) {
        if (value.isEmpty() || !dbFile.isFile) return
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val cv = ContentValues()
                cv.put("key", key)
                cv.put("valueType", 4)
                cv.put("value", value.toByteArray())
                db.insertWithOnConflict("KeyValueEntity", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private fun putFile(zos: ZipOutputStream, name: String, file: File) {
        require(file.length() <= MAX_ENTRY_SIZE) { "备份文件过大：$name" }
        putEntry(zos, name, file.readBytes())
    }

    private fun putEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun joinUrl(base: String, name: String): String = base.trimEnd('/') + "/" + name.trimStart('/')
}
