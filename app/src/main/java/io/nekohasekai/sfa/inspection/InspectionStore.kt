package io.nekohasekai.sfa.inspection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All data is device-local and excluded from platform / application backups. */
object InspectionStore {
    data class AiConfig(val endpoint: String, val model: String, val key: String)
    val changes = MutableStateFlow(0L)
    private const val KEY_ALIAS = "angelabox-inspection-ai-v1"

    private fun file(context: Context, name: String) = AtomicFile(File(context.noBackupFilesDir, "inspection-$name.json"))
    private fun read(context: Context, name: String): JSONObject = try {
        val source = file(context, name)
        if (source.baseFile.length() > 64 * 1024) JSONObject() else JSONObject(source.openRead().bufferedReader().use { it.readText() })
    } catch (_: Exception) {
        JSONObject()
    }
    private fun write(context: Context, name: String, data: JSONObject) {
        val target = file(context, name)
        val stream = target.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            changes.value += 1
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }
    @Synchronized
    fun enabled(context: Context): Boolean = read(context, "settings").optBoolean("enabled", false)
    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) = write(context, "settings", JSONObject().put("enabled", enabled))
    @Synchronized
    fun latest(context: Context): InspectionPolicy.Snapshot? = runCatching {
        val json = read(context, "result")
        val facts = json.getJSONArray("facts")
        InspectionPolicy.Snapshot(
            json.getLong("at"), json.getLong("profile"), json.getBoolean("service"),
            (0 until facts.length()).map { index ->
                val fact = facts.getJSONObject(index)
                InspectionPolicy.normalized(InspectionPolicy.Fact(fact.getString("id"), InspectionPolicy.State.valueOf(fact.getString("state")), fact.getString("code")))
            },
            json.optLong("network", -1L).takeIf { it != -1L },
            json.optString("context"),
        )
    }.getOrNull()
    @Synchronized
    fun save(context: Context, snapshot: InspectionPolicy.Snapshot) = write(
        context, "result",
        JSONObject().put("at", snapshot.collectedAt).put("profile", snapshot.profileId).put("service", snapshot.serviceUp).put("network", snapshot.networkId ?: -1L).put("context", snapshot.contextToken)
            .put("facts", JSONArray().apply {
                snapshot.facts.map(InspectionPolicy::normalized).forEach {
                    put(JSONObject().put("id", it.id).put("state", it.state.name).put("code", it.code))
                }
            }),
    )
    @Synchronized
    fun clearResult(context: Context) {
        file(context, "result").delete()
        changes.value += 1
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized
    fun saveConfig(context: Context, config: AiConfig) {
        InspectionAi.validateConfig(config)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plain = JSONObject().put("endpoint", config.endpoint).put("model", config.model).put("key", config.key).toString()
        write(context, "ai", JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)))
    }
    @Synchronized
    fun config(context: Context): AiConfig? = runCatching {
        val data = read(context, "ai")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(data.getString("iv"), Base64.NO_WRAP)))
        val json = JSONObject(String(cipher.doFinal(Base64.decode(data.getString("ciphertext"), Base64.NO_WRAP)), Charsets.UTF_8))
        AiConfig(json.getString("endpoint"), json.getString("model"), json.getString("key"))
    }.getOrNull()
    @Synchronized
    fun clearConfig(context: Context) {
        file(context, "ai").delete()
        changes.value += 1
    }
}
