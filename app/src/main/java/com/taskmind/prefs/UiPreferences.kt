package com.taskmind.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.uiDataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_ui")

/**
 * Choices about how the app behaves, as opposed to how it captures.
 *
 * Its own store rather than more keys on the settings repository, for the same
 * reason the sync package has one: that file belongs to the frozen capture
 * engine, and none of this has anything to do with capture.
 */
class UiPreferences(private val context: Context) {

    /** What a swipe does to a task row. */
    enum class SwipeAction(val label: String) {
        COMPLETE("Complete"),
        ARCHIVE("Archive"),
        DELETE("Delete"),
        SNOOZE("Snooze a day"),
        NONE("Nothing"),
    }

    private object K {
        val swipeRight = stringPreferencesKey("swipe_right")
        val swipeLeft = stringPreferencesKey("swipe_left")
        val ongoingReminder = booleanPreferencesKey("ongoing_reminder")
    }

    data class State(
        /** Swiping left-to-right. Completing is the one people reach for. */
        val swipeRight: SwipeAction = SwipeAction.COMPLETE,
        /** Right-to-left. Archive rather than delete: undoing a mis-swipe
         *  should not depend on catching a snackbar in time. */
        val swipeLeft: SwipeAction = SwipeAction.ARCHIVE,
        val ongoingReminder: Boolean = false,
    )

    val state: Flow<State> = context.uiDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            State(
                swipeRight = p[K.swipeRight].toAction(SwipeAction.COMPLETE),
                swipeLeft = p[K.swipeLeft].toAction(SwipeAction.ARCHIVE),
                ongoingReminder = p[K.ongoingReminder] ?: false,
            )
        }

    suspend fun current(): State = state.first()

    suspend fun setSwipeRight(action: SwipeAction) {
        context.uiDataStore.edit { it[K.swipeRight] = action.name }
    }

    suspend fun setSwipeLeft(action: SwipeAction) {
        context.uiDataStore.edit { it[K.swipeLeft] = action.name }
    }

    suspend fun setOngoingReminder(enabled: Boolean) {
        context.uiDataStore.edit { it[K.ongoingReminder] = enabled }
    }

    /** An unknown name falls back rather than crashing - a stored value can
     *  outlive the enum it came from. */
    private fun String?.toAction(fallback: SwipeAction): SwipeAction =
        this?.let { name -> SwipeAction.entries.firstOrNull { it.name == name } } ?: fallback
}
