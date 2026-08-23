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

    private val weight = requireNotNull(Weight.ofKilogramsOrNull(74.05))

    @Test
    fun `a weight shows two decimals in the phone's language`() {
        assertEquals("74.05", ProgressFormat.weight(weight, EN))
        assertEquals("74,05", ProgressFormat.weight(weight, FR))
    }

    @Test
    fun `a round weight still shows both decimals`() {
        assertEquals("74.00", ProgressFormat.weight(Weight.ofHundredthsClamped(7_400), EN))
        assertEquals("74.50", ProgressFormat.weight(Weight.ofHundredthsClamped(7_450), EN))
        assertEquals("250.00", ProgressFormat.weight(Weight.ofHundredthsClamped(25_000), EN))
    }

    @Test
    fun `a missing value shows the unavailable dash`() {
        assertEquals("—", ProgressFormat.weight(null, EN))
        assertEquals("—", ProgressFormat.bmi(null, EN))
        assertEquals("—", ProgressFormat.signedWeight(null, EN))
        assertEquals("—", ProgressFormat.signedPace(null, EN))
        assertEquals("—", ProgressFormat.signedKilograms(null, EN))
    }

    /** PRD FR-BMI-001: the BMI is derived, so it keeps its single decimal. */
    @Test
    fun `a bmi shows one decimal`() {
        assertEquals("23.0", ProgressFormat.bmi(23.0, EN))
        assertEquals("23,0", ProgressFormat.bmi(23.04, FR))
    }

    /** PRD FR-PROGRESS-003: the change is a weight difference, so two decimals. */
    @Test
    fun `the period change carries two decimals and its sign`() {
        assertEquals("+0.20", ProgressFormat.signedWeight(0.2, EN))
        assertEquals("−0.35", ProgressFormat.signedWeight(-0.35, EN))
        assertEquals("−1,15", ProgressFormat.signedWeight(-1.15, FR))
    }

    /** PRD FR-PROGRESS-003: the pace is derived, so one decimal. */
    @Test
    fun `the weekly pace carries one decimal and its sign`() {
        assertEquals("+0.2", ProgressFormat.signedPace(0.2, EN))
        assertEquals("−0.3", ProgressFormat.signedPace(-0.3, EN))
        assertEquals("−1,1", ProgressFormat.signedPace(-1.1, FR))
    }

    @Test
    fun `a value rounding to zero is never a negative zero`() {
        assertEquals("+0.00", ProgressFormat.signedWeight(0.0, EN))
        assertEquals("+0.00", ProgressFormat.signedWeight(-0.004, EN))
        assertEquals("−0.01", ProgressFormat.signedWeight(-0.006, EN))
        assertEquals("+0.0", ProgressFormat.signedPace(-0.04, EN))
        assertEquals("−0.1", ProgressFormat.signedPace(-0.06, EN))
    }

    @Test
    fun `a non finite pace is unavailable rather than infinity`() {
        assertEquals("—", ProgressFormat.signedPace(Double.NaN, EN))
        assertEquals("—", ProgressFormat.signedPace(Double.POSITIVE_INFINITY, EN))
        assertEquals("—", ProgressFormat.signedWeight(Double.NaN, EN))
    }

    @Test
    fun `the chart badge carries the unit`() {
        assertEquals("−1.15 kg", ProgressFormat.signedKilograms(-1.15, EN))
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
