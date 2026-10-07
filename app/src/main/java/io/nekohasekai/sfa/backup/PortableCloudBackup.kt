package io.nekohasekai.sfa.backup

import io.nekohasekai.sfa.chain.ChainBinding
import io.nekohasekai.sfa.chain.ChainBindingCodec
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.OverlayScripts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cross-platform cloud backup codec (`angelabox-cloud/1`).
 * Windows must implement the same JSON. Android SQLite is not the interchange.
 */
object PortableCloudBackup {
    const val FORMAT = "angelabox-cloud/1"
    const val APP = "angelabox"
    const val VERSION = 3
    const val MANIFEST = "manifest.json"
    const val PROFILES = "profiles.json"
    const val SETTINGS = "settings.json"

    data class PortableProfile(
        val id: String,
        val name: String,
        val type: String,
        val remoteUrl: String,
        val autoUpdate: Boolean,
        val autoUpdateIntervalMinutes: Int,
        val lastUpdated: Long,
        val icon: String?,
        val config: String,
        val order: Int,
    )

    data class PortableChainBinding(
        val profileId: String,
        val entryTag: String,
        val landingProfileId: String,
        val landingTag: String,
    )

    data class Archive(
        val writtenBy: String,
        val time: Long,
        val selected: String?,
        val profiles: List<PortableProfile>,
        val configs: Map<String, ByteArray>,
        val settings: JSONObject,
    )

    fun manifest(writtenBy: String, time: Long = System.currentTimeMillis()): JSONObject =
        JSONObject()
            .put("version", VERSION)
            .put("format", FORMAT)
            .put("app", APP)
            .put("written_by", writtenBy)
            .put("time", time)
            .put("secrets", "omitted")

    fun isPortableManifest(raw: String): Boolean {
        if (raw.isBlank()) return false
        return try {
            val obj = JSONObject(raw)
            val format = obj.optString("format")
            val version = obj.optInt("version", 0)
            val app = obj.optString("app")
            format == FORMAT || (version >= VERSION && (app == APP || app == "chainbox"))
        } catch (_: Exception) {
            false
        }
    }

    fun buildFromAndroid(
        profiles: List<Profile>,
        selectedProfileId: Long,
        writtenBy: String = "android",
        time: Long = System.currentTimeMillis(),
    ): Archive {
        val portable = mutableListOf<PortableProfile>()
        val configs = linkedMapOf<String, ByteArray>()
        profiles.forEachIndexed { index, profile ->
            val uuid = ProfileStableIds.ensure(profile.id)
            val src = File(profile.typed.path)
            if (!src.isFile) return@forEachIndexed
            val rel = "configs/$uuid.json"
            configs[rel] = src.readBytes()
            portable += PortableProfile(
                id = uuid,
                name = profile.name,
                type = if (profile.typed.type == TypedProfile.Type.Remote) "remote" else "local",
                remoteUrl = profile.typed.remoteURL,
                autoUpdate = profile.typed.autoUpdate,
                autoUpdateIntervalMinutes = profile.typed.autoUpdateInterval,
                lastUpdated = profile.typed.lastUpdated.time,
                icon = profile.icon,
                config = rel,
                order = index,
            )
        }
        val selected = if (selectedProfileId >= 0L) {
            ProfileStableIds.load()[selectedProfileId]
        } else {
            null
        }
        return Archive(
            writtenBy = writtenBy,
            time = time,
            selected = selected,
            profiles = portable,
            configs = configs,
            settings = snapshotSettings(ProfileStableIds.load()),
        )
    }

    fun encodeProfiles(selected: String?, profiles: List<PortableProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("type", profile.type)
                    .put("remote_url", profile.remoteUrl)
                    .put("auto_update", profile.autoUpdate)
                    .put("auto_update_interval_minutes", profile.autoUpdateIntervalMinutes)
                    .put("last_updated", profile.lastUpdated)
                    .put("icon", profile.icon ?: JSONObject.NULL)
                    .put("config", profile.config)
                    .put("order", profile.order),
            )
        }
        return JSONObject()
            .put("selected", selected ?: JSONObject.NULL)
            .put("profiles", array)
            .toString()
    }

    fun parseProfiles(raw: String): Pair<String?, List<PortableProfile>> {
        val root = JSONObject(raw)
        val selected = root.optString("selected").trim().ifEmpty { null }
        val array = root.optJSONArray("profiles") ?: JSONArray()
        val out = ArrayList<PortableProfile>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            val config = obj.optString("config").trim()
            if (id.isEmpty() || name.isEmpty() || !config.startsWith("configs/") || config.contains("..")) {
                continue
            }
            val type = obj.optString("type").trim().ifEmpty { "local" }
            out += PortableProfile(
                id = id,
                name = name,
                type = if (type == "remote") "remote" else "local",
                remoteUrl = obj.optString("remote_url").trim(),
                autoUpdate = obj.optBoolean("auto_update", false),
                autoUpdateIntervalMinutes = obj.optInt("auto_update_interval_minutes", 60),
                lastUpdated = obj.optLong("last_updated", 0L),
                icon = obj.optString("icon").trim().ifEmpty { null },
                config = config,
                order = obj.optInt("order", i),
            )
        }
        return selected to out.sortedBy { it.order }
    }

    fun snapshotSettings(stableIds: Map<Long, String>): JSONObject {
        val chain = ChainBindingCodec.parse(Settings.chainBindingsJson).values.mapNotNull { binding ->
            val profile = stableIds[binding.profileId] ?: return@mapNotNull null
            val landing = stableIds[binding.landingProfileId] ?: return@mapNotNull null
            JSONObject()
                .put("profile_id", profile)
                .put("entry_tag", binding.entryTag)
                .put("landing_profile_id", landing)
                .put("landing_tag", binding.landingTag)
        }
        val chainArray = JSONArray()
        chain.forEach { chainArray.put(it) }
        val scriptBindings = JSONObject()
        OverlayScripts.decodeBindings(Settings.overlayScriptBindingsJson).forEach { (profileId, ids) ->
            val uuid = stableIds[profileId] ?: return@forEach
            val array = JSONArray()
            ids.forEach { array.put(it) }
            scriptBindings.put(uuid, array)
        }
        val webdav = JSONObject()
            .put("url", Settings.webdavUrl)
            .put("user", Settings.webdavUser)
            .put("remote_file", Settings.webdavRemoteFile.ifEmpty { "backup.zip" })
        return JSONObject()
            .put("china_direct", Settings.chinaDirect)
            .put("ads_block", Settings.adsBlock)
            .put("strict_route", Settings.strictRoute)
            .put("dns_protect", Settings.dnsProtect)
            .put("disable_ipv6", Settings.disableIpv6)
            .put("disable_quic", Settings.disableQuic)
            .put("exclude_cn_quic", Settings.excludeCnQuic)
            .put("webrtc_protect", Settings.webrtcProtect)
            .put("on_demand", Settings.onDemand)
            .put("config_normalize", Settings.configNormalize)
            .put("auto_redirect", Settings.autoRedirect)
            .put("chain_bindings", chainArray)
            .put("overlay_scripts", JSONArray(Settings.overlayScriptsJson.ifBlank { "[]" }))
            .put("overlay_script_bindings", scriptBindings)
            .put("webdav", webdav)
    }

    fun applyPortableSettings(raw: String, uuidToLocal: Map<String, Long>) {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        fun bool(key: String, fallback: Boolean): Boolean =
            if (root.has(key)) root.optBoolean(key, fallback) else fallback

        Settings.chinaDirect = bool("china_direct", Settings.chinaDirect)
        Settings.adsBlock = bool("ads_block", Settings.adsBlock)
        Settings.strictRoute = bool("strict_route", Settings.strictRoute)
        Settings.dnsProtect = bool("dns_protect", Settings.dnsProtect)
        Settings.disableIpv6 = bool("disable_ipv6", Settings.disableIpv6)
        Settings.disableQuic = bool("disable_quic", Settings.disableQuic)
        Settings.excludeCnQuic = bool("exclude_cn_quic", Settings.excludeCnQuic)
        Settings.webrtcProtect = bool("webrtc_protect", Settings.webrtcProtect)
        Settings.onDemand = bool("on_demand", Settings.onDemand)
        Settings.configNormalize = bool("config_normalize", Settings.configNormalize)
        Settings.autoRedirect = bool("auto_redirect", Settings.autoRedirect)

        val webdav = root.optJSONObject("webdav")
        if (webdav != null) {
            val url = webdav.optString("url").trim()
            val user = webdav.optString("user").trim()
            val remote = webdav.optString("remote_file").trim()
            if (url.isNotEmpty()) Settings.webdavUrl = url
            if (user.isNotEmpty()) Settings.webdavUser = user
            if (remote.isNotEmpty()) Settings.webdavRemoteFile = remote
        }

        val scripts = root.optJSONArray("overlay_scripts")
        if (scripts != null) {
            Settings.overlayScriptsJson = scripts.toString()
        }

        val chainArray = root.optJSONArray("chain_bindings") ?: JSONArray()
        val chain = linkedMapOf<Long, ChainBinding>()
        for (i in 0 until chainArray.length()) {
            val item = chainArray.optJSONObject(i) ?: continue
            val profileId = uuidToLocal[item.optString("profile_id")] ?: continue
            val landingId = uuidToLocal[item.optString("landing_profile_id")] ?: continue
            val landingTag = item.optString("landing_tag").trim()
            if (landingTag.isEmpty()) continue
            chain[profileId] = ChainBinding(
                profileId = profileId,
                entryTag = item.optString("entry_tag").trim(),
                landingProfileId = landingId,
                landingTag = landingTag,
            )
        }
        Settings.chainBindingsJson = ChainBindingCodec.encode(chain)
        Settings.chainEnabled = chain.isNotEmpty()

        val scriptBindings = root.optJSONObject("overlay_script_bindings") ?: JSONObject()
        val localBindings = linkedMapOf<Long, List<String>>()
        val keys = scriptBindings.keys()
        while (keys.hasNext()) {
            val uuid = keys.next()
            val local = uuidToLocal[uuid] ?: continue
            val array = scriptBindings.optJSONArray(uuid) ?: continue
            val ids = buildList {
                for (i in 0 until array.length()) {
                    val id = array.optString(i).trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
            localBindings[local] = ids
        }
        Settings.overlayScriptBindingsJson = OverlayScripts.encodeBindings(localBindings)
    }

    fun importStableIds(profiles: List<PortableProfile>, uuidToLocal: Map<String, Long>) {
        uuidToLocal.forEach { (uuid, local) ->
            ProfileStableIds.put(local, uuid)
        }
        profiles.forEach { profile ->
            val local = uuidToLocal[profile.id] ?: return@forEach
            ProfileStableIds.put(local, profile.id)
        }
    }
}
