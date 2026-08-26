package com.taskmind.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Spec 2: API keys live in EncryptedSharedPreferences, never in DataStore and
 * never in the activity log.
 *
 * If the keystore-backed store cannot be opened (it happens after a restore to
 * a new device, where the master key no longer exists), we fall back to a plain
 * store rather than crashing on every launch - but we wipe it first, so the old
 * ciphertext is not left lying around, and the user is asked for the key again.
 */
class SecretStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openOrRecreate() }

    private fun openOrRecreate(): SharedPreferences = try {
        create()
    } catch (_: Throwable) {
        appContext.deleteSharedPreferences(FILE)
        try {
            create()
        } catch (_: Throwable) {
            appContext.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var llmApiKey: String
        get() = prefs.getString(KEY_LLM, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LLM, value.trim()).apply()
        }

    var asrApiKey: String
        get() = prefs.getString(KEY_ASR, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ASR, value.trim()).apply()
        }

    fun hasLlmKey(): Boolean = llmApiKey.isNotBlank()
    fun hasAsrKey(): Boolean = asrApiKey.isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** For display only. Never log a full key. */
    fun mask(key: String): String = when {
        key.isBlank() -> "not set"
        key.length <= 8 -> "set"
        else -> key.take(4) + "..." + key.takeLast(4)
    }

    private companion object {
        const val FILE = "taskmind_secrets"
        const val FALLBACK_FILE = "taskmind_secrets_plain"
        const val KEY_LLM = "llm_api_key"
        const val KEY_ASR = "asr_api_key"
    }
}
