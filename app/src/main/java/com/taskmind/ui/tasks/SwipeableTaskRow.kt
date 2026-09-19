package com.taskmind.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.taskmind.prefs.UiPreferences.SwipeAction
import com.taskmind.ui.design.Space

/**
 * A task row you can swipe.
 *
 * WHY THE ROW SNAPS BACK
 *
 * `confirmValueChange` always returns false, so the box never settles in a
 * dismissed state - it performs the action and springs back. The row then
 * disappears, or does not, because the list changed underneath it.
 *
 * Letting the dismiss stick looks smoother for one frame and is wrong the
 * moment an action does not remove the row from the current view: completing
 * a task while looking at "All" would leave a permanently blank space where it
 * used to be. Snapping back is correct in every case, which is worth more than
 * the animation.
 *
 * Swiping is off in selection mode, where a horizontal drag is how you scrub
 * a multi-select and would otherwise fight this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableTaskRow(
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    enabled: Boolean,
    onAction: (SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled || (rightAction == SwipeAction.NONE && leftAction == SwipeAction.NONE)) {
        content()
        return
    }

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onAction(rightAction)
                SwipeToDismissBoxValue.EndToStart -> onAction(leftAction)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = rightAction != SwipeAction.NONE,
        enableDismissFromEndToStart = leftAction != SwipeAction.NONE,
        backgroundContent = {
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> rightAction
                SwipeToDismissBoxValue.EndToStart -> leftAction
                SwipeToDismissBoxValue.Settled -> null
            }
            SwipeBackground(
                action = action,
                alignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Alignment.CenterEnd
                } else {
                    Alignment.CenterStart
                },
            )
        },
        content = { content() },
    )
}

/**
 * What shows behind the row as it moves.
 *
 * Colour carries the meaning before the icon is readable: green for done,
 * red for gone, neutral for everything reversible in between.
 */
@Composable
private fun SwipeBackground(action: SwipeAction?, alignment: Alignment) {
    val colour = when (action) {
        SwipeAction.COMPLETE -> MaterialTheme.colorScheme.primaryContainer
        SwipeAction.DELETE -> MaterialTheme.colorScheme.errorContainer
        SwipeAction.ARCHIVE, SwipeAction.SNOOZE -> MaterialTheme.colorScheme.secondaryContainer
        SwipeAction.NONE, null -> Color.Transparent
    }
    val icon: ImageVector? = when (action) {
        SwipeAction.COMPLETE -> Icons.Filled.Check
        SwipeAction.DELETE -> Icons.Outlined.Delete
        SwipeAction.ARCHIVE -> Icons.Outlined.Archive
        SwipeAction.SNOOZE -> Icons.Outlined.Snooze
        SwipeAction.NONE, null -> null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colour)
            .padding(horizontal = Space.section),
        contentAlignment = alignment,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = action?.label)
        }
    }
}
