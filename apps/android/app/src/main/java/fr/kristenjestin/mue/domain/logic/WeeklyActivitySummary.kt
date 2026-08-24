package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.DateWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * What the Activity dashboard says about one week (PRD FR-ACTIVITY-001).
 *
 * Everything here is counted on the stored local date of each session, so no zone takes part
 * and the aggregate is the same on any phone. A null [energyKcal] means the row is not shown
 * at all: PRD 13.3 forbids replacing a missing estimation with a zero.
 */
data class WeeklyActivitySummary(
    /** Monday to Sunday, always. */
    val week: DateWindow,
    val totalDuration: ActivityDuration,
    val sessionCount: Int,
    val energyKcal: Int?,
    /** Seven totals, Monday first, one per day of [week]. */
    val dailyDurations: List<ActivityDuration>,
) {
    val hasActivity: Boolean get() = sessionCount > 0

    /** The day the bars scale against: PRD FR-ACTIVITY-001 makes the tallest fill the height. */
    val longestDay: ActivityDuration get() = dailyDurations.max()

    fun durationOn(day: DayOfWeek): ActivityDuration = dailyDurations[day.value - 1]

    /** Zero when the week is empty, so a caller never divides by nothing. */
    fun fractionOfLongestDay(index: Int): Double {
        val longest = longestDay.seconds
        return if (longest == 0) 0.0 else dailyDurations[index].seconds.toDouble() / longest
    }

    companion object {
        const val DAYS_IN_WEEK: Int = 7

        /**
         * The week starts on Monday whatever the phone's region says (PRD FR-ACTIVITY-001).
         * `WeekFields.of(locale)` would make the dashboard move when the region changes.
         */
        fun weekOf(date: LocalDate): DateWindow {
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return DateWindow.of(monday, monday.plusDays((DAYS_IN_WEEK - 1).toLong()))
        }

        /** [summaries] may cover any span; only the week containing [anyDayOfWeek] is counted. */
        fun of(summaries: List<ActivitySummary>, anyDayOfWeek: LocalDate): WeeklyActivitySummary {
            val week = weekOf(anyDayOfWeek)
            val inWeek = summaries.filter { it.startedOn in week }

            val daily = MutableList(DAYS_IN_WEEK) { ActivityDuration.ZERO }
            inWeek.forEach { summary ->
                val index = summary.startedOn.dayOfWeek.value - 1
                daily[index] = daily[index] + summary.duration
            }

            val energies = inWeek.mapNotNull { it.estimatedEnergyKcal }
            return WeeklyActivitySummary(
                week = week,
                totalDuration = ActivityDuration.sum(inWeek.map { it.duration }),
                sessionCount = inWeek.size,
                energyKcal = energies.takeIf { it.isNotEmpty() }?.sum(),
                dailyDurations = daily.toList(),
            )
        }

        fun empty(anyDayOfWeek: LocalDate): WeeklyActivitySummary = WeeklyActivitySummary(
            week = weekOf(anyDayOfWeek),
            totalDuration = ActivityDuration.ZERO,
            sessionCount = 0,
            energyKcal = null,
            dailyDurations = List(DAYS_IN_WEEK) { ActivityDuration.ZERO },
        )
    }
}
