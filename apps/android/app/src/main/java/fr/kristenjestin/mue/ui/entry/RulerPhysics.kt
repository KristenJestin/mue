package fr.kristenjestin.mue.ui.entry

import fr.kristenjestin.mue.domain.model.Weight
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Visual weight of a graduation, decided by the tenth it sits on. */
enum class RulerTick { Minor, Medium, Major }

/**
 * Every number the touch scale is made of, and the geometry that turns finger pixels into
 * tenths of a kilogram.
 *
 * This object is deliberately free of Compose and of Android: the feel of the scale is the
 * riskiest part of the app (PRD 18) and it has to be provable on the JVM and tunable in one
 * place after the first tests on real hardware.
 *
 * The scale's continuous position is expressed in *tenths of a kilogram*, not pixels. Working
 * in the value domain means the end stops, the fling and the magnetism all clamp against
 * [Weight.RANGE] directly, and none of the physics changes when the screen density does.
 */
object RulerPhysics {

    // --- Geometry -------------------------------------------------------------------

    const val TENTHS_PER_KILOGRAM: Int = 10

    /**
     * Ruler travel for one kilogram, in dp. The prototype glides at 80 CSS px per kilogram;
     * 80 dp keeps that feel and puts one tenth 8 dp away from the next, which is far enough
     * apart to be aimed at with a finger and close enough for a 4 kg sweep across the screen.
     */
    const val DP_PER_KILOGRAM: Float = 80f

    val DP_PER_TENTH: Float = DP_PER_KILOGRAM / TENTHS_PER_KILOGRAM

    /** A graduation every tenth, taller every half kilogram, tallest and labelled every kilogram. */
    const val TENTHS_PER_MEDIUM_TICK: Int = 5
    const val TENTHS_PER_MAJOR_TICK: Int = TENTHS_PER_KILOGRAM

    /** Fraction of the half-width that stays fully opaque before the ruler fades to nothing. */
    const val EDGE_FADE_START: Float = 0.55f

    // --- Fling ----------------------------------------------------------------------

    /**
     * Friction constant of `exponentialDecay`, copied from `FloatExponentialDecaySpec`. It is
     * repeated here so [flingDistanceTenths] predicts the very animation the screen runs.
     */
    const val DECAY_FRICTION_BASE: Float = 4.2f

    /**
     * Rather more friction than the Compose default of 1. A weight scale is an aiming device:
     * a hard flick should cross a couple of kilograms, not thirty, so the value stays readable
     * while it glides. Gives the "short precise glide" of PRD FR-ENTRY-002.
     */
    const val FLING_FRICTION_MULTIPLIER: Float = 2.6f

    /** The decay ends here, in tenths per second — 0.15 kg/s is already imperceptible. */
    const val FLING_VELOCITY_THRESHOLD: Float = 1.5f

    /** Ceiling on a throw, in tenths per second. Caps any single fling at roughly 6.4 kg. */
    const val MAX_FLING_VELOCITY: Float = 700f

    /** Below this projected travel the fling is not worth running; settle straight away. */
    const val MIN_FLING_TENTHS: Float = 0.5f

    /** Critically damped: the magnetism pulls, it never bounces (PRD FR-ENTRY-002). */
    const val SETTLE_DAMPING_RATIO: Float = 1f
    const val SETTLE_STIFFNESS: Float = 1200f

    // --- Haptics --------------------------------------------------------------------

    /** PRD FR-ENTRY-002 asks for a tick every 0.5 kg, never every tenth. */
    const val TENTHS_PER_HAPTIC_STEP: Int = 5

    // --- Accessible stepping --------------------------------------------------------

    /** One press of `−` or `+` (PRD FR-ENTRY-003). */
    const val STEP_TENTHS: Int = 1

    /** PRD FR-ENTRY-003: a held press starts repeating after about this long. */
    const val STEP_REPEAT_DELAY_MILLIS: Long = 400L

    /** First repeat interval, then multiplied by [STEP_REPEAT_DECAY] until [STEP_REPEAT_MIN_INTERVAL_MILLIS]. */
    const val STEP_REPEAT_INTERVAL_MILLIS: Long = 140L
    const val STEP_REPEAT_MIN_INTERVAL_MILLIS: Long = 36L
    const val STEP_REPEAT_DECAY: Float = 0.82f

    // --- Conversions ----------------------------------------------------------------

    /**
     * Finger travel in pixels turned into a change in tenths.
     *
     * The sign is the whole point of PRD FR-ENTRY-002 and is not a tunable: values grow
     * left to right along the ruler, so pulling the ruler leftwards (negative [dragPx])
     * brings a *higher* value under the fixed marker.
     */
    fun dragToTenths(dragPx: Float, pixelsPerTenth: Float): Float =
        if (pixelsPerTenth <= 0f) 0f else -dragPx / pixelsPerTenth

    /** Finger velocity in px/s turned into a scale velocity in tenths/s, already capped. */
    fun velocityToTenths(velocityPxPerSecond: Float, pixelsPerTenth: Float): Float {
        if (pixelsPerTenth <= 0f) return 0f
        val tenths = -velocityPxPerSecond / pixelsPerTenth
        return tenths.coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
    }

    /**
     * Where an exponential decay launched at [velocityTenthsPerSecond] comes to rest,
     * relative to its starting point. Mirrors `FloatExponentialDecaySpec.getTargetValue`.
     */
    fun flingDistanceTenths(velocityTenthsPerSecond: Float): Float =
        velocityTenthsPerSecond / (DECAY_FRICTION_BASE * FLING_FRICTION_MULTIPLIER)

    /** True when a throw is fast enough to be worth animating rather than settling at once. */
    fun isFlingWorthwhile(velocityTenthsPerSecond: Float): Boolean =
        abs(flingDistanceTenths(velocityTenthsPerSecond)) >= MIN_FLING_TENTHS

    // --- Range ----------------------------------------------------------------------

    /** The hard end stops of PRD FR-ENTRY-002: the ruler stops dead, it never rebounds. */
    val LOWER_STOP: Float = Weight.MIN_TENTHS.toFloat()
    val UPPER_STOP: Float = Weight.MAX_TENTHS.toFloat()

    fun clampPosition(positionTenths: Float): Float =
        positionTenths.coerceIn(LOWER_STOP, UPPER_STOP)

    /** The magnetism: the ruler always comes to rest on a valid tenth. */
    fun snapToTenth(positionTenths: Float): Int =
        positionTenths.roundToInt().coerceIn(Weight.MIN_TENTHS, Weight.MAX_TENTHS)

    fun isAtStop(positionTenths: Float): Boolean =
        positionTenths <= LOWER_STOP || positionTenths >= UPPER_STOP

    /** Adds [steps] tenths without ever leaving the range, for the `−` and `+` controls. */
    fun step(tenths: Int, steps: Int): Int =
        (tenths + steps).coerceIn(Weight.MIN_TENTHS, Weight.MAX_TENTHS)

    // --- Drawing --------------------------------------------------------------------

    /** The tenths that can be seen around [positionTenths], clipped to the valid range. */
    fun visibleTenths(positionTenths: Float, halfWidthPx: Float, pixelsPerTenth: Float): IntRange {
        if (pixelsPerTenth <= 0f || halfWidthPx <= 0f) return IntRange.EMPTY
        val span = halfWidthPx / pixelsPerTenth
        val first = max(Weight.MIN_TENTHS.toFloat(), ceil(positionTenths - span)).toInt()
        val last = min(Weight.MAX_TENTHS.toFloat(), floor(positionTenths + span)).toInt()
        return if (first > last) IntRange.EMPTY else first..last
    }

    /** Horizontal position of a graduation, with the fixed marker sitting at [centreXPx]. */
    fun tickX(tenth: Int, positionTenths: Float, pixelsPerTenth: Float, centreXPx: Float): Float =
        centreXPx + (tenth - positionTenths) * pixelsPerTenth

    fun tickOf(tenth: Int): RulerTick = when {
        tenth % TENTHS_PER_MAJOR_TICK == 0 -> RulerTick.Major
        tenth % TENTHS_PER_MEDIUM_TICK == 0 -> RulerTick.Medium
        else -> RulerTick.Minor
    }

    /**
     * Opacity of a graduation, fading to nothing at both edges.
     *
     * The fade is computed per tick rather than painted as an overlay so it works whatever
     * sits behind the ruler — the amber glow of the scaffold included.
     */
    fun edgeAlpha(distanceFromCentrePx: Float, halfWidthPx: Float): Float {
        if (halfWidthPx <= 0f) return 0f
        val ratio = (abs(distanceFromCentrePx) / halfWidthPx).coerceIn(0f, 1f)
        if (ratio <= EDGE_FADE_START) return 1f
        val remaining = (1f - ratio) / (1f - EDGE_FADE_START)
        return (remaining * remaining).coerceIn(0f, 1f)
    }

    // --- Feedback -------------------------------------------------------------------

    /**
     * True when moving from [fromTenths] to [toTenths] crosses a half-kilogram graduation.
     * Reaching an end stop cannot cross one, so no end-stop tick is ever produced.
     */
    fun crossesHapticStep(fromTenths: Int, toTenths: Int): Boolean =
        Math.floorDiv(fromTenths, TENTHS_PER_HAPTIC_STEP) !=
            Math.floorDiv(toTenths, TENTHS_PER_HAPTIC_STEP)

    /** Interval before the [iteration]-th auto-repeat of a held `−` / `+` press. */
    fun repeatIntervalMillis(iteration: Int): Long {
        val decayed = STEP_REPEAT_INTERVAL_MILLIS * STEP_REPEAT_DECAY.pow(max(0, iteration))
        return max(STEP_REPEAT_MIN_INTERVAL_MILLIS, decayed.roundToLong())
    }
}
