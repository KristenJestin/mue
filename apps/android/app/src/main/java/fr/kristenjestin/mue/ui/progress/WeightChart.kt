package fr.kristenjestin.mue.ui.progress

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueColors
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

private val ChartPadding = 12.dp
private val LineWidth = 3.dp
private val PointRadius = 3.dp
private val LatestPointRadius = 5.dp
private val SelectionRingGap = 4.dp
private val SelectionRingWidth = 2.dp
private val TapSlop = 32.dp
private val HairlineWidth = 1.dp

/** Fractions of the canvas height carrying a grid rule, as in the prototype. */
private val GridFractions = floatArrayOf(0.25f, 0.5f, 0.75f)

/** Only ever visible before the first measurement loads, when nothing is drawn anyway. */
private val InitialRange = ChartGeometry.ValueRange(69f, 71f)

/**
 * The weight curve (PRD FR-PROGRESS-002).
 *
 * Changing the period must not blank or rebuild the chart, and two periods rarely hold the
 * same number of measurements — so every series is resampled onto a fixed number of x
 * positions ([ChartGeometry.SAMPLE_COUNT]) and the two arrays are simply blended. The value
 * window is blended alongside them, which is what makes the vertical scale glide instead of
 * jumping. A period change landing mid-morph freezes the frame currently on screen and
 * starts over from there, so the curve is never recreated.
 *
 * Reduced motion swaps the morph for a 100 ms cross-fade between the two curves (PRD 14).
 */
@Composable
internal fun WeightChart(
    points: List<Measurement>,
    selectedDate: LocalDate?,
    onSelectedDateChange: (LocalDate?) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val duration = MueMotion.durationOf(MueMotion.PeriodChangeMillis)

    var transition by remember {
        val initial = frameOf(points, previous = null)
        mutableStateOf(ChartTransition(initial, initial))
    }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(points, reduceMotion) {
        val current = transition.frameAt(progress.value, crossFade = reduceMotion)
        transition = ChartTransition(current, frameOf(points, previous = current))
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = duration, easing = MueMotion.Standard))
    }

    val onSelect by rememberUpdatedState(onSelectedDateChange)
    val fractions = remember(points) { ChartGeometry.xFractions(points.map(Measurement::date)) }
    val tapSlopPx = with(LocalDensity.current) { TapSlop.toPx() }

    Canvas(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(points, tapSlopPx) {
                detectTapGestures { offset ->
                    val index = ChartGeometry.nearestPointIndex(
                        xFractions = fractions,
                        touchX = offset.x,
                        width = size.width.toFloat(),
                        horizontalPadding = ChartPadding.toPx(),
                        maxDistance = tapSlopPx,
                    )
                    onSelect(index?.let { points[it].date })
                }
            },
    ) {
        val fraction = progress.value
        val active = transition

        drawGrid(colors.hairline)

        if (reduceMotion) {
            drawSeries(active.from, colors, alpha = 1f - fraction, selectedDate = null)
            drawSeries(active.to, colors, alpha = fraction, selectedDate = selectedDate)
        } else {
            val frame = active.frameAt(fraction, crossFade = false)
            drawCurve(frame, colors.accent, alpha = frame.lineAlpha)
            drawPoints(frame, active.from.points, colors, 1f - fraction, selectedDate = null)
            drawPoints(frame, active.to.points, colors, fraction, selectedDate)
        }
    }
}

@Stable
private class ChartTransition(val from: ChartFrame, val to: ChartFrame) {

    /**
     * The frame to draw at [fraction]. Under [crossFade] the geometry is not blended at
     * all — the two curves are drawn over each other with opposite alphas — so the target
     * frame is returned as is.
     */
    fun frameAt(fraction: Float, crossFade: Boolean): ChartFrame = when {
        crossFade || fraction >= 1f -> to
        fraction <= 0f -> from
        else -> ChartFrame(
            samples = ChartGeometry.lerpSamples(from.samples, to.samples, fraction),
            range = from.range.lerpTo(to.range, fraction),
            lineAlpha = ChartGeometry.lerp(from.lineAlpha, to.lineAlpha, fraction),
            points = to.points,
        )
    }
}

private class ChartFrame(
    val samples: FloatArray,
    val range: ChartGeometry.ValueRange,
    /** Zero below two measurements: a single dot must not grow a flat line out of nothing. */
    val lineAlpha: Float,
    val points: List<PlottedPoint>,
)

private class PlottedPoint(val date: LocalDate, val xFraction: Float)

/**
 * An empty period reuses [previous]'s geometry so the curve fades out where it stood
 * instead of collapsing towards an arbitrary baseline.
 */
private fun frameOf(points: List<Measurement>, previous: ChartFrame?): ChartFrame {
    if (points.isEmpty()) {
        val range = previous?.range ?: InitialRange
        return ChartFrame(
            samples = previous?.samples
                ?: FloatArray(ChartGeometry.SAMPLE_COUNT) { (range.min + range.max) / 2f },
            range = range,
            lineAlpha = 0f,
            points = emptyList(),
        )
    }
    val values = points.map { it.weight.kilograms.toFloat() }
    val fractions = ChartGeometry.xFractions(points.map(Measurement::date))
    return ChartFrame(
        samples = ChartGeometry.resample(fractions, values),
        range = ChartGeometry.rangeOf(values, previous?.range ?: InitialRange),
        lineAlpha = if (points.size >= 2) 1f else 0f,
        points = points.mapIndexed { index, point -> PlottedPoint(point.date, fractions[index]) },
    )
}

private fun DrawScope.drawGrid(color: Color) {
    val width = HairlineWidth.toPx()
    for (fraction in GridFractions) {
        val y = size.height * fraction
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = width)
    }
}

private fun DrawScope.drawSeries(
    frame: ChartFrame,
    colors: MueColors,
    alpha: Float,
    selectedDate: LocalDate?,
) {
    drawCurve(frame, colors.accent, alpha = frame.lineAlpha * alpha)
    drawPoints(frame, frame.points, colors, alpha, selectedDate)
}

private fun DrawScope.drawCurve(frame: ChartFrame, color: Color, alpha: Float) {
    if (alpha <= 0.01f || frame.samples.size < 2) return
    val padding = ChartPadding.toPx()
    val path = Path()
    frame.samples.forEachIndexed { index, value ->
        val x = ChartGeometry.xToPixel(
            fraction = index.toFloat() / (frame.samples.size - 1),
            width = size.width,
            horizontalPadding = padding,
        )
        val y = ChartGeometry.valueToPixel(value, frame.range, size.height, padding)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        alpha = alpha.coerceIn(0f, 1f),
        style = Stroke(width = LineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Dots ride [frame]'s curve rather than their own raw value, so during a morph they stay
 * glued to the line instead of drifting off it.
 */
private fun DrawScope.drawPoints(
    frame: ChartFrame,
    points: List<PlottedPoint>,
    colors: MueColors,
    alpha: Float,
    selectedDate: LocalDate?,
) {
    if (alpha <= 0.01f || points.isEmpty()) return
    val padding = ChartPadding.toPx()
    val clamped = alpha.coerceIn(0f, 1f)

    points.forEachIndexed { index, point ->
        val x = ChartGeometry.xToPixel(point.xFraction, size.width, padding)
        val value = ChartGeometry.sampleAt(frame.samples, point.xFraction)
        val y = ChartGeometry.valueToPixel(value, frame.range, size.height, padding)
        val latest = index == points.lastIndex

        if (point.date == selectedDate) {
            drawLine(
                color = colors.accent,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = HairlineWidth.toPx(),
                alpha = clamped * 0.35f,
            )
            drawCircle(
                color = colors.accent,
                radius = LatestPointRadius.toPx() + SelectionRingGap.toPx(),
                center = Offset(x, y),
                alpha = clamped,
                style = Stroke(width = SelectionRingWidth.toPx()),
            )
        }

        drawCircle(
            color = if (latest) colors.textPrimary else colors.accent,
            radius = (if (latest) LatestPointRadius else PointRadius).toPx(),
            center = Offset(x, y),
            alpha = clamped,
        )
    }
}
