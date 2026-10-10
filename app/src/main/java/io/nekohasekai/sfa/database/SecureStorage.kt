package io.nekohasekai.sfa.database

import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.nekohasekai.sfa.Application

/**
 * 敏感凭据的加密存储（AndroidX Security + Tink，密钥在 Android Keystore）。
 *
 * 覆盖：GitHub Token、WebDAV 密码。备份时仍排除凭据（PortableCloudBackup 不读这里）。
 */
object SecureStorage {
    private const val PREFS_NAME = "secure_credentials"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val context = Application.application
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val created = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs = created
            return created
        }
    }

    fun get(key: String): String? = runCatching { prefs().getString(key, null) }.getOrNull()

    fun set(key: String, value: String) {
        runCatching { prefs().edit().putString(key, value).apply() }
    }

    fun remove(key: String) {
        runCatching { prefs().edit().remove(key).apply() }
    }

    fun contains(key: String): Boolean = runCatching { prefs().contains(key) }.getOrDefault(false)
}
