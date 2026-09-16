package io.nekohasekai.sfa.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.SSLCertificateSocketFactory
import android.util.Base64
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Path
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

object BackupManager {
    private const val MANIFEST = "manifest.json"
    private const val VERSION = 2
    private const val MAX_ENTRIES = 512
    private const val MAX_ENTRY_SIZE = 32L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 128L * 1024 * 1024

    private val systemSslSocketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        try {
            val store = KeyStore.getInstance("AndroidCAStore")
            store.load(null)
            tmf.init(store)
        } catch (_: Exception) {
            tmf.init(null as KeyStore?)
        }
        SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, SecureRandom()) }.socketFactory
    }

    fun createBackupFile(context: Context, dest: File): Result<File> = runCatching {
        dest.parentFile?.mkdirs()
        val savedDavPass = Settings.webdavPassword
        val savedToken = Settings.githubToken
        Settings.webdavPassword = ""
        Settings.githubToken = ""
        try {
            Thread.sleep(250)
            checkpoint(context, Path.SETTINGS_DATABASE_PATH)
            checkpoint(context, Path.PROFILES_DATABASE_PATH)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
                val manifest = JSONObject()
                    .put("version", VERSION)
                    .put("app", "chainbox")
                    .put("time", System.currentTimeMillis())
                    .put("secrets", "omitted")
                putEntry(zos, MANIFEST, manifest.toString().toByteArray())
                copyMainDbOnly(zos, context, Path.SETTINGS_DATABASE_PATH)
                copyMainDbOnly(zos, context, Path.PROFILES_DATABASE_PATH)
                val configs = File(context.filesDir, "configs")
                if (configs.isDirectory) {
                    configs.listFiles()?.forEach { f ->
                        if (f.isFile) putFile(zos, "configs/${f.name}", f)
                    }
                }
            }
        } finally {
            Settings.webdavPassword = savedDavPass
            Settings.githubToken = savedToken
        }
        dest
    }

    fun restoreBackupFile(context: Context, src: File, compat: Boolean = false): Result<Unit> = runCatching {
        if (!isZipFile(src)) {
            if (!compat) error("不是有效的 ZIP 备份（可能下到了网页错误页）。请重新备份后再恢复。")
        }
        val keepDav = Settings.webdavPassword
        val keepTok = Settings.githubToken
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
                        if (compat) break else error("备份包含过多文件")
                    }
                    val name = entry.name.trimStart('/')
                    if (!entry.isDirectory && name.isNotEmpty() && !name.contains("..") && !name.contains('\\')) {
                        val outFile = when {
                            name == MANIFEST -> File(staging, MANIFEST)
                            name.startsWith("configs/") -> {
                                val relative = name.removePrefix("configs/")
                                if (relative.isBlank() || relative.contains('/')) {
                                    if (compat) null else error("非法备份路径")
                                } else {
                                    File(staging, "configs").also { it.mkdirs() }.let { File(it, relative) }
                                }
                            }
                            name.endsWith(".db") && !name.contains('/') -> File(staging, name)
                            else -> null
                        }
                        if (outFile != null) {
                            if (name.endsWith(".db") || name.startsWith("configs/")) hasData = true
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
                                        if (compat) break else error("备份展开大小超过限制")
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
        } catch (e: Exception) {
            if (!compat) throw e
        }
        if (!hasData) error("备份里没有配置或数据库，无法恢复")

        if (compat) {
            mergeProfilesFromBackup(
                context,
                File(staging, Path.PROFILES_DATABASE_PATH),
                File(staging, "configs"),
            )
            staging.deleteRecursively()
            return@runCatching
        }

        runCatching { BoxService.stop() }
        Thread.sleep(350)
        Settings.closeDatabase()
        ProfileManager.closeDatabase()

        val settingsDb = File(staging, Path.SETTINGS_DATABASE_PATH)
        val profilesDb = File(staging, Path.PROFILES_DATABASE_PATH)
        if (settingsDb.isFile) {
            val dest = context.getDatabasePath(Path.SETTINGS_DATABASE_PATH)
            dest.parentFile?.mkdirs()
            deleteSidecars(dest)
            settingsDb.copyTo(dest, overwrite = true)
            deleteSidecars(dest)
        }
        if (profilesDb.isFile) {
            val dest = context.getDatabasePath(Path.PROFILES_DATABASE_PATH)
            dest.parentFile?.mkdirs()
            deleteSidecars(dest)
            profilesDb.copyTo(dest, overwrite = true)
            deleteSidecars(dest)
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
        staging.deleteRecursively()
    }

    /**
     * 兼容模式：把备份里的配置追加到当前列表。同名或同一远程 URL 的现有配置保留，
     * 不替换 settings / 不覆盖已有 JSON。
     */
    internal fun mergeProfilesFromBackup(context: Context, backupProfilesDb: File, backupConfigs: File) {
        if (!backupProfilesDb.isFile) return
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
                if (nameIdx < 0 || typedIdx < 0) return
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
                            autoUpdate = typed.autoUpdate
                            autoUpdateInterval = typed.autoUpdateInterval
                        }
                        val icon = if (iconIdx >= 0 && !c.isNull(iconIdx)) c.getString(iconIdx) else null
                        ProfileManager.create(
                            Profile(name = name, icon = icon, typed = imported),
                            andSelect = false,
                        )
                        names.add(name)
                        if (url.isNotEmpty()) urls.add(url)
                    }
                }
            }
        }
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
                require(isZipFile(localFile)) {
                    "下载内容不是 ZIP 备份（服务器可能返回了错误页）。请确认远程文件名「$remoteName」正确。"
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
                                val next = try { URL(target).let { URL(it, loc) } } catch (_: Exception) { null }
                                val nextUrl = next?.toString().orEmpty()
                                if (
                                    next == null ||
                                    next.protocol != "https" ||
                                    next.host != URL(target).host
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
                    try { conn.disconnect() } catch (_: Exception) {}
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
        val host = try { URL(baseUrl).host } catch (_: Exception) { baseUrl }
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

    private inline fun <T> wrapSsl(block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            throw friendlySsl(e)
        }
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
        val conn = openConnection(URL(requireHttps(url)))
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

    private fun openConnection(url: URL): HttpsURLConnection {
        require(url.protocol.equals("https", ignoreCase = true)) { "WebDAV 必须使用 HTTPS" }
        val conn = url.openConnection()
        require(conn is HttpsURLConnection) { "WebDAV 必须使用 HTTPS" }
        conn.sslSocketFactory = platformSslSocketFactory()
        conn.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        return conn
    }

    @Suppress("DEPRECATION")
    private fun platformSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        return try {
            SSLCertificateSocketFactory.getDefault(15_000, null)
        } catch (_: Exception) {
            try {
                HttpsURLConnection.getDefaultSSLSocketFactory()
            } catch (_: Exception) {
                systemSslSocketFactory
            }
        }
    }

    private fun requireHttps(url: String): String {
        val normalized = url.trim()
        require(normalized.startsWith("https://", ignoreCase = true)) { "WebDAV 必须使用 HTTPS" }
        RemoteUrlGuard.requireAllowed(normalized, RemoteUrlGuard.Kind.SUBSCRIPTION)
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

    private fun copyMainDbOnly(zos: ZipOutputStream, context: Context, dbName: String) {
        val main = context.getDatabasePath(dbName)
        if (main.isFile) putFile(zos, dbName, main)
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
