package fr.kristenjestin.mue.ui.components

import fr.kristenjestin.mue.domain.model.ActivityDuration
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One row is 48 dp, which is 48 px on a 1x screen. */
private const val PX_PER_VALUE = 48f

/** PRD FR-ACTIVITY-005: the two wheels a duration is made of. */
private val HOURS = 0..99
private val MINUTES = 0 until ActivityDuration.SECONDS_PER_MINUTE

class MueWheelPhysicsTest {

    // --- Direction, the wheel's one non-tunable rule ---------------------------------

    @Test
    fun `dragging the wheel down brings a lower value under the centre`() {
        assertEquals(-1f, MueWheelPhysics.dragToValues(PX_PER_VALUE, PX_PER_VALUE))
    }

    @Test
    fun `dragging the wheel up brings a higher value under the centre`() {
        assertEquals(1f, MueWheelPhysics.dragToValues(-PX_PER_VALUE, PX_PER_VALUE))
    }

    @Test
    fun `a row of travel is the touch target PRD 15 asks for`() {
        assertEquals(48f, MueWheelPhysics.DP_PER_VALUE)
    }

    @Test
    fun `a degenerate density never divides by zero`() {
        assertEquals(0f, MueWheelPhysics.dragToValues(48f, pixelsPerValue = 0f))
        assertEquals(0f, MueWheelPhysics.velocityToValues(-2_000f, pixelsPerValue = 0f))
    }

    // --- Velocity and decay ----------------------------------------------------------

    @Test
    fun `an upward flick produces a positive wheel velocity`() {
        assertEquals(25f, MueWheelPhysics.velocityToValues(-1_200f, PX_PER_VALUE))
    }

    @Test
    fun `no throw can exceed the cap, in either direction`() {
        val fast = 1_000_000f
        assertEquals(
            MueWheelPhysics.MAX_FLING_VELOCITY,
            MueWheelPhysics.velocityToValues(-fast, PX_PER_VALUE),
        )
        assertEquals(
            -MueWheelPhysics.MAX_FLING_VELOCITY,
            MueWheelPhysics.velocityToValues(fast, PX_PER_VALUE),
        )
    }

    /**
     * The reason the friction is not the ruler's. A row is 48 dp, so a throw has to carry much
     * further than the ruler's deliberately short glide before the wheel beats a keyboard.
     *
     * The two velocities below are a finger's, converted through the emulator's own 2.625
     * density: a brisk flick is around 5 000 px/s and a hard one around 9 000.
     */
    @Test
    fun `a flick covers a useful stretch of the wheel`() {
        val pixelsPerValue = PX_PER_VALUE * 2.625f
        val brisk = MueWheelPhysics.velocityToValues(-5_000f, pixelsPerValue)
        val hard = MueWheelPhysics.velocityToValues(-9_000f, pixelsPerValue)

        assertTrue(
            MueWheelPhysics.flingDistanceValues(brisk) >= 12f,
            "a brisk flick only reaches ${MueWheelPhysics.flingDistanceValues(brisk)} rows",
        )
        // Two hard flicks cross the minutes wheel end to end, which is the bar this friction
        // was chosen against.
        assertTrue(
            MueWheelPhysics.flingDistanceValues(hard) * 2f >= MINUTES.last.toFloat(),
            "a hard flick only reaches ${MueWheelPhysics.flingDistanceValues(hard)} rows",
        )
    }

    /** And the cap has to sit above a full traversal, or the wheel would have a hidden ceiling. */
    @Test
    fun `the cap never stops a throw short of either end`() {
        val reach = abs(MueWheelPhysics.flingDistanceValues(MueWheelPhysics.MAX_FLING_VELOCITY))
        assertTrue(reach >= MINUTES.last.toFloat(), "a capped throw only reaches $reach rows")
    }

    @Test
    fun `a nudge settles rather than gliding`() {
        assertFalse(MueWheelPhysics.isFlingWorthwhile(1f))
        assertTrue(MueWheelPhysics.isFlingWorthwhile(60f))
    }

    // --- End stops and the magnet ----------------------------------------------------

    @Test
    fun `the wheel stops dead at both ends and never wraps`() {
        assertEquals(0f, MueWheelPhysics.clampPosition(-14f, MINUTES))
        assertEquals(59f, MueWheelPhysics.clampPosition(120f, MINUTES))
        assertEquals(99f, MueWheelPhysics.clampPosition(1_000f, HOURS))
        assertTrue(MueWheelPhysics.isAtStop(0f, MINUTES))
        assertTrue(MueWheelPhysics.isAtStop(59f, MINUTES))
        assertFalse(MueWheelPhysics.isAtStop(30f, MINUTES))
    }

    @Test
    fun `the wheel always comes to rest on a whole value`() {
        assertEquals(44, MueWheelPhysics.snapToValue(43.6f, MINUTES))
        assertEquals(43, MueWheelPhysics.snapToValue(43.4f, MINUTES))
        assertEquals(0, MueWheelPhysics.snapToValue(-9f, MINUTES))
        assertEquals(59, MueWheelPhysics.snapToValue(200f, MINUTES))
    }

    @Test
    fun `an accessibility step never leaves the range`() {
        assertEquals(31, MueWheelPhysics.step(30, 1, MINUTES))
        assertEquals(59, MueWheelPhysics.step(59, 1, MINUTES))
        assertEquals(0, MueWheelPhysics.step(0, -1, MINUTES))
        assertEquals(99, MueWheelPhysics.step(90, 20, HOURS))
    }

    /** TalkBack stops on every value, ends excluded — one per minute, one per hour. */
    @Test
    fun `every value is a stop an assistive gesture can reach`() {
        assertEquals(58, MueWheelPhysics.adjustableSteps(MINUTES))
        assertEquals(98, MueWheelPhysics.adjustableSteps(HOURS))
        assertEquals(0, MueWheelPhysics.adjustableSteps(0..0))
        assertEquals(0, MueWheelPhysics.adjustableSteps(IntRange.EMPTY))
    }

    // --- Drawing ---------------------------------------------------------------------

    @Test
    fun `only the rows on screen are drawn, and never one outside the range`() {
        // A row and a half either side of the centre: the three visible rows.
        val halfHeight = PX_PER_VALUE * MueWheelPhysics.VISIBLE_VALUES / 2f

        assertEquals(29..31, MueWheelPhysics.visibleValues(30f, halfHeight, PX_PER_VALUE, MINUTES))
        assertEquals(0..1, MueWheelPhysics.visibleValues(0f, halfHeight, PX_PER_VALUE, MINUTES))
        assertEquals(58..59, MueWheelPhysics.visibleValues(59f, halfHeight, PX_PER_VALUE, MINUTES))
    }

    @Test
    fun `a wheel with no size draws nothing`() {
        assertEquals(IntRange.EMPTY, MueWheelPhysics.visibleValues(30f, 0f, PX_PER_VALUE, MINUTES))
        assertEquals(IntRange.EMPTY, MueWheelPhysics.visibleValues(30f, 120f, 0f, MINUTES))
    }

    @Test
    fun `the selected value sits on the centre and its neighbours one row away`() {
        assertEquals(120f, MueWheelPhysics.valueY(30, 30f, PX_PER_VALUE, centreYPx = 120f))
        assertEquals(168f, MueWheelPhysics.valueY(31, 30f, PX_PER_VALUE, centreYPx = 120f))
        assertEquals(72f, MueWheelPhysics.valueY(29, 30f, PX_PER_VALUE, centreYPx = 120f))
    }

    @Test
    fun `a row fades out towards the ends and is solid at the centre`() {
        val halfHeight = 120f
        assertEquals(1f, MueWheelPhysics.edgeAlpha(0f, halfHeight))
        assertEquals(1f, MueWheelPhysics.edgeAlpha(-60f, halfHeight))
        assertEquals(0f, MueWheelPhysics.edgeAlpha(halfHeight, halfHeight))
        assertTrue(MueWheelPhysics.edgeAlpha(108f, halfHeight) < 1f)
        assertTrue(MueWheelPhysics.edgeAlpha(108f, halfHeight) > 0f)
        // Symmetric: a row above the centre reads exactly like the one below it.
        assertEquals(
            MueWheelPhysics.edgeAlpha(108f, halfHeight),
            MueWheelPhysics.edgeAlpha(-108f, halfHeight),
        )
        assertEquals(0f, MueWheelPhysics.edgeAlpha(30f, halfHeightPx = 0f))
    }

    // --- Feedback --------------------------------------------------------------------

    @Test
    fun `the wheel ticks every five rows rather than on every one`() {
        assertTrue(MueWheelPhysics.crossesHapticStep(4, 5))
        assertFalse(MueWheelPhysics.crossesHapticStep(5, 6))
        assertFalse(MueWheelPhysics.crossesHapticStep(30, 30))
        assertTrue(MueWheelPhysics.crossesHapticStep(30, 29))
    }

    /** Standing still at an end stop cannot cross a tick, so no end-stop buzz is produced. */
    @Test
    fun `an end stop produces no tick`() {
        assertFalse(MueWheelPhysics.crossesHapticStep(0, 0))
        assertFalse(MueWheelPhysics.crossesHapticStep(59, 59))
    }

    // --- The ruler's shape, kept ------------------------------------------------------

    /**
     * The wheel and the weight ruler are one family, and two of their numbers say so: the same
     * decay friction base and the same critically damped settle. If either is ever retuned this
     * is where the other finds out.
     */
    @Test
    fun `the glide and the magnet keep the shape the ruler was tuned to`() {
        assertEquals(4.2f, MueWheelPhysics.DECAY_FRICTION_BASE)
        assertEquals(1f, MueWheelPhysics.SETTLE_DAMPING_RATIO)
        assertEquals(1_200f, MueWheelPhysics.SETTLE_STIFFNESS)
        assertEquals(5, MueWheelPhysics.VALUES_PER_HAPTIC_STEP)
    }
}
