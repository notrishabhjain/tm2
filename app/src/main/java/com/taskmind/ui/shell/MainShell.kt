package com.taskmind.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The four places worth keeping one tap away.
 *
 * Everything used to hang off an overflow menu on the task screen, which is
 * the "hard to find" complaint in one sentence: a menu is where you look when
 * you already know a thing exists. A bar names the destinations up front, and
 * carries the one number that should never need hunting for - how many items
 * are waiting for a decision.
 */
enum class MainTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    TASKS("tasks", "Tasks", Icons.Filled.Checklist, Icons.Outlined.Checklist),
    REVIEW("review", "Review", Icons.Filled.Inbox, Icons.Outlined.Inbox),
    CALLS("calls", "Calls", Icons.Filled.Phone, Icons.Outlined.Phone),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * Wraps a top-level destination with the navigation bar.
 *
 * Only the four tabs use this. A pushed screen - a task, a sub-page of
 * settings - keeps a back arrow and no bar, so "deeper" always looks
 * different from "elsewhere".
 */
@Composable
fun MainShell(
    current: MainTab,
    reviewBadge: Int,
    onSelect: (MainTab) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                MainTab.entries.forEach { tab ->
                    val selected = tab == current
                    NavigationBarItem(
                        selected = selected,
                        onClick = { if (!selected) onSelect(tab) },
                        icon = {
                            val icon = if (selected) tab.selectedIcon else tab.icon
                            if (tab == MainTab.REVIEW && reviewBadge > 0) {
                                BadgedBox(badge = { Badge { Text(reviewBadge.toString()) } }) {
                                    Icon(icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
            content(Modifier)
        }
    }
}
