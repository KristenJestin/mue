package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.minutesOf
import fr.kristenjestin.mue.domain.model.summaryOf
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeeklyActivitySummaryTest {

    /** A Tuesday. */
    private val today: LocalDate = LocalDate.of(2026, 8, 25)

    @Test
    fun `the week runs from Monday to Sunday`() {
        val week = WeeklyActivitySummary.weekOf(today)
        assertEquals(LocalDate.of(2026, 8, 24), week.start)
        assertEquals(LocalDate.of(2026, 8, 30), week.endInclusive)
    }

    @Test
    fun `a Monday and a Sunday belong to the week they open and close`() {
        assertEquals(
            LocalDate.of(2026, 8, 24),
            WeeklyActivitySummary.weekOf(LocalDate.of(2026, 8, 24)).start,
        )
        assertEquals(
            LocalDate.of(2026, 8, 24),
            WeeklyActivitySummary.weekOf(LocalDate.of(2026, 8, 30)).start,
        )
        assertEquals(
            LocalDate.of(2026, 8, 31),
            WeeklyActivitySummary.weekOf(LocalDate.of(2026, 8, 31)).start,
        )
    }

    @Test
    fun `the week never depends on the phone's region`() {
        val previous = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.FRANCE, Locale.forLanguageTag("ar-EG")).forEach { locale ->
                Locale.setDefault(locale)
                assertEquals(
                    LocalDate.of(2026, 8, 24),
                    WeeklyActivitySummary.weekOf(today).start,
                    "week start under $locale",
                )
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a week straddling a month boundary keeps both of its halves`() {
        // Monday 31 August to Sunday 6 September 2026.
        val summaries = listOf(
            summaryOf("2026-08-31", minutes = 30),
            summaryOf("2026-09-06", minutes = 45),
            summaryOf("2026-09-07", minutes = 60),
        )
        val week = WeeklyActivitySummary.of(summaries, LocalDate.of(2026, 9, 2))
        assertEquals(LocalDate.of(2026, 8, 31), week.week.start)
        assertEquals(LocalDate.of(2026, 9, 6), week.week.endInclusive)
        assertEquals(2, week.sessionCount)
        assertEquals(minutesOf(75), week.totalDuration)
        assertEquals(minutesOf(30), week.durationOn(DayOfWeek.MONDAY))
        assertEquals(minutesOf(45), week.durationOn(DayOfWeek.SUNDAY))
    }

    @Test
    fun `sessions outside the week are not counted`() {
        val summaries = listOf(
            summaryOf("2026-08-23", minutes = 90),
            summaryOf("2026-08-24", minutes = 45),
            summaryOf("2026-08-31", minutes = 90),
        )
        val week = WeeklyActivitySummary.of(summaries, today)
        assertEquals(1, week.sessionCount)
        assertEquals(minutesOf(45), week.totalDuration)
    }

    @Test
    fun `several sessions on one day add up on that day`() {
        val summaries = listOf(
            summaryOf("2026-08-25", minutes = 45, id = "a"),
            summaryOf("2026-08-25", minutes = 30, id = "b"),
            summaryOf("2026-08-27", minutes = 60),
        )
        val week = WeeklyActivitySummary.of(summaries, today)
        assertEquals(3, week.sessionCount)
        assertEquals(minutesOf(135), week.totalDuration)
        assertEquals(minutesOf(75), week.durationOn(DayOfWeek.TUESDAY))
        assertEquals(minutesOf(60), week.durationOn(DayOfWeek.THURSDAY))
        assertEquals(ActivityDuration.ZERO, week.durationOn(DayOfWeek.MONDAY))
    }

    @Test
    fun `the seven days are always there, Monday first`() {
        val week = WeeklyActivitySummary.of(listOf(summaryOf("2026-08-30", minutes = 20)), today)
        assertEquals(7, week.dailyDurations.size)
        assertEquals(ActivityDuration.ZERO, week.dailyDurations.first())
        assertEquals(minutesOf(20), week.dailyDurations.last())
    }

    @Test
    fun `energy is added up only from the sessions that carry it`() {
        val summaries = listOf(
            summaryOf("2026-08-25", minutes = 45, energyKcal = 280),
            summaryOf("2026-08-26", minutes = 30, energyKcal = null),
            summaryOf("2026-08-27", minutes = 55, energyKcal = 320),
        )
        val week = WeeklyActivitySummary.of(summaries, today)
        assertEquals(600, week.energyKcal)
        assertEquals(3, week.sessionCount)
    }

    @Test
    fun `a week without a single estimation shows no energy rather than a zero`() {
        val week = WeeklyActivitySummary.of(listOf(summaryOf("2026-08-25", minutes = 45)), today)
        assertNull(week.energyKcal)
        assertTrue(week.hasActivity)
    }

    @Test
    fun `an empty week is calm, not zeroed`() {
        val week = WeeklyActivitySummary.of(emptyList(), today)
        assertFalse(week.hasActivity)
        assertEquals(0, week.sessionCount)
        assertEquals(ActivityDuration.ZERO, week.totalDuration)
        assertNull(week.energyKcal)
        assertEquals(List(7) { ActivityDuration.ZERO }, week.dailyDurations)
        assertEquals(WeeklyActivitySummary.empty(today), week)
    }

    @Test
    fun `the bars are scaled against the longest day of the week shown`() {
        val summaries = listOf(
            summaryOf("2026-08-24", minutes = 30),
            summaryOf("2026-08-26", minutes = 60),
        )
        val week = WeeklyActivitySummary.of(summaries, today)
        assertEquals(minutesOf(60), week.longestDay)
        assertEquals(0.5, week.fractionOfLongestDay(0), 1e-9)
        assertEquals(1.0, week.fractionOfLongestDay(2), 1e-9)
        assertEquals(0.0, week.fractionOfLongestDay(1), 1e-9)
    }

    @Test
    fun `an empty week divides by nothing`() {
        val week = WeeklyActivitySummary.of(emptyList(), today)
        assertEquals(0.0, week.fractionOfLongestDay(0), 1e-9)
        assertEquals(ActivityDuration.ZERO, week.longestDay)
    }
}
