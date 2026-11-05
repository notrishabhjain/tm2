package com.taskmind.data.repo

import android.content.Context
import com.taskmind.core.Journal
import com.taskmind.data.db.entity.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The record of every change TaskMind made on its own, newest last, one JSON
 * line each (see [Journal]). A file, like the learning log: small, local,
 * and no database migration to roll back if a release has to be.
 */
class TwinJournal(context: Context) {

    private val file = File(context.filesDir, "twin_journal.log")
    private val lock = Mutex()

    private val _entries = MutableStateFlow(read())
    /** Newest first. */
    val entries: StateFlow<List<Journal.Entry>> = _entries.asStateFlow()

    /** Writes one entry. Returns its id, or null when there was nothing to record. */
    suspend fun record(source: String, summary: String, changes: List<Journal.Change>): String? {
        if (changes.isEmpty()) return null
        val entry = Journal.Entry(UUID.randomUUID().toString(), System.currentTimeMillis(), source, summary, changes)
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    file.appendText(Journal.encode(entry) + "\n")
                    if (file.length() > TRIM_AT_BYTES) rewrite(readFile().takeLast(KEEP))
                }
                _entries.value = (listOf(entry) + _entries.value).take(KEEP)
            }
        }
        return entry.id
    }

    suspend fun byId(id: String): Journal.Entry? = _entries.value.firstOrNull { it.id == id }

    suspend fun markUndone(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = readFile().map { if (it.id == id) it.copy(undone = true) else it }
            runCatching { rewrite(all) }
            _entries.value = all.reversed().take(KEEP)
        }
    }

    /** "Erase everything": the record of what the twin changed goes with the tasks it changed. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching { file.delete() }
            _entries.value = emptyList()
        }
    }

    private fun rewrite(entries: List<Journal.Entry>) {
        file.writeText(entries.joinToString("\n", postfix = "\n", transform = Journal::encode))
    }

    private fun readFile(): List<Journal.Entry> =
        runCatching { if (file.exists()) file.readLines().mapNotNull(Journal::decode) else emptyList() }
            .getOrDefault(emptyList())

    private fun read(): List<Journal.Entry> = readFile().reversed().take(KEEP)

    companion object {
        private const val KEEP = 300
        private const val TRIM_AT_BYTES = 600_000L

        const val SOURCE_TWIN = "twin"
        const val SOURCE_CAPTURE = "capture"
        const val SOURCE_EVENING = "evening"

        fun snapshot(t: TaskEntity): Journal.Snapshot = Journal.Snapshot(
            title = t.title,
            dueAt = t.dueAt,
            reminderAt = t.reminderAt,
            priority = t.priority.name,
            status = t.status.name,
            area = t.area,
            plannedFor = t.plannedFor,
            inbox = t.inbox,
            notes = t.notes,
            tags = t.tags,
            projectId = t.projectId,
        )

        fun changed(before: TaskEntity, after: TaskEntity): Journal.Change =
            Journal.Change(before.id, Journal.Kind.CHANGED, snapshot(before), snapshot(after))
    }
}
