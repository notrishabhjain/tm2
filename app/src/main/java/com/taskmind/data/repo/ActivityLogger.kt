package com.taskmind.data.repo

import android.util.Log
import com.taskmind.core.LogLevel
import com.taskmind.data.db.dao.ActivityLogDao
import com.taskmind.data.db.entity.ActivityLogEntity
import com.taskmind.intake.FunnelLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spec 15 - the activity log is the primary debugging instrument, because there
 * is no local machine and no adb logcat.
 *
 * Every stage writes at least one line: capture received, pre-filter verdict and
 * the rule that fired, ASR started/finished, LLM call and model, evidence check
 * result and score, funnel verdict, dedup hit, task created.
 */
class ActivityLogger(
    private val dao: ActivityLogDao,
    private val scope: CoroutineScope,
) : FunnelLog {

    private val trimLock = Mutex()
    private var writesSinceTrim = 0

    override suspend fun log(stage: String, level: LogLevel, message: String, detail: String?) {
        write(stage, level, message, detail)
    }

    suspend fun write(stage: String, level: LogLevel, message: String, detail: String? = null) {
        // Mirrored to logcat when a machine happens to be attached; the database
        // is the copy that matters.
        Log.println(level.toAndroidPriority(), TAG, "[$stage] $message${detail?.let { " | $it" }.orEmpty()}")
        runCatching {
            dao.insert(
                ActivityLogEntity(
                    timestamp = System.currentTimeMillis(),
                    stage = stage,
                    level = level,
                    message = message.take(500),
                    detail = detail?.take(4000),
                ),
            )
            trimIfNeeded()
        }
    }

    /** Fire-and-forget for call sites that are not suspending (services, receivers). */
    fun post(stage: String, level: LogLevel, message: String, detail: String? = null) {
        scope.launch(Dispatchers.IO) { write(stage, level, message, detail) }
    }

    private suspend fun trimIfNeeded() {
        trimLock.withLock {
            writesSinceTrim++
            if (writesSinceTrim < TRIM_EVERY) return
            writesSinceTrim = 0
        }
        runCatching { dao.trimTo(KEEP) }
    }

    suspend fun exportAsText(limit: Int = KEEP): String {
        val entries = dao.recent(limit)
        return buildString {
            append("TaskMind activity log (newest first, ").append(entries.size).append(" entries)\n\n")
            for (e in entries) {
                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp)))
                append(' ').append(e.level.name.padEnd(5))
                append(' ').append(e.stage.padEnd(10))
                append(' ').append(e.message)
                append('\n')
                e.detail?.let { append("    ").append(it.replace("\n", "\n    ")).append('\n') }
            }
        }
    }

    private fun LogLevel.toAndroidPriority(): Int = when (this) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    companion object {
        private const val TAG = "TaskMind"
        const val KEEP = 500
        private const val TRIM_EVERY = 25
    }
}
