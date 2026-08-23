package fr.kristenjestin.mue.domain.model

import java.time.LocalDate

/**
 * The four Progress filters (PRD FR-PROGRESS-001).
 *
 * Each period is a calendar window that ends on the current day. The window is
 * exactly as long as its name says and today counts as one of its days, so
 * `7 days` covers today plus the six days before it — never eight days.
 */
enum class Period {
    SEVEN_DAYS,
    THIRTY_DAYS,
    THREE_MONTHS,

    /** Every measurement ever recorded, with no bound on either side. */
    ALL,
    ;

    fun windowEndingOn(today: LocalDate): DateWindow = when (this) {
        SEVEN_DAYS -> DateWindow.of(today.minusDays(6), today)
        THIRTY_DAYS -> DateWindow.of(today.minusDays(29), today)
        THREE_MONTHS -> DateWindow.of(today.minusMonths(3).plusDays(1), today)
        ALL -> DateWindow.UNBOUNDED
    }
}
