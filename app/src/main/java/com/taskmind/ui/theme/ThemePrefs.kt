package com.taskmind.ui.theme

import android.content.Context

/**
 * The two theme choices, in a store that answers synchronously.
 *
 * Same reason as the app lock: the theme is decided before the first frame.
 * A suspending read means the app composes in one palette and repaints in
 * another, which is a visible flash on every launch.
 */
object ThemePrefs {

    /**
     * Dynamic colour is OFF by default now, and that is the single biggest
     * visual change in this release.
     *
     * It was on. On Android 12 and later that throws away every colour chosen
     * for this app and replaces the lot with a scheme derived from the
     * wallpaper - so the considered green, the priority ramp and the
     * overdue red all became whatever the wallpaper happened to suggest. It
     * is a lovely feature for a calendar widget and the wrong default for an
     * app whose colours carry meaning.
     *
     * Still offered, because some people want the phone to match itself.
     */
    fun dynamicColour(context: Context): Boolean = prefs(context).getBoolean(KEY_DYNAMIC, false)

    fun setDynamicColour(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    /** null follows the system, which is the default. */
    fun darkOverride(context: Context): Boolean? =
        prefs(context).let { if (it.contains(KEY_DARK)) it.getBoolean(KEY_DARK, false) else null }

    fun setDarkOverride(context: Context, dark: Boolean?) {
        prefs(context).edit().apply {
            if (dark == null) remove(KEY_DARK) else putBoolean(KEY_DARK, dark)
        }.apply()
    }

    private const val KEY_DYNAMIC = "dynamic_colour"
    private const val KEY_DARK = "dark_override"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("taskmind_theme", Context.MODE_PRIVATE)
}
