package com.taskmind.profiles

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_profiles")

/**
 * Who is work and who is personal, plus which profile you are looking at.
 *
 * Its own store rather than more keys on the settings repository, for the same
 * reason the sync and UI packages have one: that file belongs to the frozen
 * capture engine and none of this is capture configuration.
 *
 * Names are stored lower-cased, because that is how [ProfileRules.Book]
 * compares them and doing it once here means the matcher never has to.
 */
class ProfileStore(private val context: Context) {

    data class State(
        val book: ProfileRules.Book = ProfileRules.Book(),
        /** Null means "show everything", which is where a new install starts. */
        val viewing: Profile? = null,
    )

    val state: Flow<State> = context.profileDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            State(
                book = ProfileRules.Book(
                    personal = p[K.personal] ?: emptySet(),
                    work = p[K.work] ?: emptySet(),
                ),
                viewing = Profile.byName(p[K.viewing]),
            )
        }

    suspend fun current(): State = state.first()

    /**
     * A null profile un-classifies the subject.
     *
     * Removed from both sets first, so a name can never be in two at once and
     * the order [ProfileRules.Book.profileOf] checks them in stops mattering.
     */
    suspend fun classify(subject: String, profile: Profile?) {
        val key = subject.trim().lowercase()
        if (key.isEmpty()) return
        context.profileDataStore.edit { prefs ->
            prefs[K.personal] = (prefs[K.personal] ?: emptySet()) - key
            prefs[K.work] = (prefs[K.work] ?: emptySet()) - key
            when (profile) {
                Profile.PERSONAL -> prefs[K.personal] = (prefs[K.personal] ?: emptySet()) + key
                Profile.WORK -> prefs[K.work] = (prefs[K.work] ?: emptySet()) + key
                null -> Unit
            }
        }
    }

    suspend fun setViewing(profile: Profile?) {
        context.profileDataStore.edit { prefs ->
            if (profile == null) prefs.remove(K.viewing) else prefs[K.viewing] = profile.name
        }
    }

    private object K {
        val personal = stringSetPreferencesKey("personal_subjects")
        val work = stringSetPreferencesKey("work_subjects")
        val viewing = stringPreferencesKey("viewing")
    }
}
