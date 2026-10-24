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
        DELETE("Delete"),
        SNOOZE("Snooze a day"),
        NONE("Nothing"),
    }

    private object K {
        val swipeRight = stringPreferencesKey("swipe_right")
        val swipeLeft = stringPreferencesKey("swipe_left")
        val ongoingReminder = booleanPreferencesKey("ongoing_reminder")
        val morningBrief = booleanPreferencesKey("morning_brief")
        val eveningBrief = booleanPreferencesKey("evening_brief")
        val weeklyReview = booleanPreferencesKey("weekly_review")
        val calendarInBrief = booleanPreferencesKey("calendar_in_brief")
    }

    data class State(
        /** Swiping left-to-right. Completing is the one people reach for. */
        val swipeRight: SwipeAction = SwipeAction.COMPLETE,
        /** Right-to-left. Snooze rather than delete: undoing a mis-swipe
         *  should not depend on catching a snackbar in time. Archive used to
         *  be the default; a stored "ARCHIVE" now falls back to this. */
        val swipeLeft: SwipeAction = SwipeAction.SNOOZE,
        val ongoingReminder: Boolean = false,
        /** 9:00 - today, late, waiting on. On by default: it is the twin
         *  speaking first, and it stays silent when there is nothing to say. */
        val morningBrief: Boolean = true,
        /** 19:00 - what slipped, with one tap to move it to tomorrow. */
        val eveningBrief: Boolean = true,
        /** Sunday 18:00 - done, new, oldest open, who asks most. */
        val weeklyReview: Boolean = true,
        /** Today's meetings at the top of the morning brief. Off until the
         *  person turns it on and grants the calendar permission. */
        val calendarInBrief: Boolean = false,
    )

    val state: Flow<State> = context.uiDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            State(
                swipeRight = p[K.swipeRight].toAction(SwipeAction.COMPLETE),
                swipeLeft = p[K.swipeLeft].toAction(SwipeAction.SNOOZE),
                ongoingReminder = p[K.ongoingReminder] ?: false,
                morningBrief = p[K.morningBrief] ?: true,
                eveningBrief = p[K.eveningBrief] ?: true,
                weeklyReview = p[K.weeklyReview] ?: true,
                calendarInBrief = p[K.calendarInBrief] ?: false,
            )
        }

    suspend fun current(): State = state.first()

    suspend fun setSwipeRight(action: SwipeAction) {
        context.uiDataStore.edit { it[K.swipeRight] = action.name }
    }

    suspend fun setSwipeLeft(action: SwipeAction) {
        context.uiDataStore.edit { it[K.swipeLeft] = action.name }
    }

    suspend fun setMorningBrief(enabled: Boolean) {
        context.uiDataStore.edit { it[K.morningBrief] = enabled }
    }

    suspend fun setWeeklyReview(enabled: Boolean) {
        context.uiDataStore.edit { it[K.weeklyReview] = enabled }
    }

    suspend fun setCalendarInBrief(enabled: Boolean) {
        context.uiDataStore.edit { it[K.calendarInBrief] = enabled }
    }

    suspend fun setEveningBrief(enabled: Boolean) {
        context.uiDataStore.edit { it[K.eveningBrief] = enabled }
    }

    suspend fun setOngoingReminder(enabled: Boolean) {
        context.uiDataStore.edit { it[K.ongoingReminder] = enabled }
    }

    /** An unknown name falls back rather than crashing - a stored value can
     *  outlive the enum it came from. */
    private fun String?.toAction(fallback: SwipeAction): SwipeAction =
        this?.let { name -> SwipeAction.entries.firstOrNull { it.name == name } } ?: fallback
}
