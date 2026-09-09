package com.taskmind.ui.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The measurements every screen shares.
 *
 * Before this, each screen picked its own padding - 12 here, 16 there, 10 in
 * the one written last - and the result read as clutter even where the content
 * was fine. Uneven spacing is what "cluttered" usually means: the eye cannot
 * find the grid, so nothing groups and everything competes.
 *
 * One scale, used everywhere, is most of the fix.
 */
object Space {
    /** Between a label and the thing it labels. */
    val hair: Dp = 2.dp

    /** Inside a row: icon to text, chip to chip. */
    val tight: Dp = 4.dp

    /** Between lines of related text. */
    val snug: Dp = 8.dp

    /** The default gap between siblings. */
    val step: Dp = 12.dp

    /** Screen margins, and the inside of a card. */
    val edge: Dp = 16.dp

    /** Between one group and the next. */
    val section: Dp = 24.dp

    /** Above a section header that follows content. */
    val breath: Dp = 32.dp

    /** Clears a floating action button at the end of a list. */
    val listBottom: Dp = 96.dp

    val screen = PaddingValues(horizontal = edge)
}

/**
 * Corner radii, by how much the surface should feel like it is floating.
 *
 * Material's default is a single radius everywhere, which flattens the
 * hierarchy: a full-width card and a small chip read as the same kind of
 * object. Varying it by size restores the difference.
 */
object Radius {
    val chip: Dp = 8.dp
    val row: Dp = 12.dp
    val card: Dp = 16.dp
    val sheet: Dp = 28.dp
}

/**
 * Minimum touch target, per the accessibility guidelines and per anyone who
 * has tried to tick a checkbox on a moving train.
 */
object Touch {
    val min: Dp = 48.dp
}
