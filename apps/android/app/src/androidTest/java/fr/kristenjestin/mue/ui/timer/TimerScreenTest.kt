package fr.kristenjestin.mue.ui.timer

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Compose coverage of the timer screen (PRD_ACTIVITY_TIMER 6.3, 11 and FR-TIMER-004, 005, 009).
 *
 * Driven through the stateless content composable, so the assertions are about what reaches the
 * screen rather than about how the ViewModel got there. Expected strings go through
 * [TimerFormat] rather than being spelled out: the clock reading follows the language of the
 * device running the test.
 */
class TimerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // region what a running timer shows

    /** PRD 6.3: the label, the chronometer, the start time, the state and the two actions. */
    @Test
    fun aRunningTimerReadsEverythingTheScreenPromises() {
        setTimer(state(TimedDraftStatus.RUNNING))

        compose.onNodeWithTag(TimerTestTags.ACTIVITY_LABEL).assertTextEquals("Treadmill walk")
        compose.onNodeWithText("Indoor · Treadmill").assertIsDisplayed()
        compose.onNodeWithTag(TimerTestTags.ELAPSED)
            .assertTextEquals(TimerFormat.elapsed(duration()))
        compose.onNodeWithTag(TimerTestTags.STARTED_AT)
            .assertTextEquals(TimerFormat.startedAt(START_TIME))
        compose.onNodeWithTag(TimerTestTags.STATUS).assertIsDisplayed()
        compose.onNodeWithText(TimerMessages.ACTIVE).assertIsDisplayed()
        compose.onNodeWithText(TimerMessages.PAUSE).assertIsDisplayed()
        compose.onNodeWithText(TimerMessages.FINISH).assertIsDisplayed()
    }

    /** PRD 18: `Paused`, never `Active time`, and the principal action offers the opposite. */
    @Test
    fun aPausedTimerOffersResume() {
        setTimer(state(TimedDraftStatus.PAUSED))

        compose.onNodeWithText(TimerMessages.PAUSED).assertIsDisplayed()
        compose.onNodeWithText(TimerMessages.RESUME).assertIsDisplayed()
        compose.onNodeWithText(TimerMessages.PAUSE).assertDoesNotExist()
        compose.onNodeWithText("Active time").assertDoesNotExist()
    }

    // endregion

    // region accessibility (PRD 11)

    /**
     * The one rule that cannot be seen: a live region on the chronometer would have TalkBack
     * read a new figure out loud every second.
     */
    @Test
    fun theElapsedValueIsDescribedAndIsNeverALiveRegion() {
        setTimer(state(TimedDraftStatus.RUNNING))

        compose.onNodeWithTag(TimerTestTags.ELAPSED).assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion),
        )
        compose.onNodeWithContentDescription(
            TimerFormat.elapsedDescription(TimedDraftStatus.RUNNING, duration()),
        ).assertExists()
    }

    /** The status word is the live region, and the only one the timer keeps open. */
    @Test
    fun theStatusWordIsTheLiveRegion() {
        setTimer(state(TimedDraftStatus.RUNNING))

        compose.onNodeWithTag(TimerTestTags.STATUS).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    /** PRD 11: both actions carry a text label beside their glyph, in a 48 dp target. */
    @Test
    fun bothActionsAreLabelledAndClearTheTouchMinimum() {
        setTimer(state(TimedDraftStatus.RUNNING))

        compose.onNodeWithTag(TimerTestTags.PRIMARY_ACTION)
            .assertHeightIsAtLeast(MueMinTouchTarget)
        compose.onNodeWithTag(TimerTestTags.FINISH).assertHeightIsAtLeast(MueMinTouchTarget)
        compose.onNodeWithTag(TimerTestTags.OVERFLOW).assertHeightIsAtLeast(MueMinTouchTarget)
    }

    // endregion

    // region the transitions

    @Test
    fun theActionsReachTheirCallbacks() {
        var paused = 0
        var finished = 0
        setTimer(
            state(TimedDraftStatus.RUNNING),
            TimerScreenActions(onTogglePause = { paused++ }, onFinish = { finished++ }),
        )

        compose.onNodeWithTag(TimerTestTags.PRIMARY_ACTION).performClick()
        compose.onNodeWithTag(TimerTestTags.FINISH).performClick()

        assertEquals(1, paused)
        assertEquals(1, finished)
    }

    /** PRD 6.3: `Discard timer` is the overflow's only entry, and never a first-class action. */
    @Test
    fun discardHidesBehindTheOverflow() {
        var requested = 0
        setTimer(
            state(TimedDraftStatus.RUNNING),
            TimerScreenActions(onRequestDiscard = { requested++ }),
        )

        compose.onNodeWithText(TimerMessages.DISCARD_TIMER).assertDoesNotExist()

        compose.onNodeWithTag(TimerTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(TimerTestTags.DISCARD_TIMER).assertIsDisplayed()
        compose.onNodeWithTag(TimerTestTags.DISCARD_TIMER).performClick()

        assertEquals(1, requested)
    }

    /** FR-TIMER-009, word for word, and `Keep timer` is the safe answer. */
    @Test
    fun theConfirmationAsksBeforeItDestroysAMeasuredDuration() {
        var kept = 0
        var discarded = 0
        setTimer(
            state(TimedDraftStatus.RUNNING, discardVisible = true),
            TimerScreenActions(
                onCancelDiscard = { kept++ },
                onConfirmDiscard = { discarded++ },
            ),
        )

        compose.onNodeWithTag(TimerTestTags.DISCARD_DIALOG).assertExists()
        compose.onNodeWithText(TimerMessages.DISCARD_TIMER_TITLE).assertIsDisplayed()
        compose.onNodeWithText(TimerMessages.DISCARD_TIMER_BODY).assertIsDisplayed()

        compose.onNodeWithTag(TimerTestTags.KEEP_TIMER).performClick()
        assertEquals(1, kept)
        assertEquals(0, discarded)
    }

    @Test
    fun theConfirmationDiscardsWhenItIsConfirmed() {
        var discarded = 0
        setTimer(
            state(TimedDraftStatus.RUNNING, discardVisible = true),
            TimerScreenActions(onConfirmDiscard = { discarded++ }),
        )

        compose.onNodeWithTag(TimerTestTags.CONFIRM_DISCARD).performClick()

        assertEquals(1, discarded)
    }

    /** FR-TIMER-009's other wording, for a draft whose time is already measured and stopped. */
    @Test
    fun aStoppedDraftIsNamedAsADraft() {
        setTimer(state(TimedDraftStatus.PENDING_REVIEW, discardVisible = true))

        compose.onNodeWithText(TimerMessages.DISCARD_DRAFT_TITLE).assertIsDisplayed()
    }

    // endregion

    // region the notice (contract decision 1)

    @Test
    fun aNoticeLandsOnTheTimersOwnStatusLine() {
        setTimer(state(TimedDraftStatus.PAUSED, notice = TimerNotice.CHECK_ACTIVITY_TIME))

        compose.onNodeWithTag(TimerTestTags.NOTICE)
            .assertTextEquals(TimerMessages.CHECK_ACTIVITY_TIME)
    }

    /** FR-TIMER-002: opening the timer that already exists announces itself here. */
    @Test
    fun aSecondStartIsAnnouncedOnTheTimer() {
        setTimer(state(TimedDraftStatus.RUNNING, notice = TimerNotice.ALREADY_IN_PROGRESS))

        compose.onNodeWithTag(TimerTestTags.NOTICE)
            .assertTextEquals(TimerMessages.ALREADY_IN_PROGRESS)
    }

    /** Carried forward from phase 3: a failed transition no longer says nothing at all. */
    @Test
    fun aFailedTransitionSaysSo() {
        setTimer(state(TimedDraftStatus.RUNNING, notice = TimerNotice.TRANSITION_FAILED))

        compose.onNodeWithTag(TimerTestTags.NOTICE)
            .assertTextEquals(TimerMessages.TRANSITION_FAILED)
    }

    @Test
    fun noNoticeMeansNoLine() {
        setTimer(state(TimedDraftStatus.RUNNING))

        compose.onNodeWithTag(TimerTestTags.NOTICE).assertDoesNotExist()
    }

    // endregion

    // region harness

    private fun setTimer(
        state: TimerUiState,
        actions: TimerScreenActions = TimerScreenActions(),
    ) {
        compose.setContent {
            MueTheme { TimerScreenContent(state = state, actions = actions) }
        }
    }

    private fun state(
        status: TimedDraftStatus,
        notice: TimerNotice? = null,
        discardVisible: Boolean = false,
    ): TimerUiState = TimerUiState(
        timer = live(status),
        notice = notice,
        isLoading = false,
        discardConfirmationVisible = discardVisible,
    )

    private fun live(status: TimedDraftStatus): LiveTimerUiState {
        val draft = TimedActivityDraft(
            id = TimedDraftId("timer-under-test"),
            status = status,
            movement = Movement.WALKING,
            startedAtMillis = 0L,
            startedOn = LocalDate.of(2026, 8, 24),
            startedAtLocalTime = START_TIME,
            environment = ActivityEnvironment.INDOOR,
            equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
        )
        val elapsed = duration()
        return LiveTimerUiState(
            draft = draft,
            elapsed = elapsed,
            basis = null,
            isIncoherent = false,
            activityLabel = TimerFormat.activityLabel(
                movement = draft.movement,
                equipment = draft.equipment,
            ),
            contextLabel = TimerFormat.context(draft.environment, draft.equipment),
            elapsedText = TimerFormat.elapsed(elapsed),
            elapsedDescription = TimerFormat.elapsedDescription(status, elapsed),
            startedAtText = TimerFormat.startedAt(draft.startedAtLocalTime),
            statusLabel = TimerFormat.statusLabel(status),
            primaryActionLabel = TimerFormat.primaryAction(status),
            bannerValue = TimerFormat.bannerValue(status, elapsed),
        )
    }

    private fun duration(): ActivityDuration =
        requireNotNull(ActivityDuration.ofSecondsOrNull(1_543))

    private companion object {
        val START_TIME: LocalTime = LocalTime.of(18, 32, 47)
    }

    // endregion
}
