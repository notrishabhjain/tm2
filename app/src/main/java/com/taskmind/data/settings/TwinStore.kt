package com.taskmind.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.twinDataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_twin")

/** The twin's small memory: who was nudged when, and whether it may search messages. */
class TwinStore(private val context: Context) {

    data class State(
        /** Task id to when a nudge for it was last sent. */
        val nudgedAt: Map<String, Long> = emptyMap(),
        /** Search the phone's messages for snippets relevant to a question. */
        val recall: Boolean = true,
        /** Offer a nudge notification when someone's promise has aged. */
        val chaseNotifications: Boolean = true,
    )

    private object K {
        val nudged = stringSetPreferencesKey("nudged")
        val recall = booleanPreferencesKey("recall")
        val chase = booleanPreferencesKey("chase_notifications")
    }

    val state: Flow<State> = context.twinDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            State(
                nudgedAt = (p[K.nudged] ?: emptySet()).mapNotNull { raw ->
                    val id = raw.substringBefore('\t')
                    raw.substringAfter('\t', "").toLongOrNull()?.let { id to it }
                }.toMap(),
                recall = p[K.recall] ?: true,
                chaseNotifications = p[K.chase] ?: true,
            )
        }

    suspend fun current(): State = state.first()

    suspend fun markNudged(taskId: String, at: Long = System.currentTimeMillis()) {
        context.twinDataStore.edit { p ->
            val cutoff = at - KEEP_MILLIS
            val kept = (p[K.nudged] ?: emptySet()).filter { raw ->
                raw.substringBefore('\t') != taskId && (raw.substringAfter('\t', "").toLongOrNull() ?: 0L) > cutoff
            }
            p[K.nudged] = (kept + "$taskId\t$at").toSet()
        }
    }

    /** "Erase everything": the tasks these were about are gone. The twin's switches stay. */
    suspend fun clearNudged() {
        context.twinDataStore.edit { it.remove(K.nudged) }
    }

    suspend fun setRecall(on: Boolean) = context.twinDataStore.edit { it[K.recall] = on }
    suspend fun setChaseNotifications(on: Boolean) = context.twinDataStore.edit { it[K.chase] = on }

    private companion object {
        const val KEEP_MILLIS = 60L * 24 * 60 * 60 * 1000
    }
}
