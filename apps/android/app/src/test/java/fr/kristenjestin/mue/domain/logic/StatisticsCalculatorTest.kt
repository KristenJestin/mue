package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.testing.measurementOf
import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatisticsCalculatorTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    @Test
    fun `an empty period leaves every indicator unavailable`() {
        val stats = StatisticsCalculator.compute(emptyList())
        assertEquals(ProgressStatistics.UNAVAILABLE, stats)
        assertNull(stats.currentWeight)
        assertNull(stats.changeKg)
        assertNull(stats.weeklyPaceKg)
        assertFalse(stats.hasData)
    }

    @Test
    fun `a single measurement gives a current weight but no change or pace`() {
        val stats = StatisticsCalculator.compute(listOf(measurementOf("2026-08-20", 74.8)))
        assertEquals(748, stats.currentWeight?.tenthsKg)
        assertNull(stats.changeKg)
        assertNull(stats.weeklyPaceKg)
        assertTrue(stats.hasData)
    }

    @Test
    fun `two measurements on the same date give no change or pace`() {
        val stats = StatisticsCalculator.compute(
            listOf(measurementOf("2026-08-20", 74.8), measurementOf("2026-08-20", 75.2))
        )
        assertNotNull(stats.current)
        assertNull(stats.changeKg)
        assertNull(stats.weeklyPaceKg)
    }

    @Test
    fun `one day apart extrapolates the pace to a full week`() {
        val stats = StatisticsCalculator.compute(
            listOf(measurementOf("2026-08-22", 75.0), measurementOf("2026-08-23", 74.5))
        )
        assertEquals(-0.5, stats.changeKg!!, TOLERANCE)
        assertEquals(-3.5, stats.weeklyPaceKg!!, TOLERANCE)
    }

    @Test
    fun `exactly one week apart makes the pace equal the change`() {
        val stats = StatisticsCalculator.compute(
            listOf(measurementOf("2026-08-16", 75.0), measurementOf("2026-08-23", 74.3))
        )
        assertEquals(-0.7, stats.changeKg!!, TOLERANCE)
        assertEquals(-0.7, stats.weeklyPaceKg!!, TOLERANCE)
    }

    @Test
    fun `a gain over months averages out to a small weekly pace`() {
        // 60 days between the two measurements: +2.0 kg / 60 * 7.
        val stats = StatisticsCalculator.compute(
            listOf(measurementOf("2026-01-01", 78.0), measurementOf("2026-03-02", 80.0))
        )
        assertEquals(2.0, stats.changeKg!!, TOLERANCE)
        assertEquals(2.0 / 60.0 * 7.0, stats.weeklyPaceKg!!, TOLERANCE)
    }

    @Test
    fun `a flat period reports a zero change and a zero pace`() {
        val stats = StatisticsCalculator.compute(
            listOf(measurementOf("2026-08-01", 74.5), measurementOf("2026-08-23", 74.5))
        )
        assertEquals(0.0, stats.changeKg!!, TOLERANCE)
        assertEquals(0.0, stats.weeklyPaceKg!!, TOLERANCE)
    }

    @Test
    fun `input order does not matter`() {
        val unsorted = listOf(
            measurementOf("2026-08-23", 74.5),
            measurementOf("2026-08-01", 75.5),
            measurementOf("2026-08-10", 80.0),
        )
        val stats = StatisticsCalculator.compute(unsorted)
        assertEquals(LocalDate.of(2026, 8, 23), stats.current?.date)
        assertEquals(LocalDate.of(2026, 8, 1), stats.first?.date)
        assertEquals(-1.0, stats.changeKg!!, TOLERANCE)
    }

    @Test
    fun `the current weight is the latest date of the period not the latest write`() {
        val stats = StatisticsCalculator.compute(
            listOf(measurementOf("2026-08-23", 70.0), measurementOf("2026-08-10", 90.0))
        )
        assertEquals(700, stats.currentWeight?.tenthsKg)
    }

    @Test
    fun `measurements outside the window are ignored entirely`() {
        val all = listOf(
            measurementOf("2026-06-01", 90.0),
            measurementOf("2026-08-18", 75.0),
            measurementOf("2026-08-23", 74.5),
        )
        val stats = StatisticsCalculator.compute(all, Period.SEVEN_DAYS.windowEndingOn(today))
        assertEquals(LocalDate.of(2026, 8, 18), stats.first?.date)
        assertEquals(-0.5, stats.changeKg!!, TOLERANCE)
    }

    @Test
    fun `an empty window never falls back to a measurement outside it`() {
        val all = listOf(measurementOf("2026-01-01", 90.0), measurementOf("2026-01-02", 89.0))
        val stats = StatisticsCalculator.compute(all, Period.SEVEN_DAYS.windowEndingOn(today))
        assertEquals(ProgressStatistics.UNAVAILABLE, stats)
    }

    @Test
    fun `a window holding a single measurement keeps the change unavailable`() {
        val all = listOf(measurementOf("2026-06-01", 90.0), measurementOf("2026-08-23", 74.5))
        val stats = StatisticsCalculator.compute(all, Period.SEVEN_DAYS.windowEndingOn(today))
        assertEquals(745, stats.currentWeight?.tenthsKg)
        assertNull(stats.changeKg)
        assertNull(stats.weeklyPaceKg)
    }

    @Test
    fun `the all period keeps every measurement`() {
        val all = listOf(measurementOf("2020-01-01", 90.0), measurementOf("2026-08-23", 74.5))
        val stats = StatisticsCalculator.compute(all, Period.ALL.windowEndingOn(today))
        assertEquals(LocalDate.of(2020, 1, 1), stats.first?.date)
        assertEquals(-15.5, stats.changeKg!!, TOLERANCE)
    }

    @Test
    fun `the window boundaries are inclusive`() {
        val window = DateWindow.of(LocalDate.of(2026, 8, 17), today)
        val all = listOf(
            measurementOf("2026-08-16", 80.0),
            measurementOf("2026-08-17", 76.0),
            measurementOf("2026-08-23", 75.0),
            measurementOf("2026-08-24", 60.0),
        )
        val stats = StatisticsCalculator.compute(all, window)
        assertEquals(LocalDate.of(2026, 8, 17), stats.first?.date)
        assertEquals(LocalDate.of(2026, 8, 23), stats.current?.date)
        assertEquals(-1.0, stats.changeKg!!, TOLERANCE)
    }

    @Test
    fun `the change is exact because it is computed from stored tenths`() {
        val stats = StatisticsCalculator.compute(
            listOf(measurementOf("2026-08-01", 74.4), measurementOf("2026-08-08", 74.1))
        )
        assertEquals(-0.3, stats.changeKg!!, 1e-12)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
