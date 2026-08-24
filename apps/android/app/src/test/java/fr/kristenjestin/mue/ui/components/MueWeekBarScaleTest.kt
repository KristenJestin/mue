package fr.kristenjestin.mue.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPSILON = 1e-4f

/**
 * FR-ACTIVITY-001's bar scale: relative to the week on screen, with a floor under any day that
 * actually happened and nothing at all under a day that did not.
 */
class MueWeekBarScaleTest {

    /** 3 dp of a 96 dp plot area — what the composable passes. */
    private val minFraction = 3f / 96f

    private fun week(vararg minutes: Long) = minutes.map { it * 60 }

    @Test
    fun `the longest day of the week fills the plot`() {
        val fractions = MueWeekBarScale.fractionsOf(week(32, 68, 18, 88, 12, 54, 8), minFraction)

        assertEquals(1f, fractions[3], EPSILON)
        assertEquals(1f, fractions.max(), EPSILON)
    }

    @Test
    fun `the other days follow in proportion to it`() {
        val fractions = MueWeekBarScale.fractionsOf(week(30, 60, 90), minFraction)

        assertEquals(1f / 3f, fractions[0], EPSILON)
        assertEquals(2f / 3f, fractions[1], EPSILON)
        assertEquals(1f, fractions[2], EPSILON)
    }

    /** The scale is per week, so the same day reads differently beside a heavier one. */
    @Test
    fun `the scale is relative to the week shown, not to a fixed ceiling`() {
        val quiet = MueWeekBarScale.fractionsOf(week(20, 40), minFraction)
        val busy = MueWeekBarScale.fractionsOf(week(20, 240), minFraction)

        assertEquals(1f, quiet[1], EPSILON)
        assertTrue(busy[0] < quiet[0], "20 minutes must read shorter in a heavier week")
    }

    @Test
    fun `a day with no activity keeps its empty rail`() {
        val fractions = MueWeekBarScale.fractionsOf(week(45, 0, 30), minFraction)

        assertEquals(0f, fractions[1])
    }

    /** PRD FR-ACTIVITY-001: a short session next to a long one must still be visible. */
    @Test
    fun `a very short day is lifted to the visible floor`() {
        val fractions = MueWeekBarScale.fractionsOf(week(1, 600), minFraction)

        assertEquals(minFraction, fractions[0], EPSILON)
        assertTrue(fractions[0] > 0f)
    }

    @Test
    fun `an empty week is seven rails and no division by zero`() {
        val fractions = MueWeekBarScale.fractionsOf(List(MueWeekBarScale.DAYS) { 0L }, minFraction)

        assertEquals(MueWeekBarScale.DAYS, fractions.size)
        assertTrue(fractions.all { it == 0f })
    }

    @Test
    fun `the order of the days is never rearranged`() {
        val values = week(10, 90, 45, 0, 5, 120, 60)
        val fractions = MueWeekBarScale.fractionsOf(values, minFraction)

        values.indices.forEach { a ->
            values.indices.forEach { b ->
                if (values[a] > values[b]) {
                    assertTrue(fractions[a] >= fractions[b], "day $a vs day $b")
                }
            }
        }
    }

    @Test
    fun `no bar ever leaves the plot area`() {
        val fractions = MueWeekBarScale.fractionsOf(week(0, 1, 5, 60, 3599), minFraction)

        assertTrue(fractions.all { it in 0f..1f }, fractions.toString())
    }

    /** A plot area shorter than the floor cannot ask for a bar taller than itself. */
    @Test
    fun `an absurd floor is clamped rather than overflowing`() {
        val fractions = MueWeekBarScale.fractionsOf(week(1, 600), minFraction = 4f)

        assertTrue(fractions.all { it <= 1f }, fractions.toString())
    }
}
