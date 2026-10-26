package io.nekohasekai.sfa.compose.screen.configuration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.ProfileContent
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.ConfigCompat
import io.nekohasekai.sfa.utils.ConfigGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Date

class ProfileImportHandler(private val context: Context) {
    companion object {
        private const val MAX_IMPORT_BYTES = 8L * 1024L * 1024L

        /**
         * 导入成功但有"跳过/修正"提示时弹窗告知。用户划线：绝不藏报错，
         * "导入成功"背后的节点丢失必须让用户看见。
         */
        fun showImportWarningsDialog(context: Context, warnings: List<String>) {
            if (warnings.isEmpty()) return
            android.app.AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.import_warnings_title, warnings.size))
                .setMessage(warnings.joinToString("\n\n"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    sealed class ImportResult {
        data class Success(val profile: Profile, val warnings: List<String> = emptyList()) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /** Content bytes captured once at parse time, with their SHA-256 (P3-4). */
    class ImportSnapshot(val bytes: ByteArray, val sha256: String)

    sealed class QRCodeParseResult {
        data class RemoteProfile(val name: String, val host: String, val url: String) : QRCodeParseResult()

        /**
         * [detail] discloses the decoded profile type (and, for remote
         * profiles, the full URL + auto-update state) to the confirmation
         * (P3-6 / P1-1); [sshWarning] names an ssh_server endpoint found in
         * the original content (P0-1).
         */
        data class LocalProfile(
            val name: String,
            val detail: String? = null,
            val sshWarning: String? = null,
        ) : QRCodeParseResult()

        data class Error(val message: String) : QRCodeParseResult()
    }

    sealed class QRSParseResult {
        data class Success(
            val name: String,
            val detail: String? = null,
            val sshWarning: String? = null,
        ) : QRSParseResult()

        data class Error(val message: String) : QRSParseResult()
    }

    sealed class UriParseResult {
        data class Success(
            val name: String,
            val detail: String? = null,
            val sshWarning: String? = null,
            val snapshot: ImportSnapshot? = null,
        ) : UriParseResult()

        data class Error(val message: String) : UriParseResult()
    }

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            io.nekohasekai.sfa.utils.ImportPathGuard.requireContentScheme(uri.scheme)
            val data = readUriBytes(uri)
                ?: return@withContext ImportResult.Error(context.getString(R.string.error_empty_file))
            importFromBytes(data, getFileNameFromUri(uri))
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * P3-4: imports the exact bytes [parseUri] captured and classified
     * before the confirmation was shown, instead of re-reading the provider
     * URI — the provider could otherwise swap the content after the user
     * confirmed. The hash is re-verified here so a mutated snapshot is
     * refused rather than imported.
     */
    suspend fun importFromSnapshot(snapshot: ImportSnapshot, fallbackName: String): ImportResult {
        if (sha256Hex(snapshot.bytes) != snapshot.sha256) {
            return ImportResult.Error(context.getString(R.string.error_import_snapshot_mismatch))
        }
        return importFromBytes(snapshot.bytes, fallbackName)
    }

    private suspend fun importFromBytes(data: ByteArray, filename: String): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val dataString = String(data)
                if (isJsonConfiguration(dataString) || io.nekohasekai.sfa.utils.ConfigIngest.looksConvertible(dataString)) {
                    return@withContext importJsonConfiguration(dataString, filename)
                }

                val content = try {
                    Libbox.decodeProfileContent(data)
                } catch (e: Exception) {
                    if (dataString.trimStart().startsWith("{") || dataString.trimStart().startsWith("[")) {
                        return@withContext importJsonConfiguration(dataString, filename)
                    }
                    return@withContext ImportResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }

                importProfile(content)
            } catch (e: Exception) {
                ImportResult.Error(e.message ?: "Unknown error")
            }
        }

    suspend fun parseUri(uri: Uri): UriParseResult = withContext(Dispatchers.IO) {
        try {
            io.nekohasekai.sfa.utils.ImportPathGuard.requireContentScheme(uri.scheme)
            val filename = getFileNameFromUri(uri)
            // P3-4: read and classify the content NOW, at confirmation time,
            // instead of trusting the provider-controlled file name alone.
            // The snapshot binds the confirmation to these exact bytes; the
            // import later consumes the snapshot (see importFromSnapshot).
            val data = readUriBytes(uri)
                ?: return@withContext UriParseResult.Success(filename)
            val snapshot = ImportSnapshot(data, sha256Hex(data))
            val dataString = String(data)
            if (isJsonConfiguration(dataString) || io.nekohasekai.sfa.utils.ConfigIngest.looksConvertible(dataString)) {
                return@withContext UriParseResult.Success(
                    name = filename,
                    detail = context.getString(R.string.import_profile_detail_type_local),
                    sshWarning = sshWarningForConfig(dataString),
                    snapshot = snapshot,
                )
            }
            val content = try {
                Libbox.decodeProfileContent(data)
            } catch (e: Exception) {
                return@withContext UriParseResult.Success(filename, snapshot = snapshot)
            }
            UriParseResult.Success(
                name = filename,
                detail = describeDecodedProfile(content),
                sshWarning = sshWarningForConfig(content.config),
                snapshot = snapshot,
            )
        } catch (e: Exception) {
            UriParseResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun parseQRCode(data: String): QRCodeParseResult = withContext(Dispatchers.IO) {
        try {
            if (data.startsWith("sing-box://import-remote-profile")) {
                try {
                    val profileInfo = Libbox.parseRemoteProfileImportLink(data)
                    return@withContext QRCodeParseResult.RemoteProfile(
                        name = profileInfo.name,
                        host = profileInfo.host,
                        url = profileInfo.url,
                    )
                } catch (e: Exception) {
                    return@withContext QRCodeParseResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }
            }

            // Node links, a base64 node list or a pasted Clash / JSON node: same detection as file import.
            if (io.nekohasekai.sfa.utils.ConfigIngest.looksConvertible(data)) {
                return@withContext QRCodeParseResult.LocalProfile(
                    name = nodeProfileName(data),
                    detail = context.getString(R.string.import_profile_detail_type_local),
                    sshWarning = sshWarningForConfig(data),
                )
            }

            if (data.startsWith("https://", ignoreCase = true)) {
                val profileName = extractProfileNameFromUrl(data)
                return@withContext QRCodeParseResult.RemoteProfile(
                    name = profileName,
                    host = extractHostFromUrl(data),
                    url = data,
                )
            }
            if (data.startsWith("http://", ignoreCase = true)) {
                return@withContext QRCodeParseResult.Error("订阅仅允许 HTTPS")
            }

            val content = try {
                Libbox.decodeProfileContent(data.toByteArray())
            } catch (e: Exception) {
                return@withContext QRCodeParseResult.Error(
                    context.getString(R.string.error_decode_profile, e.message),
                )
            }
            QRCodeParseResult.LocalProfile(
                name = content.name,
                detail = describeDecodedProfile(content),
                sshWarning = sshWarningForConfig(content.config),
            )
        } catch (e: Exception) {
            QRCodeParseResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun importFromQRCode(data: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (data.startsWith("sing-box://import-remote-profile")) {
                try {
                    val profileInfo = Libbox.parseRemoteProfileImportLink(data)
                    return@withContext importRemoteProfile(profileInfo.name, profileInfo.url)
                } catch (e: Exception) {
                    return@withContext ImportResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }
            }

            if (io.nekohasekai.sfa.utils.ConfigIngest.looksConvertible(data)) {
                return@withContext importJsonConfiguration(data, nodeProfileName(data))
            }

            if (data.startsWith("https://", ignoreCase = true)) {
                val profileName = extractProfileNameFromUrl(data)
                importRemoteProfile(profileName, data)
            } else if (data.startsWith("http://", ignoreCase = true)) {
                ImportResult.Error("订阅仅允许 HTTPS")
            } else {
                val content = try {
                    Libbox.decodeProfileContent(data.toByteArray())
                } catch (e: Exception) {
                    return@withContext ImportResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }
                importProfile(content)
            }
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun parseQRSData(data: ByteArray): QRSParseResult = withContext(Dispatchers.IO) {
        try {
            val content = try {
                Libbox.decodeProfileContent(data)
            } catch (e: Exception) {
                return@withContext QRSParseResult.Error(
                    context.getString(R.string.error_decode_profile, e.message),
                )
            }
            QRSParseResult.Success(
                name = content.name,
                detail = describeDecodedProfile(content),
                sshWarning = sshWarningForConfig(content.config),
            )
        } catch (e: Exception) {
            QRSParseResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun importFromQRSData(data: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        try {
            val content = try {
                Libbox.decodeProfileContent(data)
            } catch (e: Exception) {
                return@withContext ImportResult.Error(
                    context.getString(R.string.error_decode_profile, e.message),
                )
            }
            importProfile(content)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun importProfile(content: ProfileContent): ImportResult {
        val typedProfile = TypedProfile()
        val profile = Profile(name = content.name, typed = typedProfile)
        profile.userOrder = ProfileManager.nextOrder()

        when (content.type) {
            Libbox.ProfileTypeLocal -> typedProfile.type = TypedProfile.Type.Local
            Libbox.ProfileTypeiCloud -> return ImportResult.Error(context.getString(R.string.icloud_profile_unsupported))
            Libbox.ProfileTypeRemote -> {
                try {
                    io.nekohasekai.sfa.utils.RemoteUrlGuard.acceptSubscription(content.remotePath)
                } catch (e: Exception) {
                    return ImportResult.Error(e.message ?: "订阅地址不安全")
                }
                typedProfile.type = TypedProfile.Type.Remote
                typedProfile.remoteURL = content.remotePath
                typedProfile.autoUpdate = content.autoUpdate
                // P1-1: same 15-minute floor as every other creation path
                // (unit is minutes, cf. NewProfileViewModel / BackupManager).
                typedProfile.autoUpdateInterval = content.autoUpdateInterval.coerceAtLeast(15)
                typedProfile.lastUpdated = Date(content.lastUpdated)
            }
        }

        // P1-3: the decoded blob used to be stored verbatim, skipping the
        // sanitize + gate every sibling import path applies. Mirror
        // importJsonConfiguration exactly: same sanitizeDetailed call, same
        // gate, same rejection surface. Sanitize also drops kernel-honored
        // file targets the blob author controls (log.output, see
        // ConfigCompat.stripLogFileOutput). Non-object configs (e.g. the
        // empty placeholder a remote blob carries) have nothing to sanitize.
        var storedConfig = content.config
        var importNotes: List<String> = emptyList()
        if (storedConfig.trimStart().startsWith("{")) {
            val detailed = ConfigCompat.sanitizeDetailed(storedConfig)
            val gate = ConfigGate.validate(detailed.content, ConfigGate::libboxCheck)
            if (gate is ConfigGate.Result.Rejected) {
                return ImportResult.Error(
                    context.getString(R.string.error_invalid_configuration, gate.reason),
                )
            }
            storedConfig = detailed.content
            importNotes = detailed.notes
        }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        configFile.writeText(storedConfig)
        typedProfile.path = configFile.path
        ProfileManager.create(profile, andSelect = Settings.selectedProfile < 0L)
        // AB-019: warm the rule-set cache at import time, so the startup
        // cache substitute has ammunition for file/QR imports too — it
        // used to be filled only by remote subscription updates.
        io.nekohasekai.sfa.bg.RuleSetWarmup.schedule(storedConfig)
        return ImportResult.Success(profile, importNotes)
    }

    private suspend fun importRemoteProfile(name: String, url: String): ImportResult {
        val secureUrl = url.trim()
        try {
            io.nekohasekai.sfa.utils.RemoteUrlGuard.acceptSubscription(secureUrl)
        } catch (e: Exception) {
            return ImportResult.Error(e.message ?: "订阅地址不安全")
        }

        val typedProfile = TypedProfile().apply {
            type = TypedProfile.Type.Remote
            remoteURL = secureUrl
            autoUpdate = true
            autoUpdateInterval = 60
            lastUpdated = Date()
        }

        val profile = Profile(name = name, typed = typedProfile).apply {
            userOrder = ProfileManager.nextOrder()
        }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        configFile.writeText("{}")
        typedProfile.path = configFile.path
        ProfileManager.create(profile, andSelect = Settings.selectedProfile < 0L)
        return ImportResult.Success(profile)
    }

    private fun readUriBytes(uri: Uri): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            io.nekohasekai.sfa.utils.ImportPathGuard.readLimited(input, MAX_IMPORT_BYTES)
        }
    }

    private fun extractProfileNameFromUrl(url: String): String {
        return url.substringAfterLast("/")
            .substringBeforeLast(".")
            .takeIf { it.isNotEmpty() }
            ?: "Remote Profile"
    }

    private fun extractHostFromUrl(url: String): String = try {
        val uri = Uri.parse(url)
        uri.host ?: url
    } catch (e: Exception) {
        url
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var filename = "Imported Profile"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                filename = cursor.getString(nameIndex)
                    ?.substringBeforeLast(".")
                    ?.takeIf { it.isNotEmpty() }
                    ?: filename
            }
        }

        if (filename == "Imported Profile") {
            uri.lastPathSegment?.let { segment ->
                filename = segment.substringBeforeLast(".").takeIf { it.isNotEmpty() } ?: filename
            }
        }
        return filename
    }

    /** A single link names the profile after its `#fragment`; anything else is "Imported Profile". */
    private fun nodeProfileName(data: String): String {
        val lines = data.trim().lines().filter { it.isNotBlank() }
        if (lines.size != 1) return "Imported Profile"
        val fragment = lines[0].substringAfter('#', "").trim()
        if (fragment.isEmpty()) return "Imported Profile"
        return try {
            java.net.URLDecoder.decode(fragment, "UTF-8")
        } catch (_: Exception) {
            fragment
        }.ifBlank { "Imported Profile" }
    }

    private fun isJsonConfiguration(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
        return try {
            val json = JSONObject(content)
            json.has("inbounds") || json.has("outbounds") || json.has("route") ||
                json.has("dns") || json.has("experimental")
        } catch (e: Exception) {
            trimmed.startsWith("[") && trimmed.endsWith("]")
        }
    }

    private suspend fun importJsonConfiguration(jsonContent: String, profileName: String): ImportResult {
        return try {
            val detailed = ConfigCompat.sanitizeDetailed(jsonContent)
            val sanitized = detailed.content
            // G4: gate aligned with the startup heal, readable reason.
            val gate = ConfigGate.validate(sanitized, ConfigGate::libboxCheck)
            if (gate is ConfigGate.Result.Rejected) {
                return ImportResult.Error(
                    context.getString(R.string.error_invalid_configuration, gate.reason),
                )
            }

            val typedProfile = TypedProfile().apply { type = TypedProfile.Type.Local }
            val profile = Profile(
                name = profileName.ifEmpty { "Imported Profile" },
                typed = typedProfile,
            ).apply { userOrder = ProfileManager.nextOrder() }

            val fileID = ProfileManager.nextFileID()
            val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
            val configFile = File(configDirectory, "$fileID.json")
            configFile.writeText(sanitized)
            typedProfile.path = configFile.path
            ProfileManager.create(profile, andSelect = Settings.selectedProfile < 0L)
            // AB-019: import-time rule-set cache warm-up (see importProfile).
            io.nekohasekai.sfa.bg.RuleSetWarmup.schedule(sanitized)
            ImportResult.Success(profile, detailed.notes)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error importing JSON configuration")
        }
    }

    /**
     * Disclosure lines for a decoded blob profile (P3-6 / P1-1): the type
     * always, and for remote profiles the full URL plus the auto-update
     * state that will be stored (interval already floored, as in
     * [importProfile]).
     */
    private fun describeDecodedProfile(content: ProfileContent): String? {
        return when (content.type) {
            Libbox.ProfileTypeRemote -> buildString {
                append(context.getString(R.string.import_profile_detail_type_remote))
                append('\n')
                append(context.getString(R.string.import_profile_detail_url, content.remotePath))
                append('\n')
                append(
                    if (content.autoUpdate) {
                        context.getString(
                            R.string.import_profile_detail_auto_update_on,
                            content.autoUpdateInterval.coerceAtLeast(15),
                        )
                    } else {
                        context.getString(R.string.import_profile_detail_auto_update_off)
                    },
                )
            }

            Libbox.ProfileTypeLocal -> context.getString(R.string.import_profile_detail_type_local)
            else -> null
        }
    }

    /**
     * P0-1 (confirmation half): returns the warning to name in the import
     * confirmation when the config enables a Tailscale SSH server, else null.
     *
     * This MUST be given the original imported content: normalization
     * (ConfigCompat / ConfigIngest, applied later at store/start time)
     * strips `ssh_server` from tailscale endpoints, so a check on the
     * normalized text would never see it. All call sites here pass
     * pre-normalization text — the raw file/QR bytes, or the decoded blob's
     * `content.config`, neither of which has been normalized yet — so the
     * warning (this check) and the strip (normalization) compose: the user
     * is told, and the stored/started config no longer carries the flag.
     */
    private fun sshWarningForConfig(configJson: String): String? {
        if (!configEnablesTailscaleSsh(configJson)) return null
        return context.getString(R.string.import_profile_ssh_server_warning)
    }

    private fun configEnablesTailscaleSsh(configJson: String): Boolean {
        val root = try {
            JSONObject(configJson)
        } catch (e: Exception) {
            return false
        }
        val endpoints = root.optJSONArray("endpoints") ?: return false
        for (i in 0 until endpoints.length()) {
            val endpoint = endpoints.optJSONObject(i) ?: continue
            if (endpoint.optString("type") != "tailscale") continue
            // The kernel accepts a bare boolean or an options object.
            val enabled = when (val sshServer = endpoint.opt("ssh_server")) {
                is Boolean -> sshServer
                is JSONObject -> sshServer.optBoolean("enabled", true)
                else -> false
            }
            if (enabled) return true
        }
        return false
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}
