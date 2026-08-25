package fr.kristenjestin.mue.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.WeeklyActivitySummary
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import fr.kristenjestin.mue.domain.repository.TimedActivityRepository
import fr.kristenjestin.mue.ui.timer.TimerFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * State of the Activity dashboard and of the history behind it (PRD FR-ACTIVITY-001, 002
 * and 012).
 *
 * Both screens are served by one ViewModel because both are the same reading of the same
 * history: the dashboard is its first five rows and the current week, the history is all of
 * it. They are separate flows so opening the dashboard never loads a history with no ceiling —
 * `WhileSubscribed` starts the second query only once the history screen is on screen.
 *
 * There is no `SavedStateHandle` here, unlike `ProgressViewModel`: neither screen holds a
 * choice of the user's. Everything they show is derived from the stored sessions and from the
 * day, and a handle carrying nothing would only suggest otherwise.
 *
 * PRD 16.4: every total is counted here and never in composition. Since PRD_ACTIVITY_TIMER 6.1
 * that also covers the two blocks the timer adds: the drafts waiting to be reviewed and the
 * `Start again` shortcut, both of them read from [TimedActivityRepository] and both of them
 * spelled here rather than in a card.
 */
class ActivityViewModel(
    private val activityRepository: ActivityRepository,
    private val timers: TimedActivityRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: () -> Locale = Locale::getDefault,
) : ViewModel() {

    /**
     * The day is read when the flow is subscribed to and travels with the rows it selected, so
     * the week on screen and the week that was queried can never disagree. Leaving the tab and
     * coming back past midnight re-subscribes and reads the new day.
     */
    private val weekReadings: Flow<WeekReading> = flow {
        val day = today()
        val week = WeeklyActivitySummary.weekOf(day)
        emitAll(activityRepository.observeSummariesIn(week).map { WeekReading(day, it) })
    }

    val uiState: StateFlow<ActivityUiState> = combine(
        weekReadings,
        activityRepository.observeRecentSummaries(RECENT_LIMIT),
        activityRepository.observeSessionCount(),
        timers.observeDraftsToReview(),
        timers.observeLastTimedStart(),
    ) { week, recent, count, drafts, lastTimed ->
        buildState(week, recent, count, drafts, lastTimed, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = buildState(
            reading = WeekReading(today(), emptyList()),
            recent = emptyList(),
            totalSessionCount = 0,
            drafts = emptyList(),
            lastTimed = null,
            isLoading = true,
        ),
    )

    val historyState: StateFlow<ActivityHistoryUiState> =
        activityRepository.observeAllSummaries()
            .map { summaries -> historyState(summaries, isLoading = false) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = historyState(emptyList(), isLoading = true),
            )

    private fun buildState(
        reading: WeekReading,
        recent: List<ActivitySummary>,
        totalSessionCount: Int,
        drafts: List<TimedActivityDraft>,
        lastTimed: StartTimerRequest?,
        isLoading: Boolean,
    ): ActivityUiState {
        val week = WeeklyActivitySummary.of(reading.summaries, reading.today)
        return ActivityUiState(
            today = reading.today,
            isLoading = isLoading,
            hasAnyActivity = totalSessionCount > 0,
            week = week,
            weekDays = weekDays(week, reading.today),
            // The query already caps the list, but the cap is the screen's rule and is
            // restated here so a repository that ignored the limit could not widen it.
            recent = recent.take(RECENT_LIMIT),
            totalSessionCount = totalSessionCount,
            reviewDrafts = reviewCards(drafts, reading.today),
            startAgain = startAgainOf(lastTimed),
        )
    }

    /**
     * FR-TIMER-008's cards, spelled once each.
     *
     * The duration read is [TimedActivityDraft.accumulatedActive] and nothing is derived from a
     * clock: `Finish` closed the last segment into that total, so a draft waiting to be reviewed
     * is worth exactly what was measured and no reading of any clock can change it.
     */
    private fun reviewCards(
        drafts: List<TimedActivityDraft>,
        today: LocalDate,
    ): List<ReviewDraftUiState> {
        if (drafts.isEmpty()) return emptyList()
        val locale = locale()
        return drafts.map { draft ->
            ReviewDraftUiState(
                id = draft.id,
                label = TimerFormat.activityLabel(
                    movement = draft.movement,
                    customMovementName = draft.customMovementName,
                    equipment = draft.equipment,
                ),
                meta = TimerFormat.reviewCardMeta(
                    date = draft.startedOn,
                    time = draft.startedAtLocalTime,
                    duration = draft.accumulatedActive,
                    today = today,
                    locale = locale,
                ),
                iconName = ActivityIcons.forMovement(draft.movement),
            )
        }
    }

    /** PRD_ACTIVITY_TIMER 6.1: null until a session with `source = timer` has been saved. */
    private fun startAgainOf(request: StartTimerRequest?): StartAgainUiState? = request?.let {
        StartAgainUiState(
            request = it,
            label = TimerFormat.activityLabel(
                movement = it.movement,
                customMovementName = it.customMovementName,
                equipment = it.equipment,
            ),
            iconName = ActivityIcons.forMovement(it.movement),
        )
    }

    private fun historyState(
        summaries: List<ActivitySummary>,
        isLoading: Boolean,
    ): ActivityHistoryUiState = ActivityHistoryUiState(
        today = today(),
        isLoading = isLoading,
        months = groupByMonth(summaries),
    )

    /**
     * PRD FR-ACTIVITY-012. The rows already arrive most recent first, so grouping preserves
     * that order inside each month and between months without sorting anything again.
     */
    private fun groupByMonth(summaries: List<ActivitySummary>): List<ActivityMonthGroup> =
        summaries
            .groupBy { YearMonth.from(it.startedOn) }
            .map { (month, sessions) -> ActivityMonthGroup(month, sessions) }

    private fun weekDays(week: WeeklyActivitySummary, today: LocalDate): List<ActivityDayBar> {
        val monday = week.week.start ?: today
        return week.dailyDurations.mapIndexed { index, duration ->
            val date = monday.plusDays(index.toLong())
            ActivityDayBar(
                day = date.dayOfWeek,
                date = date,
                duration = duration,
                isToday = date == today,
            )
        }
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    /** One week's rows and the day they were selected for, kept together on purpose. */
    private data class WeekReading(val today: LocalDate, val summaries: List<ActivitySummary>)

    companion object {
        /** PRD FR-ACTIVITY-002: five recent sessions, and `See all` above that. */
        const val RECENT_LIMIT: Int = 5

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                ActivityViewModel(
                    activityRepository = app.container.activityRepository,
                    timers = app.container.timer.timedActivityRepository,
                )
            }
        }
    }
}
