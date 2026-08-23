package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import java.time.temporal.ChronoUnit

/**
 * The Progress indicators for one period (PRD FR-PROGRESS-003).
 *
 * A null field means the screen shows `—`. Nothing here ever falls back to a
 * measurement taken outside the period.
 */
data class ProgressStatistics(
    /** The measurement with the most recent date *inside the period* (PRD BR-004). */
    val current: Measurement?,
    val first: Measurement?,
    /** Last weight minus first weight, in kilograms. Null below two distinct dates. */
    val changeKg: Double?,
    /** Average kilograms per week between the first and last measurement of the period. */
    val weeklyPaceKg: Double?,
) {
    val currentWeight: Weight? get() = current?.weight

    val hasData: Boolean get() = current != null

    companion object {
        val UNAVAILABLE: ProgressStatistics = ProgressStatistics(null, null, null, null)
    }
}

object StatisticsCalculator {

    /**
     * Filters to the window first, so a measurement outside the period can never
     * leak into an indicator (PRD FR-PROGRESS-003).
     */
    fun compute(allMeasurements: List<Measurement>, window: DateWindow): ProgressStatistics =
        compute(allMeasurements.filter { it.date in window })

    /** [periodMeasurements] must already be restricted to the period; order is irrelevant. */
    fun compute(periodMeasurements: List<Measurement>): ProgressStatistics {
        val sorted = periodMeasurements.sortedBy { it.date }
        val first = sorted.firstOrNull() ?: return ProgressStatistics.UNAVAILABLE
        val last = sorted.last()

        // One measurement, or several recorded on the same day: there is no span to
        // divide by, so change and pace stay unavailable.
        val days = ChronoUnit.DAYS.between(first.date, last.date)
        if (days <= 0L) return ProgressStatistics(last, first, null, null)

        val changeKg = (last.weight - first.weight) / HUNDREDTHS_PER_KILOGRAM
        return ProgressStatistics(
            current = last,
            first = first,
            changeKg = changeKg,
            weeklyPaceKg = changeKg / days * DAYS_PER_WEEK,
        )
    }

    private const val DAYS_PER_WEEK = 7.0
    private const val HUNDREDTHS_PER_KILOGRAM = 100.0
}
