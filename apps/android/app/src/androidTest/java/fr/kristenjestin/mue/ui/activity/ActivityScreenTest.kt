package fr.kristenjestin.mue.ui.activity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import fr.kristenjestin.mue.domain.logic.WeeklyActivitySummary
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** A Sunday, so its Monday-to-Sunday week is 2026-08-17 to 2026-08-23. */
private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

/**
 * Compose coverage of the Activity dashboard and of the history (PRD FR-ACTIVITY-001, 002,
 * 003 and 012).
 *
 * Both screens are driven through their stateless content composables, so the assertions are
 * about what reaches the screen rather than about how the ViewModel got there. Expected strings
 * go through [ActivityFormat] rather than being spelled out, because numbers and dates follow
 * the language of the device running the test (PRD 12).
 */
class ActivityScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // region the dashboard

    /** PRD 13.1: nothing recorded yet, so the screen is an invitation and nothing else. */
    @Test
    fun anEmptyHistoryInvitesAFirstActivity() {
        setDashboard(dashboardState(emptyList()))

        compose.onNodeWithText(EMPTY_HISTORY_TITLE).assertIsDisplayed()
        compose.onNodeWithText(EMPTY_HISTORY_ACTION).assertIsDisplayed()
        compose.onNodeWithText(RECENT_TITLE).assertDoesNotExist()
        compose.onNodeWithTag(ActivityTestTags.WEEKLY_BARS).assertDoesNotExist()
        compose.onNodeWithText(SEE_ALL_LABEL).assertDoesNotExist()
    }

    @Test
    fun theEmptyStateActionOpensTheForm() {
        var opened = 0
        setDashboard(dashboardState(emptyList()), onLogActivity = { opened++ })

        compose.onNodeWithText(EMPTY_HISTORY_ACTION).performClick()

        assertEquals(1, opened)
    }

    /** PRD FR-ACTIVITY-001: the week's range, its title, its count and its energy. */
    @Test
    fun aPopulatedWeekReadsItsTotals() {
        setDashboard(dashboardState(week()))

        compose.onNodeWithText(ActivityFormat.weekRange(weekWindow())).assertIsDisplayed()
        compose.onNodeWithText(MOVED_PREFIX).assertIsDisplayed()
        compose.onNodeWithText("${ActivityFormat.duration(durationOf(138))}.").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "${ActivityFormat.sessionCount(3)} $THIS_WEEK_SUFFIX",
        ).assertExists()
        compose.onNodeWithText(ActivityFormat.energy(1_010)).assertIsDisplayed()
    }

    /** PRD FR-ACTIVITY-001: seven rails, whatever the week holds. */
    @Test
    fun theWeekAlwaysHasSevenRails() {
        setDashboard(dashboardState(week()))

        compose.onNodeWithTag(ActivityTestTags.WEEKLY_BARS).assertIsDisplayed()
        repeat(7) { index ->
            compose.onNodeWithTag(ActivityTestTags.weeklyBar(index)).assertExists()
        }
    }

    /** PRD 13.2: the quiet week, with no reformulation and no zero energy. */
    @Test
    fun aWeekWithNoActivityStaysQuietAndKeepsItsRails() {
        setDashboard(
            dashboardState(
                recent = listOf(summary("2026-07-14", energyKcal = 280)),
                weekSummaries = emptyList(),
                totalSessionCount = 1,
            ),
        )

        compose.onNodeWithText(QUIET_WEEK_TITLE).assertIsDisplayed()
        compose.onNodeWithText(MOVED_PREFIX).assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "${ActivityFormat.sessionCount(0)} $THIS_WEEK_SUFFIX",
        ).assertExists()
        compose.onNodeWithTag(ActivityTestTags.WEEKLY_BARS).assertIsDisplayed()
        compose.onNodeWithText(ENERGY_LABEL).assertDoesNotExist()
        // The history it does have is still on screen (PRD 13.2).
        compose.onNodeWithText(RECENT_TITLE).assertIsDisplayed()
    }

    /** PRD 13.3: no estimation at all, so no energy line and certainly no `0 kcal`. */
    @Test
    fun aWeekWithNoEstimatedEnergyShowsNoEnergyAtAll() {
        setDashboard(
            dashboardState(
                listOf(
                    summary("2026-08-18", minutes = 45, energyKcal = null),
                    summary("2026-08-20", minutes = 30, energyKcal = null),
                ),
            ),
        )

        compose.onNodeWithText(ENERGY_LABEL).assertDoesNotExist()
        compose.onNodeWithText(ActivityFormat.energy(0)).assertDoesNotExist()
        compose.onNodeWithText(MOVED_PREFIX).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "${ActivityFormat.sessionCount(2)} $THIS_WEEK_SUFFIX",
        ).assertExists()
    }

    /** PRD FR-ACTIVITY-002: five cards, each with its label, its date and its facts. */
    @Test
    fun theRecentCardsCarryTheirLabelDateAndFacts() {
        setDashboard(dashboardState(week()))

        compose.onNodeWithTag(ActivityTestTags.DASHBOARD)
            .performScrollToNode(hasTestTag(ActivityTestTags.RECENT_LIST))

        compose.onNodeWithText("Treadmill walk").assertIsDisplayed()
        compose.onNodeWithText(ActivityFormat.dayLabel(TODAY, TODAY)).assertIsDisplayed()
        compose.onNodeWithText(ActivityFormat.duration(durationOf(45))).assertIsDisplayed()
        compose.onNodeWithText(ActivityFormat.distance(4_200)).assertIsDisplayed()
        compose.onNodeWithText(ActivityFormat.energy(280)).assertIsDisplayed()
        compose.onNodeWithText("Strength training").assertIsDisplayed()
        compose.onNodeWithText(ActivityFormat.setCount(12)).assertIsDisplayed()
    }

    /** PRD 11.3 on a card: an unestimated session shows no energy rather than a zero. */
    @Test
    fun aCardWithoutEnergyShowsNoZero() {
        setDashboard(
            dashboardState(listOf(summary("2026-08-20", distanceMetres = 4_200, energyKcal = null))),
        )

        compose.onNodeWithTag(ActivityTestTags.DASHBOARD)
            .performScrollToNode(hasTestTag(ActivityTestTags.RECENT_LIST))
        compose.onNodeWithText(ActivityFormat.distance(4_200)).assertIsDisplayed()
        compose.onNodeWithText(ActivityFormat.energy(0)).assertDoesNotExist()
    }

    /** PRD FR-ACTIVITY-002: hidden at five sessions or fewer. */
    @Test
    fun seeAllIsHiddenUntilThereIsMoreToSee() {
        setDashboard(dashboardState(week(), totalSessionCount = 5))

        compose.onNodeWithTag(ActivityTestTags.SEE_ALL).assertDoesNotExist()
    }

    @Test
    fun seeAllOpensTheHistory() {
        var opened = 0
        setDashboard(dashboardState(week(), totalSessionCount = 9), onSeeAll = { opened++ })

        compose.onNodeWithTag(ActivityTestTags.SEE_ALL).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun tappingACardOpensThatSession() {
        var opened: ActivityId? = null
        setDashboard(dashboardState(week()), onOpenSession = { opened = it })

        compose.onNodeWithTag(ActivityTestTags.DASHBOARD)
            .performScrollToNode(hasTestTag(ActivityTestTags.sessionCard("strength")))
        compose.onNodeWithTag(ActivityTestTags.sessionCard("strength")).performClick()

        assertEquals(ActivityId("strength"), opened)
    }

    /** PRD FR-ACTIVITY-003: the action is always one tap away. */
    @Test
    fun logActivityIsAlwaysOffered() {
        var opened = 0
        setDashboard(dashboardState(week()), onLogActivity = { opened++ })

        compose.onNodeWithTag(ActivityTestTags.LOG_ACTIVITY).assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.LOG_ACTIVITY).performClick()

        assertEquals(1, opened)
    }

    /**
     * PRD 15's reading order — title, summary, action, history — which is also what keeps the
     * action reachable now that the list below it holds five cards rather than the prototype's
     * two (PRD FR-ACTIVITY-002 and 003).
     */
    @Test
    fun theActionComesBeforeTheHistory() {
        setDashboard(dashboardState(week()))

        val action = compose.onNodeWithTag(ActivityTestTags.LOG_ACTIVITY)
            .getUnclippedBoundsInRoot()
        val history = compose.onNodeWithTag(ActivityTestTags.RECENT_LIST)
            .getUnclippedBoundsInRoot()

        assertTrue("the action should sit above the history", action.top < history.top)
    }

    // endregion

    // region the history

    /** PRD FR-ACTIVITY-012: grouped by month, most recent first. */
    @Test
    fun theHistoryGroupsItsSessionsByMonth() {
        setHistory(
            historyState(
                listOf(
                    summary("2026-08-23", id = "august-late"),
                    summary("2026-08-04", id = "august-early"),
                    summary("2026-07-30", id = "july"),
                ),
            ),
        )

        compose.onNodeWithText(HISTORY_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ActivityFormat.monthTitle(YearMonth.of(2026, 8)))
            .assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.HISTORY_LIST)
            .performScrollToNode(hasTestTag(ActivityTestTags.sessionCard("july")))
        compose.onNodeWithText(ActivityFormat.monthTitle(YearMonth.of(2026, 7)))
            .assertIsDisplayed()
    }

    /** PRD FR-ACTIVITY-012: the very same card, opening the very same editor. */
    @Test
    fun aHistoryCardOpensTheSameEditor() {
        var opened: ActivityId? = null
        setHistory(
            historyState(listOf(summary("2026-08-23", id = "august-late"))),
            onOpenSession = { opened = it },
        )

        compose.onNodeWithTag(ActivityTestTags.sessionCard("august-late")).performClick()

        assertEquals(ActivityId("august-late"), opened)
    }

    /** PRD 7: reached from the dashboard, so it carries a back control and not the wordmark. */
    @Test
    fun theHistoryGoesBackToTheDashboard() {
        var back = 0
        setHistory(historyState(listOf(summary("2026-08-23"))), onBack = { back++ })

        compose.onNodeWithText("MUE").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, back)
    }

    @Test
    fun anEmptyHistoryScreenSaysSoQuietly() {
        setHistory(historyState(emptyList()))

        compose.onNodeWithText(HISTORY_EMPTY).assertIsDisplayed()
    }

    // endregion

    // region harness

    private fun setDashboard(
        state: ActivityUiState,
        onLogActivity: () -> Unit = {},
        onSeeAll: () -> Unit = {},
        onOpenSession: (ActivityId) -> Unit = {},
    ) {
        compose.setContent {
            MueTheme {
                ActivityDashboardContent(
                    state = state,
                    onLogActivity = onLogActivity,
                    onSeeAll = onSeeAll,
                    onOpenSession = onOpenSession,
                )
            }
        }
    }

    private fun setHistory(
        state: ActivityHistoryUiState,
        onBack: () -> Unit = {},
        onOpenSession: (ActivityId) -> Unit = {},
    ) {
        compose.setContent {
            MueTheme {
                ActivityHistoryContent(
                    state = state,
                    onBack = onBack,
                    onOpenSession = onOpenSession,
                )
            }
        }
    }

    private fun dashboardState(
        recent: List<ActivitySummary>,
        weekSummaries: List<ActivitySummary> = recent,
        totalSessionCount: Int = recent.size,
    ): ActivityUiState = previewDashboardState(
        recent = recent,
        weekSummaries = weekSummaries,
        totalSessionCount = totalSessionCount,
        today = TODAY,
    )

    private fun historyState(sessions: List<ActivitySummary>): ActivityHistoryUiState =
        previewHistoryState(sessions, today = TODAY)

    /** The prototype's own week: a walk, a strength session and a ride. */
    private fun week(): List<ActivitySummary> = listOf(
        summary("2026-08-23", minutes = 45, distanceMetres = 4_200, energyKcal = 280),
        summary(
            "2026-08-20",
            minutes = 55,
            label = "Strength training",
            movement = Movement.STRENGTH_TRAINING,
            setCount = 12,
            energyKcal = 320,
            id = "strength",
        ),
        summary(
            "2026-08-18",
            minutes = 38,
            label = "Cycling",
            movement = Movement.CYCLING,
            distanceMetres = 14_800,
            energyKcal = 410,
            startedAtTime = LocalTime.of(18, 30),
            id = "ride",
        ),
    )

    private fun weekWindow() =
        WeeklyActivitySummary.weekOf(TODAY)

    private fun durationOf(value: Int): ActivityDuration =
        requireNotNull(ActivityDuration.ofHoursAndMinutesOrNull(0, value))

    private fun summary(
        isoDate: String,
        minutes: Int = 45,
        label: String = "Treadmill walk",
        movement: Movement = Movement.WALKING,
        distanceMetres: Int? = null,
        setCount: Int? = null,
        energyKcal: Int? = null,
        startedAtTime: LocalTime? = null,
        id: String = "session-$isoDate",
    ): ActivitySummary = ActivitySummary(
        id = ActivityId(id),
        label = label,
        movement = movement,
        startedOn = LocalDate.parse(isoDate),
        startedAtTime = startedAtTime,
        duration = durationOf(minutes),
        distanceMetres = distanceMetres,
        validSetCount = setCount,
        estimatedEnergyKcal = energyKcal,
    )

    // endregion
}
