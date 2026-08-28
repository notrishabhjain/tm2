package com.taskmind.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.runtimeDataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_runtime")

/**
 * State the app maintains about itself, as opposed to choices the user made.
 *
 * It is deliberately not part of [Settings]: nothing here appears on a settings
 * screen, a reset must not clear it, and it changes on a schedule the user has
 * no say in.
 *
 * The cooldowns exist because a rate limit is a fact about the provider, not
 * about the request that happened to discover it. Without somewhere to write
 * that down, every queued capture rediscovers the limit one paid request at a
 * time - which is exactly what emptied a day's quota on the device.
 */
class RuntimeStateStore(private val context: Context) {

    private object K {
        val llmCooldownUntil = longPreferencesKey("llm_cooldown_until")
        val llmCooldownReason = stringPreferencesKey("llm_cooldown_reason")
        val asrCooldownUntil = longPreferencesKey("asr_cooldown_until")
        val asrCooldownReason = stringPreferencesKey("asr_cooldown_reason")

        /**
         * The provider configuration that BLOCKED_CONFIG captures were parked
         * against. When the live configuration stops matching this, the
         * settings have changed and those captures are worth another try.
         */
        val blockedConfigFingerprint = stringPreferencesKey("blocked_config_fingerprint")
    }

    data class Cooldown(val until: Long, val reason: String) {
        fun activeAt(now: Long): Boolean = until > now
        fun remainingMillis(now: Long): Long = (until - now).coerceAtLeast(0)
    }

    val state: Flow<RuntimeState> = context.runtimeDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            RuntimeState(
                llmCooldown = Cooldown(
                    until = prefs[K.llmCooldownUntil] ?: 0L,
                    reason = prefs[K.llmCooldownReason].orEmpty(),
                ),
                asrCooldown = Cooldown(
                    until = prefs[K.asrCooldownUntil] ?: 0L,
                    reason = prefs[K.asrCooldownReason].orEmpty(),
                ),
                blockedConfigFingerprint = prefs[K.blockedConfigFingerprint].orEmpty(),
            )
        }

    suspend fun current(): RuntimeState = state.first()

    suspend fun startLlmCooldown(until: Long, reason: String) {
        context.runtimeDataStore.edit { prefs ->
            // Never shorten a cooldown already in force: a later request that
            // slipped through must not reopen the gate for the rest.
            val existing = prefs[K.llmCooldownUntil] ?: 0L
            if (until > existing) {
                prefs[K.llmCooldownUntil] = until
                prefs[K.llmCooldownReason] = reason
            }
        }
    }

    suspend fun startAsrCooldown(until: Long, reason: String) {
        context.runtimeDataStore.edit { prefs ->
            val existing = prefs[K.asrCooldownUntil] ?: 0L
            if (until > existing) {
                prefs[K.asrCooldownUntil] = until
                prefs[K.asrCooldownReason] = reason
            }
        }
    }

    /** Used when the user fixes the configuration and asks to try now. */
    suspend fun clearCooldowns() {
        context.runtimeDataStore.edit { prefs ->
            prefs.remove(K.llmCooldownUntil)
            prefs.remove(K.llmCooldownReason)
            prefs.remove(K.asrCooldownUntil)
            prefs.remove(K.asrCooldownReason)
        }
    }

    suspend fun setBlockedConfigFingerprint(fingerprint: String) {
        context.runtimeDataStore.edit { it[K.blockedConfigFingerprint] = fingerprint }
    }
}

data class RuntimeState(
    val llmCooldown: RuntimeStateStore.Cooldown = RuntimeStateStore.Cooldown(0L, ""),
    val asrCooldown: RuntimeStateStore.Cooldown = RuntimeStateStore.Cooldown(0L, ""),
    val blockedConfigFingerprint: String = "",
)
