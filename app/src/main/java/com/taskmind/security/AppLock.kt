package com.taskmind.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Whether the app asks who you are before showing anything.
 *
 * WHY THIS IS NOT DATASTORE
 *
 * The lock decision has to be made before the first frame is drawn. DataStore
 * reads are suspending, so the UI would compose first and the lock would fall
 * over already-visible content - which shows the person holding the phone
 * exactly what the lock exists to hide. Plain SharedPreferences answers
 * synchronously.
 *
 * WHAT IT PROTECTS AGAINST, AND WHAT IT DOES NOT
 *
 * Someone picking up the unlocked phone, and the task list showing up in the
 * app switcher or in a screenshot. It is not disk encryption and does not
 * pretend to be: the database is protected by the device's own encryption and
 * by app sandboxing, as it was before.
 */
object AppLock {

    /**
     * Unlocking survives a brief trip out of the app.
     *
     * Without this, tapping a link in a task and coming back means
     * authenticating again, and a lock that fires constantly is a lock people
     * turn off. A minute is long enough to answer a message and short enough
     * that a phone left on a desk is not open.
     */
    const val GRACE_MILLIS = 60_000L

    /** In memory only: killing the app always relocks it. */
    @Volatile
    private var unlockedAt: Long = 0L

    @Volatile
    private var backgroundedAt: Long = 0L

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCK, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK, enabled).apply()
        if (!enabled) unlockedAt = System.currentTimeMillis()
    }

    /**
     * Keeps the task list out of the app switcher and out of screenshots.
     *
     * Separate from the lock because they answer different worries. A shoulder
     * surfer sees the recents thumbnail without ever holding the phone, and
     * somebody who does not want a passcode prompt may still want that.
     */
    fun hideContent(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE, false)

    fun setHideContent(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE, hide).apply()
    }

    /**
     * Whether this device can actually ask. A lock nobody can pass is a locked
     * phone, so the setting refuses to turn on when this is false.
     */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    /** Why it is unavailable, in words the settings screen can show. */
    fun unavailableReason(context: Context): String? =
        when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "Set a screen lock, fingerprint or face unlock in your phone's settings first."
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            ->
                "This device cannot ask for a fingerprint, face or passcode."
            else -> "Your phone is not able to ask for a passcode right now."
        }

    fun locked(context: Context): Boolean {
        if (!enabled(context)) return false
        if (unlockedAt == 0L) return true
        val away = backgroundedAt
        if (away == 0L) return false
        return System.currentTimeMillis() - away > GRACE_MILLIS
    }

    fun markUnlocked() {
        unlockedAt = System.currentTimeMillis()
        backgroundedAt = 0L
    }

    /** Called when the app goes to the background; starts the grace clock. */
    fun markBackgrounded() {
        if (backgroundedAt == 0L) backgroundedAt = System.currentTimeMillis()
    }

    /** Called when the app comes back; clears the clock if we are still inside the grace window. */
    fun markForegrounded() {
        if (backgroundedAt != 0L && System.currentTimeMillis() - backgroundedAt <= GRACE_MILLIS) {
            backgroundedAt = 0L
        }
    }

    /** Locks immediately, whatever the grace window says. */
    fun lockNow() {
        unlockedAt = 0L
        backgroundedAt = 0L
    }

    /** A weak biometric is enough here, and a device passcode always works. */
    const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private const val KEY_LOCK = "app_lock"
    private const val KEY_HIDE = "hide_content"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("taskmind_security", Context.MODE_PRIVATE)
}
