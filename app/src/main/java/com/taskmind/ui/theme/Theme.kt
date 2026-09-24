package com.taskmind.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.taskmind.core.Priority
import com.taskmind.ui.design.Radius

/**
 * Spec 16: dark and light themes following the system, with 4.5:1 contrast.
 * Dynamic colour is used where the platform offers it, because a phone that
 * already looks like the user's wallpaper is one less thing to argue with.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D5D4B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F2D6),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4B635A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE9DC),
    onSecondaryContainer = Color(0xFF072018),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6BB),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFFA7F2D6),
    secondary = Color(0xFFB2CCC0),
    onSecondary = Color(0xFF1D352C),
    secondaryContainer = Color(0xFF344C42),
    onSecondaryContainer = Color(0xFFCDE9DC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF89938E),
)

/**
 * Dynamic colour is OFF by default - see [ThemePrefs.dynamicColour].
 *
 * The parameters stay so a preview can force either, but the running app takes
 * its answer from the store, synchronously, because a suspending read means
 * the first frame is drawn in one palette and repainted in another.
 *
 * The shapes are wired in too. [Radius] existed and every screen applied it by
 * hand, which meant anything drawn by Material itself - menus, sheets, dialogs,
 * chips - kept Material's single default radius and quietly disagreed with
 * everything around it.
 */
@Composable
fun TaskMindTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = darkTheme ?: ThemePrefs.darkOverride(context) ?: isSystemInDarkTheme()
    val dynamic = dynamicColor ?: ThemePrefs.dynamicColour(context)

    val colorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TaskMindTypography,
        shapes = TaskMindShapes,
        content = content,
    )
}

/**
 * One radius scale, from [Radius], so a dropdown menu and the card it opens
 * over have the same corners.
 */
private val TaskMindShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.chip),
    small = RoundedCornerShape(Radius.chip),
    medium = RoundedCornerShape(Radius.row),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.sheet),
)

/** Priority carries visual weight (spec 16), and a label for screen readers. */
object PriorityStyle {

    fun color(priority: Priority, dark: Boolean): Color = when (priority) {
        Priority.URGENT -> if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E)
        Priority.HIGH -> if (dark) Color(0xFFFFCC80) else Color(0xFFB25E00)
        Priority.MEDIUM -> if (dark) Color(0xFF8BD6BB) else Color(0xFF1D5D4B)
        Priority.LOW -> if (dark) Color(0xFFB0BEC5) else Color(0xFF5F6B67)
    }

    fun label(priority: Priority): String = when (priority) {
        Priority.URGENT -> "Urgent"
        Priority.HIGH -> "High"
        Priority.MEDIUM -> "Medium"
        Priority.LOW -> "Low"
    }
}
