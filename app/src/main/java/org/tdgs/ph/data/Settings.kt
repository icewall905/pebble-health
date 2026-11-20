package org.tdgs.ph.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.store by preferencesDataStore("settings")

data class HubConfig(val baseUrl: String, val token: String)

data class Prefs(val stepGoal: Int, val sleepGoalH: Int, val theme: String)

/** Hub URL and preferences in DataStore; the API token is AES-GCM encrypted with a non-exportable Keystore key. */
class Settings(private val context: Context) {
    private val urlKey = stringPreferencesKey("hub_url")
    private val tokenKey = stringPreferencesKey("hub_token_enc")
    private val uploadTokenKey = stringPreferencesKey("workout_upload_token_enc")
    private val stepGoalKey = intPreferencesKey("step_goal")
    private val sleepGoalKey = intPreferencesKey("sleep_goal_h")
    private val themeKey = stringPreferencesKey("theme")

    val config: Flow<HubConfig?> = context.store.data.map { p ->
        val url = p[urlKey] ?: return@map null
        val token = p[tokenKey]?.let { runCatching { decrypt(it) }.getOrNull() } ?: return@map null
        HubConfig(url, token)
    }

    val workoutUploadConfig: Flow<HubConfig?> = context.store.data.map { p ->
        val url = p[urlKey] ?: return@map null
        val token = p[uploadTokenKey]?.let { runCatching { decrypt(it, UPLOAD_ALIAS) }.getOrNull() } ?: return@map null
        HubConfig(url, token)
    }

    suspend fun currentWorkoutUpload(): HubConfig? = workoutUploadConfig.first()

    suspend fun saveWorkoutUpload(expected: HubConfig, token: String) {
        require(token.isNotBlank() && token.length <= 200 && token.none(Char::isWhitespace))
        val encrypted = encrypt(token, UPLOAD_ALIAS)
        context.store.edit {
            check(it[urlKey] == expected.baseUrl && it[tokenKey]?.let { value -> decrypt(value) } == expected.token)
            it[uploadTokenKey] = encrypted
        }
    }

    suspend fun clearWorkoutUpload() = context.store.edit { it.remove(uploadTokenKey) }

    val prefs: Flow<Prefs> = context.store.data.map { p ->
        Prefs(p[stepGoalKey] ?: 8000, p[sleepGoalKey] ?: 8, p[themeKey] ?: "system")
    }

    suspend fun current(): HubConfig? = config.first()

    suspend fun save(config: HubConfig) {
        val encrypted = encrypt(config.token)
        context.store.edit {
            if (it[urlKey] != config.baseUrl || it[tokenKey]?.let { value ->
                    runCatching { decrypt(value) }.getOrNull()
                } != config.token
            ) it.remove(uploadTokenKey)
            it[urlKey] = config.baseUrl
            it[tokenKey] = encrypted
        }
    }

    suspend fun clear() = context.store.edit {
        it.remove(urlKey)
        it.remove(tokenKey)
        it.remove(uploadTokenKey)
    }

    suspend fun setStepGoal(v: Int) = context.store.edit { it[stepGoalKey] = v.coerceIn(1000, 50000) }
    suspend fun setSleepGoal(v: Int) = context.store.edit { it[sleepGoalKey] = v.coerceIn(4, 12) }
    suspend fun setTheme(v: String) = context.store.edit { it[themeKey] = v }

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String, alias: String = ALIAS): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(alias))
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String, alias: String = ALIAS): String {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    private companion object {
        const val ALIAS = "hub_token"
        const val UPLOAD_ALIAS = "workout_upload_token"
    }
}
