package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.WeeklyActivitySummary
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedDraftId
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
    /**
     * PRD_ACTIVITY_TIMER FR-TIMER-008: the timed drafts waiting to be reviewed, most recent
     * first and with no ceiling — the screen shows three and rolls the rest out in place.
     */
    val reviewDrafts: List<ReviewDraftUiState> = emptyList(),
    /** PRD_ACTIVITY_TIMER 6.1: the last timed session, ready to be started again. */
    val startAgain: StartAgainUiState? = null,
) {
    /**
     * PRD 13.1: an empty history replaces the whole dashboard with an invitation.
     *
     * A measured draft is not a session, so it does not clear this — but it is still measured
     * time, and hiding it would be exactly what FR-TIMER-008 forbids. The invitation therefore
     * keeps the review block above its two actions rather than swallowing it.
     */
    val showEmptyHistory: Boolean get() = !isLoading && !hasAnyActivity

    /** FR-TIMER-008: the block exists exactly while a draft is waiting. */
    val showReviewDrafts: Boolean get() = reviewDrafts.isNotEmpty()

    /**
     * PRD_ACTIVITY_TIMER 6.1: the shortcut appears once something has been timed.
     *
     * Contract decision 2 is why this is asked separately from [showEmptyHistory]: with no
     * history there is nothing to start again, so the invitation carries two actions and never
     * three.
     */
    val showStartAgain: Boolean get() = startAgain != null

    /** PRD FR-ACTIVITY-002: nothing to see beyond what is already on screen. */
    val showSeeAll: Boolean get() = totalSessionCount > ActivityViewModel.RECENT_LIMIT

    /** PRD 13.3: an unestimated week keeps its count and its duration, and shows no energy. */
    val showWeeklyEnergy: Boolean get() = week.energyKcal != null

    /** PRD 13.2: the quiet week drops the cumulative duration from the editorial title. */
    val showWeeklyDuration: Boolean get() = week.hasActivity
}

/**
 * One timed draft waiting on the dashboard (PRD_ACTIVITY_TIMER FR-TIMER-008).
 *
 * Every string is already spelled: PRD 16.4 keeps aggregation and formatting out of
 * composition, and a card that formatted its own date would do it again on every frame of the
 * list's expansion.
 */
@Immutable
data class ReviewDraftUiState(
    val id: TimedDraftId,
    val label: String,
    /** The day, the start time and the measured duration on one line. */
    val meta: String,
    val iconName: String,
)

/**
 * The `Start again` shortcut (PRD_ACTIVITY_TIMER 6.1).
 *
 * It carries the whole [request] because that is what contract decision 4 copies — movement,
 * custom name, environment and equipment — and PRD 16 has it open the prefilled start screen
 * rather than start anything.
 */
@Immutable
data class StartAgainUiState(
    val request: StartTimerRequest,
    val label: String,
    val iconName: String,
)

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
