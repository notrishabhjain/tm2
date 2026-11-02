package com.taskmind.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Every change TaskMind makes on its own - what the twin applied, a capture
 * moving a deadline, the evening "move to tomorrow" - written down with what
 * each task looked like before and after, so it can be undone.
 *
 * Undo is careful rather than blunt: a field is put back only if it still
 * holds the value the change gave it. If the user has edited it since, their
 * edit stands and the undo says so.
 *
 * Pure: entries are JSON lines, and working out an undo needs only the
 * entry and the tasks as they are now.
 */
object Journal {

    /** The fields of a task a change can touch. */
    data class Snapshot(
        val title: String,
        val dueAt: Long?,
        val reminderAt: Long?,
        val priority: String,
        val status: String,
        val area: String?,
        val plannedFor: String?,
        val inbox: Boolean,
        val notes: String?,
        val tags: List<String>,
        val projectId: String?,
    )

    enum class Kind { CREATED, CHANGED }

    data class Change(val taskId: String, val kind: Kind, val before: Snapshot?, val after: Snapshot?)

    data class Entry(
        val id: String,
        val at: Long,
        /** "twin", "capture", "evening": who made it. */
        val source: String,
        val summary: String,
        val changes: List<Change>,
        val undone: Boolean = false,
    )

    /** What undoing one change does to one task. */
    sealed interface Revert {
        val taskId: String

        /** Put these values back. [kept] fields were edited since and are left alone. */
        data class Restore(override val taskId: String, val to: Snapshot, val kept: Int) : Revert

        /** The change created this task: remove it. */
        data class Remove(override val taskId: String) : Revert

        /** Nothing to do: the task is gone, or already back as it was. */
        data class Nothing(override val taskId: String, val why: String) : Revert
    }

    /**
     * The undo of [entry] against the tasks as they are now ([current], null
     * for a task that no longer exists).
     */
    fun undo(entry: Entry, current: (String) -> Snapshot?): List<Revert> = entry.changes.map { change ->
        val now = current(change.taskId)
        when {
            now == null -> Revert.Nothing(change.taskId, "no longer exists")
            change.kind == Kind.CREATED -> if (now.status == "DELETED") Revert.Nothing(change.taskId, "already deleted") else Revert.Remove(change.taskId)
            change.before == null || change.after == null -> Revert.Nothing(change.taskId, "nothing recorded")
            else -> restore(change.taskId, change.before, change.after, now)
        }
    }

    private fun restore(id: String, before: Snapshot, after: Snapshot, now: Snapshot): Revert {
        var kept = 0
        var touched = 0
        fun <T> field(b: T, a: T, n: T): T {
            if (b == a) return n
            if (n == a) {
                touched++
                return b
            }
            kept++
            return n
        }
        val to = Snapshot(
            title = field(before.title, after.title, now.title),
            dueAt = field(before.dueAt, after.dueAt, now.dueAt),
            reminderAt = field(before.reminderAt, after.reminderAt, now.reminderAt),
            priority = field(before.priority, after.priority, now.priority),
            status = field(before.status, after.status, now.status),
            area = field(before.area, after.area, now.area),
            plannedFor = field(before.plannedFor, after.plannedFor, now.plannedFor),
            inbox = field(before.inbox, after.inbox, now.inbox),
            notes = field(before.notes, after.notes, now.notes),
            tags = field(before.tags, after.tags, now.tags),
            projectId = field(before.projectId, after.projectId, now.projectId),
        )
        return if (touched == 0) {
            Revert.Nothing(id, if (kept > 0) "edited since" else "already as it was")
        } else {
            Revert.Restore(id, to, kept)
        }
    }

    /** The fields that differ, by name: what a change changed. */
    fun changedFields(before: Snapshot, after: Snapshot): List<String> = buildList {
        if (before.title != after.title) add("title")
        if (before.dueAt != after.dueAt) add("due")
        if (before.reminderAt != after.reminderAt) add("reminder")
        if (before.priority != after.priority) add("priority")
        if (before.status != after.status) add("status")
        if (before.area != after.area) add("area")
        if (before.plannedFor != after.plannedFor) add("day")
        if (before.inbox != after.inbox) add("inbox")
        if (before.notes != after.notes) add("notes")
        if (before.tags != after.tags) add("tags")
        if (before.projectId != after.projectId) add("matter")
    }

    // --------------------------------------------------------------- JSON

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(entry: Entry): String = JsonObject(
        mapOf(
            "id" to JsonPrimitive(entry.id),
            "at" to JsonPrimitive(entry.at),
            "source" to JsonPrimitive(entry.source),
            "summary" to JsonPrimitive(entry.summary),
            "undone" to JsonPrimitive(entry.undone),
            "changes" to JsonArray(
                entry.changes.map { c ->
                    JsonObject(
                        mapOf(
                            "task" to JsonPrimitive(c.taskId),
                            "kind" to JsonPrimitive(c.kind.name),
                            "before" to (c.before?.let(::snap) ?: JsonNull),
                            "after" to (c.after?.let(::snap) ?: JsonNull),
                        ),
                    )
                },
            ),
        ),
    ).toString()

    fun decode(line: String): Entry? = runCatching {
        val o = json.parseToJsonElement(line).jsonObject
        Entry(
            id = o.string("id") ?: return null,
            at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
            source = o.string("source").orEmpty(),
            summary = o.string("summary").orEmpty(),
            undone = o["undone"]?.jsonPrimitive?.booleanOrNull ?: false,
            changes = o["changes"]?.jsonArray.orEmpty().mapNotNull { el ->
                val c = el as? JsonObject ?: return@mapNotNull null
                Change(
                    taskId = c.string("task") ?: return@mapNotNull null,
                    kind = runCatching { Kind.valueOf(c.string("kind").orEmpty()) }.getOrDefault(Kind.CHANGED),
                    before = (c["before"] as? JsonObject)?.let(::unsnap),
                    after = (c["after"] as? JsonObject)?.let(::unsnap),
                )
            },
        )
    }.getOrNull()

    private fun snap(s: Snapshot): JsonObject = JsonObject(
        mapOf(
            "title" to JsonPrimitive(s.title),
            "due" to (s.dueAt?.let { JsonPrimitive(it) } ?: JsonNull),
            "reminder" to (s.reminderAt?.let { JsonPrimitive(it) } ?: JsonNull),
            "priority" to JsonPrimitive(s.priority),
            "status" to JsonPrimitive(s.status),
            "area" to str(s.area),
            "day" to str(s.plannedFor),
            "inbox" to JsonPrimitive(s.inbox),
            "notes" to str(s.notes),
            "tags" to JsonArray(s.tags.map { JsonPrimitive(it) }),
            "matter" to str(s.projectId),
        ),
    )

    private fun unsnap(o: JsonObject): Snapshot = Snapshot(
        title = o.string("title").orEmpty(),
        dueAt = o.long("due"),
        reminderAt = o.long("reminder"),
        priority = o.string("priority") ?: "MEDIUM",
        status = o.string("status") ?: "ACTIVE",
        area = o.string("area"),
        plannedFor = o.string("day"),
        inbox = (o["inbox"] as? JsonPrimitive)?.booleanOrNull ?: false,
        notes = o.string("notes"),
        tags = (o["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
        projectId = o.string("matter"),
    )

    private fun str(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
}
