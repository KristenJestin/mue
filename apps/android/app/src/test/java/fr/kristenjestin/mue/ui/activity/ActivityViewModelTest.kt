package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.minutesOf
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Sunday. Its Monday-to-Sunday week runs from 2026-08-17 to 2026-08-23. */
private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)
private val MONDAY: LocalDate = LocalDate.of(2026, 8, 17)

/**
 * The dashboard and the history as the ViewModel counts them (PRD FR-ACTIVITY-001, 002 and
 * 012), with the repository faked so the week boundaries, the cap and the three empty states
 * are proved without a database.
 *
 * PRD 16.4 keeps every aggregate here, which is exactly why every one of them is testable
 * without Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region the week

    /** PRD FR-ACTIVITY-001: Monday to Sunday, and nothing of the week before. */
    @Test
    fun `the week runs from monday to sunday whatever the region says`() = activityTest(
        sessions = listOf(
            summary("2026-08-16", minutes = 60), // the Sunday before: outside
            summary("2026-08-17", minutes = 30), // the Monday: inside
            summary("2026-08-23", minutes = 45), // the Sunday: inside
            summary("2026-08-24", minutes = 90), // the Monday after: outside
        ),
    ) { viewModel ->
        val week = viewModel.state().week

        assertEquals(MONDAY, week.week.start)
        assertEquals(TODAY, week.week.endInclusive)
        assertEquals(2, week.sessionCount)
        assertEquals(minutesOf(75), week.totalDuration)
    }

    /** A week is not a month: the totals cross the boundary without noticing it. */
    @Test
    fun `a week spanning two months counts both of its halves`() = activityTest(
        today = LocalDate.of(2026, 9, 2),
        sessions = listOf(
            summary("2026-08-30", minutes = 60), // the Sunday before: outside
            summary("2026-08-31", minutes = 30), // the Monday: inside
            summary("2026-09-06", minutes = 45), // the Sunday: inside
            summary("2026-09-07", minutes = 20), // the Monday after: outside
        ),
    ) { viewModel ->
        val state = viewModel.state()

        assertEquals(LocalDate.of(2026, 8, 31), state.week.week.start)
        assertEquals(LocalDate.of(2026, 9, 6), state.week.week.endInclusive)
        assertEquals(2, state.week.sessionCount)
        assertEquals(minutesOf(75), state.week.totalDuration)
        assertEquals("Aug 31 – Sep 6", ActivityFormat.weekRange(state.week.week, Locale.US))
    }

    /** PRD FR-ACTIVITY-001: seven days, Monday first, each one the total of that day. */
    @Test
    fun `the seven bars are monday first and add up the day they stand for`() = activityTest(
        sessions = listOf(
            summary("2026-08-17", minutes = 20),
            summary("2026-08-17", minutes = 12, id = "second-monday"),
            summary("2026-08-20", minutes = 45),
            summary("2026-08-23", minutes = 30),
        ),
    ) { viewModel ->
        val bars = viewModel.state().weekDays

        assertEquals(7, bars.size)
        assertEquals(DayOfWeek.MONDAY, bars.first().day)
        assertEquals(DayOfWeek.SUNDAY, bars.last().day)
        assertEquals(minutesOf(32), bars[0].duration)
        assertEquals(minutesOf(0), bars[1].duration)
        assertEquals(minutesOf(45), bars[3].duration)
        assertEquals(minutesOf(30), bars[6].duration)
    }

    @Test
    fun `only the current day is the emphasised one`() = activityTest { viewModel ->
        val bars = viewModel.state().weekDays

        assertEquals(listOf(TODAY), bars.filter { it.isToday }.map { it.date })
        assertEquals(MONDAY, bars.first().date)
    }

    /** PRD 11.3: the recorded estimations are added, never recomputed. */
    @Test
    fun `the week adds the energies it was given`() = activityTest(
        sessions = listOf(
            summary("2026-08-18", energyKcal = 280),
            summary("2026-08-20", energyKcal = 320),
            summary("2026-08-21", energyKcal = null),
        ),
    ) { viewModel ->
        val state = viewModel.state()

        assertTrue(state.showWeeklyEnergy)
        assertEquals(600, state.week.energyKcal)
    }

    // endregion

    // region the recent sessions

    /** PRD FR-ACTIVITY-002: five, and the five most recent ones. */
    @Test
    fun `the dashboard keeps five sessions at most`() = activityTest(
        sessions = (1..8).map { day -> summary("2026-08-%02d".format(day + 10), minutes = day) },
    ) { viewModel ->
        val recent = viewModel.state().recent

        assertEquals(ActivityViewModel.RECENT_LIMIT, recent.size)
        assertEquals(
            listOf("2026-08-18", "2026-08-17", "2026-08-16", "2026-08-15", "2026-08-14"),
            recent.map { it.startedOn.toString() },
        )
    }

    /** PRD FR-ACTIVITY-002: `See all` is hidden at five sessions or fewer. */
    @Test
    fun `see all appears only above five sessions`() = activityTest(
        sessions = (1..5).map { day -> summary("2026-08-1$day") },
    ) { viewModel ->
        assertFalse(viewModel.state().showSeeAll)
        assertEquals(5, viewModel.state().totalSessionCount)
    }

    @Test
    fun `a sixth session opens the history`() = activityTest(
        sessions = (1..6).map { day -> summary("2026-08-1$day") },
    ) { viewModel ->
        assertTrue(viewModel.state().showSeeAll)
        assertEquals(6, viewModel.state().totalSessionCount)
        assertEquals(ActivityViewModel.RECENT_LIMIT, viewModel.state().recent.size)
    }

    /** PRD 11.3 on a card: a session with no estimation carries none, and never a zero. */
    @Test
    fun `a session without energy keeps a dash rather than a zero`() = activityTest(
        sessions = listOf(summary("2026-08-20", energyKcal = null, distanceMetres = 4_200)),
    ) { viewModel ->
        val session = viewModel.state().recent.single()

        assertNull(session.estimatedEnergyKcal)
        assertEquals(listOf("4.2 km"), ActivityFormat.facts(session, Locale.US))
        assertEquals(
            ActivityFormat.UNAVAILABLE,
            ActivityFormat.energy(session.estimatedEnergyKcal, Locale.US),
        )
    }

    // endregion

    // region the three empty states

    /** PRD 13.1: nothing was ever recorded. */
    @Test
    fun `an empty history is its own state`() = activityTest { viewModel ->
        val state = viewModel.state()

        assertTrue(state.showEmptyHistory)
        assertFalse(state.hasAnyActivity)
        assertEquals(0, state.totalSessionCount)
        assertTrue(state.recent.isEmpty())
    }

    /** PRD 13.2: the history exists, this week does not. */
    @Test
    fun `a week with no activity is not an empty history`() = activityTest(
        sessions = listOf(summary("2026-07-14", energyKcal = 280)),
    ) { viewModel ->
        val state = viewModel.state()

        assertFalse(state.showEmptyHistory)
        assertTrue(state.hasAnyActivity)
        assertFalse(state.week.hasActivity)
        assertFalse(state.showWeeklyDuration)
        assertEquals(0, state.week.sessionCount)
        assertEquals(minutesOf(0), state.week.totalDuration)
        // The seven rails stay, empty.
        assertEquals(7, state.weekDays.size)
        assertTrue(state.weekDays.all { it.duration.seconds == 0 })
        // And the session recorded in July is still on the dashboard.
        assertEquals(1, state.recent.size)
    }

    /** PRD 13.3: no estimation at all, and no zero standing in for one. */
    @Test
    fun `a week with no estimated energy keeps its count and its duration`() = activityTest(
        sessions = listOf(
            summary("2026-08-18", minutes = 45, energyKcal = null),
            summary("2026-08-20", minutes = 30, energyKcal = null),
        ),
    ) { viewModel ->
        val state = viewModel.state()

        assertFalse(state.showWeeklyEnergy)
        assertNull(state.week.energyKcal)
        assertEquals(2, state.week.sessionCount)
        assertEquals(minutesOf(75), state.week.totalDuration)
        assertTrue(state.showWeeklyDuration)
    }

    // endregion

    // region the history

    /** PRD FR-ACTIVITY-012: grouped by month, most recent first, with no ceiling. */
    @Test
    fun `the history groups every session by month, most recent first`() = activityTest(
        sessions = listOf(
            summary("2026-08-23"),
            summary("2026-08-04"),
            summary("2026-07-30"),
            summary("2025-12-31"),
        ),
    ) { viewModel ->
        val months = viewModel.historyState().months

        assertEquals(
            listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 7), YearMonth.of(2025, 12)),
            months.map { it.month },
        )
        assertEquals(2, months.first().sessions.size)
        assertEquals(
            listOf("2026-08-23", "2026-08-04"),
            months.first().sessions.map { it.startedOn.toString() },
        )
    }

    @Test
    fun `the history has no limit of its own`() = activityTest(
        sessions = (1..30).map { day -> summary("2026-06-%02d".format(day)) },
    ) { viewModel ->
        assertEquals(30, viewModel.historyState().months.single().sessions.size)
        assertEquals(ActivityViewModel.RECENT_LIMIT, viewModel.state().recent.size)
    }

    @Test
    fun `an empty history has no month at all`() = activityTest { viewModel ->
        assertTrue(viewModel.historyState().isEmpty)
        assertTrue(viewModel.historyState().months.isEmpty())
    }

    // endregion

    // region harness

    private fun ActivityViewModel.state(): ActivityUiState = uiState.value

    private fun ActivityViewModel.historyState(): ActivityHistoryUiState = historyState.value

    private fun activityTest(
        sessions: List<ActivitySummary> = emptyList(),
        today: LocalDate = TODAY,
        body: suspend TestScope.(ActivityViewModel) -> Unit,
    ) = runTest {
        val viewModel = ActivityViewModel(
            activityRepository = FakeActivityRepository(sessions),
            clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch { viewModel.historyState.collect {} }
        advanceUntilIdle()
        body(viewModel)
    }

    // endregion
}

private fun summary(
    isoDate: String,
    minutes: Int = 45,
    energyKcal: Int? = null,
    distanceMetres: Int? = null,
    setCount: Int? = null,
    startedAtTime: LocalTime? = null,
    id: String = "session-$isoDate-$minutes",
): ActivitySummary = ActivitySummary(
    id = ActivityId(id),
    label = "Treadmill walk",
    movement = Movement.WALKING,
    startedOn = LocalDate.parse(isoDate),
    startedAtTime = startedAtTime,
    duration = minutesOf(minutes),
    distanceMetres = distanceMetres,
    validSetCount = setCount,
    estimatedEnergyKcal = energyKcal,
)

/**
 * An in-memory history ordering exactly as the storage does (contract section 3): most recent
 * day first, timed sessions before untimed ones on a shared day, and a stable final tiebreak.
 * Only the reads are implemented — neither of these two screens writes anything.
 */
private class FakeActivityRepository(sessions: List<ActivitySummary>) : ActivityRepository {

    private val entries = MutableStateFlow(sessions.sortedWith(STORED_ORDER))

    override fun observeRecentSummaries(limit: Int): Flow<List<ActivitySummary>> =
        entries.map { all -> all.take(limit) }

    override fun observeAllSummaries(): Flow<List<ActivitySummary>> = entries.asStateFlow()

    override fun observeSummariesIn(window: DateWindow): Flow<List<ActivitySummary>> =
        entries.map { all -> all.filter { it.startedOn in window } }

    override fun observeSessionCount(): Flow<Int> = entries.map { it.size }

    override suspend fun findDetail(id: ActivityId): ActivitySessionDetail? = null

    override suspend fun save(detail: ActivitySessionDetail) = Unit

    override suspend fun delete(id: ActivityId) = Unit

    override suspend fun findLastPerformance(
        exercise: ExerciseDefinitionId,
        excludingSession: ActivityId?,
    ): LastPerformance? = null

    private companion object {
        val STORED_ORDER: Comparator<ActivitySummary> =
            compareByDescending<ActivitySummary> { it.startedOn }
                .thenByDescending { it.startedAtTime ?: LocalTime.MIN }
                .thenBy { it.id.value }
    }
}
