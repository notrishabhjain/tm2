package com.taskmind.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * The type scale, tuned for a list you read rather than a page you admire.
 *
 * Material's defaults were used before, unaltered, and they are built for
 * roomy marketing surfaces: generous line heights, wide tracking on labels,
 * body and title too close in weight to separate at a glance. In a dense
 * agenda that reads as mush - which is most of what "it looks unpolished"
 * turns out to mean.
 *
 * Three deliberate departures:
 *
 *  - Titles are heavier and tracked tighter, so a task title is unmistakably
 *    the thing to read first and everything else recedes.
 *  - Body line height is pulled in. Two-line task titles were floating apart.
 *  - Labels lose Material's wide letter spacing, which was designed for
 *    all-caps buttons and makes lower-case chips look stretched.
 *
 * No custom font file. A downloaded typeface would cost a few hundred KB in
 * the APK and a fallback path to get wrong, for a change nobody asked for; the
 * system font on this phone is already good and already familiar.
 */
private val trimmed = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = trimmed,
)

val TaskMindTypography = Typography(
    displaySmall = style(34, 40, FontWeight.Normal, (-0.4)),
    headlineMedium = style(26, 32, FontWeight.SemiBold, (-0.3)),
    headlineSmall = style(22, 28, FontWeight.SemiBold, (-0.2)),

    titleLarge = style(21, 27, FontWeight.SemiBold, (-0.2)),
    titleMedium = style(16, 22, FontWeight.SemiBold, (-0.1)),
    titleSmall = style(14, 20, FontWeight.Medium),

    bodyLarge = style(16, 23),
    bodyMedium = style(14, 20),
    bodySmall = style(13, 18),

    labelLarge = style(14, 18, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium, 0.1),
    labelSmall = style(11, 15, FontWeight.Medium, 0.1),
)
