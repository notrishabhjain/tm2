package com.taskmind.sync

import com.taskmind.core.LogLevel
import com.taskmind.core.Priority
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.di.AppContainer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.time.OffsetDateTime

/**
 * Brings edits made in the browser back to the phone.
 *
 * Three separate things arrive, and they are handled differently on purpose:
 *
 *  - **New tasks** are drained from `web_new_tasks` and created through
 *    `createManualTask`, which goes through the intake funnel. Spec 5 says the
 *    funnel is the only thing that may create a task, and `ArchitectureTest`
 *    enforces it; the browser does not get an exception.
 *  - **Review decisions** are carried out with `acceptReviewItem` and
 *    `dismissReviewItem` - the same methods the phone's own Approve and Reject
 *    buttons call. The browser records what you decided; the phone does it.
 *  - **Task edits** are applied field by field, with status changes going
 *    through the repository so a completed recurring task still spawns its
 *    next instance exactly as it would on the phone.
 *
 * Nothing here writes to the database directly. Every change goes through
 * [com.taskmind.data.repo.TaskRepository], which is why the capture-to-task
 * engine needed no changes to support any of this.
 */
class SyncPull(
    private val container: AppContainer,
    private val store: SyncStore,
    private val api: SupabaseApi,
    private val projectUrl: String,
    private val anonKey: String,
    private val accessToken: String,
) {

    data class Applied(val created: Int, val decided: Int, val edited: Int, val skipped: Int) {
        val total: Int get() = created + decided + edited
        val nothing: Boolean get() = total == 0 && skipped == 0
    }

    /**
     * Each step stops the run if it fails, so a later step never operates on
     * half-applied state - and because every step is idempotent, the retry
     * simply starts again from the top.
     */
    suspend fun run(): SupabaseApi.Outcome<Applied> {
        val created = when (val out = drainNewTasks()) {
            is SupabaseApi.Outcome.Ok -> out.value
            is SupabaseApi.Outcome.Failed -> return out
        }
        val decided = when (val out = applyReviewDecisions()) {
            is SupabaseApi.Outcome.Ok -> out.value
            is SupabaseApi.Outcome.Failed -> return out
        }
        val edits = when (val out = applyTaskEdits()) {
            is SupabaseApi.Outcome.Ok -> out.value
            is SupabaseApi.Outcome.Failed -> return out
        }
        return SupabaseApi.Outcome.Ok(
            Applied(created = created, decided = decided, edited = edits.first, skipped = edits.second),
        )
    }

    // -- new tasks ----------------------------------------------------------

    /**
     * Each row is deleted immediately after its task is created, not in one
     * batch at the end.
     *
     * A manual task carries no sourceRef, and the funnel's unique index treats
     * NULLs as distinct, so manual tasks are allowed to repeat - which means
     * the funnel will NOT absorb a double-create the way it does for captured
     * ones. Deleting per row keeps the window where a crash could duplicate
     * something down to a single task instead of the whole batch.
     */
    private suspend fun drainNewTasks(): SupabaseApi.Outcome<Int> {
        val rows = when (val out = api.select(
            projectUrl, anonKey, accessToken, "web_new_tasks",
            "order=created_at.asc", NEW_TASK_LIMIT,
        )) {
            is SupabaseApi.Outcome.Ok -> out.value
            is SupabaseApi.Outcome.Failed -> return out
        }
        if (rows.isEmpty()) return SupabaseApi.Outcome.Ok(0)

        var made = 0
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val id = row.str("id") ?: continue
            val title = row.str("title")?.takeIf { it.isNotBlank() } ?: continue

            container.taskRepository.createManualTask(
                title = title,
                notes = row.str("notes"),
                dueAt = row.millis("due_at"),
                priority = row.priority("priority"),
            )
            made++

            val removed = api.deleteIds(projectUrl, anonKey, accessToken, "web_new_tasks", listOf(id))
            if (removed is SupabaseApi.Outcome.Failed) {
                // The task exists locally now. Leaving the row behind would
                // create it a second time on the next run, so stop here and
                // let the retry re-attempt the delete before anything else.
                return SupabaseApi.Outcome.Failed(
                    "Created $made task(s) but could not clear the queue: ${removed.message}",
                    removed.retryable,
                )
            }
        }
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "web sync: created $made task(s) from the browser")
        return SupabaseApi.Outcome.Ok(made)
    }

    // -- review decisions ---------------------------------------------------

    private suspend fun applyReviewDecisions(): SupabaseApi.Outcome<Int> {
        val rows = when (val out = api.select(
            projectUrl, anonKey, accessToken, "review_items",
            "web_decision=not.is.null&order=web_decided_at.asc", DECISION_LIMIT,
        )) {
            is SupabaseApi.Outcome.Ok -> out.value
            is SupabaseApi.Outcome.Failed -> return out
        }
        if (rows.isEmpty()) return SupabaseApi.Outcome.Ok(0)

        var done = 0
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val id = row.str("id") ?: continue
            when (row.str("web_decision")) {
                "APPROVED" -> container.taskRepository.acceptReviewItem(id)
                "REJECTED" -> container.taskRepository.dismissReviewItem(id)
                else -> continue
            }
            done++
        }

        // No cleanup call needed. These items are no longer PENDING locally,
        // and the push that follows mirrors pending items exactly - so it
        // removes them from the server as part of its normal work.
        container.logger.write(Stage.SYSTEM, LogLevel.INFO, "web sync: applied $done review decision(s)")
        return SupabaseApi.Outcome.Ok(done)
    }

    // -- task edits ---------------------------------------------------------

    /** Returns applied and skipped counts. */
    private suspend fun applyTaskEdits(): SupabaseApi.Outcome<Pair<Int, Int>> {
        val since = store.current().pulledThrough
        val filter = buildString {
            if (since.isBlank()) {
                append("web_updated_at=not.is.null")
            } else {
                // gte, not gt. The browser stamps these to the millisecond, so
                // two edits can share a timestamp - and `gt` would drop the
                // second one for good. `gte` re-reads the boundary row on
                // every run instead, which is one row and no lost work:
                // applying an edit twice sets the same fields to the same
                // values, and the comparison below skips it anyway.
                append("web_updated_at=gte.").append(URLEncoder.encode(since, "UTF-8"))
            }
            append("&order=web_updated_at.asc")
        }

        val rows = when (val out = api.select(
            projectUrl, anonKey, accessToken, "tasks", filter, EDIT_LIMIT,
        )) {
            is SupabaseApi.Outcome.Ok -> out.value
            is SupabaseApi.Outcome.Failed -> return out
        }
        if (rows.isEmpty()) return SupabaseApi.Outcome.Ok(0 to 0)

        var applied = 0
        var skipped = 0
        var watermark = since
        // Compared as instants, not as text. Postgres trims trailing zeros
        // from fractional seconds, so "…33.1+00:00" and "…33.05+00:00" sort
        // correctly as strings only by coincidence. The string is still what
        // gets stored, so the filter round-trips exactly.
        var newest = Long.MIN_VALUE

        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val id = row.str("id") ?: continue
            val editedAt = row.str("web_updated_at") ?: continue
            val editedMillis = row.millis("web_updated_at") ?: continue

            // Seen and decided, whether or not it was applied. Advancing past
            // a skipped row matters: leaving it behind would re-fetch it on
            // every run for as long as the phone's copy stays newer.
            if (editedMillis > newest) {
                newest = editedMillis
                watermark = editedAt
            }

            val local = container.taskRepository.byId(id)
            if (local == null) {
                // Gone on the phone - deleted here after the browser edited it.
                // The push removes it from the server; nothing to apply.
                skipped++
                continue
            }

            // Last write wins, honestly: whichever side edited later. Without
            // this the browser would always win simply by being applied second.
            if (local.updatedAt > editedMillis) {
                skipped++
                continue
            }

            if (applyOne(id, row)) applied++ else skipped++
        }

        store.recordPulled(watermark)
        if (applied > 0) {
            container.logger.write(Stage.SYSTEM, LogLevel.INFO, "web sync: applied $applied browser edit(s)")
        }
        return SupabaseApi.Outcome.Ok(applied to skipped)
    }

    /** Status first, through the repository, then the plain fields. */
    private suspend fun applyOne(id: String, row: JsonObject): Boolean {
        val repo = container.taskRepository
        val before = repo.byId(id) ?: return false

        val wanted = row.str("status")?.let { name ->
            runCatching { TaskStatus.valueOf(name) }.getOrNull()
        }

        // Routed through the repository rather than written straight to the
        // row, because these are not simple field writes: completing a
        // recurring task creates its next instance, and reopening clears the
        // completion time. The browser gets the same behaviour as the phone.
        if (wanted != null && wanted != before.status) {
            when (wanted) {
                TaskStatus.COMPLETED -> repo.complete(id)
                TaskStatus.ACTIVE -> repo.reopen(id)
                TaskStatus.ARCHIVED -> repo.archive(id)
                TaskStatus.DELETED -> repo.delete(id)
            }
        }

        val current = repo.byId(id) ?: return true
        val edited = current.copy(
            title = row.str("title")?.takeIf { it.isNotBlank() } ?: current.title,
            notes = if (row.has("notes")) row.str("notes") else current.notes,
            dueAt = if (row.has("due_at")) row.millis("due_at") else current.dueAt,
            reminderAt = if (row.has("reminder_at")) row.millis("reminder_at") else current.reminderAt,
            priority = row.priority("priority", current.priority),
        )
        // update() stamps a fresh updatedAt, so guarding on equality keeps a
        // no-op pull from looking like a local edit and bouncing back up.
        if (edited != current) repo.update(edited)
        return true
    }

    // -- row reading --------------------------------------------------------
    //
    // Small helpers rather than a serializable data class: the phone reads six
    // of the twenty columns, and a strict deserialiser would fail the whole
    // pull the first time a column is added on the server.

    private fun JsonObject.has(key: String): Boolean = containsKey(key)

    private fun JsonObject.str(key: String): String? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    /** Postgres timestamptz to epoch millis. Accepts both `Z` and `+00:00`. */
    private fun JsonObject.millis(key: String): Long? {
        val text = str(key) ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun JsonObject.priority(key: String, fallback: Priority = Priority.MEDIUM): Priority {
        val text = str(key) ?: return fallback
        return runCatching { Priority.valueOf(text) }.getOrDefault(fallback)
    }

    private companion object {
        const val NEW_TASK_LIMIT = 100
        const val DECISION_LIMIT = 200
        const val EDIT_LIMIT = 300
    }
}
