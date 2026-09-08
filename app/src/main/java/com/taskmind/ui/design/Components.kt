package com.taskmind.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The building blocks the redesigned screens are made of.
 *
 * Deliberately few. A component set that offers three ways to draw a row is a
 * component set that produces three different-looking rows on three screens,
 * which is where the inconsistency came from in the first place.
 */

/**
 * A heading that separates one group from the next, with an optional count.
 *
 * The count carries real weight here: "Overdue" tells you a section exists,
 * "Overdue 3" tells you whether to care. Putting it in the header rather than
 * making the user count rows is the difference between scanning and reading.
 */
@Composable
fun GroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    accent: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge, vertical = Space.snug),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(Space.snug))
            CountPill(count, accent)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A small number on a tinted ground. Reads as data, not as a button. */
@Composable
fun CountPill(count: Int, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        shape = RoundedCornerShape(Radius.chip),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = Space.snug, vertical = Space.hair),
        )
    }
}

/**
 * One tappable line in a settings list, in the shape every phone already uses.
 *
 * Icon, title, a line of supporting text, a chevron. Familiar on purpose: the
 * settings screen is not the place to teach anyone a new interaction.
 */
@Composable
fun NavRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Touch.min)
            .padding(horizontal = Space.edge, vertical = Space.step),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(Space.edge))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (badge != null && badge > 0) {
            CountPill(badge, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.snug))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * A group of rows on one raised surface.
 *
 * Grouping is what makes a long settings screen readable: six labelled groups
 * of four rows can be skimmed, twenty-four rows in a column cannot.
 */
@Composable
fun Grouped(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) GroupHeader(title)
        Surface(
            shape = RoundedCornerShape(Radius.card),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.edge),
        ) {
            Column(Modifier.padding(vertical = Space.tight), content = { content() })
        }
    }
}

/**
 * A hairline between rows inside a group, inset past the icon column so it
 * separates the text rather than cutting the row in half.
 */
@Composable
fun RowDivider(inset: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (inset) 54.dp else Space.edge, end = Space.edge)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
    )
}

/**
 * What a screen shows when it has nothing to show.
 *
 * An empty list with no explanation is indistinguishable from a broken one -
 * a distinction this app has had to make more than once.
 */
@Composable
fun Empty(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Space.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.step),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

/** A short, quiet label - a source, a tag, a state. Never interactive. */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.chip),
        color = color.copy(alpha = 0.10f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Space.snug, vertical = Space.hair),
        )
    }
}

/** Padding that clears a floating action button at the end of a list. */
fun listContentPadding(): PaddingValues =
    PaddingValues(top = Space.snug, bottom = Space.listBottom)
