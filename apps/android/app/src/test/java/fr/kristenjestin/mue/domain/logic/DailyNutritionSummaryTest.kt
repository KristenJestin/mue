package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY_TRENDS: LocalDate = LocalDate.parse("2026-08-19")

/** PRD_FOOD 10.1 and 13.1: what one day of the journal is worth. */
class DailyNutritionSummaryTest {

    private val day = listOf(
        logEntryOf(at = "08:10", slot = MealSlot.BREAKFAST, nutrients = per100(energy = 300.0, protein = 20.0), id = "b1"),
        logEntryOf(at = "13:00", slot = MealSlot.LUNCH, nutrients = per100(energy = 700.0, protein = 30.0), id = "l1"),
        logEntryOf(at = "13:20", slot = MealSlot.LUNCH, nutrients = per100(energy = 100.0, protein = 5.0), id = "l2"),
    )

    @Test
    fun `a moment shows its own total, which is a local addition`() {
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, day)
        assertEquals(800_000, summary.totalIn(MealSlot.LUNCH)?.energy?.milliKcal)
        assertEquals(300_000, summary.totalIn(MealSlot.BREAKFAST)?.energy?.milliKcal)
    }

    @Test
    fun `PRD_FOOD 10_1 - a moment with no line has no total to show`() {
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, day)
        assertNull(summary.totalIn(MealSlot.SNACK))
        assertNull(summary.slotTotalIn(MealSlot.DINNER))
    }

    @Test
    fun `only the moments that carry a line appear, in the moments' own order`() {
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, day)
        assertEquals(listOf(MealSlot.BREAKFAST, MealSlot.LUNCH), summary.slots.map { it.slot })
    }

    @Test
    fun `a moment counts its own lines`() {
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, day)
        assertEquals(2, summary.slotTotalIn(MealSlot.LUNCH)?.entryCount)
        assertEquals(1, summary.slotTotalIn(MealSlot.BREAKFAST)?.entryCount)
    }

    @Test
    fun `the day total is the strict sum of every line`() {
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, day)
        assertEquals(1_100_000, summary.total.energy?.milliKcal)
        assertEquals(55_000, summary.total.protein?.milligrams)
        assertEquals(3, summary.entryCount)
    }

    @Test
    fun `only the lines of the day take part`() {
        val across = day + logEntryOf(isoDate = "2026-08-18", nutrients = per100(energy = 999.0), id = "other-day")
        assertEquals(1_100_000, DailyNutritionSummary.of(TODAY_TRENDS, across).total.energy?.milliKcal)
    }

    @Test
    fun `PRD_FOOD 10_4 - a day nobody wrote on is not recorded`() {
        val empty = DailyNutritionSummary.empty(TODAY_TRENDS)
        assertFalse(empty.isRecorded)
        assertEquals(0, empty.entryCount)
        assertTrue(empty.slots.isEmpty())
    }

    @Test
    fun `an empty day sums to a known zero, which nothing is allowed to show as a total`() {
        val empty = DailyNutritionSummary.empty(TODAY_TRENDS)
        assertEquals(Nutrients.ZERO, empty.total)
        assertNull(empty.recordedEnergy)
        assertFalse(empty.countsTowardsEnergyAverage)
    }

    @Test
    fun `PRD_FOOD 22 - an unknown energy makes the day's energy unknown`() {
        val withUnknown = day + logEntryOf(at = "16:00", nutrients = per100(protein = 5.0), id = "mystery")
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, withUnknown)
        assertNull(summary.total.energy)
    }

    @Test
    fun `PRD_FOOD 22 - it does not make the day's other metrics unknown`() {
        val withUnknown = day + logEntryOf(at = "16:00", nutrients = per100(protein = 5.0), id = "mystery")
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, withUnknown)
        assertEquals(60_000, summary.total.protein?.milligrams)
    }

    @Test
    fun `a day recorded with an unknown energy is recorded all the same`() {
        val summary = DailyNutritionSummary.of(
            TODAY_TRENDS,
            listOf(logEntryOf(nutrients = Nutrients.UNKNOWN, id = "mystery")),
        )
        assertTrue(summary.isRecorded)
        assertNull(summary.recordedEnergy)
        assertFalse(summary.countsTowardsEnergyAverage)
    }

    @Test
    fun `a recorded day of known zeroes is not the same as a day nobody wrote on`() {
        val zeroDay = DailyNutritionSummary.of(
            TODAY_TRENDS,
            listOf(logEntryOf(title = "Black coffee", nutrients = Nutrients.ZERO, id = "coffee")),
        )
        val nothing = DailyNutritionSummary.empty(TODAY_TRENDS)
        assertEquals(nothing.total, zeroDay.total)
        assertTrue(zeroDay.isRecorded)
        assertFalse(nothing.isRecorded)
        assertEquals(Energy.ZERO, zeroDay.recordedEnergy)
        assertNull(nothing.recordedEnergy)
    }

    @Test
    fun `an unknown line in one moment leaves the other moments alone`() {
        val withUnknown = day + logEntryOf(at = "20:00", slot = MealSlot.DINNER, nutrients = Nutrients.UNKNOWN, id = "d1")
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, withUnknown)
        assertEquals(800_000, summary.totalIn(MealSlot.LUNCH)?.energy?.milliKcal)
        assertNull(summary.totalIn(MealSlot.DINNER)?.energy)
        assertNull(summary.total.energy)
    }

    @Test
    fun `the summary keeps the date it was asked for`() {
        assertEquals(TODAY_TRENDS, DailyNutritionSummary.of(TODAY_TRENDS, day).date)
    }

    @Test
    fun `a proposal is not a line and never enters the day total`() {
        val summary = DailyNutritionSummary.of(TODAY_TRENDS, emptyList())
        assertFalse(summary.isRecorded)
        assertEquals(1, MealSlotRules.pendingPlans(listOf(planOf(isoDate = "2026-08-19"))).size)
    }
}

/** PRD_FOOD 10.5 and 22: seven days of what was recorded, and nothing invented. */
class NutritionTrendTest {

    private val entries = listOf(
        logEntryOf(isoDate = "2026-08-15", at = "13:00", nutrients = per100(energy = 200.0, protein = 10.0), id = "t1"),
        logEntryOf(isoDate = "2026-08-17", at = "13:00", nutrients = per100(protein = 10.0), id = "t2"),
        logEntryOf(isoDate = "2026-08-19", at = "13:00", nutrients = per100(energy = 400.0, protein = 20.0), id = "t3"),
    )

    private val trend = NutritionTrend.of(entries, TODAY_TRENDS)

    @Test
    fun `a trend is seven days, today included`() {
        assertEquals(7, trend.days.size)
        assertEquals(NutritionTrend.DAYS, trend.days.size)
    }

    @Test
    fun `the days run oldest first and end on today`() {
        assertEquals(LocalDate.parse("2026-08-13"), trend.days.first().date)
        assertEquals(TODAY_TRENDS, trend.days.last().date)
    }

    @Test
    fun `the window is the seven days the trend covers`() {
        assertEquals(DateWindow.of(LocalDate.parse("2026-08-13"), TODAY_TRENDS), trend.window)
    }

    @Test
    fun `three days were recorded, the unknown one among them`() {
        assertEquals(3, trend.daysRecorded)
        assertTrue(trend.hasAnyRecord)
    }

    @Test
    fun `the day whose energy is unknown is recorded but does not count towards the mean`() {
        val unknownDay = assertNotNull(trend.dayOn(LocalDate.parse("2026-08-17")))
        assertTrue(unknownDay.isRecorded)
        assertNull(unknownDay.recordedEnergy)
        assertFalse(unknownDay.countsTowardsEnergyAverage)
    }

    @Test
    fun `the mean is taken over the two days whose energy is known`() {
        assertEquals(300_000, trend.averageEnergy?.milliKcal)
    }

    @Test
    fun `a mean over the three recorded days would be a different figure, and a wrong one`() {
        assertNotEquals(200_000, trend.averageEnergy?.milliKcal)
    }

    @Test
    fun `PRD_FOOD 22 - the unknown day draws no bar`() {
        assertEquals(0.0, trend.barFraction(4))
    }

    @Test
    fun `a day nobody wrote on draws no bar either`() {
        assertEquals(0.0, trend.barFraction(0))
        assertEquals(0.0, trend.barFraction(1))
    }

    @Test
    fun `the bars are drawn against the tallest recorded day`() {
        assertEquals(400_000, trend.highestEnergy?.milliKcal)
        assertEquals(0.5, trend.barFraction(2))
        assertEquals(1.0, trend.barFraction(6))
    }

    @Test
    fun `a bar outside the window is no bar at all`() {
        assertEquals(NutritionTrend.NO_BAR, trend.barFraction(-1))
        assertEquals(NutritionTrend.NO_BAR, trend.barFraction(7))
    }

    @Test
    fun `the trend counts the lines it was given`() {
        assertEquals(3, trend.entryCount)
    }

    @Test
    fun `the history is clickable because every day is there, recorded or not`() {
        assertNotNull(trend.dayAt(0))
        assertNotNull(trend.dayOn(LocalDate.parse("2026-08-14")))
        assertFalse(assertNotNull(trend.dayOn(LocalDate.parse("2026-08-14"))).isRecorded)
        assertNull(trend.dayOn(LocalDate.parse("2026-08-12")))
    }

    @Test
    fun `a week nobody wrote in has no mean at all, rather than a mean of zero`() {
        val empty = NutritionTrend.empty(TODAY_TRENDS)
        assertEquals(0, empty.daysRecorded)
        assertFalse(empty.hasAnyRecord)
        assertNull(empty.averageEnergy)
        assertNull(empty.highestEnergy)
        assertEquals(0, empty.entryCount)
    }

    @Test
    fun `an empty week draws seven bars of no height`() {
        val empty = NutritionTrend.empty(TODAY_TRENDS)
        assertEquals(7, empty.days.size)
        assertTrue(empty.days.indices.all { empty.barFraction(it) == NutritionTrend.NO_BAR })
    }

    @Test
    fun `a week whose only recorded day is a known zero draws no bar and means zero`() {
        val zeroOnly = NutritionTrend.of(
            listOf(logEntryOf(isoDate = "2026-08-19", nutrients = Nutrients.ZERO, id = "z")),
            TODAY_TRENDS,
        )
        assertEquals(1, zeroOnly.daysRecorded)
        assertEquals(Energy.ZERO, zeroOnly.averageEnergy)
        assertEquals(NutritionTrend.NO_BAR, zeroOnly.barFraction(6))
    }

    @Test
    fun `a week where only unknown energies were recorded has no mean`() {
        val unknownOnly = NutritionTrend.of(
            listOf(logEntryOf(isoDate = "2026-08-18", nutrients = Nutrients.UNKNOWN, id = "u")),
            TODAY_TRENDS,
        )
        assertEquals(1, unknownOnly.daysRecorded)
        assertNull(unknownOnly.averageEnergy)
        assertNull(unknownOnly.highestEnergy)
    }

    @Test
    fun `a lone day of a wider window is still one day`() {
        val single = NutritionTrend.of(entries, TODAY_TRENDS, TODAY_TRENDS)
        assertEquals(1, single.days.size)
        assertEquals(400_000, single.averageEnergy?.milliKcal)
    }

    @Test
    fun `a window that ends before it starts covers no day at all`() {
        val reversed = NutritionTrend.of(entries, TODAY_TRENDS, TODAY_TRENDS.minusDays(1))
        assertTrue(reversed.days.isEmpty())
        assertEquals(0, reversed.daysRecorded)
        assertNull(reversed.averageEnergy)
    }

    @Test
    fun `a line outside the window never reaches a day of it`() {
        val outside = entries + logEntryOf(isoDate = "2026-07-01", nutrients = per100(energy = 5_000.0), id = "old")
        val same = NutritionTrend.of(outside, TODAY_TRENDS)
        assertEquals(3, same.entryCount)
        assertEquals(300_000, same.averageEnergy?.milliKcal)
    }

    @Test
    fun `Trends carries no other series than what was eaten`() {
        val day = assertNotNull(trend.dayOn(LocalDate.parse("2026-08-15")))
        assertEquals(10_000, day.total.protein?.milligrams)
        assertEquals(200_000, day.total.energy?.milliKcal)
    }
}
