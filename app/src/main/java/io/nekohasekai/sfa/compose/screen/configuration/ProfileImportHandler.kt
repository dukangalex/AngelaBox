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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Date

class ProfileImportHandler(private val context: Context) {
    companion object {
        private const val MAX_IMPORT_BYTES = 8L * 1024L * 1024L
    }

    sealed class ImportResult {
        data class Success(val profile: Profile) : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    sealed class QRCodeParseResult {
        data class RemoteProfile(val name: String, val host: String, val url: String) : QRCodeParseResult()

        data class LocalProfile(val name: String) : QRCodeParseResult()

        data class Error(val message: String) : QRCodeParseResult()
    }

    sealed class QRSParseResult {
        data class Success(val name: String) : QRSParseResult()

        data class Error(val message: String) : QRSParseResult()
    }

    sealed class UriParseResult {
        data class Success(val name: String) : UriParseResult()

        data class Error(val message: String) : UriParseResult()
    }

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val data = readUriBytes(uri)
                ?: return@withContext ImportResult.Error(context.getString(R.string.error_empty_file))

            val filename = getFileNameFromUri(uri)
            val dataString = String(data)
            if (isJsonConfiguration(dataString)) {
                return@withContext importJsonConfiguration(dataString, filename)
            }

            val content =
                try {
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
            val data = readUriBytes(uri)
                ?: return@withContext UriParseResult.Error(context.getString(R.string.error_empty_file))

            val filename = getFileNameFromUri(uri)
            val dataString = String(data)

            if (isJsonConfiguration(dataString)) {
                return@withContext UriParseResult.Success(name = filename)
            }

            val content =
                try {
                    Libbox.decodeProfileContent(data)
                } catch (e: Exception) {
                    if (dataString.trimStart().startsWith("{") || dataString.trimStart().startsWith("[")) {
                        return@withContext UriParseResult.Success(name = filename)
                    }
                    return@withContext UriParseResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }

            UriParseResult.Success(name = content.name)
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

            if (data.startsWith("http://") || data.startsWith("https://")) {
                val profileName = extractProfileNameFromUrl(data)
                return@withContext QRCodeParseResult.RemoteProfile(
                    name = profileName,
                    host = extractHostFromUrl(data),
                    url = data,
                )
            }

            val content =
                try {
                    Libbox.decodeProfileContent(data.toByteArray())
                } catch (e: Exception) {
                    return@withContext QRCodeParseResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }

            return@withContext QRCodeParseResult.LocalProfile(name = content.name)
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

            if (data.startsWith("http://") || data.startsWith("https://")) {
                val profileName = extractProfileNameFromUrl(data)
                importRemoteProfile(profileName, data)
            } else {
                val content =
                    try {
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
            QRSParseResult.Success(name = content.name)
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
            Libbox.ProfileTypeLocal -> {
                typedProfile.type = TypedProfile.Type.Local
            }
            Libbox.ProfileTypeiCloud -> {
                return ImportResult.Error(context.getString(R.string.icloud_profile_unsupported))
            }
            Libbox.ProfileTypeRemote -> {
                typedProfile.type = TypedProfile.Type.Remote
                typedProfile.remoteURL = content.remotePath
                typedProfile.autoUpdate = content.autoUpdate
                typedProfile.autoUpdateInterval = content.autoUpdateInterval
                typedProfile.lastUpdated = Date(content.lastUpdated)
            }
        }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        configFile.writeText(content.config)
        typedProfile.path = configFile.path

        ProfileManager.create(profile, andSelect = Settings.selectedProfile < 0L)

        return ImportResult.Success(profile)
    }

    private suspend fun importRemoteProfile(name: String, url: String): ImportResult {
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = url
                autoUpdate = true
                autoUpdateInterval = 60
                lastUpdated = Date()
            }

        val profile =
            Profile(name = name, typed = typedProfile).apply {
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
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > MAX_IMPORT_BYTES) {
                    throw IllegalArgumentException("Imported profile exceeds 8 MiB limit")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
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
                filename = segment
                    .substringBeforeLast(".")
                    .takeIf { it.isNotEmpty() }
                    ?: filename
            }
        }

        return filename
    }

    private fun isJsonConfiguration(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false
        }

        return try {
            val json = JSONObject(content)
            json.has("inbounds") ||
                json.has("outbounds") ||
                json.has("route") ||
                json.has("dns") ||
                json.has("experimental")
        } catch (e: Exception) {
            trimmed.startsWith("[") && trimmed.endsWith("]")
        }
    }

    private suspend fun importJsonConfiguration(jsonContent: String, profileName: String): ImportResult {
        return try {
            val sanitized = ConfigCompat.sanitize(jsonContent)
            try {
                Libbox.checkConfig(sanitized)
            } catch (e: Exception) {
                return ImportResult.Error(
                    context.getString(R.string.error_invalid_configuration, e.message),
                )
            }

            val typedProfile =
                TypedProfile().apply {
                    type = TypedProfile.Type.Local
                }

            val profile =
                Profile(
                    name = profileName.ifEmpty { "Imported Profile" },
                    typed = typedProfile,
                ).apply {
                    userOrder = ProfileManager.nextOrder()
                }

            val fileID = ProfileManager.nextFileID()
            val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
            val configFile = File(configDirectory, "$fileID.json")
            configFile.writeText(sanitized)
            typedProfile.path = configFile.path

            ProfileManager.create(profile, andSelect = Settings.selectedProfile < 0L)

            ImportResult.Success(profile)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error importing JSON configuration")
        }
    }
}
