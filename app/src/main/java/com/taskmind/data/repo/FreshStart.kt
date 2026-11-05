package com.taskmind.data.repo

import androidx.core.app.NotificationManagerCompat
import com.taskmind.capture.AudioSource
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.sync.SyncStore
import com.taskmind.widget.TasksWidget
import com.taskmind.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Erase everything": a fresh start, in one tap.
 *
 * WHAT GOES
 *
 * Every task, inbox item and matter; every captured message, mail and call
 * transcript; the activity log and the record of model calls; the feedback
 * the app learned from and the twin's journal; salvage files and scratch
 * audio. And the copy on the web page, which the next sync empties before it
 * does anything else (see [SyncStore.requestWipe]) - so a phone that is
 * offline right now still clears it once it is back.
 *
 * WHAT STAYS
 *
 * Settings, keys and connected accounts, and the choices already made about
 * people and chats: which apps are read, which groups are ignored, the rules
 * approved under "What TaskMind has learned". A fresh start is not a
 * reinstall - the next message is read exactly as before.
 *
 * The phone's own call recordings stay where the dialer saved them. They were
 * never TaskMind's; it only read them. The exception is when "Delete a
 * recording once it is transcribed" is on - the user has already said
 * TaskMind may delete them - and then the ones it still holds go too.
 *
 * NOTHING COMES BACK
 *
 * The line drawn by [com.taskmind.data.settings.SettingsRepository.markFreshStart]
 * keeps notifications still in the shade, and calls already in the call log,
 * from being read again; email remembers what it has read already.
 */
class FreshStart(private val container: AppContainer) {

    data class Result(val tasks: Int, val captures: Int, val web: Boolean)

    suspend fun eraseEverything(): Result = withContext(Dispatchers.IO) {
        val context = container.context
        val db = container.database
        val settings = container.settingsRepository.current()

        // No message pass in flight while the tables empty, or one that read
        // a capture just before could write its task just after.
        val (tasks, captures) = container.instantLane.withMessagesPaused {
            val taskCount = db.taskDao().total()
            val captureCount = db.rawCaptureDao().total()
            val recordings = if (settings.deleteRecordingsAfterTranscription) db.rawCaptureDao().audioPaths() else emptyList()

            // The line first: anything that arrives from here on is new.
            container.settingsRepository.markFreshStart(System.currentTimeMillis())
            // Every table in one transaction, then compacted: Room's own
            // clearAllTables also reclaims the pages the rows were in, so the
            // words are gone from the file, not only from the tables. The
            // compacting can be refused while a screen is mid-read; by then
            // the rows are already gone, and only a failure that left them
            // there counts.
            runCatching { db.clearAllTables() }.onFailure { failure ->
                if (db.taskDao().total() > 0 || db.rawCaptureDao().total() > 0) throw failure
            }
            recordings.forEach { path -> runCatching { AudioSource.delete(context, path) } }
            taskCount to captureCount
        }

        container.learningStore.clearFeedback()
        container.twinJournal.clear()
        container.twinStore.clearNudged()
        listOf(
            File(context.filesDir, "salvage"),
            File(context.cacheDir, "asr"),
            File(context.cacheDir, "voice"),
            File(context.cacheDir, "diagnostics"),
            File(context.cacheDir, "selftest"),
        ).forEach { runCatching { it.deleteRecursively() } }

        // The web page.
        val sync = SyncStore(context)
        val syncState = sync.current()
        val web = syncState.configured
        if (web) {
            sync.requestWipe()
            if (syncState.enabled) Scheduler.enqueueSync(context)
        }

        // What is on screen outside the app: task notifications in the shade,
        // the widget, and the next reminder alarm, all of which pointed at
        // tasks that no longer exist.
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        runCatching { TasksWidget.redrawAll(context) }
        runCatching { Scheduler.scheduleNextReminder(context, container) }

        container.logger.write(
            Stage.SYSTEM,
            LogLevel.INFO,
            "erased everything - a fresh start",
            "$tasks task(s), $captures capture(s)" + if (web) "; the web copy is emptied at the next sync" else "",
        )
        Result(tasks, captures, web)
    }
}
