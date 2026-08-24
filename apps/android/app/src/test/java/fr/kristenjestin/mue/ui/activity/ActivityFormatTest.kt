package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.minutesOf
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val EN = Locale.US
private val FR = Locale.FRANCE

/** 2026-08-17 is a Monday, so this is the Monday-to-Sunday week of PRD FR-ACTIVITY-001. */
private val MONDAY: LocalDate = LocalDate.of(2026, 8, 17)
private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

/**
 * The display rules of the Activity module (PRD 11, 12 and 13.3).
 *
 * Both languages are exercised because PRD 12 splits the two directions: the labels stay
 * English, the numbers and the dates follow the phone. A French assertion that only checked
 * the English output would prove nothing.
 */
class ActivityFormatTest {

    // region durations

    /** PRD section 18's own spelling for a session under an hour. */
    @Test
    fun `a duration under an hour reads in minutes`() {
        assertEquals("45 min", ActivityFormat.duration(minutesOf(45), EN))
        assertEquals("45 min", ActivityFormat.duration(minutesOf(45), FR))
        assertEquals("1 min", ActivityFormat.duration(minutesOf(1), EN))
    }

    /** PRD FR-ACTIVITY-001's own spelling for the weekly total. */
    @Test
    fun `a duration of an hour or more reads in hours and minutes`() {
        assertEquals("2h 15m", ActivityFormat.duration(minutesOf(135), EN))
        assertEquals("2h 15m", ActivityFormat.duration(minutesOf(135), FR))
        assertEquals("99h 59m", ActivityFormat.duration(minutesOf(99 * 60 + 59), EN))
    }

    @Test
    fun `a whole number of hours drops the minutes`() {
        assertEquals("2h", ActivityFormat.duration(minutesOf(120), EN))
        assertEquals("1h", ActivityFormat.duration(minutesOf(60), EN))
    }

    @Test
    fun `an empty day still has a reading, which is what an accessible bar says`() {
        assertEquals("0 min", ActivityFormat.duration(ActivityDuration.ZERO, EN))
    }

    // endregion

    // region quantities

    /** PRD 12: metres are stored, kilometres with a decimal are shown. */
    @Test
    fun `a distance shows its decimals in the phone's language`() {
        assertEquals("4.2 km", ActivityFormat.distance(4_200, EN))
        assertEquals("4,2 km", ActivityFormat.distance(4_200, FR))
    }

    @Test
    fun `a round distance still shows its decimal`() {
        assertEquals("5.0 km", ActivityFormat.distance(5_000, EN))
    }

    /**
     * A card reads the same value as the form it was typed in: the hundredth of a kilometre
     * the session stores is shown rather than rounded to the tenth.
     */
    @Test
    fun `a distance carrying a hundredth shows it rather than rounding up`() {
        assertEquals("2.95 km", ActivityFormat.distance(2_950, EN))
        assertEquals("2,95 km", ActivityFormat.distance(2_950, FR))
        assertEquals("0.42 km", ActivityFormat.distance(420, EN))
    }

    /** PRD 11.3: the prefix says it is an estimation, and it is never replaced by a zero. */
    @Test
    fun `an energy carries the estimation prefix and its unit`() {
        assertEquals("≈280 kcal", ActivityFormat.energy(280, EN))
        assertEquals("≈1,234 kcal", ActivityFormat.energy(1_234, EN))
    }

    @Test
    fun `an energy groups its thousands the way the phone does`() {
        val french = ActivityFormat.energy(1_234, FR)
        assertTrue(french.startsWith("≈") && french.endsWith(" kcal"), french)
        // Whichever space French groups with, the grouped digits are not the English ones.
        assertFalse(french.contains(','), french)
        assertTrue(french.contains('1') && french.contains("234"), french)
    }

    /** PRD 12 and 13.3: a missing optional value is a dash, never a zero. */
    @Test
    fun `a missing value shows the unavailable dash`() {
        assertEquals("—", ActivityFormat.energy(null, EN))
        assertEquals("—", ActivityFormat.distance(null, EN))
        assertEquals("—", ActivityFormat.setCount(null, EN))
    }

    /** PRD 11.2: warm-ups included, and the noun follows the count. */
    @Test
    fun `a set count agrees with its noun`() {
        assertEquals("1 set", ActivityFormat.setCount(1, EN))
        assertEquals("12 sets", ActivityFormat.setCount(12, EN))
    }

    /** PRD 13.2 shows `0 sessions` rather than hiding the count. */
    @Test
    fun `a session count agrees with its noun and admits zero`() {
        assertEquals("0 sessions", ActivityFormat.sessionCount(0, EN))
        assertEquals("1 session", ActivityFormat.sessionCount(1, EN))
        assertEquals("3 sessions", ActivityFormat.sessionCount(3, EN))
        assertEquals("session", ActivityFormat.sessionNoun(1))
        assertEquals("sessions", ActivityFormat.sessionNoun(0))
    }

    // endregion

    // region card facts

    /** PRD FR-ACTIVITY-002: distance and energy for the prototype's treadmill walk. */
    @Test
    fun `a distance session reads its distance and its energy`() {
        val facts = ActivityFormat.facts(summary(distanceMetres = 4_200, energyKcal = 280), EN)

        assertEquals(listOf("4.2 km", "≈280 kcal"), facts)
    }

    /** PRD FR-ACTIVITY-002: sets and energy for the prototype's strength session. */
    @Test
    fun `a strength session reads its sets and its energy`() {
        val facts = ActivityFormat.facts(summary(setCount = 12, energyKcal = 320), EN)

        assertEquals(listOf("12 sets", "≈320 kcal"), facts)
    }

    /** PRD FR-ACTIVITY-002 allows two secondary facts, so the third is dropped. */
    @Test
    fun `at most two secondary facts reach a card`() {
        val facts = ActivityFormat.facts(
            summary(distanceMetres = 4_200, setCount = 12, energyKcal = 280),
            EN,
        )

        assertEquals(listOf("4.2 km", "12 sets"), facts)
    }

    /** PRD 11.3: an unestimated session says nothing about energy rather than `0 kcal`. */
    @Test
    fun `a session without energy shows no energy at all and never a zero`() {
        val facts = ActivityFormat.facts(summary(distanceMetres = 4_200, energyKcal = null), EN)

        assertEquals(listOf("4.2 km"), facts)
        assertFalse(facts.any { it.contains("kcal") }, facts.toString())
        assertEquals(ActivityFormat.UNAVAILABLE, ActivityFormat.energy(null, EN))
    }

    @Test
    fun `a session with nothing but a duration has no secondary fact`() {
        assertEquals(emptyList(), ActivityFormat.facts(summary(), EN))
    }

    // endregion

    // region dates

    @Test
    fun `the current day and the one before it are named rather than dated`() {
        assertEquals("Today", ActivityFormat.dayLabel(TODAY, TODAY, EN))
        assertEquals("Yesterday", ActivityFormat.dayLabel(TODAY.minusDays(1), TODAY, EN))
        assertEquals("Today", ActivityFormat.dayLabel(TODAY, TODAY, FR))
    }

    /** The prototype's `Thu`: still this week, so the weekday is enough. */
    @Test
    fun `a day earlier in the week is named by its weekday`() {
        assertEquals("Thu", ActivityFormat.dayLabel(LocalDate.of(2026, 8, 20), TODAY, EN))
        assertEquals("Mon", ActivityFormat.dayLabel(MONDAY, TODAY, EN))
        assertTrue(ActivityFormat.dayLabel(MONDAY, TODAY, FR).startsWith("lun"))
    }

    @Test
    fun `an older day of the same year is dated without its year`() {
        assertEquals("Aug 3", ActivityFormat.dayLabel(LocalDate.of(2026, 8, 3), TODAY, EN))
        assertEquals("3 août", ActivityFormat.dayLabel(LocalDate.of(2026, 8, 3), TODAY, FR))
    }

    @Test
    fun `a day of another year keeps its year`() {
        assertEquals(
            "Dec 3, 2025",
            ActivityFormat.dayLabel(LocalDate.of(2025, 12, 3), TODAY, EN),
        )
    }

    @Test
    fun `a start time joins the day when the session has one`() {
        assertEquals("Today", ActivityFormat.dayAndTime(TODAY, null, TODAY, EN))

        val timed = ActivityFormat.dayAndTime(TODAY, LocalTime.of(21, 5), TODAY, FR)
        assertEquals("Today · 21:05", timed)
    }

    @Test
    fun `a time follows the phone's clock`() {
        assertEquals("21:05", ActivityFormat.time(LocalTime.of(21, 5), FR))

        val english = ActivityFormat.time(LocalTime.of(21, 5), EN)
        assertTrue(english.startsWith("9:05"), english)
        assertTrue(english.contains("PM"), english)
    }

    // endregion

    // region the week

    /**
     * PRD FR-ACTIVITY-001 illustrates the header with `Aug 18–24`, which is a Tuesday in 2026.
     * The wording is what that example fixes; the boundaries are Monday to Sunday and come
     * from `WeeklyActivitySummary`, so the real week of this day reads `Aug 17–23`.
     */
    @Test
    fun `a week inside one month shares its month between both ends`() {
        val week = DateWindow.of(MONDAY, MONDAY.plusDays(6))

        assertEquals("Aug 17–23", ActivityFormat.weekRange(week, EN))
        assertEquals("17–23 août", ActivityFormat.weekRange(week, FR))
    }

    @Test
    fun `a week spanning two months names both`() {
        val week = DateWindow.of(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6))

        assertEquals("Aug 31 – Sep 6", ActivityFormat.weekRange(week, EN))
        assertTrue(ActivityFormat.weekRange(week, FR).startsWith("31 août – 6 "))
    }

    @Test
    fun `a week spanning two years keeps both years`() {
        val week = DateWindow.of(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4))

        assertEquals("Dec 29, 2025 – Jan 4, 2026", ActivityFormat.weekRange(week, EN))
    }

    @Test
    fun `an unbounded window has no range to show`() {
        assertEquals("—", ActivityFormat.weekRange(DateWindow.UNBOUNDED, EN))
    }

    @Test
    fun `the bars are lettered in the phone's language`() {
        val letters = listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY,
        ).map { ActivityFormat.dayInitial(it, EN) }

        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), letters)
        assertEquals("L", ActivityFormat.dayInitial(DayOfWeek.MONDAY, FR))
    }

    /** PRD 15: the current day is said out loud, not only tinted. */
    @Test
    fun `a bar says its day, whether it is today and how long it lasted`() {
        assertEquals(
            "Monday, 32 min",
            ActivityFormat.dayDescription(DayOfWeek.MONDAY, minutesOf(32), isToday = false, EN),
        )
        assertEquals(
            "Sunday, Today, 1h 5m",
            ActivityFormat.dayDescription(DayOfWeek.SUNDAY, minutesOf(65), isToday = true, EN),
        )
    }

    /** PRD 13.2: an empty rail is nothing at all, not a zero. */
    @Test
    fun `an empty day says it has no activity`() {
        assertEquals(
            "Friday, no activity",
            ActivityFormat.dayDescription(
                DayOfWeek.FRIDAY,
                ActivityDuration.ZERO,
                isToday = false,
                EN,
            ),
        )
    }

    @Test
    fun `a month heading names its month and its year`() {
        assertEquals("August 2026", ActivityFormat.monthTitle(YearMonth.of(2026, 8), EN))
        assertEquals("août 2026", ActivityFormat.monthTitle(YearMonth.of(2026, 8), FR))
    }

    // endregion

    private fun summary(
        distanceMetres: Int? = null,
        setCount: Int? = null,
        energyKcal: Int? = null,
    ): ActivitySummary = ActivitySummary(
        id = ActivityId("session-1"),
        label = "Treadmill walk",
        movement = Movement.WALKING,
        startedOn = TODAY,
        startedAtTime = null,
        duration = minutesOf(45),
        distanceMetres = distanceMetres,
        validSetCount = setCount,
        estimatedEnergyKcal = energyKcal,
    )
}
