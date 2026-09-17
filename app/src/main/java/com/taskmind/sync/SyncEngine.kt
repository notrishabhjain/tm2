package com.taskmind.sync

import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.data.db.entity.ReviewItemEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.tagging.AutoTagger
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
 * Keeps the phone and the web mirror in step, in both directions.
 *
 * The phone is still where a task really lives. The browser records what you
 * did - edited this, approved that, added one - and the phone carries it out
 * through the same repository methods its own buttons call, so a recurring
 * task completed in a browser still spawns its next instance and an approved
 * candidate still goes through the intake funnel.
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
 * request that sends nothing. Incoming edits have their own mark over
 * `web_updated_at`, a column only the browser ever writes - see [SyncPull].
 *
 * WHEN BOTH SIDES EDITED THE SAME TASK
 *
 * The later edit wins, compared honestly: the browser's `web_updated_at`
 * against the task's own `updatedAt`. There is no merge and no prompt. For one
 * person with a phone and a laptop that is the right trade, but it does mean
 * editing the same task in both places within one sync window loses one of
 * them, with nothing to say so.
 *
 * Everything here goes through the repository and DAO surface that already
 * existed. The capture-to-task pipeline does not know it is here.
 */
class SyncEngine(
    private val container: AppContainer,
    private val store: SyncStore,
    private val secrets: SyncSecrets,
    private val api: SupabaseApi = SupabaseApi(container.httpClient),
) {

    sealed interface Result {
        data class Pushed(
            val tasks: Int,
            val reviews: Int,
            val pulled: SyncPull.Applied = SyncPull.Applied(0, 0, 0, 0),
        ) : Result
        object Skipped : Result
        data class Failed(val message: String, val retryable: Boolean) : Result
    }

    suspend fun run(force: Boolean = false): Result = withContext(Dispatchers.IO) {
        // Before anything else: if this build sends a column the last one did
        // not, re-send everything once so older tasks are not left behind with
        // it empty.
        val migrated = store.migratePushSchemaIfNeeded()
        if (migrated) {
            container.logger.write(
                Stage.SYSTEM,
                LogLevel.INFO,
                "Web sync: re-sending everything once, the push now carries tags",
            )
        }
        val state = store.current()

        if (!state.enabled && !force) return@withContext Result.Skipped
        if (!state.configured) return@withContext fail("Sync is not set up yet.", retryable = false)
        if (!secrets.signedIn()) return@withContext fail("Not signed in to the web account.", retryable = false)

        val now = System.currentTimeMillis()
        store.recordAttempt(now)

        val token = accessToken(state.projectUrl)
            ?: return@withContext fail(store.current().lastResult.ifBlank { "Could not sign in." }, retryable = true)

        // ---- pull, THEN push ----------------------------------------------
        //
        // The order is the whole trick, and getting it backwards silently
        // loses work: pushing first would overwrite the browser's edit with
        // the phone's older copy, and the pull that followed would then read
        // back the row the push had just flattened. Pulling first merges the
        // edit into the local task, so the push sends the merged result up.

        val pulled = when (val out = SyncPull(container, store, api, state.projectUrl, secrets.anonKey, token).run()) {
            is SupabaseApi.Outcome.Ok -> out.value
            is SupabaseApi.Outcome.Failed -> return@withContext fail(out.message, out.retryable)
        }

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
        val summary = buildList {
            if (pulled.created > 0) add("added ${pulled.created} from the browser")
            if (pulled.decided > 0) add("applied ${pulled.decided} review decision(s)")
            if (pulled.edited > 0) add("applied ${pulled.edited} browser edit(s)")
            if (changed.isNotEmpty()) add("sent ${changed.size} task(s)")
            if (isEmpty()) add("up to date - nothing had changed")
        }.joinToString(", ").replaceFirstChar { it.uppercase() } + "."

        store.recordSuccess(System.currentTimeMillis(), mark, changed.size, summary)
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "Web sync: $summary")

        Result.Pushed(changed.size, pending.size, pulled)
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
            // Derived here rather than in the browser so the rules live in one
            // place. Recomputed on every push, so changing a rule re-tags
            // everything the next time a task moves.
            "auto_tags" to JsonArray(
                AutoTagger.keys(
                    sourceType = task.sourceType,
                    sourceApp = task.sourceApp,
                    sourceLabel = task.sourceLabel,
                    title = task.title,
                    evidence = task.evidence,
                ).map { JsonPrimitive(it) },
            ),
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
