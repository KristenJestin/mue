package fr.kristenjestin.mue.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightTest {

    @Test
    fun `accepts the lower bound of 30 kg`() {
        assertEquals(300, Weight.ofKilogramsOrNull(30.0)?.tenthsKg)
    }

    @Test
    fun `accepts the upper bound of 250 kg`() {
        assertEquals(2500, Weight.ofKilogramsOrNull(250.0)?.tenthsKg)
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
    fun `rounds before checking the range so 29 point 95 becomes a valid 30 kg`() {
        assertEquals(300, Weight.ofKilogramsOrNull(29.95)?.tenthsKg)
    }

    @Test
    fun `rounds 74 point 44 down to 74 point 4`() {
        assertEquals(744, Weight.ofKilogramsOrNull(74.44)?.tenthsKg)
    }

    @Test
    fun `rounds 74 point 45 up to 74 point 5`() {
        assertEquals(745, Weight.ofKilogramsOrNull(74.45)?.tenthsKg)
    }

    @Test
    fun `rejects non finite input`() {
        assertNull(Weight.ofKilogramsOrNull(Double.NaN))
        assertNull(Weight.ofKilogramsOrNull(Double.POSITIVE_INFINITY))
        assertNull(Weight.ofKilogramsOrNull(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `rejects tenths outside the range`() {
        assertNull(Weight.ofTenthsOrNull(299))
        assertNull(Weight.ofTenthsOrNull(2501))
        assertNotNull(Weight.ofTenthsOrNull(Weight.MIN_TENTHS))
        assertNotNull(Weight.ofTenthsOrNull(Weight.MAX_TENTHS))
    }

    @Test
    fun `clamping snaps to the nearest bound instead of failing`() {
        assertEquals(Weight.MIN_TENTHS, Weight.ofTenthsClamped(0).tenthsKg)
        assertEquals(Weight.MAX_TENTHS, Weight.ofTenthsClamped(9_999).tenthsKg)
        assertEquals(745, Weight.ofTenthsClamped(745).tenthsKg)
    }

    @Test
    fun `converts tenths to kilograms without drift`() {
        assertEquals(74.5, Weight.ofTenthsClamped(745).kilograms, 0.0)
        assertEquals(30.0, Weight.ofTenthsClamped(300).kilograms, 0.0)
        assertEquals(250.0, Weight.ofTenthsClamped(2500).kilograms, 0.0)
    }

    @Test
    fun `default weight is the 70 kg the entry screen starts from`() {
        assertEquals(700, Weight.DEFAULT.tenthsKg)
    }

    @Test
    fun `subtraction yields whole tenths`() {
        val heavier = Weight.ofTenthsClamped(750)
        val lighter = Weight.ofTenthsClamped(745)
        assertEquals(5, heavier - lighter)
        assertEquals(-5, lighter - heavier)
        assertEquals(0, heavier - heavier)
    }

    @Test
    fun `orders by tenths`() {
        val lighter = Weight.ofTenthsClamped(700)
        val heavier = Weight.ofTenthsClamped(701)
        assertTrue(lighter < heavier)
        assertEquals(lighter, Weight.ofTenthsClamped(700))
    }
}
