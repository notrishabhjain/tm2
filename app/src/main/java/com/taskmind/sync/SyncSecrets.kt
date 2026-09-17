package com.taskmind.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The sync credentials, in keystore-backed encrypted storage.
 *
 * This deliberately mirrors [com.taskmind.data.settings.SecretStore] rather
 * than extending it. That class exposes two named properties and nothing
 * generic, so holding the sync token there would mean editing it - and the
 * capture-to-task backend it belongs to is frozen at the version that works.
 * Duplicating thirty lines is the cheaper mistake.
 *
 * A refresh token, not a password. Signing in happens once; after that the app
 * holds a token that can be revoked from the Supabase dashboard without
 * changing the account password, and a stolen phone yields no password to try
 * anywhere else.
 */
class SyncSecrets(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openOrRecreate() }

    /**
     * After a restore to a new device the master key is gone and the old
     * ciphertext is unreadable. Wipe rather than crash on every launch: the
     * user signs in again, which is a minor annoyance, where a boot loop is
     * not recoverable from the phone.
     */
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

    /**
     * Supabase's anon key. Public by design - it ships in the browser bundle
     * of every Supabase web app - but there is no reason to leave it
     * somewhere a log dump would pick it up.
     */
    var anonKey: String
        get() = prefs.getString(KEY_ANON, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ANON, value.trim()).apply()

    var refreshToken: String
        get() = prefs.getString(KEY_REFRESH, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_REFRESH, value.trim()).apply()

    /**
     * Held in memory only. It expires within the hour, so persisting it buys
     * nothing and widens what a backup extraction would yield.
     */
    @Volatile
    var accessToken: String = ""

    @Volatile
    var accessTokenExpiresAt: Long = 0L

    fun signedIn(): Boolean = refreshToken.isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
        accessToken = ""
        accessTokenExpiresAt = 0L
    }

    private companion object {
        const val FILE = "taskmind_sync_secrets"
        const val FALLBACK_FILE = "taskmind_sync_secrets_plain"
        const val KEY_ANON = "anon_key"
        const val KEY_REFRESH = "refresh_token"
    }
}
