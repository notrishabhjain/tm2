package com.taskmind.diagnostics

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.taskmind.capture.NotificationCaptureService

/**
 * Spec 17.4: re-check every permission on app start and on every watchdog
 * cycle. A revocation must appear on the status screen within one cycle, not
 * silently disable a feature.
 */
object PermissionState {

    data class Item(
        val key: String,
        val label: String,
        val granted: Boolean,
        val required: Boolean,
        val explanation: String,
        val fixIntent: Intent?,
    )

    fun all(context: Context): List<Item> = listOf(
        notificationAccess(context),
        postNotifications(context),
        allFilesAccess(context),
        runtime(
            context,
            Manifest.permission.READ_CALL_LOG,
            "Call log",
            "Needed to notice that a call ended and who it was with.",
        ),
        runtime(
            context,
            Manifest.permission.READ_PHONE_STATE,
            "Phone state",
            "The second of three call-end triggers. Without it only the call log and the watchdog remain.",
        ),
        runtime(
            context,
            Manifest.permission.READ_CONTACTS,
            "Contacts",
            "Turns an SMS sender's number into their name, so a task says who asked.",
        ),
        batteryOptimisation(context),
        exactAlarms(context),
        installPackages(context),
    )

    fun notificationAccess(context: Context) = Item(
        key = "notification_listener",
        label = "Notification access",
        granted = NotificationCaptureService.isEnabled(context),
        required = true,
        explanation = "Without this TaskMind cannot see any message, and message capture does nothing at all.",
        fixIntent = NotificationCaptureService.settingsIntent(),
    )

    fun allFilesAccess(context: Context) = Item(
        key = "all_files",
        label = "All files access",
        granted = Environment.isExternalStorageManager(),
        required = false,
        explanation = "Needed to find the recordings your phone app writes. Call capture cannot work without it.",
        fixIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        },
    )

    fun postNotifications(context: Context) = Item(
        key = "post_notifications",
        label = "Show notifications",
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        required = false,
        explanation = "Used only to tell you a task was captured and to fire reminders.",
        fixIntent = appSettings(context),
    )

    fun batteryOptimisation(context: Context): Item {
        val power = context.getSystemService(PowerManager::class.java)
        val ignoring = power?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        @Suppress("BatteryLife")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return Item(
            key = "battery",
            label = "Battery optimisation off",
            granted = ignoring,
            required = false,
            explanation = "With optimisation on, Android stops the background work that finds your calls.",
            fixIntent = intent,
        )
    }

    fun exactAlarms(context: Context): Item {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (alarmManager?.canScheduleExactAlarms() ?: false)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            null
        }
        return Item(
            key = "exact_alarms",
            label = "Exact alarms",
            granted = granted,
            required = false,
            explanation = "Reminders fire on time with this. Without it they still fire, just late.",
            fixIntent = intent,
        )
    }

    fun installPackages(context: Context) = Item(
        key = "install_packages",
        label = "Install unknown apps",
        granted = context.packageManager.canRequestPackageInstalls(),
        required = false,
        explanation = "Needed for TaskMind to update itself, since it is not distributed through a store.",
        fixIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        },
    )

    private fun runtime(context: Context, permission: String, label: String, explanation: String) = Item(
        key = permission,
        label = label,
        granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        required = false,
        explanation = explanation,
        fixIntent = null,
    )

    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Spec 17.2: Autostart is a separate Xiaomi permission. With it off, static
     * broadcast receivers never fire and background services are killed
     * regardless of Android's own battery settings.
     */
    fun isXiaomi(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")
    }

    fun autostartIntent(context: Context): Intent {
        val intent = Intent().apply {
            component = android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        }
        val resolvable = context.packageManager.resolveActivity(intent, 0) != null
        // Wrapped with a fallback to app details plus written instructions,
        // because the component name changes between HyperOS builds.
        return if (resolvable) intent else appSettings(context)
    }

    const val AUTOSTART_INSTRUCTIONS =
        "Open Settings, then Apps, then Permissions, then Autostart, find TaskMind in the list and turn " +
            "the switch on. On some HyperOS builds this lives under Security, then Permissions, then Autostart."
}
