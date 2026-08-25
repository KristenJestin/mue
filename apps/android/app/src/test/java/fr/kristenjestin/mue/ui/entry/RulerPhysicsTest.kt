package fr.kristenjestin.mue.ui.entry

import fr.kristenjestin.mue.domain.model.Weight
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One graduation is 8 px on a 1x screen, so one hundredth is 0.8 px. */
private const val PX_PER_HUNDREDTH = 0.8f
private const val PX_PER_TICK = PX_PER_HUNDREDTH * RulerPhysics.HUNDREDTHS_PER_TICK

class RulerPhysicsTest {

    // --- Direction, the one non-tunable rule of FR-ENTRY-002 -------------------------

    @Test
    fun `dragging left increases the weight`() {
        assertEquals(100f, RulerPhysics.dragToHundredths(-80f, PX_PER_HUNDREDTH))
    }

    @Test
    fun `dragging right decreases the weight`() {
        assertEquals(-100f, RulerPhysics.dragToHundredths(80f, PX_PER_HUNDREDTH))
    }

    @Test
    fun `a kilogram of travel is the kilogram spacing`() {
        assertEquals(0.8f, RulerPhysics.DP_PER_HUNDREDTH)
        assertEquals(8f, RulerPhysics.DP_PER_TICK)
        assertEquals(1f, RulerPhysics.dragToHundredths(-PX_PER_HUNDREDTH, PX_PER_HUNDREDTH))
    }

    @Test
    fun `a degenerate density never divides by zero`() {
        assertEquals(0f, RulerPhysics.dragToHundredths(-80f, pixelsPerHundredth = 0f))
        assertEquals(0f, RulerPhysics.velocityToHundredths(-2000f, pixelsPerHundredth = 0f))
    }

    // --- Velocity and decay ----------------------------------------------------------

    @Test
    fun `a leftward flick produces a positive scale velocity`() {
        assertEquals(2500f, RulerPhysics.velocityToHundredths(-2000f, PX_PER_HUNDREDTH))
    }

    @Test
    fun `velocity is capped in both directions`() {
        assertEquals(
            RulerPhysics.MAX_FLING_VELOCITY,
            RulerPhysics.velocityToHundredths(-1_000_000f, PX_PER_HUNDREDTH),
        )
        assertEquals(
            -RulerPhysics.MAX_FLING_VELOCITY,
            RulerPhysics.velocityToHundredths(1_000_000f, PX_PER_HUNDREDTH),
        )
    }

    @Test
    fun `decay distance follows the exponential decay model`() {
        val friction = RulerPhysics.DECAY_FRICTION_BASE * RulerPhysics.FLING_FRICTION_MULTIPLIER
        assertEquals(2500f / friction, RulerPhysics.flingDistanceHundredths(2500f), 1e-3f)
    }

    /** The feel of the glide is unchanged by the step: it is still measured in kilograms. */
    @Test
    fun `a hard flick glides a couple of kilograms, not a couple of dozen`() {
        val distance = RulerPhysics.flingDistanceHundredths(
            RulerPhysics.velocityToHundredths(-2000f, PX_PER_HUNDREDTH)
        )
        assertTrue(distance in 150f..350f, "expected 1.5-3.5 kg of glide, got ${distance / 100} kg")
    }

    @Test
    fun `the fastest possible throw stays inside a short glide`() {
        val distance = RulerPhysics.flingDistanceHundredths(RulerPhysics.MAX_FLING_VELOCITY)
        assertTrue(distance <= 700f, "expected at most 7 kg of glide, got ${distance / 100} kg")
    }

    @Test
    fun `a nudge is not worth flinging`() {
        assertFalse(RulerPhysics.isFlingWorthwhile(10f))
        assertFalse(RulerPhysics.isFlingWorthwhile(-10f))
        assertTrue(RulerPhysics.isFlingWorthwhile(600f))
        assertTrue(RulerPhysics.isFlingWorthwhile(-600f))
    }

    // --- End stops -------------------------------------------------------------------

    @Test
    fun `the position never leaves the valid range`() {
        assertEquals(RulerPhysics.LOWER_STOP, RulerPhysics.clampPosition(-40_000f))
        assertEquals(RulerPhysics.UPPER_STOP, RulerPhysics.clampPosition(999_999f))
        assertEquals(7_405f, RulerPhysics.clampPosition(7_405f))
    }

    @Test
    fun `a fling into a stop lands exactly on the stop`() {
        val projected =
            24_950f + RulerPhysics.flingDistanceHundredths(RulerPhysics.MAX_FLING_VELOCITY)
        assertEquals(Weight.MAX_HUNDREDTHS, RulerPhysics.snapToStep(projected))
        assertEquals(RulerPhysics.UPPER_STOP, RulerPhysics.clampPosition(projected))
    }

    @Test
    fun `the stops are recognised`() {
        assertTrue(RulerPhysics.isAtStop(RulerPhysics.LOWER_STOP))
        assertTrue(RulerPhysics.isAtStop(RulerPhysics.UPPER_STOP))
        assertFalse(RulerPhysics.isAtStop(7_405f))
    }

    @Test
    fun `the stops are the bounds of BR-003`() {
        assertEquals(30f, RulerPhysics.LOWER_STOP / RulerPhysics.HUNDREDTHS_PER_KILOGRAM)
        assertEquals(250f, RulerPhysics.UPPER_STOP / RulerPhysics.HUNDREDTHS_PER_KILOGRAM)
    }

    // --- Magnetism -------------------------------------------------------------------

    @Test
    fun `the ruler settles on the nearest twentieth of a kilogram`() {
        assertEquals(7_405, RulerPhysics.snapToStep(7_405.37f))
        assertEquals(7_405, RulerPhysics.snapToStep(7_402.5f))
        assertEquals(7_400, RulerPhysics.snapToStep(7_402.4f))
        assertEquals(7_410, RulerPhysics.snapToStep(7_407.6f))
    }

    /**
     * PRD FR-ENTRY-002 puts a line every 0.1 kg and a resting place every 0.05 kg, so the
     * marker sitting between two lines is the intended outcome, not a rounding slip.
     */
    @Test
    fun `the settle lands between two graduations half the time`() {
        val landing = RulerPhysics.snapToStep(7_404f)
        assertEquals(7_405, landing)
        assertEquals(0, landing % Weight.STEP_HUNDREDTHS)
        assertTrue(landing % RulerPhysics.HUNDREDTHS_PER_TICK != 0, "expected an off-tick landing")
    }

    @Test
    fun `every landing is a whole number of steps`() {
        var position = Weight.MIN_HUNDREDTHS.toFloat()
        while (position <= Weight.MAX_HUNDREDTHS) {
            assertEquals(0, RulerPhysics.snapToStep(position) % Weight.STEP_HUNDREDTHS, "$position")
            position += 3.7f
        }
    }

    @Test
    fun `snapping clamps as well as rounds`() {
        assertEquals(Weight.MIN_HUNDREDTHS, RulerPhysics.snapToStep(120f))
        assertEquals(Weight.MAX_HUNDREDTHS, RulerPhysics.snapToStep(90_000f))
        assertEquals(Weight.MAX_HUNDREDTHS, RulerPhysics.snapToStep(1e9f))
        assertEquals(Weight.MIN_HUNDREDTHS, RulerPhysics.snapToStep(-1e9f))
    }

    // --- Stepping --------------------------------------------------------------------

    @Test
    fun `a step moves exactly five hundredths`() {
        assertEquals(5, RulerPhysics.STEP_HUNDREDTHS)
        assertEquals(7_410, RulerPhysics.step(7_405, 1))
        assertEquals(7_400, RulerPhysics.step(7_405, -1))
        assertEquals(7_425, RulerPhysics.step(7_405, 4))
    }

    @Test
    fun `stepping stops dead at both end stops`() {
        assertEquals(Weight.MIN_HUNDREDTHS, RulerPhysics.step(Weight.MIN_HUNDREDTHS, -1))
        assertEquals(Weight.MAX_HUNDREDTHS, RulerPhysics.step(Weight.MAX_HUNDREDTHS, 1))
    }

    /** Twenty presses of `+` add exactly one kilogram — no accumulated drift. */
    @Test
    fun `twenty presses make a kilogram`() {
        var value = 7_000
        repeat(20) { value = RulerPhysics.step(value, 1) }
        assertEquals(7_100, value)
    }

    // --- Geometry --------------------------------------------------------------------

    @Test
    fun `the value under the marker sits exactly at the centre`() {
        val tick = 7_405 / RulerPhysics.HUNDREDTHS_PER_TICK
        assertEquals(200f, RulerPhysics.tickX(tick, 7_400f, PX_PER_HUNDREDTH, centreXPx = 200f))
    }

    @Test
    fun `higher values are drawn to the right of the marker`() {
        val higher = RulerPhysics.tickX(750, 7_400f, PX_PER_HUNDREDTH, centreXPx = 200f)
        val lower = RulerPhysics.tickX(730, 7_400f, PX_PER_HUNDREDTH, centreXPx = 200f)
        assertEquals(280f, higher)
        assertEquals(120f, lower)
    }

    @Test
    fun `a graduation is worth ten hundredths`() {
        assertEquals(10, RulerPhysics.HUNDREDTHS_PER_TICK)
        assertEquals(7_400, RulerPhysics.tickValue(740))
        assertEquals(74, RulerPhysics.tickLabel(740))
    }

    @Test
    fun `only the graduations that fit on screen are drawn`() {
        val visible = RulerPhysics.visibleTicks(7_450f, halfWidthPx = 80f, PX_PER_HUNDREDTH)
        assertEquals(735..755, visible)
    }

    /**
     * The step halved but the graduations did not, so the per-frame loop is exactly as long
     * as it was: one iteration per drawn line, never one per reachable value.
     */
    @Test
    fun `halving the step did not lengthen the draw loop`() {
        val halfWidthPx = 540f
        val visible = RulerPhysics.visibleTicks(7_450f, halfWidthPx, PX_PER_HUNDREDTH)
        val expected = (2 * halfWidthPx / PX_PER_TICK).toInt()
        assertTrue(
            visible.count() in (expected - 1)..(expected + 1),
            "expected about $expected graduations, got ${visible.count()}",
        )
    }

    @Test
    fun `the ruler is not drawn past its end stops`() {
        assertEquals(
            300..310,
            RulerPhysics.visibleTicks(3_000f, halfWidthPx = 80f, PX_PER_HUNDREDTH),
        )
        assertEquals(
            2_490..2_500,
            RulerPhysics.visibleTicks(25_000f, halfWidthPx = 80f, PX_PER_HUNDREDTH),
        )
    }

    @Test
    fun `an unmeasured ruler draws nothing`() {
        assertTrue(RulerPhysics.visibleTicks(7_450f, 0f, PX_PER_HUNDREDTH).isEmpty())
        assertTrue(RulerPhysics.visibleTicks(7_450f, 80f, 0f).isEmpty())
    }

    @Test
    fun `whole kilograms are the major graduations`() {
        assertEquals(RulerTick.Major, RulerPhysics.tickOf(740))
        assertEquals(RulerTick.Medium, RulerPhysics.tickOf(745))
        assertEquals(RulerTick.Minor, RulerPhysics.tickOf(741))
        assertEquals(RulerTick.Minor, RulerPhysics.tickOf(749))
    }

    @Test
    fun `graduations fade out towards both edges`() {
        assertEquals(1f, RulerPhysics.edgeAlpha(0f, halfWidthPx = 100f))
        assertEquals(1f, RulerPhysics.edgeAlpha(50f, halfWidthPx = 100f))
        assertEquals(0f, RulerPhysics.edgeAlpha(100f, halfWidthPx = 100f))
        assertEquals(0f, RulerPhysics.edgeAlpha(-100f, halfWidthPx = 100f))
        val mid = RulerPhysics.edgeAlpha(80f, halfWidthPx = 100f)
        assertTrue(mid > 0f && mid < 1f, "expected a partial fade, got $mid")
        assertEquals(mid, RulerPhysics.edgeAlpha(-80f, halfWidthPx = 100f))
    }

    /**
     * The strip runs the full width of the screen, so the half-width is roughly this on a
     * 411 dp phone. The kilogram either side of the marker is what the eye aims at, and it
     * has to survive the fade at that width — as does the one beyond it, in part.
     */
    @Test
    fun `the kilogram either side of the marker survives the edge fade`() {
        val halfWidthDp = 206f
        assertEquals(1f, RulerPhysics.edgeAlpha(RulerPhysics.DP_PER_KILOGRAM, halfWidthDp))
        assertEquals(1f, RulerPhysics.edgeAlpha(-RulerPhysics.DP_PER_KILOGRAM, halfWidthDp))
        val second = RulerPhysics.edgeAlpha(2 * RulerPhysics.DP_PER_KILOGRAM, halfWidthDp)
        assertTrue(second > 0f, "the second kilogram must still be readable, got $second")
    }

    /** The fade must dissolve the ends rather than cut them: no visible step at the edge. */
    @Test
    fun `the fade reaches nothing exactly at the edge`() {
        val halfWidth = 200f
        val nearEdge = RulerPhysics.edgeAlpha(halfWidth * 0.99f, halfWidth)
        assertTrue(nearEdge < 0.02f, "expected the ramp to be spent at the edge, got $nearEdge")
        assertEquals(0f, RulerPhysics.edgeAlpha(halfWidth, halfWidth))
    }

    // --- Feedback --------------------------------------------------------------------

    /** PRD FR-ENTRY-002 keeps the drag cadence at 0.5 kg; the smaller step must not shift it. */
    @Test
    fun `a tick fires every half kilogram and never on a step`() {
        assertTrue(RulerPhysics.crossesHapticStep(7_445, 7_450))
        assertTrue(RulerPhysics.crossesHapticStep(7_500, 7_495))
        assertFalse(RulerPhysics.crossesHapticStep(7_450, 7_455))
        assertFalse(RulerPhysics.crossesHapticStep(7_455, 7_460))
        assertFalse(RulerPhysics.crossesHapticStep(7_450, 7_450))
    }

    @Test
    fun `the bucket changes exactly on the half kilograms`() {
        assertEquals(50, RulerPhysics.HUNDREDTHS_PER_HAPTIC_STEP)
        assertEquals(RulerPhysics.hapticStepOf(7_450), RulerPhysics.hapticStepOf(7_499))
        assertEquals(RulerPhysics.hapticStepOf(7_400), RulerPhysics.hapticStepOf(7_449))
        assertTrue(RulerPhysics.hapticStepOf(7_449) < RulerPhysics.hapticStepOf(7_450))
    }

    /**
     * A drag delivers a burst of movement, not one step at a time. What matters is that the
     * ruler ticks once per half kilogram of *travel*, whatever size the jumps between two
     * frames happen to be — the scale must feel the same slow and fast.
     */
    @Test
    fun `a drag ticks once per half kilogram whatever the frame rate`() {
        fun ticksAlong(path: List<Int>): Int =
            path.zipWithNext().count { (from, to) -> RulerPhysics.crossesHapticStep(from, to) }

        val slow = (7_400..7_600 step Weight.STEP_HUNDREDTHS).toList()
        val fast = listOf(7_400, 7_470, 7_520, 7_580, 7_600)
        assertEquals(4, ticksAlong(slow), "expected 74.5, 75.0, 75.5 and 76.0")
        assertEquals(4, ticksAlong(fast))
    }

    @Test
    fun `a drag that turns back ticks on the way back too`() {
        val path = listOf(7_450, 7_500, 7_550, 7_500, 7_450)
        val ticks = path.zipWithNext().count { (from, to) -> RulerPhysics.crossesHapticStep(from, to) }
        assertEquals(4, ticks)
    }

    @Test
    fun `pushing against a stop produces no tick`() {
        val stopped = RulerPhysics.step(Weight.MIN_HUNDREDTHS, -1)
        assertFalse(RulerPhysics.crossesHapticStep(Weight.MIN_HUNDREDTHS, stopped))
        val topped = RulerPhysics.step(Weight.MAX_HUNDREDTHS, 1)
        assertFalse(RulerPhysics.crossesHapticStep(Weight.MAX_HUNDREDTHS, topped))
    }

    @Test
    fun `a held press accelerates then levels off`() {
        val first = RulerPhysics.repeatIntervalMillis(0)
        val tenth = RulerPhysics.repeatIntervalMillis(10)
        val hundredth = RulerPhysics.repeatIntervalMillis(100)
        assertEquals(RulerPhysics.STEP_REPEAT_INTERVAL_MILLIS, first)
        assertTrue(tenth < first, "expected the repeat to speed up")
        assertEquals(RulerPhysics.STEP_REPEAT_MIN_INTERVAL_MILLIS, hundredth)
        assertEquals(400L, RulerPhysics.STEP_REPEAT_DELAY_MILLIS)
    }
}
