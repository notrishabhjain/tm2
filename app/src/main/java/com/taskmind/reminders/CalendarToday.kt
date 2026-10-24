package com.taskmind.reminders

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.taskmind.core.Brief
import com.taskmind.core.DateResolver

/**
 * Today's calendar events, for the morning brief.
 *
 * Read on the phone, used for one notification, never stored and never sent
 * anywhere. Only when the person switched it on and granted the calendar
 * permission; otherwise the brief simply has no meetings in it.
 */
object CalendarToday {

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun read(context: Context, now: Long): List<Brief.Meeting> {
        if (!granted(context)) return emptyList()
        val start = DateResolver.startOfDay(now)
        val end = DateResolver.endOfDay(now)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY,
                ),
                // Declined invitations are not part of anyone's day.
                "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                        val allDay = cursor.getInt(2) == 1
                        add(Brief.Meeting(if (allDay) null else cursor.getLong(1), title))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}
