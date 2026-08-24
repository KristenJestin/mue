package fr.kristenjestin.mue.ui.components

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every number the vertical wheel is made of, and the geometry that turns finger pixels into
 * whole values.
 *
 * The wheel is the weight ruler's vertical sibling — one fixed centre, the values sliding under
 * it, a short glide and a magnet onto the nearest stop — so the shape of the movement is the
 * ruler's exactly: an exponential decay of the same friction base, then a critically damped
 * spring onto the landing value. Those numbers are stated again here rather than imported from
 * `RulerPhysics`, because a shared component must not depend on a screen; if either control is
 * ever retuned the other should be looked at.
 *
 * Two of them are deliberately not the ruler's, and both follow from what the two controls are
 * for. The ruler aims at one of four thousand 0.05 kg steps at 0.8 dp each, so PRD FR-ENTRY-002
 * asks it to glide *short*: a hard flick crosses a couple of kilograms and the value stays
 * readable throughout. A wheel row is sixty times that distance, so the same friction would move
 * about seven minutes a throw and make the wheel worse than the keyboard it replaces. It is
 * lighter here, and the cap is set where a finger cannot reach rather than where the ruler's is.
 *
 * Like the ruler's, this object is free of Compose and of Android: the feel is the risky part
 * and it has to be provable on the JVM and tunable in one place.
 *
 * The continuous position is expressed in *values*, not pixels, so the end stops, the fling and
 * the magnetism all clamp against the wheel's own range and nothing changes with the density.
 */
object MueWheelPhysics {

    // --- Geometry -------------------------------------------------------------------

    /**
     * Travel of one value, in dp. It is also the height of a row, which is why it is the 48 dp
     * minimum of PRD 15 rather than a number chosen for the look: the band a value occupies is
     * the band a finger has to land in.
     */
    const val DP_PER_VALUE: Float = 48f

    /**
     * Rows on screen at once: the centre and one either side.
     *
     * Five would read more like a wheel, but at 48 dp a row that is 240 dp of a form whose next
     * field is 64 dp, and at either end stop most of it is empty air. Three keeps the sense of a
     * strip running past a fixed point and costs a third of the space.
     */
    const val VISIBLE_VALUES: Int = 3

    /**
     * Fraction of the half-height that stays fully opaque before the wheel fades out.
     *
     * Much longer than the ruler's ramp because the wheel is much shorter: the ruler runs the
     * width of the screen and can dissolve over a kilometre and a half, while here the row next
     * to the centre is already two thirds of the way out and still has to be read.
     */
    const val EDGE_FADE_START: Float = 0.7f

    // --- Fling ----------------------------------------------------------------------

    /**
     * Friction constant of `exponentialDecay`, copied from `FloatExponentialDecaySpec`, so
     * [flingDistanceValues] predicts the very animation the wheel runs.
     */
    const val DECAY_FRICTION_BASE: Float = 4.2f

    /**
     * A fifth of the ruler's 2.6, and lighter than Compose's own list default of 1.
     *
     * A row is 48 dp, which is a large step to travel: at the ruler's friction a hard flick
     * would move about seven minutes, and reaching `45` would take six of them. Here a brisk
     * flick covers around eighteen rows and a hard one around thirty-four, so a sixty-minute
     * wheel is two throws end to end and the magnet still lands it on a whole value.
     */
    const val FLING_FRICTION_MULTIPLIER: Float = 0.5f

    /** The decay ends here, in values per second — a third of a row a second is already still. */
    const val FLING_VELOCITY_THRESHOLD: Float = 0.3f

    /**
     * Ceiling on a throw, in values per second. Set above what a finger can produce so it never
     * truncates a real flick, and low enough that a stray velocity reading cannot slam the wheel
     * from one end stop to the other.
     */
    const val MAX_FLING_VELOCITY: Float = 180f

    /** Below half a row of projected travel the fling is not worth running; settle at once. */
    const val MIN_FLING_VALUES: Float = 0.5f

    /** Critically damped: the magnet pulls, it never bounces, exactly as the ruler does not. */
    const val SETTLE_DAMPING_RATIO: Float = 1f
    const val SETTLE_STIFFNESS: Float = 1200f

    /** Displacement below which the settle has arrived, in values — a five-hundredth of a row. */
    const val SETTLE_VISIBILITY_THRESHOLD: Float = 0.002f

    // --- Feedback -------------------------------------------------------------------

    /**
     * The ruler ticks every five drawn graduations rather than every reachable step, so a glide
     * is a rhythm and not a buzz. The wheel keeps the count: one tick every five rows.
     */
    const val VALUES_PER_HAPTIC_STEP: Int = 5

    // --- Conversions ----------------------------------------------------------------

    /**
     * Finger travel in pixels turned into a change in values.
     *
     * The sign is the wheel's one fixed rule and not a tunable: values grow downwards, so
     * pulling the wheel down (positive [dragPx]) brings a *lower* value under the centre.
     */
    fun dragToValues(dragPx: Float, pixelsPerValue: Float): Float =
        if (pixelsPerValue <= 0f) 0f else -dragPx / pixelsPerValue

    /** Finger velocity in px/s turned into a wheel velocity in values/s, already capped. */
    fun velocityToValues(velocityPxPerSecond: Float, pixelsPerValue: Float): Float {
        if (pixelsPerValue <= 0f) return 0f
        val values = -velocityPxPerSecond / pixelsPerValue
        return values.coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
    }

    /**
     * Where an exponential decay launched at [velocityValuesPerSecond] comes to rest, relative
     * to its starting point. Mirrors `FloatExponentialDecaySpec.getTargetValue`.
     */
    fun flingDistanceValues(velocityValuesPerSecond: Float): Float =
        velocityValuesPerSecond / (DECAY_FRICTION_BASE * FLING_FRICTION_MULTIPLIER)

    /** True when a throw is fast enough to be worth animating rather than settling at once. */
    fun isFlingWorthwhile(velocityValuesPerSecond: Float): Boolean =
        abs(flingDistanceValues(velocityValuesPerSecond)) >= MIN_FLING_VALUES

    // --- Range ----------------------------------------------------------------------

    /** Hard end stops: the wheel stops dead at either end, it never rebounds and never wraps. */
    fun clampPosition(position: Float, range: IntRange): Float =
        position.coerceIn(range.first.toFloat(), range.last.toFloat())

    /** The magnet: the wheel always comes to rest on a whole value inside the range. */
    fun snapToValue(position: Float, range: IntRange): Int =
        position.roundToInt().coerceIn(range.first, range.last)

    fun isAtStop(position: Float, range: IntRange): Boolean =
        position <= range.first || position >= range.last

    /** Adds [steps] whole values without ever leaving the range, for an accessibility action. */
    fun step(value: Int, steps: Int, range: IntRange): Int =
        (value + steps).coerceIn(range.first, range.last)

    /** Discrete positions an assistive gesture can stop on, ends excluded, never negative. */
    fun adjustableSteps(range: IntRange): Int = max(0, range.last - range.first - 1)

    // --- Drawing --------------------------------------------------------------------

    /** The values that can be seen around [position], clipped to the wheel's own range. */
    fun visibleValues(
        position: Float,
        halfHeightPx: Float,
        pixelsPerValue: Float,
        range: IntRange,
    ): IntRange {
        if (pixelsPerValue <= 0f || halfHeightPx <= 0f) return IntRange.EMPTY
        val span = halfHeightPx / pixelsPerValue
        val first = max(range.first.toFloat(), ceil(position - span)).toInt()
        val last = min(range.last.toFloat(), floor(position + span)).toInt()
        return if (first > last) IntRange.EMPTY else first..last
    }

    /** Vertical position of [value], with the fixed centre sitting at [centreYPx]. */
    fun valueY(value: Int, position: Float, pixelsPerValue: Float, centreYPx: Float): Float =
        centreYPx + (value - position) * pixelsPerValue

    /**
     * Opacity of a row, fading to nothing at both ends of the wheel.
     *
     * Computed per row rather than painted as an overlay so it works whatever sits behind the
     * wheel — the amber glow of the scaffold included, exactly as on the ruler.
     */
    fun edgeAlpha(distanceFromCentrePx: Float, halfHeightPx: Float): Float {
        if (halfHeightPx <= 0f) return 0f
        val ratio = (abs(distanceFromCentrePx) / halfHeightPx).coerceIn(0f, 1f)
        if (ratio <= EDGE_FADE_START) return 1f
        val remaining = (1f - ratio) / (1f - EDGE_FADE_START)
        return (remaining * remaining).coerceIn(0f, 1f)
    }

    /** Which five-row bucket a value falls in; the tick fires when this number changes. */
    fun hapticStepOf(value: Int): Int = Math.floorDiv(value, VALUES_PER_HAPTIC_STEP)

    /**
     * True when moving from [from] to [to] crosses a tick. Reaching an end stop cannot cross
     * one, so no end-stop tick is ever produced.
     */
    fun crossesHapticStep(from: Int, to: Int): Boolean = hapticStepOf(from) != hapticStepOf(to)
}
