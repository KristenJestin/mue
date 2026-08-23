package fr.kristenjestin.mue.ui.entry

import fr.kristenjestin.mue.domain.model.Weight
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One tenth is 8 px on a 1x screen, which keeps the arithmetic below readable. */
private const val PX_PER_TENTH = 8f

class RulerPhysicsTest {

    // --- Direction, the one non-tunable rule of FR-ENTRY-002 -------------------------

    @Test
    fun `dragging left increases the weight`() {
        assertEquals(10f, RulerPhysics.dragToTenths(dragPx = -80f, pixelsPerTenth = PX_PER_TENTH))
    }

    @Test
    fun `dragging right decreases the weight`() {
        assertEquals(-10f, RulerPhysics.dragToTenths(dragPx = 80f, pixelsPerTenth = PX_PER_TENTH))
    }

    @Test
    fun `one tenth of a kilogram is one eighth of the kilogram spacing`() {
        assertEquals(8f, RulerPhysics.DP_PER_TENTH)
        assertEquals(1f, RulerPhysics.dragToTenths(-PX_PER_TENTH, PX_PER_TENTH))
    }

    @Test
    fun `a degenerate density never divides by zero`() {
        assertEquals(0f, RulerPhysics.dragToTenths(-80f, pixelsPerTenth = 0f))
        assertEquals(0f, RulerPhysics.velocityToTenths(-2000f, pixelsPerTenth = 0f))
    }

    // --- Velocity and decay ----------------------------------------------------------

    @Test
    fun `a leftward flick produces a positive scale velocity`() {
        assertEquals(250f, RulerPhysics.velocityToTenths(-2000f, PX_PER_TENTH))
    }

    @Test
    fun `velocity is capped in both directions`() {
        assertEquals(
            RulerPhysics.MAX_FLING_VELOCITY,
            RulerPhysics.velocityToTenths(-1_000_000f, PX_PER_TENTH),
        )
        assertEquals(
            -RulerPhysics.MAX_FLING_VELOCITY,
            RulerPhysics.velocityToTenths(1_000_000f, PX_PER_TENTH),
        )
    }

    @Test
    fun `decay distance follows the exponential decay model`() {
        val friction = RulerPhysics.DECAY_FRICTION_BASE * RulerPhysics.FLING_FRICTION_MULTIPLIER
        assertEquals(250f / friction, RulerPhysics.flingDistanceTenths(250f), 1e-4f)
    }

    @Test
    fun `a hard flick glides a couple of kilograms, not a couple of dozen`() {
        val distance = RulerPhysics.flingDistanceTenths(RulerPhysics.velocityToTenths(-2000f, PX_PER_TENTH))
        assertTrue(distance in 15f..35f, "expected 1.5-3.5 kg of glide, got ${distance / 10} kg")
    }

    @Test
    fun `the fastest possible throw stays inside a short glide`() {
        val distance = RulerPhysics.flingDistanceTenths(RulerPhysics.MAX_FLING_VELOCITY)
        assertTrue(distance <= 70f, "expected at most 7 kg of glide, got ${distance / 10} kg")
    }

    @Test
    fun `a nudge is not worth flinging`() {
        assertFalse(RulerPhysics.isFlingWorthwhile(1f))
        assertFalse(RulerPhysics.isFlingWorthwhile(-1f))
        assertTrue(RulerPhysics.isFlingWorthwhile(60f))
        assertTrue(RulerPhysics.isFlingWorthwhile(-60f))
    }

    // --- End stops -------------------------------------------------------------------

    @Test
    fun `the position never leaves the valid range`() {
        assertEquals(RulerPhysics.LOWER_STOP, RulerPhysics.clampPosition(-4000f))
        assertEquals(RulerPhysics.UPPER_STOP, RulerPhysics.clampPosition(99_999f))
        assertEquals(745f, RulerPhysics.clampPosition(745f))
    }

    @Test
    fun `a fling into a stop lands exactly on the stop`() {
        val projected = 2495f + RulerPhysics.flingDistanceTenths(RulerPhysics.MAX_FLING_VELOCITY)
        assertEquals(Weight.MAX_TENTHS, RulerPhysics.snapToTenth(projected))
        assertEquals(RulerPhysics.UPPER_STOP, RulerPhysics.clampPosition(projected))
    }

    @Test
    fun `the stops are recognised`() {
        assertTrue(RulerPhysics.isAtStop(RulerPhysics.LOWER_STOP))
        assertTrue(RulerPhysics.isAtStop(RulerPhysics.UPPER_STOP))
        assertFalse(RulerPhysics.isAtStop(745f))
    }

    // --- Magnetism -------------------------------------------------------------------

    @Test
    fun `the ruler settles on the nearest tenth`() {
        assertEquals(745, RulerPhysics.snapToTenth(745.37f))
        assertEquals(745, RulerPhysics.snapToTenth(744.5f))
        assertEquals(744, RulerPhysics.snapToTenth(744.49f))
        assertEquals(746, RulerPhysics.snapToTenth(745.51f))
    }

    @Test
    fun `snapping clamps as well as rounds`() {
        assertEquals(Weight.MIN_TENTHS, RulerPhysics.snapToTenth(12f))
        assertEquals(Weight.MAX_TENTHS, RulerPhysics.snapToTenth(9_000f))
    }

    // --- Stepping --------------------------------------------------------------------

    @Test
    fun `a step moves exactly one tenth`() {
        assertEquals(746, RulerPhysics.step(745, 1))
        assertEquals(744, RulerPhysics.step(745, -1))
    }

    @Test
    fun `stepping stops dead at both end stops`() {
        assertEquals(Weight.MIN_TENTHS, RulerPhysics.step(Weight.MIN_TENTHS, -1))
        assertEquals(Weight.MAX_TENTHS, RulerPhysics.step(Weight.MAX_TENTHS, 1))
    }

    // --- Geometry --------------------------------------------------------------------

    @Test
    fun `the value under the marker sits exactly at the centre`() {
        assertEquals(200f, RulerPhysics.tickX(745, 745f, PX_PER_TENTH, centreXPx = 200f))
    }

    @Test
    fun `higher values are drawn to the right of the marker`() {
        val higher = RulerPhysics.tickX(750, 745f, PX_PER_TENTH, centreXPx = 200f)
        val lower = RulerPhysics.tickX(740, 745f, PX_PER_TENTH, centreXPx = 200f)
        assertEquals(240f, higher)
        assertEquals(160f, lower)
    }

    @Test
    fun `only the tenths that fit on screen are drawn`() {
        val visible = RulerPhysics.visibleTenths(745f, halfWidthPx = 80f, pixelsPerTenth = PX_PER_TENTH)
        assertEquals(735..755, visible)
    }

    @Test
    fun `the ruler is not drawn past its end stops`() {
        assertEquals(
            Weight.MIN_TENTHS..310,
            RulerPhysics.visibleTenths(300f, halfWidthPx = 80f, pixelsPerTenth = PX_PER_TENTH),
        )
        assertEquals(
            2490..Weight.MAX_TENTHS,
            RulerPhysics.visibleTenths(2500f, halfWidthPx = 80f, pixelsPerTenth = PX_PER_TENTH),
        )
    }

    @Test
    fun `an unmeasured ruler draws nothing`() {
        assertTrue(RulerPhysics.visibleTenths(745f, 0f, PX_PER_TENTH).isEmpty())
        assertTrue(RulerPhysics.visibleTenths(745f, 80f, 0f).isEmpty())
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
     * The `−` and `+` controls take their width from the row, so the strip is roughly this
     * narrow on a 411 dp phone. The kilogram either side of the marker is what the eye aims
     * at, and it has to survive the fade at that width.
     */
    @Test
    fun `the kilogram either side of the marker survives the edge fade`() {
        val halfWidthDp = 130f
        assertEquals(1f, RulerPhysics.edgeAlpha(RulerPhysics.DP_PER_KILOGRAM, halfWidthDp))
        assertEquals(1f, RulerPhysics.edgeAlpha(-RulerPhysics.DP_PER_KILOGRAM, halfWidthDp))
    }

    // --- Feedback --------------------------------------------------------------------

    @Test
    fun `a tick fires every half kilogram and never on a tenth`() {
        assertTrue(RulerPhysics.crossesHapticStep(744, 745))
        assertTrue(RulerPhysics.crossesHapticStep(750, 749))
        assertFalse(RulerPhysics.crossesHapticStep(745, 746))
        assertFalse(RulerPhysics.crossesHapticStep(746, 747))
        assertFalse(RulerPhysics.crossesHapticStep(745, 745))
    }

    @Test
    fun `pushing against a stop produces no tick`() {
        val stopped = RulerPhysics.step(Weight.MIN_TENTHS, -1)
        assertFalse(RulerPhysics.crossesHapticStep(Weight.MIN_TENTHS, stopped))
        val topped = RulerPhysics.step(Weight.MAX_TENTHS, 1)
        assertFalse(RulerPhysics.crossesHapticStep(Weight.MAX_TENTHS, topped))
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
