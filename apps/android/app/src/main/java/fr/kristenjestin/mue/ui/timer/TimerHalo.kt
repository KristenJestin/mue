package fr.kristenjestin.mue.ui.timer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/** The prototype's `h-60 w-60` dial, two rails narrower so a 640 dp screen still holds it. */
internal val TimerHaloDiameter: Dp = 220.dp

/** How far in from the rim the opaque face sits, which is where the amber shows through. */
private val HaloFaceInset: Dp = 34.dp

/**
 * The pulse of the prototype's `pulse-soft`, at its own speed.
 *
 * Deliberately not run through [fr.kristenjestin.mue.ui.theme.MueMotion.durationOf]: that
 * collapses a transition to a short fade, and a breath collapsed to 100 ms is a strobe. PRD 11
 * asks for a slow halo without an aggressive pulse and for reduced motion to be honoured — so
 * reduced motion switches the movement **off** here rather than speeding it up.
 */
private const val HaloBreathMillis = 1_600

private const val HaloRestingScale = 0.96f
private const val HaloPeakScale = 1.05f
private const val HaloRestingAlpha = 0.26f
private const val HaloPeakAlpha = 0.55f

/** Where the amber ring peaks, as a fraction of the rim: just outside the opaque face. */
private const val HaloRingStop = 0.74f

/**
 * The dial of PRD 6.3: a hairline rim, an opaque face, and a slow amber halo behind it while
 * the timer runs.
 *
 * [active] is the whole of the halo's state — PRD 6.3 gives a paused timer *no* halo, not a
 * dimmer one, so the amber is absent rather than faded, and the state is never carried by that
 * absence alone (the status word beside it says which it is).
 *
 * The breath is composed only while it is wanted: an infinite transition that exists and is
 * ignored keeps a frame clock awake for a screen that may sit paused for an hour.
 */
@Composable
internal fun TimerHalo(
    active: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = TimerHaloDiameter,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MueTheme.colors
    val breath = if (active && !LocalReduceMotion.current) breathing() else HaloStill

    val glowAlpha = if (active) lerp(HaloRestingAlpha, HaloPeakAlpha, breath) else 0f
    val glowScale = lerp(HaloRestingScale, HaloPeakScale, breath)

    Box(
        modifier = modifier
            .size(diameter)
            .drawBehind {
                val rim = size.minDimension / 2f
                val face = rim - HaloFaceInset.toPx()

                if (glowAlpha > 0f) {
                    val reach = rim * glowScale
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to colors.glow.copy(alpha = 0f),
                                HaloRingStop to colors.glow.copy(alpha = glowAlpha),
                                1f to colors.glow.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = reach,
                        ),
                        radius = reach,
                    )
                }

                drawCircle(color = colors.hairline, radius = rim, style = Stroke(RimWidth.toPx()))
                drawCircle(color = colors.canvasElevated, radius = face)
                drawCircle(color = colors.hairline, radius = face, style = Stroke(RimWidth.toPx()))
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Half a breath, which is what a still halo is worth. */
private const val HaloStill = 0.5f

private val RimWidth: Dp = 1.dp

@Composable
private fun breathing(): Float {
    val transition = rememberInfiniteTransition(label = "timerHalo")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(HaloBreathMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "timerHaloBreath",
    )
    return breath
}
