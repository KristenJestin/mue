package fr.kristenjestin.mue.ui.progress

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Pure maths behind the weight curve: value ranges, the resampling that makes two curves
 * of different lengths morphable, and hit testing.
 *
 * Deliberately free of Compose and of Android so it can be unit-tested on the JVM. Pixels
 * are plain floats here; the composable owns the density conversion.
 */
object ChartGeometry {

    /**
     * A curve of any number of measurements is resampled onto this many evenly spaced
     * x positions, which is what lets two periods morph into each other: the arrays always
     * line up. It is odd on purpose so the exact middle is a sample, and dense enough that
     * a vertex falling between two samples is under a pixel off on a phone-wide chart.
     */
    const val SAMPLE_COUNT: Int = 257

    /** A flat history still gets a readable window instead of a zero-height one. */
    const val MIN_SPAN_KG: Float = 1f

    /** Head- and foot-room above and below the curve, as a fraction of the span. */
    const val RANGE_PADDING_FRACTION: Float = 0.12f

    /** Where a lone measurement sits horizontally: centred rather than glued to an edge. */
    const val SINGLE_POINT_FRACTION: Float = 0.5f

    /** The vertical window the curve is drawn in. [min] is always strictly below [max]. */
    data class ValueRange(val min: Float, val max: Float) {
        val span: Float get() = max - min

        fun lerpTo(other: ValueRange, fraction: Float): ValueRange = ValueRange(
            min = lerp(min, other.min, fraction),
            max = lerp(max, other.max, fraction),
        )
    }

    /**
     * Window covering [values], never narrower than [MIN_SPAN_KG] so a flat line cannot
     * produce a division by zero, and padded so the curve does not touch the edges.
     * Returns [fallback] when there is nothing to measure.
     */
    fun rangeOf(values: List<Float>, fallback: ValueRange): ValueRange {
        if (values.isEmpty()) return fallback
        var low = values[0]
        var high = values[0]
        for (value in values) {
            if (value < low) low = value
            if (value > high) high = value
        }
        val missing = MIN_SPAN_KG - (high - low)
        if (missing > 0f) {
            low -= missing / 2f
            high += missing / 2f
        }
        val padding = (high - low) * RANGE_PADDING_FRACTION
        return ValueRange(low - padding, high + padding)
    }

    /**
     * Horizontal position of each measurement, `0` at the oldest and `1` at the newest,
     * proportional to the number of days between them — so a gap in the history shows as
     * a gap on the curve. [dates] must be sorted ascending.
     */
    fun xFractions(dates: List<LocalDate>): List<Float> {
        if (dates.isEmpty()) return emptyList()
        if (dates.size == 1) return listOf(SINGLE_POINT_FRACTION)
        val first = dates.first()
        val total = ChronoUnit.DAYS.between(first, dates.last()).toFloat()
        if (total <= 0f) return dates.map { SINGLE_POINT_FRACTION }
        return dates.map { ChronoUnit.DAYS.between(first, it).toFloat() / total }
    }

    /**
     * Projects a polyline onto [sampleCount] evenly spaced x positions. [xFractions] must
     * be ascending and the same size as [values], and neither may be empty.
     */
    fun resample(
        xFractions: List<Float>,
        values: List<Float>,
        sampleCount: Int = SAMPLE_COUNT,
    ): FloatArray {
        require(xFractions.size == values.size) { "x and value counts differ" }
        require(values.isNotEmpty()) { "an empty series cannot be resampled" }
        if (values.size == 1) return FloatArray(sampleCount) { values[0] }

        val samples = FloatArray(sampleCount)
        var segment = 0
        for (i in 0 until sampleCount) {
            val x = i.toFloat() / (sampleCount - 1)
            while (segment < xFractions.size - 2 && x > xFractions[segment + 1]) segment++
            val startX = xFractions[segment]
            val endX = xFractions[segment + 1]
            val within = if (endX > startX) ((x - startX) / (endX - startX)).coerceIn(0f, 1f) else 0f
            samples[i] = lerp(values[segment], values[segment + 1], within)
        }
        return samples
    }

    /** Value of a resampled curve at [fraction] of its width, clamped to both ends. */
    fun sampleAt(samples: FloatArray, fraction: Float): Float {
        if (samples.isEmpty()) return 0f
        if (samples.size == 1) return samples[0]
        val position = (fraction.coerceIn(0f, 1f)) * (samples.size - 1)
        val low = position.toInt()
        val high = (low + 1).coerceAtMost(samples.size - 1)
        return lerp(samples[low], samples[high], position - low)
    }

    fun lerpSamples(from: FloatArray, to: FloatArray, fraction: Float): FloatArray {
        require(from.size == to.size) { "sample arrays of different sizes cannot be blended" }
        return FloatArray(from.size) { lerp(from[it], to[it], fraction) }
    }

    fun xToPixel(fraction: Float, width: Float, horizontalPadding: Float): Float {
        val usable = (width - horizontalPadding * 2f).coerceAtLeast(0f)
        return horizontalPadding + fraction * usable
    }

    /** Inverted, as on screen: the highest weight sits at the top of the canvas. */
    fun valueToPixel(
        value: Float,
        range: ValueRange,
        height: Float,
        verticalPadding: Float,
    ): Float {
        val usable = (height - verticalPadding * 2f).coerceAtLeast(0f)
        val span = range.span
        if (span <= 0f) return verticalPadding + usable / 2f
        val fromTop = ((range.max - value) / span).coerceIn(0f, 1f)
        return verticalPadding + fromTop * usable
    }

    /**
     * Index of the measurement closest to a touch at [touchX], or null when the touch
     * landed further than [maxDistance] from any of them.
     */
    fun nearestPointIndex(
        xFractions: List<Float>,
        touchX: Float,
        width: Float,
        horizontalPadding: Float,
        maxDistance: Float,
    ): Int? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        xFractions.forEachIndexed { index, fraction ->
            val distance = abs(xToPixel(fraction, width, horizontalPadding) - touchX)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return if (best >= 0 && bestDistance <= maxDistance) best else null
    }

    fun lerp(start: Float, stop: Float, fraction: Float): Float =
        start + (stop - start) * fraction
}
