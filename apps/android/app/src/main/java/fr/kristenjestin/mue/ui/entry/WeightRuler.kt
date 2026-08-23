package fr.kristenjestin.mue.ui.entry

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Height of the whole scale strip: graduations, labels and the centre marker. */
val WeightRulerHeight: Dp = 100.dp

private val TickCentreY: Dp = 34.dp
private val MinorTickHalfHeight: Dp = 7.dp
private val MediumTickHalfHeight: Dp = 12.dp
private val MajorTickHalfHeight: Dp = 18.dp
private val MinorTickWidth: Dp = 1.dp
private val MediumTickWidth: Dp = 1.5.dp
private val MajorTickWidth: Dp = 2.dp
private val LabelTop: Dp = 74.dp
private val MarkerTop: Dp = 8.dp
private val MarkerBottom: Dp = 60.dp
private val MarkerWidth: Dp = 2.dp
private val MarkerDotY: Dp = 65.dp
private val MarkerDotRadius: Dp = 5.dp
private val MarkerGlowRadius: Dp = 22.dp
private val StepButtonSize: Dp = MueMinTouchTarget

/** Graduation opacities, relative to `textQuiet`, matched to the prototype's 24 % hairlines. */
private const val MinorTickAlpha = 0.6f
private const val MediumTickAlpha = 1f

/** How long the amber flare lingers on the marker after a save (PRD 13). */
private const val SaveFlareMillis = 700

/** Discrete positions TalkBack can stop on: one per 0.05 kg, ends excluded. */
private val AdjustableSteps: Int =
    (Weight.MAX_HUNDREDTHS - Weight.MIN_HUNDREDTHS) / Weight.STEP_HUNDREDTHS - 1

/**
 * The touch scale: a fixed amber marker with the ruler sliding underneath it.
 *
 * Two rules drive the whole implementation. The marker never moves, and dragging left
 * *increases* the weight because values grow left to right along the ruler
 * (PRD FR-ENTRY-002) — that direction is the one thing here which is not tunable.
 *
 * Nothing about the movement is composed. The gesture writes into [ruler], the graduations
 * read it back inside the draw scope, and [onWeightChange] is called once, when the scale has
 * stopped. What recomposes during a drag is therefore nothing at all in this file.
 */
@Composable
fun WeightRuler(
    ruler: RulerState,
    weight: Weight,
    onWeightChange: (Weight) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onHapticTick: () -> Unit = {},
    saveFlareCount: Int = 0,
) {
    val colors = MueTheme.colors
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val pixelsPerHundredth = with(density) { RulerPhysics.DP_PER_HUNDREDTH.dp.toPx() }

    val currentOnWeightChange by rememberUpdatedState(onWeightChange)
    val currentOnHapticTick by rememberUpdatedState(onHapticTick)
    val allowFling by rememberUpdatedState(!reduceMotion)

    /*
     * The half-kilogram ticks of PRD FR-ENTRY-002.
     *
     * Observed through a snapshot flow rather than through a `LaunchedEffect` key: a key is a
     * composition read, and reading the live value here would undo the whole point of keeping
     * it out of composition. Only movement of the ruler ticks — a value arriving from the
     * keyboard or from `−` / `+` leaves [RulerState.interacting] false and stays silent.
     */
    LaunchedEffect(ruler) {
        var previous = ruler.displayedHundredths
        snapshotFlow { ruler.displayedHundredths }.collect { hundredths ->
            val crossed = RulerPhysics.crossesHapticStep(previous, hundredths)
            previous = hundredths
            if (crossed && ruler.interacting) currentOnHapticTick()
        }
    }

    val flare = remember { Animatable(0f) }
    LaunchedEffect(saveFlareCount) {
        if (saveFlareCount > 0 && !reduceMotion) {
            flare.snapTo(1f)
            flare.animateTo(0f, tween(SaveFlareMillis, easing = LinearEasing))
        }
    }

    val textMeasurer = rememberTextMeasurer(cacheSize = 24)
    val labelStyle = MueTheme.typography.micro.copy(color = colors.textQuiet)
    val tickPalette = remember(colors) {
        RulerPalette(quiet = colors.textQuiet, major = colors.textSecondary, accent = colors.accent)
    }

    val gestureModifier = if (enabled) {
        Modifier.pointerInput(pixelsPerHundredth) {
            awaitEachGesture {
                // One scope from touch down to lift, so no pointer event can fall between two
                // of them — on the one control that has to track the finger without a
                // perceptible lag (PRD 16.2).
                val down = awaitFirstDown(requireUnconsumed = false)
                ruler.onDragStart()

                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)

                horizontalDrag(down.id) { change ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    ruler.onDrag(change.positionChange().x, pixelsPerHundredth)
                    change.consume()
                }

                ruler.onDragEnd(
                    velocityHundredthsPerSecond = RulerPhysics.velocityToHundredths(
                        tracker.calculateVelocity().x,
                        pixelsPerHundredth,
                    ),
                    allowFling = allowFling,
                ) { hundredths ->
                    currentOnWeightChange(Weight.ofHundredthsClamped(hundredths))
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WeightRulerHeight)
            .then(gestureModifier)
            .semantics {
                contentDescription = "Weight scale"
                stateDescription = EntryFormat.spokenWeight(weight)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = weight.hundredthsKg.toFloat(),
                    range = RulerPhysics.LOWER_STOP..RulerPhysics.UPPER_STOP,
                    steps = AdjustableSteps,
                )
                setProgress { value ->
                    val hundredths = RulerPhysics.snapToStep(value)
                    // The ruler is moved here rather than through the screen's state: an
                    // accessibility action is an order, and orders move the scale directly.
                    ruler.jumpTo(hundredths)
                    currentOnWeightChange(Weight.ofHundredthsClamped(hundredths))
                    true
                }
                if (!enabled) disabled()
            }
            .rulerGraduations(ruler, flare, textMeasurer, labelStyle, tickPalette),
    )
}

/** The three greys of a graduation and the amber of the marker, resolved once. */
private class RulerPalette(val quiet: Color, val major: Color, val accent: Color)

/**
 * Everything that does not move: the geometry in pixels and the marker's halo shader.
 *
 * Rebuilt only when the strip is resized or when the save flare animates, never while the
 * ruler slides. That matters most for the halo: a radial gradient rebuilt per frame is a new
 * shader for the GPU to upload on every one of them.
 */
private class RulerCanvasCache(
    val centreX: Float,
    val pixelsPerHundredth: Float,
    val tickCentre: Float,
    val labelTop: Float,
    val glowCentre: Offset,
    val glowRadius: Float,
    val glow: Brush,
)

/**
 * Paints the graduations, their labels and the centre marker.
 *
 * [Modifier.drawWithCache] rather than a `Canvas`: the position is read inside `onDrawBehind`,
 * so a drag invalidates the draw phase and nothing else — no recomposition, no relayout.
 */
private fun Modifier.rulerGraduations(
    ruler: RulerState,
    flare: Animatable<Float, *>,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    palette: RulerPalette,
): Modifier = drawWithCache {
    val cache = buildCanvasCache(palette.accent, flare.value)

    onDrawBehind {
        val position = ruler.positionHundredths
        val centreX = cache.centreX
        val pixelsPerHundredth = cache.pixelsPerHundredth

        // Indexed by graduation, not by reachable value: the scale settles every 0.05 kg but
        // still draws a line every 0.1 kg (PRD FR-ENTRY-002), so this loop is the length it
        // has always been. Iterating the values instead would double it, every frame.
        for (index in RulerPhysics.visibleTicks(position, centreX, pixelsPerHundredth)) {
            val x = RulerPhysics.tickX(index, position, pixelsPerHundredth, centreX)
            val alpha = RulerPhysics.edgeAlpha(x - centreX, centreX)
            if (alpha <= 0.01f) continue

            val tick = RulerPhysics.tickOf(index)
            val halfHeight = when (tick) {
                RulerTick.Minor -> MinorTickHalfHeight
                RulerTick.Medium -> MediumTickHalfHeight
                RulerTick.Major -> MajorTickHalfHeight
            }.toPx()
            val strokeWidth = when (tick) {
                RulerTick.Minor -> MinorTickWidth
                RulerTick.Medium -> MediumTickWidth
                RulerTick.Major -> MajorTickWidth
            }.toPx()
            val tint = when (tick) {
                RulerTick.Minor -> palette.quiet.scaleAlpha(MinorTickAlpha * alpha)
                RulerTick.Medium -> palette.quiet.scaleAlpha(MediumTickAlpha * alpha)
                RulerTick.Major -> palette.major.scaleAlpha(alpha)
            }

            drawLine(
                color = tint,
                start = Offset(x, cache.tickCentre - halfHeight),
                end = Offset(x, cache.tickCentre + halfHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )

            if (tick == RulerTick.Major) {
                val label = textMeasurer.measure(
                    RulerPhysics.tickLabel(index).toString(),
                    labelStyle,
                )
                drawText(
                    textLayoutResult = label,
                    color = palette.quiet,
                    topLeft = Offset(x - label.size.width / 2f, cache.labelTop),
                    alpha = alpha,
                )
            }
        }

        drawMarker(centreX, palette.accent, cache)
    }
}

/** Dims a palette colour without discarding the transparency the token already carries. */
private fun Color.scaleAlpha(factor: Float): Color = copy(alpha = alpha * factor)

private fun CacheDrawScope.buildCanvasCache(accent: Color, flare: Float): RulerCanvasCache {
    val centreX = size.width / 2f
    val glowRadius = MarkerGlowRadius.toPx() * (1f + 0.6f * flare)
    val glowCentre = Offset(centreX, (MarkerTop.toPx() + MarkerBottom.toPx()) / 2f)
    return RulerCanvasCache(
        centreX = centreX,
        pixelsPerHundredth = RulerPhysics.DP_PER_HUNDREDTH.dp.toPx(),
        tickCentre = TickCentreY.toPx(),
        labelTop = LabelTop.toPx(),
        glowCentre = glowCentre,
        glowRadius = glowRadius,
        glow = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.30f + 0.45f * flare), Color.Transparent),
            center = glowCentre,
            radius = glowRadius,
        ),
    )
}

private fun DrawScope.drawMarker(centreX: Float, accent: Color, cache: RulerCanvasCache) {
    drawCircle(brush = cache.glow, radius = cache.glowRadius, center = cache.glowCentre)
    drawLine(
        color = accent,
        start = Offset(centreX, MarkerTop.toPx()),
        end = Offset(centreX, MarkerBottom.toPx()),
        strokeWidth = MarkerWidth.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(
        color = accent,
        radius = MarkerDotRadius.toPx(),
        center = Offset(centreX, MarkerDotY.toPx()),
    )
}

/**
 * `−` and `+`, the complete alternative to the gesture demanded by PRD FR-ENTRY-003 and 14.
 *
 * The repeat lives in a plain coroutine rather than in a gesture library so it can accelerate,
 * and the click is published through semantics so TalkBack's double tap gives one clean step
 * with no long press involved.
 *
 * [onStep] is told whether it was reached by a deliberate press or by the auto-repeat of a
 * held one. Only the caller knows what to make of that; the button itself treats them alike.
 */
@Composable
fun RulerStepButton(
    glyph: String,
    stepDescription: String,
    onStep: (held: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MueTheme.colors
    val currentOnStep by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(StepButtonSize)
            .background(
                color = if (pressed) colors.surfaceStrong else colors.surface,
                shape = CircleShape,
            )
            .border(1.dp, colors.surfaceBorder, CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    while (isActive) {
                        // Press and release stay in the same scope: between two of them no
                        // pointer event is queued, and a quick tap could fall in the gap.
                        awaitPointerEventScope {
                            awaitFirstDown().consume()
                            pressed = true
                            currentOnStep(false)
                            val repeat = launch { repeatStep { currentOnStep(true) } }
                            waitForUpOrCancellation()
                            repeat.cancel()
                            pressed = false
                        }
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = stepDescription
                onClick {
                    currentOnStep(false)
                    true
                }
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        MueText(
            text = glyph,
            style = MueTheme.typography.metricMedium,
            color = if (enabled) colors.accent else colors.textQuiet,
        )
    }
}

/** PRD FR-ENTRY-003: repeat after about 400 ms, then speed up. */
private suspend fun repeatStep(onStep: () -> Unit) {
    delay(RulerPhysics.STEP_REPEAT_DELAY_MILLIS)
    var iteration = 0
    while (true) {
        onStep()
        delay(RulerPhysics.repeatIntervalMillis(iteration))
        iteration++
    }
}
