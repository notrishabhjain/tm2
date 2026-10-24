package com.taskmind.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.taskmind.core.SmartReminder

/**
 * Getting from a task back to the person it is about.
 *
 * Most captured tasks end in "reply to someone" or "call someone back". The
 * task already knows who - the sender's name is in its source label - so the
 * phone book can turn that into a number, and the number into a call or a
 * WhatsApp chat, without the user hunting for either.
 *
 * Read-only, and only with the contacts permission the call capture already
 * asks for. Nothing here leaves the phone.
 */
object ContactLookup {

    private val PHONE_LIKE = Regex("^[+\\d][\\d\\s-]{6,}$")

    /** A dialable number for [name], or null. */
    fun numberFor(context: Context, name: String?): String? {
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return null
        // Unsaved senders arrive as the number itself.
        if (PHONE_LIKE.matches(clean)) return clean.replace(Regex("[\\s-]"), "")
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? COLLATE NOCASE",
                arrayOf(clean),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.replace(Regex("[\\s-]"), "")
    }

    fun dial(context: Context, number: String) {
        launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    /**
     * Opens the chat with [number] in WhatsApp. wa.me needs the country code
     * and no "+"; a bare ten-digit Indian mobile is assumed to be +91.
     */
    fun whatsapp(context: Context, number: String) {
        var digits = number.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        if (digits.startsWith("0") && digits.length == 11) digits = "91" + digits.drop(1)
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))
    }

    /** Opens an app by package - for a group, whose chat cannot be linked to. */
    fun openApp(context: Context, packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch(context, it) }
    }

    /**
     * Hands the task to the phone's calendar app to add as an event. No
     * calendar permission: the calendar app does the writing, and the person
     * confirms it there.
     */
    fun addToCalendar(context: Context, title: String, notes: String?, dueAt: Long) {
        val allDay = SmartReminder.isDateOnly(dueAt)
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, if (allDay) SmartReminder.morningOf(dueAt) else dueAt)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, if (allDay) SmartReminder.morningOf(dueAt) else dueAt + 30 * 60_000L)
        notes?.takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        launch(context, intent)
    }

    private fun launch(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
