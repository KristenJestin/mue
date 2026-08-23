package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.model.Weight
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val EN = Locale.US
private val FR = Locale.FRANCE

class ProgressFormatTest {

    private val weight = requireNotNull(Weight.ofKilogramsOrNull(74.5))

    @Test
    fun `a weight shows one decimal in the phone's language`() {
        assertEquals("74.5", ProgressFormat.weight(weight, EN))
        assertEquals("74,5", ProgressFormat.weight(weight, FR))
    }

    @Test
    fun `a missing value shows the unavailable dash`() {
        assertEquals("—", ProgressFormat.weight(null, EN))
        assertEquals("—", ProgressFormat.bmi(null, EN))
        assertEquals("—", ProgressFormat.signed(null, EN))
        assertEquals("—", ProgressFormat.signedKilograms(null, EN))
    }

    @Test
    fun `a bmi shows one decimal`() {
        assertEquals("23.0", ProgressFormat.bmi(23.0, EN))
        assertEquals("23,0", ProgressFormat.bmi(23.04, FR))
    }

    @Test
    fun `change and pace always carry their sign`() {
        assertEquals("+0.2", ProgressFormat.signed(0.2, EN))
        assertEquals("−0.3", ProgressFormat.signed(-0.3, EN))
        assertEquals("−1,1", ProgressFormat.signed(-1.1, FR))
    }

    @Test
    fun `a value rounding to zero is never a negative zero`() {
        assertEquals("+0.0", ProgressFormat.signed(0.0, EN))
        assertEquals("+0.0", ProgressFormat.signed(-0.04, EN))
        assertEquals("−0.1", ProgressFormat.signed(-0.06, EN))
    }

    @Test
    fun `a non finite pace is unavailable rather than infinity`() {
        assertEquals("—", ProgressFormat.signed(Double.NaN, EN))
        assertEquals("—", ProgressFormat.signed(Double.POSITIVE_INFINITY, EN))
    }

    @Test
    fun `the chart badge carries the unit`() {
        assertEquals("−1.1 kg", ProgressFormat.signedKilograms(-1.1, EN))
    }

    @Test
    fun `dates follow the phone's language`() {
        val date = LocalDate.of(2026, 8, 18)

        val english = ProgressFormat.date(date, EN)
        assertTrue(english.contains("18") && english.contains("2026"), english)
        assertTrue(english.contains("Aug"), english)

        val french = ProgressFormat.date(date, FR)
        assertTrue(french.contains("18") && french.contains("2026"), french)
        assertTrue(french.lowercase().contains("août"), french)
    }

    @Test
    fun `the current day reads Today`() {
        val today = LocalDate.of(2026, 8, 23)

        assertEquals("Today", ProgressFormat.dateOrToday(today, today, EN))
        assertEquals(
            ProgressFormat.date(today.minusDays(1), EN),
            ProgressFormat.dateOrToday(today.minusDays(1), today, EN),
        )
    }
}
