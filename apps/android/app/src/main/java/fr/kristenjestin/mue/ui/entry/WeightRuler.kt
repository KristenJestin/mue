package fr.kristenjestin.mue.ui.entry

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.roundToInt

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

/**
 * The touch scale: a fixed amber marker with the ruler sliding underneath it.
 *
 * Two rules drive the whole implementation. The marker never moves, and dragging left
 * *increases* the weight because values grow left to right along the ruler
 * (PRD FR-ENTRY-002) — that direction is the one thing here which is not tunable.
 *
 * The continuous position lives in an [Animatable] measured in tenths of a kilogram. That
 * buys three PRD requirements at once: [Animatable.updateBounds] turns 30.0 and 250.0 into
 * dead stops with no rebound, the decay is genuinely cancellable by a new touch, and the
 * magnetism is a plain `animateTo` onto the nearest whole tenth.
 */
@Composable
fun WeightRuler(
    weight: Weight,
    onWeightChange: (Weight) -> Unit,
    modifier: Modifier = Modifier,
    weightRevision: Int = 0,
    enabled: Boolean = true,
    onHapticTick: () -> Unit = {},
    saveFlareCount: Int = 0,
) {
    val colors = MueTheme.colors
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val pixelsPerTenth = with(density) { RulerPhysics.DP_PER_TENTH.dp.toPx() }

    val position = remember {
        Animatable(weight.tenthsKg.toFloat()).apply {
            updateBounds(RulerPhysics.LOWER_STOP, RulerPhysics.UPPER_STOP)
        }
    }
    var dragging by remember { mutableStateOf(false) }
    val settling = position.isRunning
    val displayedTenths by remember { derivedStateOf { RulerPhysics.snapToTenth(position.value) } }

    val currentOnWeightChange by rememberUpdatedState(onWeightChange)
    val currentOnHapticTick by rememberUpdatedState(onHapticTick)
    val allowFling by rememberUpdatedState(!reduceMotion)

    val lastTicked = remember { mutableIntStateOf(weight.tenthsKg) }
    LaunchedEffect(displayedTenths) {
        val previous = lastTicked.intValue
        lastTicked.intValue = displayedTenths
        // Only movement of the ruler ticks; a value arriving from the keyboard stays silent.
        if ((dragging || settling) && RulerPhysics.crossesHapticStep(previous, displayedTenths)) {
            currentOnHapticTick()
        }
        currentOnWeightChange(Weight.ofTenthsClamped(displayedTenths))
    }

    /*
     * The history seed, the `−` / `+` controls and the keyboard move the ruler from outside.
     *
     * The trigger is [weightRevision] and never [weight] itself: the screen's value trails the
     * ruler's position by a frame, so a ruler watching that value would keep mistaking a stale
     * echo of its own movement for an order to jump, and would fight its own inertia.
     */
    LaunchedEffect(weightRevision) {
        if (RulerPhysics.snapToTenth(position.value) != weight.tenthsKg) {
            lastTicked.intValue = weight.tenthsKg
            // Also cancels a running fling, which is what an incoming order should do.
            position.snapTo(weight.tenthsKg.toFloat())
        }
    }

    val decay = remember {
        exponentialDecay<Float>(
            frictionMultiplier = RulerPhysics.FLING_FRICTION_MULTIPLIER,
            absVelocityThreshold = RulerPhysics.FLING_VELOCITY_THRESHOLD,
        )
    }
    val settleSpec = remember {
        spring<Float>(
            dampingRatio = RulerPhysics.SETTLE_DAMPING_RATIO,
            stiffness = RulerPhysics.SETTLE_STIFFNESS,
        )
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

    val gestureModifier = if (enabled) {
        Modifier.pointerInput(pixelsPerTenth) {
            coroutineScope {
                while (isActive) {
                    val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                    // PRD FR-ENTRY-002: a new touch interrupts the inertia immediately.
                    position.stop()
                    dragging = true

                    // The target is accumulated here rather than read back from the Animatable
                    // so a burst of drag events can never apply out of order.
                    var target = position.value
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    awaitPointerEventScope {
                        horizontalDrag(down.id) { change ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            target = RulerPhysics.clampPosition(
                                target + RulerPhysics.dragToTenths(
                                    change.positionChange().x,
                                    pixelsPerTenth,
                                ),
                            )
                            val frameTarget = target
                            launch { position.snapTo(frameTarget) }
                            change.consume()
                        }
                    }

                    val velocity = RulerPhysics.velocityToTenths(
                        tracker.calculateVelocity().x,
                        pixelsPerTenth,
                    )
                    dragging = false
                    launch { settleRuler(position, velocity, decay, settleSpec, allowFling) }
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
                    current = weight.tenthsKg.toFloat(),
                    range = RulerPhysics.LOWER_STOP..RulerPhysics.UPPER_STOP,
                    steps = Weight.MAX_TENTHS - Weight.MIN_TENTHS - 1,
                )
                setProgress { value ->
                    currentOnWeightChange(Weight.ofTenthsClamped(value.roundToInt()))
                    true
                }
                if (!enabled) disabled()
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centreX = size.width / 2f
            val tickCentre = TickCentreY.toPx()
            val currentPosition = position.value

            for (tenth in RulerPhysics.visibleTenths(currentPosition, centreX, pixelsPerTenth)) {
                val x = RulerPhysics.tickX(tenth, currentPosition, pixelsPerTenth, centreX)
                val alpha = RulerPhysics.edgeAlpha(x - centreX, centreX)
                if (alpha <= 0.01f) continue

                val tick = RulerPhysics.tickOf(tenth)
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
                    RulerTick.Minor -> colors.textQuiet.scaleAlpha(MinorTickAlpha * alpha)
                    RulerTick.Medium -> colors.textQuiet.scaleAlpha(MediumTickAlpha * alpha)
                    RulerTick.Major -> colors.textSecondary.scaleAlpha(alpha)
                }

                drawLine(
                    color = tint,
                    start = Offset(x, tickCentre - halfHeight),
                    end = Offset(x, tickCentre + halfHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )

                if (tick == RulerTick.Major) {
                    val label = textMeasurer.measure(
                        (tenth / RulerPhysics.TENTHS_PER_KILOGRAM).toString(),
                        labelStyle,
                    )
                    drawText(
                        textLayoutResult = label,
                        color = colors.textQuiet,
                        topLeft = Offset(x - label.size.width / 2f, LabelTop.toPx()),
                        alpha = alpha,
                    )
                }
            }

            drawMarker(centreX = centreX, accent = colors.accent, flare = flare.value)
        }
    }
}

/** Dims a palette colour without discarding the transparency the token already carries. */
private fun Color.scaleAlpha(factor: Float): Color = copy(alpha = alpha * factor)

private fun DrawScope.drawMarker(centreX: Float, accent: Color, flare: Float) {
    val markerTop = MarkerTop.toPx()
    val markerBottom = MarkerBottom.toPx()
    val glowRadius = MarkerGlowRadius.toPx() * (1f + 0.6f * flare)
    val glowCentre = Offset(centreX, (markerTop + markerBottom) / 2f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.30f + 0.45f * flare), Color.Transparent),
            center = glowCentre,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = glowCentre,
    )
    drawLine(
        color = accent,
        start = Offset(centreX, markerTop),
        end = Offset(centreX, markerBottom),
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
 * The fling of PRD FR-ENTRY-002 followed by the magnetic settle.
 *
 * Reaching an end stop returns early: the bound is already an exact tenth and the ruler must
 * stay dead still there. When animations are reduced the inertia is dropped but the settle is
 * kept — landing on a valid tenth is an input aid, not decoration (PRD 14).
 */
private suspend fun settleRuler(
    position: Animatable<Float, AnimationVector1D>,
    velocityTenthsPerSecond: Float,
    decay: DecayAnimationSpec<Float>,
    settleSpec: AnimationSpec<Float>,
    allowFling: Boolean,
) {
    if (allowFling && RulerPhysics.isFlingWorthwhile(velocityTenthsPerSecond)) {
        val result = position.animateDecay(velocityTenthsPerSecond, decay)
        if (result.endReason == AnimationEndReason.BoundReached) return
    }
    val target = RulerPhysics.snapToTenth(position.value).toFloat()
    if (position.value != target) position.animateTo(target, settleSpec)
}

/**
 * `−` and `+`, the complete alternative to the gesture demanded by PRD FR-ENTRY-003 and 14.
 *
 * The repeat lives in a plain coroutine rather than in a gesture library so it can accelerate,
 * and the click is published through semantics so TalkBack's double tap gives one clean step
 * with no long press involved.
 */
@Composable
fun RulerStepButton(
    glyph: String,
    stepDescription: String,
    onStep: () -> Unit,
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
                        val down = awaitPointerEventScope { awaitFirstDown() }
                        down.consume()
                        pressed = true
                        currentOnStep()
                        val repeat = launch { repeatStep { currentOnStep() } }
                        awaitPointerEventScope { waitForUpOrCancellation() }
                        repeat.cancel()
                        pressed = false
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = stepDescription
                onClick {
                    currentOnStep()
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

/**
 * The scale as the screen uses it: the ruler running the full width of the phone with the two
 * accessible controls floating over its faded ends.
 *
 * The controls sit where the ruler has already dimmed to nothing, so they hide no graduation
 * the user could be aiming at while staying permanently visible, as PRD FR-ENTRY-003 requires.
 */
@Composable
fun WeightScale(
    weight: Weight,
    onWeightChange: (Weight) -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    weightRevision: Int = 0,
    enabled: Boolean = true,
    onHapticTick: () -> Unit = {},
    saveFlareCount: Int = 0,
) {
    Box(modifier = modifier.fillMaxWidth().height(WeightRulerHeight)) {
        WeightRuler(
            weight = weight,
            onWeightChange = onWeightChange,
            weightRevision = weightRevision,
            enabled = enabled,
            onHapticTick = onHapticTick,
            saveFlareCount = saveFlareCount,
        )
        RulerStepButton(
            glyph = "−",
            stepDescription = "Decrease weight by 0.1 kilograms",
            onStep = { onStep(-RulerPhysics.STEP_TENTHS) },
            enabled = enabled && weight.tenthsKg > Weight.MIN_TENTHS,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = MueTheme.spacing.md),
        )
        RulerStepButton(
            glyph = "+",
            stepDescription = "Increase weight by 0.1 kilograms",
            onStep = { onStep(RulerPhysics.STEP_TENTHS) },
            enabled = enabled && weight.tenthsKg < Weight.MAX_TENTHS,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = MueTheme.spacing.md),
        )
    }
}
