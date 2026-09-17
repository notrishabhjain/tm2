package com.taskmind.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_sync")

/**
 * Where sync points, and how the last attempt went.
 *
 * Its own store rather than a few more keys on [com.taskmind.data.settings.SettingsRepository]:
 * that file belongs to the frozen backend, and sync is a separate concern with
 * a separate lifetime - signing out should clear all of this and none of that.
 *
 * Nothing secret lives here. The refresh token and anon key are in
 * [SyncSecrets].
 */
class SyncStore(private val context: Context) {

    private object K {
        val enabled = booleanPreferencesKey("enabled")

        /** e.g. https://abcdefgh.supabase.co - no trailing slash, enforced on write. */
        val projectUrl = stringPreferencesKey("project_url")

        /** Shown on the settings screen so you can tell which account is paired. */
        val email = stringPreferencesKey("email")

        /**
         * The high-water mark: every task whose updatedAt is at or below this
         * has been accepted by the server.
         *
         * Advanced ONLY after a whole push succeeds. Advancing it per-chunk
         * would silently strand every row in a chunk that failed after an
         * earlier one had moved the mark past it.
         */
        val pushedThrough = longPreferencesKey("pushed_through")

        /**
         * The newest `web_updated_at` already applied from the browser.
         *
         * Separate from [pushedThrough] because they measure different
         * clocks: one is the phone's own edit times, the other is the
         * browser's. Sharing a single mark would make a busy phone skip
         * browser edits, or a busy browser make the phone re-send everything.
         */
        val pulledThrough = stringPreferencesKey("pulled_through")

        /**
         * What the phone last sent, as a shape rather than a time.
         *
         * The push only sends tasks that changed since the watermark, so a
         * release that adds a column would leave every existing task without
         * it until something happened to touch that task - which, for a task
         * finished last month, is never. Bumping this forces one full re-send.
         */
        val pushSchema = intPreferencesKey("push_schema")

        val lastAttemptAt = longPreferencesKey("last_attempt_at")
        val lastSuccessAt = longPreferencesKey("last_success_at")
        val lastResult = stringPreferencesKey("last_result")
        val lastPushedCount = longPreferencesKey("last_pushed_count")
    }

    data class State(
        val enabled: Boolean = false,
        val projectUrl: String = "",
        val email: String = "",
        val pushedThrough: Long = 0L,
        /** ISO-8601, or empty for "never pulled". Compared as a string, which
         *  is safe because every value comes from Postgres in UTC. */
        val pulledThrough: String = "",
        val pushSchema: Int = 0,
        val lastAttemptAt: Long = 0L,
        val lastSuccessAt: Long = 0L,
        val lastResult: String = "",
        val lastPushedCount: Int = 0,
    ) {
        val configured: Boolean get() = projectUrl.isNotBlank()
    }

    val state: Flow<State> = context.syncDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            State(
                enabled = p[K.enabled] ?: false,
                projectUrl = p[K.projectUrl].orEmpty(),
                email = p[K.email].orEmpty(),
                pushedThrough = p[K.pushedThrough] ?: 0L,
                pulledThrough = p[K.pulledThrough].orEmpty(),
                pushSchema = p[K.pushSchema] ?: 0,
                lastAttemptAt = p[K.lastAttemptAt] ?: 0L,
                lastSuccessAt = p[K.lastSuccessAt] ?: 0L,
                lastResult = p[K.lastResult].orEmpty(),
                lastPushedCount = (p[K.lastPushedCount] ?: 0L).toInt(),
            )
        }

    suspend fun current(): State = state.first()

    suspend fun setEnabled(value: Boolean) {
        context.syncDataStore.edit { it[K.enabled] = value }
    }

    /** Trailing slashes are stripped here so no call site has to remember. */
    suspend fun setProject(url: String, email: String) {
        context.syncDataStore.edit {
            it[K.projectUrl] = url.trim().trimEnd('/')
            it[K.email] = email.trim()
        }
    }

    suspend fun recordAttempt(at: Long) {
        context.syncDataStore.edit { it[K.lastAttemptAt] = at }
    }

    /**
     * Re-sends everything once when the shape of a push has changed.
     *
     * Returns true when it did, so the caller can re-read the state it had
     * already loaded. Cheap on a quiet install and correct on a busy one -
     * the alternative is a column that is silently empty for older tasks and
     * a user wondering why half their tasks have no tags.
     */
    suspend fun migratePushSchemaIfNeeded(): Boolean {
        if (current().pushSchema >= PUSH_SCHEMA) return false
        context.syncDataStore.edit {
            it[K.pushedThrough] = 0L
            it[K.pushSchema] = PUSH_SCHEMA
        }
        return true
    }

    /** Advanced only after every pulled row has been applied. */
    suspend fun recordPulled(through: String) {
        if (through.isBlank()) return
        context.syncDataStore.edit { it[K.pulledThrough] = through }
    }

    suspend fun recordSuccess(at: Long, pushedThrough: Long, count: Int, message: String) {
        context.syncDataStore.edit {
            it[K.lastSuccessAt] = at
            it[K.pushedThrough] = pushedThrough
            it[K.lastPushedCount] = count.toLong()
            it[K.lastResult] = message
        }
    }

    suspend fun recordFailure(message: String) {
        context.syncDataStore.edit { it[K.lastResult] = message }
    }

    /**
     * Resets the watermark so the next run re-sends everything.
     *
     * The escape hatch for "the web app is missing things and I cannot tell
     * why" - which, without this, would mean reinstalling the app.
     */
    suspend fun forceFullResync() {
        context.syncDataStore.edit { it[K.pushedThrough] = 0L }
    }

    /**
     * Re-applies every browser edit from the beginning.
     *
     * Safe to do: applying an edit twice sets the same fields to the same
     * values, and the last-write-wins comparison skips anything the phone has
     * since changed.
     */
    suspend fun forceFullRepull() {
        context.syncDataStore.edit { it.remove(K.pulledThrough) }
    }

    suspend fun clear() {
        context.syncDataStore.edit { it.clear() }
    }

    companion object {
        /** Bump when a push starts sending a new column. 2 added `auto_tags`. */
        const val PUSH_SCHEMA = 2
    }
}
