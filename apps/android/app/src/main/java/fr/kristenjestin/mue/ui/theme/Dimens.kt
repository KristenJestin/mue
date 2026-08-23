package fr.kristenjestin.mue.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale of the prototypes, which lay out on a 4 dp grid with an unusually wide
 * screen gutter — low density is part of the visual identity.
 */
@Immutable
data class MueSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 28.dp,
    val xxxl: Dp = 40.dp,
    /** Gutter on both sides of every screen. */
    val screenHorizontal: Dp = 28.dp,
    /** Gap between the status bar and the header row. */
    val screenTop: Dp = 12.dp,
    /** Gap between the header row and the screen title block. */
    val headerToContent: Dp = 28.dp,
    /** Breathing room at the bottom of a scrolling screen, above the tab bar. */
    val screenBottom: Dp = 24.dp,
    /** Padding inside a large card. */
    val cardPadding: Dp = 20.dp,
    /** Padding inside a compact card or a field. */
    val fieldPaddingHorizontal: Dp = 16.dp,
    val fieldPaddingVertical: Dp = 12.dp,
)

@Immutable
data class MueShapes(
    /** Large cards and the bottom sheet. */
    val card: Shape = RoundedCornerShape(28.dp),
    /** Compact cards, fields and buttons. */
    val field: Shape = RoundedCornerShape(18.dp),
    val button: Shape = RoundedCornerShape(18.dp),
    val small: Shape = RoundedCornerShape(12.dp),
    val pill: Shape = RoundedCornerShape(percent = 50),
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
)

/** Android's minimum touch target; every interactive component honours it. */
val MueMinTouchTarget: Dp = 48.dp

val LocalMueSpacing = staticCompositionLocalOf { MueSpacing() }
val LocalMueShapes = staticCompositionLocalOf { MueShapes() }
