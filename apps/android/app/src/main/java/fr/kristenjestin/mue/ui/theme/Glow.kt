package fr.kristenjestin.mue.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min

/** Alpha of the glow at its centre. Chosen so white-on-glow text keeps 4.5:1 at the peak. */
private const val DefaultGlowAlpha = 0.26f

/** The prototype fades the amber to nothing at 65 % of the gradient ray. */
private const val TransparentStopFraction = 0.65f

/**
 * Amber halo bleeding down from the top edge, shared by the three screens.
 *
 * Applied behind the content, so translucent cards and text sit on top of it exactly as in
 * the prototypes. The end colour keeps the amber hue at zero alpha: fading to
 * [Color.Transparent] would drag the ramp through black and dirty the halo.
 */
fun Modifier.mueAmberGlow(
    color: Color,
    height: Dp = 320.dp,
    alpha: Float = DefaultGlowAlpha,
): Modifier = this.drawWithCache {
    val glowHeight = height.toPx()
    val radius = TransparentStopFraction * hypot(size.width / 2f, glowHeight)
    val brush = Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
        center = Offset(size.width / 2f, 0f),
        radius = radius,
    )
    onDrawBehind {
        drawRect(brush = brush, size = Size(size.width, min(glowHeight, size.height)))
    }
}

/** Overload using the current theme accent. */
@Composable
fun Modifier.mueAmberGlow(
    height: Dp = 320.dp,
    alpha: Float = DefaultGlowAlpha,
): Modifier = mueAmberGlow(color = MueTheme.colors.glow, height = height, alpha = alpha)
