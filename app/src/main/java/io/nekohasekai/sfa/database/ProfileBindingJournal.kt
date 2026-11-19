package io.nekohasekai.sfa.database

import android.system.Os
import android.system.OsConstants
import io.nekohasekai.sfa.chain.ChainBindingCodec
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.preference.KeyValueDatabase
import io.nekohasekai.sfa.database.preference.KeyValueEntity
import io.nekohasekai.sfa.utils.ProfileFiles
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** A conflict is not permission to discard the intent or start without the saved chain. */
internal class ProfileBindingRecoveryException : IOException("配置与链路保存尚未恢复，已停止读写；请保留应用数据并重试，勿覆盖或删除配置")

/**
 * Roll-forward protocol for the profile file + settings database pair. The caller must
 * serialize EVERY access with the same storage monitor. Staged config bytes live only
 * in noBackupFilesDir, not in a CursorWindow-sized DB value or a portable backup.
 *
 * One Room transaction commits the intended binding snapshot AND the recovery record.
 * The file is then replaced durably. Readers must recover before observing either store.
 * A conflicting file, identity or binding snapshot is preserved and blocks access.
 * This is process-crash recovery, not a claim about arbitrary device power-loss semantics.
 */
internal class ProfileBindingJournal(
    private val database: () -> KeyValueDatabase,
    private val stageDirectory: File,
    private val profilePath: (Long) -> String?,
    private val profileDirectory: File = stageDirectory.parentFile!!,
    private val syncDirectory: (File) -> Unit = ::syncProfileDirectory,
    private val cut: (String) -> Unit = {},
) {
    companion object {
        const val KEY = "pending_profile_binding_commit_v1"
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val TOKEN = Regex("[0-9a-f-]{36}")
        private val HASH = Regex("[0-9a-f]{64}")
        val bindingKeys = listOf(
            SettingsKey.CHAIN_BINDINGS,
            SettingsKey.CHAIN_ENABLED,
            SettingsKey.CHAIN_BOUND_PROFILE_ID,
            SettingsKey.CHAIN_ENTRY_TAG,
            SettingsKey.CHAIN_LANDING_PROFILE_ID,
            SettingsKey.CHAIN_LANDING_TAG,
        )

        fun entries(snapshot: io.nekohasekai.sfa.chain.ChainBindingSnapshot): List<KeyValueEntity> = listOf(
            KeyValueEntity(SettingsKey.CHAIN_BINDINGS).put(snapshot.json),
            KeyValueEntity(SettingsKey.CHAIN_ENABLED).put(snapshot.enabled),
            KeyValueEntity(SettingsKey.CHAIN_BOUND_PROFILE_ID).put(snapshot.boundProfileId),
            KeyValueEntity(SettingsKey.CHAIN_ENTRY_TAG).put(snapshot.entryTag),
            KeyValueEntity(SettingsKey.CHAIN_LANDING_PROFILE_ID).put(snapshot.landingProfileId),
            KeyValueEntity(SettingsKey.CHAIN_LANDING_TAG).put(snapshot.landingTag),
        )

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun fingerprint(entries: List<KeyValueEntity>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            entries.sortedBy { it.key }.forEach {
                digest.update(it.key.toByteArray())
                digest.update(0.toByte())
                digest.update(it.valueType.toByte())
                digest.update(it.value.size.toString().toByteArray())
                digest.update(0.toByte())
                digest.update(it.value)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private data class Intent(
        val id: Long,
        val path: String,
        val canonicalPath: String,
        val token: String,
        val before: String,
        val after: String,
        val bindings: String,
    ) {
        fun encode(): String = JSONObject().put("version", 1).put("id", id).put("path", path)
            .put("canonical", canonicalPath).put("token", token).put("before", before)
            .put("after", after).put("bindings", bindings).toString()
    }

    private fun decode(raw: String): Intent = try {
        require(raw.length <= 32 * 1024)
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        Intent(
            json.getLong("id"),
            json.getString("path"),
            json.getString("canonical"),
            json.getString("token"),
            json.getString("before"),
            json.getString("after"),
            json.getString("bindings"),
        ).also {
            require(it.id >= 0 && TOKEN.matches(it.token))
            require(listOf(it.before, it.after, it.bindings).all(HASH::matches))
        }
    } catch (_: Exception) {
        throw ProfileBindingRecoveryException()
    }

    private fun pending(): String? {
        val value = database().keyValuePairDao()[KEY] ?: return null
        return value.string ?: throw ProfileBindingRecoveryException()
    }

    private fun currentFingerprint(): String {
        val dao = database().keyValuePairDao()
        val entries = bindingKeys.map { dao[it] ?: throw ProfileBindingRecoveryException() }
        return fingerprint(entries)
    }

    private fun bytes(file: File): ByteArray {
        if (!file.isFile || file.length() > MAX_BYTES) throw ProfileBindingRecoveryException()
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_BYTES) throw ProfileBindingRecoveryException()
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    /** Returns true only when a pending intent was successfully completed. */
    fun recover(): Boolean {
        val raw = pending() ?: run {
            // A death before the intent transaction leaves an unreferenced stage.
            // All callers hold the storage monitor, so no live writer owns it.
            stageDirectory.listFiles()?.filter { TOKEN.matches(it.name) && it.isFile }?.forEach { it.delete() }
            return false
        }
        val intent = decode(raw)
        val target = File(intent.path)
        // Do not resurrect a deleted/replaced profile, follow a new symlink, or
        // overwrite independently changed content/settings. Leave the record intact.
        if (profilePath(intent.id) != intent.path || target.canonicalPath != intent.canonicalPath ||
            target.canonicalFile.parentFile != profileDirectory.canonicalFile ||
            currentFingerprint() != intent.bindings
        ) {
            throw ProfileBindingRecoveryException()
        }
        val actual = hash(bytes(target))
        if (actual != intent.after) {
            if (actual != intent.before) throw ProfileBindingRecoveryException()
            val staged = File(stageDirectory, intent.token)
            val content = bytes(staged)
            if (hash(content) != intent.after) throw ProfileBindingRecoveryException()
            ProfileFiles.writeAtomically(target, content.toString(Charsets.UTF_8))
            cut("file_replaced")
            syncDirectory(target.parentFile ?: throw ProfileBindingRecoveryException())
        } else {
            // Covers death after rename but before the directory was synced.
            FileOutputStream(target, true).use { it.fd.sync() }
            syncDirectory(target.parentFile ?: throw ProfileBindingRecoveryException())
        }
        cut("file_durable")
        database().runInTransaction {
            if (pending() != raw || currentFingerprint() != intent.bindings) throw ProfileBindingRecoveryException()
            database().keyValuePairDao().delete(KEY)
            cut("before_clear_commit")
        }
        cut("intent_cleared")
        File(stageDirectory, intent.token).delete()
        return true
    }

    fun commit(profileId: Long, file: File, expected: String, content: String, bindings: List<KeyValueEntity>): Boolean {
        recover()
        if (profilePath(profileId) != file.path || !file.isFile) return false
        val oldBytes = bytes(file)
        if (oldBytes.toString(Charsets.UTF_8) != expected) return false
        if (file.canonicalFile.parentFile != profileDirectory.canonicalFile) throw ProfileBindingRecoveryException()
        val newBytes = content.toByteArray(Charsets.UTF_8)
        require(newBytes.size <= MAX_BYTES) { "配置超过 8 MiB，无法安全保存" }
        require(bindings.map { it.key }.toSet() == bindingKeys.toSet())
        // Validate the actual map too, before persisting any intended update.
        ChainBindingCodec.parse(bindings.first { it.key == SettingsKey.CHAIN_BINDINGS }.string.orEmpty())
        if (!stageDirectory.isDirectory && !stageDirectory.mkdirs()) throw IOException("无法准备配置恢复目录")
        syncDirectory(stageDirectory.parentFile ?: throw ProfileBindingRecoveryException())
        val token = UUID.randomUUID().toString()
        val staged = File(stageDirectory, token)
        FileOutputStream(staged).use { out ->
            out.write(newBytes)
            out.fd.sync()
        }
        syncDirectory(stageDirectory)
        cut("staged")
        val intent = Intent(
            profileId,
            file.path,
            file.canonicalPath,
            token,
            hash(oldBytes),
            hash(newBytes),
            fingerprint(bindings),
        )
        try {
            database().runInTransaction {
                if (pending() != null) throw ProfileBindingRecoveryException()
                val dao = database().keyValuePairDao()
                bindings.forEach { dao.put(it) }
                dao.put(KeyValueEntity(KEY).put(intent.encode()))
                cut("before_intent_commit")
            }
        } catch (error: Throwable) {
            // Only remove an unreferenced stage, never an uncertain committed intent.
            if (pending() == null) staged.delete()
            throw error
        }
        cut("intent_committed")
        recover()
        return true
    }
}

/** Android supports directory fsync; errors retain the recovery record and fail closed. */
internal fun syncProfileDirectory(directory: File) {
    val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
}
