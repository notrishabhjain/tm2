package com.taskmind.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.design.Space
import com.taskmind.ui.theme.ThemePrefs

/**
 * Light or dark, and whether to follow the wallpaper.
 *
 * Both are read synchronously when the theme is built, so both need the
 * activity to be recreated to take effect. Saying that on screen is cheaper
 * and more honest than a restart-yourself mechanism that would be the only
 * one of its kind in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSection() {
    val context = LocalContext.current
    var dark by remember(context) { mutableStateOf(ThemePrefs.darkOverride(context)) }
    var dynamic by remember(context) { mutableStateOf(ThemePrefs.dynamicColour(context)) }

    SectionCard(title = "Appearance") {
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Space.snug))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
            val options = listOf<Pair<String, Boolean?>>(
                "Follow system" to null,
                "Light" to false,
                "Dark" to true,
            )
            options.forEach { (label, value) ->
                FilterChip(
                    selected = dark == value,
                    onClick = {
                        dark = value
                        ThemePrefs.setDarkOverride(context, value)
                    },
                    label = { Text(label) },
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(Modifier.height(Space.step))
            LabeledSwitch(
                label = "Use my wallpaper's colours",
                checked = dynamic,
                onCheckedChange = {
                    dynamic = it
                    ThemePrefs.setDynamicColour(context, it)
                },
                description = "Off by default. TaskMind's own colours carry meaning — the red edge " +
                    "on an overdue task, the priority ramp — and the wallpaper palette replaces all " +
                    "of them with whatever your background happens to suggest.",
            )
        }

        Spacer(Modifier.height(Space.step))
        Text(
            "Reopen TaskMind for a change here to take effect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
