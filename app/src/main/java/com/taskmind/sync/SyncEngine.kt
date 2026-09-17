package com.taskmind.sync

import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.data.db.entity.ReviewItemEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Pushes tasks and pending review items to Supabase so the web app can show
 * them. One direction only, for now: the phone is the source of truth and the
 * browser is a window onto it.
 *
 * WHAT TRAVELS
 *
 * Tasks and review items, with the evidence quote that justified each one.
 * Deliberately left behind: [ReviewItemEntity.sourceText] and every raw
 * capture - the full message bodies and call transcripts. Those are the
 * sensitive half of this app's data and they do not leave the device. The
 * server has no column for them, so a mistake here cannot quietly start
 * sending them.
 *
 * Also left behind: `sourceRef`, `rawCaptureId` and `titleKey`. They are
 * internal plumbing, they identify a message or a call, and nothing on the web
 * needs them.
 *
 * HOW IT DECIDES WHAT TO SEND
 *
 * A high-water mark over `updatedAt`, held in [SyncStore] and advanced only
 * once a whole run has succeeded. So a push that dies halfway is re-sent in
 * full next time rather than leaving a hole, and a quiet day costs one
 * request that sends nothing.
 *
 * This reads through the DAO surface that already exists and writes nothing
 * locally except its own bookkeeping. The capture-to-task pipeline does not
 * know it is here.
 */
class SyncEngine(
    private val container: AppContainer,
    private val store: SyncStore,
    private val secrets: SyncSecrets,
    private val api: SupabaseApi = SupabaseApi(container.httpClient),
) {

    sealed interface Result {
        data class Pushed(val tasks: Int, val reviews: Int) : Result
        object Skipped : Result
        data class Failed(val message: String, val retryable: Boolean) : Result
    }

    suspend fun run(force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val state = store.current()

        if (!state.enabled && !force) return@withContext Result.Skipped
        if (!state.configured) return@withContext fail("Sync is not set up yet.", retryable = false)
        if (!secrets.signedIn()) return@withContext fail("Not signed in to the web account.", retryable = false)

        val now = System.currentTimeMillis()
        store.recordAttempt(now)

        val token = accessToken(state.projectUrl)
            ?: return@withContext fail(store.current().lastResult.ifBlank { "Could not sign in." }, retryable = true)

        // ---- tasks --------------------------------------------------------

        val allTasks = container.database.taskDao().allForExport()
        val changed = allTasks.filter { it.updatedAt > state.pushedThrough }

        val taskPush = api.upsert(
            state.projectUrl, secrets.anonKey, token, "tasks",
            JsonArray(changed.map(::taskJson)),
        )
        if (taskPush is SupabaseApi.Outcome.Failed) return@withContext fail(taskPush.message, taskPush.retryable)

        // Tombstones. The phone marks a task DELETED rather than removing the
        // row, so the id is still here to tell the server about.
        val deleted = container.database.taskDao().deletedIds()
        val taskDelete = api.deleteIds(state.projectUrl, secrets.anonKey, token, "tasks", deleted)
        if (taskDelete is SupabaseApi.Outcome.Failed) return@withContext fail(taskDelete.message, taskDelete.retryable)

        // ---- review items -------------------------------------------------
        //
        // Pending only, and the server is made to match exactly: anything it
        // holds that is no longer pending here has been approved or rejected
        // on the phone, and showing it as still waiting would be a lie.

        val pending = container.database.reviewItemDao().observePending().first()

        val reviewPush = api.upsert(
            state.projectUrl, secrets.anonKey, token, "review_items",
            JsonArray(pending.map(::reviewJson)),
        )
        if (reviewPush is SupabaseApi.Outcome.Failed) return@withContext fail(reviewPush.message, reviewPush.retryable)

        val reviewPrune = api.deleteNotIn(
            state.projectUrl, secrets.anonKey, token, "review_items", pending.map { it.id },
        )
        if (reviewPrune is SupabaseApi.Outcome.Failed) return@withContext fail(reviewPrune.message, reviewPrune.retryable)

        // ---- done ---------------------------------------------------------
        //
        // The mark moves to the newest row actually sent, not to "now". If a
        // task is written while this run is in flight, its updatedAt is above
        // the mark and the next run picks it up.
        val mark = changed.maxOfOrNull { it.updatedAt } ?: state.pushedThrough
        val summary = if (changed.isEmpty() && deleted.isEmpty()) {
            "Up to date - nothing had changed."
        } else {
            "Sent ${changed.size} task(s), ${pending.size} awaiting review."
        }
        store.recordSuccess(System.currentTimeMillis(), mark, changed.size, summary)
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "Web sync: $summary")

        Result.Pushed(changed.size, pending.size)
    }

    /**
     * A usable access token, refreshing if the one in hand has aged out.
     *
     * Access tokens last an hour and sync runs for months, so the refresh path
     * is the normal path, not the exception.
     */
    private suspend fun accessToken(projectUrl: String): String? {
        val held = secrets.accessToken
        if (held.isNotBlank() && System.currentTimeMillis() < secrets.accessTokenExpiresAt) return held

        return when (val out = api.refresh(projectUrl, secrets.anonKey, secrets.refreshToken)) {
            is SupabaseApi.Outcome.Ok -> {
                secrets.accessToken = out.value.accessToken
                secrets.accessTokenExpiresAt = out.value.expiresAt
                // Supabase rotates refresh tokens; keeping the old one would
                // work exactly once more and then lock the phone out.
                secrets.refreshToken = out.value.refreshToken
                out.value.accessToken
            }
            is SupabaseApi.Outcome.Failed -> {
                store.recordFailure("Sign-in expired: ${out.message}")
                null
            }
        }
    }

    private suspend fun fail(message: String, retryable: Boolean): Result {
        store.recordFailure(message)
        container.logger.write(Stage.SYSTEM, LogLevel.WARN, "Web sync failed: $message")
        return Result.Failed(message, retryable)
    }

    // -- mapping ------------------------------------------------------------

    private fun taskJson(task: TaskEntity): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(task.id),
            "title" to JsonPrimitive(task.title),
            "notes" to str(task.notes),
            "due_at" to iso(task.dueAt),
            "reminder_at" to iso(task.reminderAt),
            "priority" to JsonPrimitive(task.priority.name),
            "status" to JsonPrimitive(task.status.name),
            "tags" to JsonArray(task.tags.map { JsonPrimitive(it) }),
            "recurrence_rule" to str(task.recurrenceRule),
            "parent_task_id" to str(task.parentTaskId),
            "source_type" to JsonPrimitive(task.sourceType.name),
            "source_label" to str(task.sourceLabel),
            "source_app" to str(task.sourceApp),
            "evidence" to str(task.evidence),
            "confidence" to (task.confidence?.let { JsonPrimitive(it) } ?: JsonNull),
            "inference_origin" to str(task.inferenceOrigin),
            "completed_at" to iso(task.completedAt),
            "created_at" to JsonPrimitive(isoString(task.createdAt)),
            "updated_at" to JsonPrimitive(isoString(task.updatedAt)),
        ),
    )

    private fun reviewJson(item: ReviewItemEntity): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(item.id),
            "title" to JsonPrimitive(item.title),
            "notes" to str(item.notes),
            "due_at" to iso(item.dueAt),
            "priority" to JsonPrimitive(item.priority.name),
            "evidence" to str(item.evidence),
            "confidence" to (item.confidence?.let { JsonPrimitive(it) } ?: JsonNull),
            "reasoning" to str(item.reasoning),
            "source_type" to JsonPrimitive(item.sourceType.name),
            "source_label" to str(item.sourceLabel),
            "source_app" to str(item.sourceApp),
            "inference_origin" to str(item.inferenceOrigin),
            "occurred_at" to JsonPrimitive(isoString(item.occurredAt)),
            "state" to JsonPrimitive(item.state.name),
            "created_at" to JsonPrimitive(isoString(item.createdAt)),
        ),
    )

    private fun str(value: String?): JsonElement =
        if (value.isNullOrBlank()) JsonNull else JsonPrimitive(value)

    private fun iso(millis: Long?): JsonElement =
        if (millis == null) JsonNull else JsonPrimitive(isoString(millis))

    /** UTC on the wire; the web app renders in IST, as the phone does. */
    private fun isoString(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
