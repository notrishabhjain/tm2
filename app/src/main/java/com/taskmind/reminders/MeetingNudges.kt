package com.taskmind.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.MeetingMatch
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.core.WaitingOn
import com.taskmind.data.repo.TaskRanking
import com.taskmind.di.AppContainer
import com.taskmind.prefs.UiPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Meetings, before and after.
 *
 * Thirty minutes before a meeting: the open tasks involving the people in it
 * or the matter it names - what you owe them, what they owe you - so you walk
 * in prepared. Only when there is something; most meetings get nothing.
 *
 * Five minutes after it ends: "Anything you agreed?", one tap to a brain dump
 * that already says which meeting it was. Meetings create work that never
 * reaches a chat; this is where it gets caught.
 *
 * Read from the phone's own calendar, only with the calendar switch on in
 * Settings and the permission granted, and never stored or sent anywhere.
 * Armed for the rest of today whenever the briefs are armed (app start and
 * each brief), as window alarms - a few minutes either way is fine.
 */
object MeetingNudges {

    const val PREP_LEAD_MILLIS = 30L * 60 * 1000
    const val AFTER_DELAY_MILLIS = 5L * 60 * 1000
    private const val WINDOW_MILLIS = 5L * 60 * 1000

    data class Event(val id: Long, val title: String, val begin: Long, val end: Long, val attendees: List<String>)

    suspend fun schedule(context: Context) {
        val app = context.applicationContext
        if (!UiPreferences(app).current().calendarInBrief || !CalendarToday.granted(app)) return
        val now = System.currentTimeMillis()
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        for (event in today(app, now)) {
            val prepAt = event.begin - PREP_LEAD_MILLIS
            if (prepAt > now) {
                runCatching { alarms.setWindow(AlarmManager.RTC_WAKEUP, prepAt, WINDOW_MILLIS, pending(app, event, MeetingReceiver.KIND_PREP)) }
            }
            val afterAt = event.end + AFTER_DELAY_MILLIS
            if (afterAt > now) {
                runCatching { alarms.setWindow(AlarmManager.RTC_WAKEUP, afterAt, WINDOW_MILLIS, pending(app, event, MeetingReceiver.KIND_AFTER)) }
            }
        }
    }

    private fun pending(context: Context, event: Event, kind: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ("meeting:$kind:${event.id}:${event.begin}").hashCode(),
            Intent(context, MeetingReceiver::class.java)
                .setAction(MeetingReceiver.ACTION)
                .putExtra(MeetingReceiver.EXTRA_KIND, kind)
                .putExtra(MeetingReceiver.EXTRA_EVENT, event.id)
                .putExtra(MeetingReceiver.EXTRA_BEGIN, event.begin),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Today's timed meetings you have not declined, with who is invited. */
    fun today(context: Context, now: Long): List<Event> =
        between(context, DateResolver.startOfDay(now), DateResolver.endOfDay(now), withAttendees = true)

    /** Timed events between [from] and [to]; attendees only when asked for (a query per event). */
    fun between(context: Context, from: Long, to: Long, withAttendees: Boolean = false): List<Event> {
        if (!CalendarToday.granted(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val events = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                ),
                "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.getInt(4) == 1) continue
                        val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        add(Event(c.getLong(0), title, c.getLong(2), c.getLong(3), emptyList()))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
        return if (withAttendees) events.map { it.copy(attendees = attendees(context, it.id)) } else events
    }

    private fun attendees(context: Context, eventId: Long): List<String> = runCatching {
        CalendarContract.Attendees.query(
            context.contentResolver,
            eventId,
            arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL),
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    listOfNotNull(c.getString(0), c.getString(1)).filter { it.isNotBlank() }.forEach(::add)
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** The meeting with this id and start, if it is still on today's calendar. */
    fun find(context: Context, eventId: Long, begin: Long): Event? =
        today(context, System.currentTimeMillis()).firstOrNull { it.id == eventId && it.begin == begin }

    /** Open tasks this meeting is about. */
    suspend fun related(container: AppContainer, event: Event): List<MeetingMatch.Task> {
        val all = container.taskRepository.observeTasks().first()
            .filter { it.status == TaskStatus.ACTIVE && it.parentTaskId == null }
        val matters = container.database.projectDao().all().associate { it.id to it.name }
        val open = all.map {
            MeetingMatch.Task(
                id = it.id,
                title = it.title,
                person = TaskRanking.person(it),
                matter = it.projectId?.let(matters::get),
                waitingOn = WaitingOn.matches(it.title, it.tags),
            )
        }
        return MeetingMatch.related(MeetingMatch.Meeting(event.title, event.attendees), open)
    }
}

/** Fires a meeting's prep or follow-up. Not exported. */
class MeetingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return
        val eventId = intent.getLongExtra(EXTRA_EVENT, -1L)
        val begin = intent.getLongExtra(EXTRA_BEGIN, -1L)
        val app = context.applicationContext
        val container = AppContainer.get(app)
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                // Moved or cancelled since it was armed: say nothing.
                val event = MeetingNudges.find(app, eventId, begin) ?: return@launch
                when (kind) {
                    KIND_PREP -> {
                        val related = MeetingNudges.related(container, event)
                        if (related.isNotEmpty()) container.notifier.postMeetingPrep(event.title, MeetingMatch.lines(related))
                    }
                    KIND_AFTER -> container.notifier.postMeetingFollowUp(event.title)
                }
            } catch (t: Throwable) {
                container.logger.write(Stage.SYSTEM, LogLevel.WARN, "meeting nudge failed", t.toString())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.taskmind.action.MEETING"
        const val EXTRA_KIND = "com.taskmind.extra.MEETING_KIND"
        const val EXTRA_EVENT = "com.taskmind.extra.MEETING_EVENT"
        const val EXTRA_BEGIN = "com.taskmind.extra.MEETING_BEGIN"
        const val KIND_PREP = "PREP"
        const val KIND_AFTER = "AFTER"
    }
}
