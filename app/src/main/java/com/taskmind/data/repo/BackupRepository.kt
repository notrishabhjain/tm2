package com.taskmind.data.repo

import android.content.Context
import android.net.Uri
import com.taskmind.core.LogLevel
import com.taskmind.core.Priority
import com.taskmind.core.SourceType
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.TaskMindDatabase
import com.taskmind.intake.IntakeResult
import com.taskmind.intake.TaskCandidate
import com.taskmind.intake.IntakeFunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spec 16 - export and import as JSON and CSV, local backup and restore, and
 * "erase all captured content" which keeps the tasks.
 *
 * Spec 17.4 / failure mode 6 also leans on this: the corruption-recovery path
 * salvages tasks to a timestamped JSON file before anything destructive is even
 * considered.
 */
class BackupRepository(
    private val context: Context,
    private val database: TaskMindDatabase,
    private val funnel: IntakeFunnel,
    private val logger: ActivityLogger,
) {

    @Serializable
    data class ExportedTask(
        val id: String,
        val title: String,
        val notes: String? = null,
        val dueAt: Long? = null,
        val priority: String = "MEDIUM",
        val status: String = "ACTIVE",
        val tags: List<String> = emptyList(),
        val projectId: String? = null,
        val recurrenceRule: String? = null,
        val reminderAt: Long? = null,
        val parentTaskId: String? = null,
        val sourceType: String = "MANUAL",
        val sourceRef: String? = null,
        val sourceLabel: String? = null,
        val sourceApp: String? = null,
        val evidence: String? = null,
        val confidence: Double? = null,
        val inferenceOrigin: String? = null,
        val completedAt: Long? = null,
        val createdAt: Long = 0,
        val updatedAt: Long = 0,
    )

    @Serializable
    data class ExportBundle(
        val app: String = "TaskMind",
        val schemaVersion: Int = 1,
        val exportedAt: Long,
        val tasks: List<ExportedTask>,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val tasks = database.taskDao().allForExport().map { it.toExported() }
        json.encodeToString(
            ExportBundle.serializer(),
            ExportBundle(exportedAt = System.currentTimeMillis(), tasks = tasks),
        )
    }

    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        val tasks = database.taskDao().allForExport()
        buildString {
            append("id,title,notes,dueAt,priority,status,tags,sourceType,sourceLabel,evidence,confidence,createdAt\n")
            for (t in tasks) {
                append(
                    listOf(
                        t.id,
                        t.title,
                        t.notes.orEmpty(),
                        t.dueAt?.toString().orEmpty(),
                        t.priority.name,
                        t.status.name,
                        t.tags.joinToString(";"),
                        t.sourceType.name,
                        t.sourceLabel.orEmpty(),
                        t.evidence.orEmpty(),
                        t.confidence?.toString().orEmpty(),
                        t.createdAt.toString(),
                    ).joinToString(",") { escapeCsv(it) },
                )
                append('\n')
            }
        }
    }

    /**
     * Import goes through the intake funnel like every other source, so an
     * import cannot introduce a task the funnel would have refused, and a
     * re-import of the same file deduplicates itself.
     */
    suspend fun importJson(text: String): ImportResult = withContext(Dispatchers.IO) {
        val bundle = runCatching { json.decodeFromString(ExportBundle.serializer(), text) }.getOrNull()
            ?: return@withContext ImportResult(0, 0, "That file is not a TaskMind export.")

        var created = 0
        var skipped = 0
        for (t in bundle.tasks) {
            val result = funnel.submit(
                TaskCandidate(
                    title = t.title,
                    evidence = t.evidence,
                    priority = runCatching { Priority.valueOf(t.priority) }.getOrDefault(Priority.MEDIUM),
                    dueAtRaw = null,
                    notes = t.notes,
                    confidence = null,
                    // Imported rows are the user's own data, not a fresh claim
                    // about a source text, so they enter as MANUAL and skip the
                    // evidence check while keeping their provenance labels.
                    sourceType = SourceType.MANUAL,
                    sourceRef = null,
                    sourceLabel = t.sourceLabel,
                    sourceApp = t.sourceApp,
                    rawCaptureId = null,
                    inferenceOrigin = t.inferenceOrigin ?: "import",
                    sourceText = "",
                    occurredAt = t.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    tags = t.tags,
                    projectId = t.projectId,
                    reminderAt = t.reminderAt,
                    recurrenceRule = t.recurrenceRule,
                ),
            )
            when (result) {
                is IntakeResult.Created -> {
                    created++
                    t.dueAt?.let { database.taskDao().setDueAt(result.taskId, it, System.currentTimeMillis()) }
                    if (t.status == TaskStatus.COMPLETED.name) {
                        database.taskDao().setStatus(
                            result.taskId,
                            TaskStatus.COMPLETED,
                            t.completedAt,
                            System.currentTimeMillis(),
                        )
                    }
                }
                else -> skipped++
            }
        }
        logger.write(Stage.SYSTEM, LogLevel.INFO, "imported $created task(s), skipped $skipped")
        ImportResult(created, skipped, null)
    }

    data class ImportResult(val created: Int, val skipped: Int, val error: String?)

    suspend fun writeToUri(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    suspend fun readFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    /**
     * Failure mode 6: salvage before anything destructive. Writes every task to
     * a timestamped JSON file inside app storage and returns its path.
     */
    suspend fun salvageTasks(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(context.filesDir, "salvage").apply { mkdirs() }
            val file = File(dir, "tasks-$stamp.json")
            file.writeText(exportJson())
            logger.write(Stage.SYSTEM, LogLevel.INFO, "salvaged tasks", file.absolutePath)
            file.absolutePath
        }.getOrNull()
    }

    /**
     * Spec 16: clears raw captures, transcripts, audio and logs BUT KEEPS
     * TASKS. The evidence quote and source label already live on the task, so
     * the user keeps the words that created each one.
     */
    suspend fun eraseCapturedContent(): Int = withContext(Dispatchers.IO) {
        val rawDao = database.rawCaptureDao()
        val captures = rawDao.recent(Int.MAX_VALUE)
        for (capture in captures) {
            capture.audioPath?.let { path -> runCatching { File(path).delete() } }
        }
        database.taskDao().detachAllRawCaptures()
        rawDao.deleteAll()
        database.callRecordDao().deleteAll()
        database.fingerprintDao().deleteAll()
        database.reviewItemDao().deleteAll()
        database.activityLogDao().deleteAll()
        database.seenPackageDao().deleteAll()
        logger.write(Stage.SYSTEM, LogLevel.INFO, "erased captured content", "${captures.size} captures; tasks kept")
        captures.size
    }

    private fun com.taskmind.data.db.entity.TaskEntity.toExported() = ExportedTask(
        id = id,
        title = title,
        notes = notes,
        dueAt = dueAt,
        priority = priority.name,
        status = status.name,
        tags = tags,
        projectId = projectId,
        recurrenceRule = recurrenceRule,
        reminderAt = reminderAt,
        parentTaskId = parentTaskId,
        sourceType = sourceType.name,
        sourceRef = sourceRef,
        sourceLabel = sourceLabel,
        sourceApp = sourceApp,
        evidence = evidence,
        confidence = confidence,
        inferenceOrigin = inferenceOrigin,
        completedAt = completedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
