package com.taskmind.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.taskmind.core.PromptKind
import com.taskmind.core.PromptSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.promptDataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_prompts")

/**
 * User overrides for the system prompts.
 *
 * Kept in their own DataStore file rather than in [SettingsRepository] because
 * these values are kilobytes of prose, they change rarely, and mixing them into
 * the settings flow would re-emit every screen that observes settings each time
 * a character is typed in the editor.
 *
 * Only overrides are stored. A prompt the user has not edited is not persisted
 * at all, so improvements to the defaults reach them on the next update rather
 * than being frozen at whatever shipped when they installed.
 */
class PromptStore(private val context: Context) {

    private fun key(kind: PromptKind) = stringPreferencesKey("prompt_${kind.key}")

    val prompts: Flow<PromptSet> = context.promptDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            PromptSet(
                notificationSystem = prefs[key(PromptKind.NOTIFICATION)]
                    ?: PromptKind.NOTIFICATION.defaultText(),
                callSystem = prefs[key(PromptKind.CALL)] ?: PromptKind.CALL.defaultText(),
                verifySystem = prefs[key(PromptKind.VERIFY)] ?: PromptKind.VERIFY.defaultText(),
            )
        }

    /** Which prompts the user has actually changed, for the "Edited" badge. */
    val overridden: Flow<Set<PromptKind>> = context.promptDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> PromptKind.entries.filter { prefs[key(it)] != null }.toSet() }

    suspend fun current(): PromptSet = prompts.first()

    suspend fun set(kind: PromptKind, text: String) {
        val trimmed = text.trim()
        context.promptDataStore.edit { prefs ->
            if (trimmed.isEmpty() || trimmed == kind.defaultText().trim()) {
                // Storing a copy of the default would freeze it. Drop the
                // override instead so future updates to the default apply.
                prefs.remove(key(kind))
            } else {
                prefs[key(kind)] = trimmed
            }
        }
    }

    suspend fun reset(kind: PromptKind) {
        context.promptDataStore.edit { it.remove(key(kind)) }
    }

    suspend fun resetAll() {
        context.promptDataStore.edit { prefs -> PromptKind.entries.forEach { prefs.remove(key(it)) } }
    }
}
