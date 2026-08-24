package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.WeeklyActivitySummary
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivitySummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * What the Activity dashboard draws (PRD FR-ACTIVITY-001, 002 and 003).
 *
 * Every total in here is already counted: PRD 16.4 keeps aggregation out of composition, so the
 * screen only formats what the ViewModel hands it. The three empty states of PRD 13 are three
 * distinct readings of this one object rather than three screens.
 */
@Immutable
data class ActivityUiState(
    val today: LocalDate,
    val isLoading: Boolean,
    /** False only when nothing was ever recorded, which is what tells PRD 13.1 from 13.2. */
    val hasAnyActivity: Boolean,
    val week: WeeklyActivitySummary,
    /** Monday first, one entry per day of [week]. */
    val weekDays: List<ActivityDayBar>,
    /** The five most recent sessions (PRD FR-ACTIVITY-002), most recent first. */
    val recent: List<ActivitySummary>,
    /** Every session ever recorded; what hides `See all` at five or fewer. */
    val totalSessionCount: Int,
) {
    /** PRD 13.1: an empty history replaces the whole dashboard with an invitation. */
    val showEmptyHistory: Boolean get() = !isLoading && !hasAnyActivity

    /** PRD FR-ACTIVITY-002: nothing to see beyond what is already on screen. */
    val showSeeAll: Boolean get() = totalSessionCount > ActivityViewModel.RECENT_LIMIT

    /** PRD 13.3: an unestimated week keeps its count and its duration, and shows no energy. */
    val showWeeklyEnergy: Boolean get() = week.energyKcal != null

    /** PRD 13.2: the quiet week drops the cumulative duration from the editorial title. */
    val showWeeklyDuration: Boolean get() = week.hasActivity
}

/** One rail of the weekly visualisation (PRD FR-ACTIVITY-001). */
@Immutable
data class ActivityDayBar(
    val day: DayOfWeek,
    val date: LocalDate,
    val duration: ActivityDuration,
    val isToday: Boolean,
)

/**
 * What the `Activity history` screen draws (PRD FR-ACTIVITY-012): every session, grouped by
 * month and most recent first. The grouping is an aggregate like any other and is therefore
 * done once in the ViewModel, not on every recomposition of a list that has no ceiling.
 */
@Immutable
data class ActivityHistoryUiState(
    val today: LocalDate,
    val isLoading: Boolean,
    val months: List<ActivityMonthGroup>,
) {
    val isEmpty: Boolean get() = !isLoading && months.isEmpty()
}

/** The sessions of one month, most recent first. */
@Immutable
data class ActivityMonthGroup(
    val month: YearMonth,
    val sessions: List<ActivitySummary>,
)
