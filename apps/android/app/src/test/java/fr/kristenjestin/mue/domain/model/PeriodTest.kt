package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeriodTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    @Test
    fun `seven days spans today and the six days before it`() {
        val window = Period.SEVEN_DAYS.windowEndingOn(today)
        assertEquals(LocalDate.of(2026, 8, 17), window.start)
        assertEquals(today, window.endInclusive)
        assertEquals(7, dayCount(window))
    }

    @Test
    fun `thirty days spans thirty calendar days ending today`() {
        val window = Period.THIRTY_DAYS.windowEndingOn(today)
        assertEquals(LocalDate.of(2026, 7, 25), window.start)
        assertEquals(today, window.endInclusive)
        assertEquals(30, dayCount(window))
    }

    @Test
    fun `three months starts the day after the same day three months earlier`() {
        val window = Period.THREE_MONTHS.windowEndingOn(today)
        assertEquals(LocalDate.of(2026, 5, 24), window.start)
        assertEquals(today, window.endInclusive)
    }

    @Test
    fun `three months clamps to a shorter month without overflowing`() {
        val endOfMay = LocalDate.of(2026, 5, 31)
        val window = Period.THREE_MONTHS.windowEndingOn(endOfMay)
        assertEquals(LocalDate.of(2026, 3, 1), window.start)
        assertEquals(endOfMay, window.endInclusive)
    }

    @Test
    fun `all is unbounded on both sides`() {
        val window = Period.ALL.windowEndingOn(today)
        assertNull(window.start)
        assertNull(window.endInclusive)
        assertTrue(LocalDate.of(1900, 1, 1) in window)
        assertTrue(LocalDate.of(2999, 12, 31) in window)
    }

    @Test
    fun `window bounds are inclusive`() {
        val window = Period.SEVEN_DAYS.windowEndingOn(today)
        assertTrue(LocalDate.of(2026, 8, 17) in window)
        assertTrue(today in window)
        assertFalse(LocalDate.of(2026, 8, 16) in window)
        assertFalse(today.plusDays(1) in window)
    }

    @Test
    fun `a half open window only constrains the side it declares`() {
        val fromOnly = DateWindow(LocalDate.of(2026, 1, 1), null)
        assertTrue(LocalDate.of(2026, 1, 1) in fromOnly)
        assertTrue(LocalDate.of(2999, 1, 1) in fromOnly)
        assertFalse(LocalDate.of(2025, 12, 31) in fromOnly)

        val untilOnly = DateWindow(null, LocalDate.of(2026, 1, 1))
        assertTrue(LocalDate.of(1900, 1, 1) in untilOnly)
        assertTrue(LocalDate.of(2026, 1, 1) in untilOnly)
        assertFalse(LocalDate.of(2026, 1, 2) in untilOnly)
    }

    @Test
    fun `every period ends on today except all`() {
        Period.entries.filter { it != Period.ALL }.forEach { period ->
            assertEquals(today, period.windowEndingOn(today).endInclusive, period.name)
        }
    }

    private fun dayCount(window: DateWindow): Long =
        ChronoUnit.DAYS.between(window.start, window.endInclusive) + 1
}
