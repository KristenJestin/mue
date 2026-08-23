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

/** Visual weight of a graduation, decided by the tick it sits on. */
enum class RulerTick { Minor, Medium, Major }

/**
 * Every number the touch scale is made of, and the geometry that turns finger pixels into
 * hundredths of a kilogram.
 *
 * This object is deliberately free of Compose and of Android: the feel of the scale is the
 * riskiest part of the app (PRD 18) and it has to be provable on the JVM and tunable in one
 * place after the first tests on real hardware.
 *
 * The scale's continuous position is expressed in *hundredths of a kilogram*, not pixels.
 * Working in the value domain means the end stops, the fling and the magnetism all clamp
 * against [Weight.RANGE] directly, and none of the physics changes when the screen density
 * does.
 *
 * Graduations are indexed separately from values. The scale settles on 0.05 kg but only draws
 * a line every 0.1 kg (PRD FR-ENTRY-002), so a graduation is addressed by its own index and
 * the draw loop runs once per *visible line* rather than once per reachable value — the marker
 * simply comes to rest between two lines half the time, which the PRD asks for explicitly.
 */
object RulerPhysics {

    // --- Geometry -------------------------------------------------------------------

    const val HUNDREDTHS_PER_KILOGRAM: Int = 100

    /**
     * Ruler travel for one kilogram, in dp. The prototype glides at 80 CSS px per kilogram;
     * 80 dp keeps that feel and puts one graduation 8 dp away from the next, which is far
     * enough apart to be aimed at with a finger and close enough for a 4 kg sweep across the
     * screen.
     */
    const val DP_PER_KILOGRAM: Float = 80f

    val DP_PER_HUNDREDTH: Float = DP_PER_KILOGRAM / HUNDREDTHS_PER_KILOGRAM

    /**
     * Spacing of the visible minor graduations, in hundredths: 0.1 kg.
     *
     * PRD FR-ENTRY-002 keeps the lines here rather than on the 0.05 kg step because a line
     * every 0.05 kg would be 4 dp from its neighbour and unreadable.
     */
    const val HUNDREDTHS_PER_TICK: Int = 10

    /** A graduation every 0.1 kg, taller every half kilogram, tallest and labelled every kilogram. */
    const val TICKS_PER_MEDIUM_TICK: Int = 5
    const val TICKS_PER_MAJOR_TICK: Int = HUNDREDTHS_PER_KILOGRAM / HUNDREDTHS_PER_TICK

    val DP_PER_TICK: Float = DP_PER_HUNDREDTH * HUNDREDTHS_PER_TICK

    /**
     * Fraction of the half-width that stays fully opaque before the ruler fades to nothing.
     *
     * The strip now runs the full width of the screen, so the ramp can be long: it starts
     * around a kilogram and a half either side of the marker, which keeps the graduations the
     * eye aims at crisp while the ends dissolve rather than being cut off.
     */
    const val EDGE_FADE_START: Float = 0.55f

    // --- Fling ----------------------------------------------------------------------

    /**
     * Friction constant of `exponentialDecay`, copied from `FloatExponentialDecaySpec`. It is
     * repeated here so [flingDistanceHundredths] predicts the very animation the screen runs.
     */
    const val DECAY_FRICTION_BASE: Float = 4.2f

    /**
     * Rather more friction than the Compose default of 1. A weight scale is an aiming device:
     * a hard flick should cross a couple of kilograms, not thirty, so the value stays readable
     * while it glides. Gives the "short precise glide" of PRD FR-ENTRY-002.
     */
    const val FLING_FRICTION_MULTIPLIER: Float = 2.6f

    /** The decay ends here, in hundredths per second — 0.15 kg/s is already imperceptible. */
    const val FLING_VELOCITY_THRESHOLD: Float = 15f

    /** Ceiling on a throw, in hundredths per second. Caps any single fling at roughly 6.4 kg. */
    const val MAX_FLING_VELOCITY: Float = 7_000f

    /** Below this projected travel the fling is not worth running; settle straight away. */
    const val MIN_FLING_HUNDREDTHS: Float = 5f

    /** Critically damped: the magnetism pulls, it never bounces (PRD FR-ENTRY-002). */
    const val SETTLE_DAMPING_RATIO: Float = 1f
    const val SETTLE_STIFFNESS: Float = 1200f

    /**
     * Displacement below which the settle is considered arrived, in hundredths — 0.001 kg.
     *
     * Stated rather than left to Compose's default of 0.01: that default is a raw number, so
     * finishing the unit change from tenths to hundredths would silently ask the spring to
     * converge ten times further and spend extra frames doing it, every time the finger lifts.
     */
    const val SETTLE_VISIBILITY_THRESHOLD: Float = 0.1f

    // --- Haptics --------------------------------------------------------------------

    /** PRD FR-ENTRY-002 asks for a tick every 0.5 kg of ruler travel, never every step. */
    const val HUNDREDTHS_PER_HAPTIC_STEP: Int = 50

    // --- Accessible stepping --------------------------------------------------------

    /** One press of `−` or `+`: 0.05 kg (PRD FR-ENTRY-003). */
    const val STEP_HUNDREDTHS: Int = Weight.STEP_HUNDREDTHS

    /** PRD FR-ENTRY-003: a held press starts repeating after about this long. */
    const val STEP_REPEAT_DELAY_MILLIS: Long = 400L

    /** First repeat interval, then multiplied by [STEP_REPEAT_DECAY] until [STEP_REPEAT_MIN_INTERVAL_MILLIS]. */
    const val STEP_REPEAT_INTERVAL_MILLIS: Long = 140L
    const val STEP_REPEAT_MIN_INTERVAL_MILLIS: Long = 36L
    const val STEP_REPEAT_DECAY: Float = 0.82f

    // --- Conversions ----------------------------------------------------------------

    /**
     * Finger travel in pixels turned into a change in hundredths.
     *
     * The sign is the whole point of PRD FR-ENTRY-002 and is not a tunable: values grow
     * left to right along the ruler, so pulling the ruler leftwards (negative [dragPx])
     * brings a *higher* value under the fixed marker.
     */
    fun dragToHundredths(dragPx: Float, pixelsPerHundredth: Float): Float =
        if (pixelsPerHundredth <= 0f) 0f else -dragPx / pixelsPerHundredth

    /** Finger velocity in px/s turned into a scale velocity in hundredths/s, already capped. */
    fun velocityToHundredths(velocityPxPerSecond: Float, pixelsPerHundredth: Float): Float {
        if (pixelsPerHundredth <= 0f) return 0f
        val hundredths = -velocityPxPerSecond / pixelsPerHundredth
        return hundredths.coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
    }

    /**
     * Where an exponential decay launched at [velocityHundredthsPerSecond] comes to rest,
     * relative to its starting point. Mirrors `FloatExponentialDecaySpec.getTargetValue`.
     */
    fun flingDistanceHundredths(velocityHundredthsPerSecond: Float): Float =
        velocityHundredthsPerSecond / (DECAY_FRICTION_BASE * FLING_FRICTION_MULTIPLIER)

    /** True when a throw is fast enough to be worth animating rather than settling at once. */
    fun isFlingWorthwhile(velocityHundredthsPerSecond: Float): Boolean =
        abs(flingDistanceHundredths(velocityHundredthsPerSecond)) >= MIN_FLING_HUNDREDTHS

    // --- Range ----------------------------------------------------------------------

    /** The hard end stops of PRD FR-ENTRY-002: the ruler stops dead, it never rebounds. */
    val LOWER_STOP: Float = Weight.MIN_HUNDREDTHS.toFloat()
    val UPPER_STOP: Float = Weight.MAX_HUNDREDTHS.toFloat()

    fun clampPosition(positionHundredths: Float): Float =
        positionHundredths.coerceIn(LOWER_STOP, UPPER_STOP)

    /**
     * The magnetism: the ruler always comes to rest on a valid 0.05 kg, whether or not a
     * graduation is drawn there (PRD FR-ENTRY-002).
     *
     * Counted in steps rather than in hundredths so the range check happens before the
     * multiplication and cannot be reached by an overflow.
     */
    fun snapToStep(positionHundredths: Float): Int =
        (positionHundredths / STEP_HUNDREDTHS).roundToInt()
            .coerceIn(MIN_STEPS, MAX_STEPS) * STEP_HUNDREDTHS

    private val MIN_STEPS: Int = Weight.MIN_HUNDREDTHS / STEP_HUNDREDTHS
    private val MAX_STEPS: Int = Weight.MAX_HUNDREDTHS / STEP_HUNDREDTHS

    fun isAtStop(positionHundredths: Float): Boolean =
        positionHundredths <= LOWER_STOP || positionHundredths >= UPPER_STOP

    /** Adds [steps] steps of 0.05 kg without ever leaving the range, for `−` and `+`. */
    fun step(hundredths: Int, steps: Int): Int =
        (hundredths + steps * STEP_HUNDREDTHS)
            .coerceIn(Weight.MIN_HUNDREDTHS, Weight.MAX_HUNDREDTHS)

    // --- Drawing --------------------------------------------------------------------

    /** Value, in hundredths, of the graduation numbered [tick]. */
    fun tickValue(tick: Int): Int = tick * HUNDREDTHS_PER_TICK

    /**
     * The graduations that can be seen around [positionHundredths], clipped to the valid range.
     *
     * Returns *tick indices*, one per drawn line, so halving the step did not double the
     * length of the per-frame loop.
     */
    fun visibleTicks(
        positionHundredths: Float,
        halfWidthPx: Float,
        pixelsPerHundredth: Float,
    ): IntRange {
        if (pixelsPerHundredth <= 0f || halfWidthPx <= 0f) return IntRange.EMPTY
        val span = halfWidthPx / pixelsPerHundredth
        val lowest = Weight.MIN_HUNDREDTHS.toFloat() / HUNDREDTHS_PER_TICK
        val highest = Weight.MAX_HUNDREDTHS.toFloat() / HUNDREDTHS_PER_TICK
        val first = max(lowest, ceil((positionHundredths - span) / HUNDREDTHS_PER_TICK)).toInt()
        val last = min(highest, floor((positionHundredths + span) / HUNDREDTHS_PER_TICK)).toInt()
        return if (first > last) IntRange.EMPTY else first..last
    }

    /** Horizontal position of graduation [tick], with the fixed marker sitting at [centreXPx]. */
    fun tickX(
        tick: Int,
        positionHundredths: Float,
        pixelsPerHundredth: Float,
        centreXPx: Float,
    ): Float = centreXPx + (tickValue(tick) - positionHundredths) * pixelsPerHundredth

    fun tickOf(tick: Int): RulerTick = when {
        tick % TICKS_PER_MAJOR_TICK == 0 -> RulerTick.Major
        tick % TICKS_PER_MEDIUM_TICK == 0 -> RulerTick.Medium
        else -> RulerTick.Minor
    }

    /** The whole kilogram printed under a major graduation. */
    fun tickLabel(tick: Int): Int = tick / TICKS_PER_MAJOR_TICK

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
     * Which half-kilogram bucket a value falls in. The tick fires when this number changes,
     * whatever distance the ruler covered in between.
     */
    fun hapticStepOf(hundredths: Int): Int = Math.floorDiv(hundredths, HUNDREDTHS_PER_HAPTIC_STEP)

    /**
     * True when moving from [fromHundredths] to [toHundredths] crosses a half-kilogram
     * graduation. Reaching an end stop cannot cross one, so no end-stop tick is ever produced.
     */
    fun crossesHapticStep(fromHundredths: Int, toHundredths: Int): Boolean =
        hapticStepOf(fromHundredths) != hapticStepOf(toHundredths)

    /** Interval before the [iteration]-th auto-repeat of a held `−` / `+` press. */
    fun repeatIntervalMillis(iteration: Int): Long {
        val decayed = STEP_REPEAT_INTERVAL_MILLIS * STEP_REPEAT_DECAY.pow(max(0, iteration))
        return max(STEP_REPEAT_MIN_INTERVAL_MILLIS, decayed.roundToLong())
    }
}
