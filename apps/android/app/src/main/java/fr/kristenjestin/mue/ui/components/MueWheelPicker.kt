package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object MueWheelPickerDefaults {

    /** One value per 48 dp: the row is the touch target PRD 15 asks for (PRD_ACTIVITIES 15). */
    val RowHeight: Dp = MueWheelPhysics.DP_PER_VALUE.dp

    val VisibleRows: Int = MueWheelPhysics.VISIBLE_VALUES

    val Height: Dp = RowHeight * VisibleRows
}

/** Inset of the centre band from the edges of the wheel, and the radius of its corners. */
private val BandInset: Dp = 2.dp
private val BandRadius: Dp = 12.dp
private val BandBorder: Dp = 1.dp

/** Opacity of a row that is not the selected one, before the edge fade is applied. */
private const val NeighbourAlpha = 0.55f

/**
 * A vertical wheel of whole numbers: a fixed centre with the values sliding under it.
 *
 * The weight ruler's sibling, turned on its side. Material has no control for this — its time
 * picker is a clock dial, which says *half past midnight* where someone entering a duration
 * means *thirty minutes* — so the family the app already owns is the one to extend rather than
 * to borrow another product's. The physics is [MueWheelPhysics], stated there and provable
 * without a device; this file only sequences it and paints it.
 *
 * Nothing about the movement is composed. The gesture writes into [state], the rows read it
 * back inside the draw scope, and [onValueChange] fires once, when the wheel has stopped — so a
 * drag costs a redraw and never a recomposition of the form around it.
 *
 * **Accessibility.** The wheel is an adjustable control, exactly as the ruler is: it carries a
 * [ProgressBarRangeInfo] with one stop per value, says its value out loud through
 * [stateDescription], and answers `setProgress`, so TalkBack's own adjust gesture moves it one
 * value at a time and can be held to run. No fling and no aiming are involved on that path,
 * which is what PRD_ACTIVITIES 15 requires of anything essential.
 *
 * Reduced motion drops the throw and keeps the magnet, as on the ruler: the wheel still comes
 * to rest on a whole value, it simply does not glide there.
 */
@Composable
fun MueWheelPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    label: String,
    stateDescriptionOf: (Int) -> String,
    modifier: Modifier = Modifier,
    formatValue: (Int) -> String = { it.toString() },
    visibleRows: Int = MueWheelPickerDefaults.VisibleRows,
    enabled: Boolean = true,
    onHapticTick: () -> Unit = {},
) {
    val colors = MueTheme.colors
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val pixelsPerValue = with(density) { MueWheelPickerDefaults.RowHeight.toPx() }

    val state = rememberMueWheelPickerState(value, range)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnHapticTick by rememberUpdatedState(onHapticTick)
    val allowFling by rememberUpdatedState(!reduceMotion)

    // A value arriving from outside — a draft restored, the other screen of the shared editor —
    // moves the wheel, but never while a finger is on it.
    LaunchedEffect(value, state) {
        if (!state.interacting && state.displayedValue != value) state.jumpTo(value)
    }

    /*
     * Observed through a snapshot flow rather than a `LaunchedEffect` key: a key is a
     * composition read, and reading the live position here would undo the point of keeping it
     * out of composition. Only movement of the wheel ticks — a value arriving from the draft or
     * from an accessibility action leaves `interacting` false and stays silent.
     */
    LaunchedEffect(state) {
        var previous = state.displayedValue
        snapshotFlow { state.displayedValue }.collect { current ->
            val crossed = MueWheelPhysics.crossesHapticStep(previous, current)
            previous = current
            if (crossed && state.interacting) currentOnHapticTick()
        }
    }

    val gestureModifier = if (enabled) {
        Modifier.pointerInput(pixelsPerValue, range) {
            awaitEachGesture {
                // One scope from touch down to lift, so no pointer event can fall between two
                // of them on a control that has to track the finger exactly.
                val down = awaitFirstDown(requireUnconsumed = false)
                state.onDragStart()

                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)

                verticalDrag(down.id) { change ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    state.onDrag(change.positionChange().y, pixelsPerValue)
                    change.consume()
                }

                state.onDragEnd(
                    velocityValuesPerSecond = MueWheelPhysics.velocityToValues(
                        tracker.calculateVelocity().y,
                        pixelsPerValue,
                    ),
                    allowFling = allowFling,
                ) { landed -> currentOnValueChange(landed) }
            }
        }
    } else {
        Modifier
    }

    val textMeasurer = rememberTextMeasurer(cacheSize = TextCacheSize)
    val rowStyle = MueTheme.typography.metricMedium
    // The centre reads like every other chosen thing in the app — a soft amber field with an
    // amber edge, as on a selected preset tile — rather than like a pair of field underlines.
    val palette = remember(colors) {
        WheelPalette(
            selected = colors.onAccentSoft,
            unselected = colors.textSecondary,
            band = colors.accentSoft,
            border = colors.accent,
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MueWheelPickerDefaults.RowHeight * visibleRows)
            .then(gestureModifier)
            .semantics {
                contentDescription = label
                stateDescription = stateDescriptionOf(value)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = range.first.toFloat()..range.last.toFloat(),
                    steps = MueWheelPhysics.adjustableSteps(range),
                )
                setProgress { target ->
                    val landed = MueWheelPhysics.snapToValue(target, range)
                    // The wheel is moved here rather than through the screen's state: an
                    // accessibility action is an order, and orders move the control directly.
                    state.jumpTo(landed)
                    currentOnValueChange(landed)
                    true
                }
                if (!enabled) disabled()
            }
            .wheelRows(state, range, textMeasurer, rowStyle, palette, formatValue),
    )
}

/** The colours of a row and of the centre band, resolved once. */
private class WheelPalette(
    val selected: Color,
    val unselected: Color,
    val band: Color,
    val border: Color,
)

/** Everything that does not move: the geometry in pixels, computed once per resize. */
private class WheelCanvasCache(
    val centreY: Float,
    val halfHeight: Float,
    val pixelsPerValue: Float,
    val bandTop: Float,
    val bandHeight: Float,
    val bandInset: Float,
    val bandRadius: Float,
    val border: Float,
)

/** Room for a hundred hours and sixty minutes without re-laying a string out mid-fling. */
private const val TextCacheSize = 128

/**
 * Paints the centre band and the values sliding under it.
 *
 * [Modifier.drawWithCache] rather than a `Canvas` of composables: the position is read inside
 * `onDrawBehind`, so a drag invalidates the draw phase and nothing else — no recomposition, no
 * relayout, and no row entering or leaving the tree as the wheel turns.
 */
private fun Modifier.wheelRows(
    state: MueWheelPickerState,
    range: IntRange,
    textMeasurer: TextMeasurer,
    rowStyle: TextStyle,
    palette: WheelPalette,
    formatValue: (Int) -> String,
): Modifier = drawWithCache {
    val cache = buildWheelCache()

    onDrawBehind {
        val position = state.position
        val selected = state.displayedValue

        val bandTopLeft = Offset(cache.bandInset, cache.bandTop)
        val bandSize = Size(size.width - cache.bandInset * 2f, cache.bandHeight)
        val bandCorner = CornerRadius(cache.bandRadius, cache.bandRadius)
        drawRoundRect(palette.band, bandTopLeft, bandSize, bandCorner)
        // The outline is what makes the fixed centre a marker rather than a lighter row, and it
        // is the non-colour cue PRD 15 asks for beside the amber.
        drawRoundRect(
            color = palette.border,
            topLeft = bandTopLeft,
            size = bandSize,
            cornerRadius = bandCorner,
            style = Stroke(width = cache.border),
        )

        for (row in MueWheelPhysics.visibleValues(
            position,
            cache.halfHeight,
            cache.pixelsPerValue,
            range,
        )) {
            val y = MueWheelPhysics.valueY(row, position, cache.pixelsPerValue, cache.centreY)
            val alpha = MueWheelPhysics.edgeAlpha(y - cache.centreY, cache.halfHeight)
            if (alpha <= 0.01f) continue

            val isSelected = row == selected
            val text = textMeasurer.measure(formatValue(row), rowStyle)
            drawText(
                textLayoutResult = text,
                color = if (isSelected) palette.selected else palette.unselected,
                topLeft = Offset(
                    x = (size.width - text.size.width) / 2f,
                    y = y - text.size.height / 2f,
                ),
                alpha = if (isSelected) alpha else alpha * NeighbourAlpha,
            )
        }
    }
}

private fun CacheDrawScope.buildWheelCache(): WheelCanvasCache {
    val pixelsPerValue = MueWheelPickerDefaults.RowHeight.toPx()
    val centreY = size.height / 2f
    return WheelCanvasCache(
        centreY = centreY,
        halfHeight = size.height / 2f,
        pixelsPerValue = pixelsPerValue,
        bandTop = centreY - pixelsPerValue / 2f,
        bandHeight = pixelsPerValue,
        bandInset = BandInset.toPx(),
        bandRadius = BandRadius.toPx(),
        border = BandBorder.toPx(),
    )
}

/**
 * Where the wheel currently is, in values, and how it gets there.
 *
 * The live position stops here rather than in a ViewModel, for the reason `RulerState` gives:
 * routing a pointer event every few milliseconds through the form's state would rebuild the
 * whole tree between the finger and the pixels. Two snapshot values leave this object and each
 * has one reader — [position] from the draw scope, [displayedValue] from the draw scope and the
 * semantics — and the caller is told once, when the wheel stops.
 */
@Stable
class MueWheelPickerState internal constructor(
    initialValue: Int,
    private val range: IntRange,
    private val scope: CoroutineScope,
) {

    /** Continuous position under the centre. Read from the draw phase only. */
    var position by mutableFloatStateOf(initialValue.toFloat())
        private set

    /** The nearest whole value: what the centre row shows while the wheel is still moving. */
    var displayedValue by mutableIntStateOf(initialValue)
        private set

    /** True from touch-down until the wheel has come to rest, glide and magnet included. */
    var interacting by mutableStateOf(false)
        private set

    private val decay: DecayAnimationSpec<Float> = exponentialDecay(
        frictionMultiplier = MueWheelPhysics.FLING_FRICTION_MULTIPLIER,
        absVelocityThreshold = MueWheelPhysics.FLING_VELOCITY_THRESHOLD,
    )

    private val settleSpec: SpringSpec<Float> = spring(
        dampingRatio = MueWheelPhysics.SETTLE_DAMPING_RATIO,
        stiffness = MueWheelPhysics.SETTLE_STIFFNESS,
        visibilityThreshold = MueWheelPhysics.SETTLE_VISIBILITY_THRESHOLD,
    )

    private var motion: Job? = null

    /**
     * Bumped whenever the current movement is abandoned.
     *
     * Cancelling a coroutine only takes effect at its next dispatch, so a settle that has just
     * been interrupted still runs its cleanup one frame *after* the touch that interrupted it.
     * Without this the finger would land on a wheel that immediately declared itself idle.
     */
    private var generation: Int = 0

    /** A new touch interrupts the glide immediately, as it does on the ruler. */
    fun onDragStart() {
        stopMotion()
        interacting = true
    }

    fun onDrag(dragPx: Float, pixelsPerValue: Float) {
        moveTo(position + MueWheelPhysics.dragToValues(dragPx, pixelsPerValue))
    }

    /**
     * Runs the glide and the magnet, then hands the landing value to [onRest] — the only moment
     * the rest of the app hears about a drag.
     */
    fun onDragEnd(
        velocityValuesPerSecond: Float,
        allowFling: Boolean,
        onRest: (Int) -> Unit,
    ) {
        val gesture = generation
        motion = scope.launch {
            try {
                settle(velocityValuesPerSecond, allowFling)
            } finally {
                if (gesture == generation) interacting = false
            }
            onRest(displayedValue)
        }
    }

    /** An order from outside the wheel: the draft, or an accessibility action. */
    fun jumpTo(value: Int) {
        stopMotion()
        interacting = false
        moveTo(value.toFloat())
    }

    private fun stopMotion() {
        generation++
        motion?.cancel()
        motion = null
    }

    private fun moveTo(value: Float) {
        val clamped = MueWheelPhysics.clampPosition(value, range)
        position = clamped
        displayedValue = MueWheelPhysics.snapToValue(clamped, range)
    }

    /**
     * The short glide, then the pull onto the nearest whole value.
     *
     * Clamping inside the decay is what makes both ends dead stops: the moment the projected
     * value leaves the range the animation is cancelled where it stands, which is already a
     * whole value, so no rebound and no second animation follow.
     */
    private suspend fun settle(velocityValuesPerSecond: Float, allowFling: Boolean) {
        if (allowFling && MueWheelPhysics.isFlingWorthwhile(velocityValuesPerSecond)) {
            AnimationState(position, velocityValuesPerSecond).animateDecay(decay) {
                moveTo(value)
                if (value != position) cancelAnimation()
            }
        }
        val target = displayedValue.toFloat()
        if (position != target) {
            AnimationState(position).animateTo(target, settleSpec) { moveTo(value) }
        }
    }
}

/** The wheel's position, kept across recompositions and tied to the screen's own scope. */
@Composable
fun rememberMueWheelPickerState(initial: Int, range: IntRange): MueWheelPickerState {
    val scope = rememberCoroutineScope()
    return remember(scope, range) { MueWheelPickerState(initial, range, scope) }
}
