package io.nekohasekai.sfa.ktx

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.libbox.ProfileContent
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.appcompat.R as AppCompatR

suspend fun Context.shareProfile(profile: Profile) {
    val content = ProfileContent()
    content.name = profile.name
    when (profile.typed.type) {
        TypedProfile.Type.Local -> {
            content.type = io.nekohasekai.libbox.Libbox.ProfileTypeLocal
        }

        TypedProfile.Type.Remote -> {
            content.type = io.nekohasekai.libbox.Libbox.ProfileTypeRemote
        }
    }
    content.config = File(profile.typed.path).readText()
    content.remotePath = profile.typed.remoteURL
    content.autoUpdate = profile.typed.autoUpdate
    content.autoUpdateInterval = profile.typed.autoUpdateInterval
    content.lastUpdated = profile.typed.lastUpdated.time

    val configDirectory = File(cacheDir, "share").also { it.mkdirs() }
    val profileFile = File(configDirectory, "${shareBasename(profile.name)}.bpf")
    require(profileFile.canonicalFile.parentFile == configDirectory.canonicalFile)
    profileFile.writeBytes(content.encode())
    val uri = FileProvider.getUriForFile(this, "$packageName.cache", profileFile)
    withContext(Dispatchers.Main) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_STREAM, uri),
                getString(AppCompatR.string.abc_shareactionprovider_share_with),
            ),
        )
    }
}

suspend fun Context.shareProfileAsJson(profile: Profile) {
    val configDirectory = File(cacheDir, "share").also { it.mkdirs() }
    val jsonFile = File(configDirectory, "${shareBasename(profile.name)}.json")
    require(jsonFile.canonicalFile.parentFile == configDirectory.canonicalFile)
    jsonFile.writeText(File(profile.typed.path).readText())
    val uri = FileProvider.getUriForFile(this, "$packageName.cache", jsonFile)
    withContext(Dispatchers.Main) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/json")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_STREAM, uri),
                getString(AppCompatR.string.abc_shareactionprovider_share_with),
            ),
        )
    }
}

internal fun shareBasename(name: String): String {
    val cleaned = name
        .replace('\\', '_')
        .replace('/', '_')
        .replace("..", "_")
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
        .trim('_', '.')
        .take(64)
    return cleaned.ifBlank { "profile" }
}
