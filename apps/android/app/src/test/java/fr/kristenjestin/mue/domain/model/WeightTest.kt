package fr.kristenjestin.mue.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightTest {

    @Test
    fun `accepts the lower bound of 30 kg`() {
        assertEquals(3_000, Weight.ofKilogramsOrNull(30.0)?.hundredthsKg)
    }

    @Test
    fun `accepts the upper bound of 250 kg`() {
        assertEquals(25_000, Weight.ofKilogramsOrNull(250.0)?.hundredthsKg)
    }

    @Test
    fun `rejects just below the lower bound`() {
        assertNull(Weight.ofKilogramsOrNull(29.9))
    }

    @Test
    fun `rejects just above the upper bound`() {
        assertNull(Weight.ofKilogramsOrNull(250.1))
    }

    @Test
    fun `rounds before checking the range so 29 point 99 becomes a valid 30 kg`() {
        assertEquals(3_000, Weight.ofKilogramsOrNull(29.99)?.hundredthsKg)
    }

    @Test
    fun `rounds to the nearest twentieth of a kilogram`() {
        assertEquals(7_405, Weight.ofKilogramsOrNull(74.05)?.hundredthsKg)
        assertEquals(7_405, Weight.ofKilogramsOrNull(74.04)?.hundredthsKg)
        assertEquals(7_405, Weight.ofKilogramsOrNull(74.06)?.hundredthsKg)
        assertEquals(7_400, Weight.ofKilogramsOrNull(74.02)?.hundredthsKg)
        assertEquals(7_410, Weight.ofKilogramsOrNull(74.08)?.hundredthsKg)
    }

    /** PRD FR-ENTRY-004 accepts two decimals in; the value still lands on the 0.05 grid. */
    @Test
    fun `every accepted value is a whole number of steps`() {
        val samples = listOf(30.0, 74.03, 74.07, 99.99, 123.46, 250.0)
        samples.forEach { kilograms ->
            val weight = requireNotNull(Weight.ofKilogramsOrNull(kilograms)) { "$kilograms" }
            assertEquals(0, weight.hundredthsKg % Weight.STEP_HUNDREDTHS, "$kilograms")
        }
    }

    @Test
    fun `rejects non finite input`() {
        assertNull(Weight.ofKilogramsOrNull(Double.NaN))
        assertNull(Weight.ofKilogramsOrNull(Double.POSITIVE_INFINITY))
        assertNull(Weight.ofKilogramsOrNull(Double.NEGATIVE_INFINITY))
    }

    /** A text field can hand over anything; no magnitude may overflow its way into the range. */
    @Test
    fun `rejects absurd magnitudes without wrapping around`() {
        assertNull(Weight.ofKilogramsOrNull(1e30))
        assertNull(Weight.ofKilogramsOrNull(-1e30))
        assertNull(Weight.ofKilogramsOrNull(Double.MAX_VALUE))
    }

    @Test
    fun `rejects hundredths outside the range`() {
        assertNull(Weight.ofHundredthsOrNull(2_999))
        assertNull(Weight.ofHundredthsOrNull(25_001))
        assertNotNull(Weight.ofHundredthsOrNull(Weight.MIN_HUNDREDTHS))
        assertNotNull(Weight.ofHundredthsOrNull(Weight.MAX_HUNDREDTHS))
    }

    @Test
    fun `clamping snaps to the nearest bound instead of failing`() {
        assertEquals(Weight.MIN_HUNDREDTHS, Weight.ofHundredthsClamped(0).hundredthsKg)
        assertEquals(Weight.MAX_HUNDREDTHS, Weight.ofHundredthsClamped(99_999).hundredthsKg)
        assertEquals(7_405, Weight.ofHundredthsClamped(7_405).hundredthsKg)
    }

    @Test
    fun `converts hundredths to kilograms without drift`() {
        assertEquals(74.05, Weight.ofHundredthsClamped(7_405).kilograms, 0.0)
        assertEquals(30.0, Weight.ofHundredthsClamped(3_000).kilograms, 0.0)
        assertEquals(250.0, Weight.ofHundredthsClamped(25_000).kilograms, 0.0)
    }

    @Test
    fun `default weight is the 70 kg the entry screen starts from`() {
        assertEquals(7_000, Weight.DEFAULT.hundredthsKg)
        assertEquals(70.0, Weight.DEFAULT.kilograms, 0.0)
    }

    @Test
    fun `the step is a twentieth of a kilogram`() {
        assertEquals(5, Weight.STEP_HUNDREDTHS)
        assertEquals(0, Weight.MIN_HUNDREDTHS % Weight.STEP_HUNDREDTHS)
        assertEquals(0, Weight.MAX_HUNDREDTHS % Weight.STEP_HUNDREDTHS)
    }

    @Test
    fun `subtraction yields whole hundredths`() {
        val heavier = Weight.ofHundredthsClamped(7_500)
        val lighter = Weight.ofHundredthsClamped(7_405)
        assertEquals(95, heavier - lighter)
        assertEquals(-95, lighter - heavier)
        assertEquals(0, heavier - heavier)
    }

    @Test
    fun `orders by hundredths`() {
        val lighter = Weight.ofHundredthsClamped(7_000)
        val heavier = Weight.ofHundredthsClamped(7_005)
        assertTrue(lighter < heavier)
        assertEquals(lighter, Weight.ofHundredthsClamped(7_000))
    }
}
