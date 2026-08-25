package fr.kristenjestin.mue.ui.timer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.ui.activity.ActivityTestTags
import fr.kristenjestin.mue.ui.activity.LogActivityMessages
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 8, 24)

/**
 * Compose coverage of the choice screen (PRD_ACTIVITY_TIMER 6.2 and FR-TIMER-012).
 *
 * Driven through the stateless content composable and its state holder, so nothing here needs a
 * database, a notification permission or a running timer behind it.
 */
class StartActivityScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** PRD 6.2: all six choices visible, with no hidden horizontal gesture. */
    @Test
    fun theSixPresetsAreAllOnScreenAtOnce() {
        setStartScreen()

        compose.onNodeWithTag(TimerTestTags.PRESET_ROW).assertIsDisplayed()
        ActivityPreset.entries.forEach { preset ->
            compose.onNodeWithTag(TimerTestTags.preset(preset.id)).assertIsDisplayed()
        }
    }

    /** PRD 6.2 forbids a recall of the last session; nothing here mentions one. */
    @Test
    fun nothingRecallsThePreviousSession() {
        setStartScreen()

        compose.onNodeWithText("Last timed activity").assertDoesNotExist()
        compose.onNodeWithText("Use again").assertDoesNotExist()
        compose.onNodeWithText(TimerMessages.START_AGAIN).assertDoesNotExist()
    }

    /**
     * Contract decision 3: the card names what is about to start, which is not a recall.
     *
     * Read through the context line rather than the name: the name is also the label of the
     * tile that was just tapped, and `Not set` belongs to the card alone.
     */
    @Test
    fun theSummaryCardNamesWhatTheNextTapWillStart() {
        setStartScreen()

        compose.onNodeWithTag(TimerTestTags.READY_CARD).assertIsDisplayed()
        compose.onNodeWithText(TimerMessages.READY_TO_START).assertIsDisplayed()
        compose.onNodeWithText("Indoor · Treadmill").assertIsDisplayed()

        compose.onNodeWithTag(TimerTestTags.preset(ActivityPreset.CYCLING.id)).performClick()

        compose.onNodeWithText("Indoor · Treadmill").assertDoesNotExist()
        compose.onNodeWithText(ActivityEnvironment.UNKNOWN.displayName).assertIsDisplayed()
    }

    /** PRD 6.2: the builder belongs to `Other` and to nothing else. */
    @Test
    fun theBuilderOnlyOpensForOther() {
        setStartScreen()

        compose.onNodeWithTag(ActivityTestTags.MOVEMENT_PICKER).assertDoesNotExist()

        compose.onNodeWithTag(TimerTestTags.preset(ActivityPreset.OTHER.id)).performClick()

        // Existence rather than display: the builder is taller than one screen, which is why
        // the column scrolls and the action is pinned over it.
        compose.onNodeWithTag(ActivityTestTags.MOVEMENT_PICKER).assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.ENVIRONMENT_PICKER).assertExists()
        compose.onNodeWithTag(ActivityTestTags.EQUIPMENT_PICKER).assertExists()
    }

    /** PRD 6.2 collects nothing a timer cannot measure yet. */
    @Test
    fun nothingIsAskedThatTheTimerCannotKnow() {
        setStartScreen()

        compose.onNodeWithTag(ActivityTestTags.EFFORT_SLIDER).assertDoesNotExist()
        compose.onNodeWithTag(ActivityTestTags.NOTES_FIELD).assertDoesNotExist()
        compose.onNodeWithTag(ActivityTestTags.DURATION_HOURS_FIELD).assertDoesNotExist()
        compose.onNodeWithTag(ActivityTestTags.DATE_FIELD).assertDoesNotExist()
    }

    @Test
    fun startTimerFiresWithAPreset() {
        var started = 0
        setStartScreen(onStart = { started++ })

        compose.onNodeWithTag(TimerTestTags.START_TIMER).performClick()

        assertEquals(1, started)
    }

    /** FR-ACTIVITY-008: the builder needs an activity before anything can be written. */
    @Test
    fun theBuilderRefusesToStartUntilAnActivityIsChosen() {
        var started = 0
        setStartScreen(onStart = { started++ })

        compose.onNodeWithTag(TimerTestTags.preset(ActivityPreset.OTHER.id)).performClick()
        compose.onNodeWithTag(TimerTestTags.START_TIMER).performClick()

        assertEquals(0, started)
        compose.onNodeWithText(LogActivityMessages.MOVEMENT_REQUIRED).assertExists()
    }

    /** Contract decision 4: `Start again` reopens the builder already filled. */
    @Test
    fun startAgainOpensTheBuilderFilledAndStartsNothingOnItsOwn() {
        var started = 0
        val state = StartActivityState.of(
            StartTimerRequest(
                movement = Movement.YOGA,
                environment = ActivityEnvironment.INDOOR,
                equipment = listOf(SessionEquipment(EquipmentType.YOGA_MAT)),
            ),
        )
        setStartScreen(state, onStart = { started++ })

        assertEquals("section 16: it opens the screen, it never starts", 0, started)
        compose.onNodeWithTag(ActivityTestTags.MOVEMENT_PICKER).assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.equipmentChip(0)).assertExists()
        // The chip's own name; the card's context line reads `Indoor · Yoga mat` instead.
        compose.onNodeWithText(EquipmentType.YOGA_MAT.displayName).assertExists()
    }

    /** FR-TIMER-012: a short explanation before the permission is ever asked for. */
    @Test
    fun theRationaleIsShownWhileNotificationsAreMissing() {
        setStartScreen(showRationale = true)

        compose.onNodeWithTag(TimerTestTags.NOTIFICATION_RATIONALE).assertIsDisplayed()
        compose.onNodeWithText(TimerMessages.NOTIFICATION_RATIONALE).assertIsDisplayed()
    }

    @Test
    fun theRationaleGoesOnceNotificationsAreAllowed() {
        setStartScreen(showRationale = false)

        compose.onNodeWithTag(TimerTestTags.NOTIFICATION_RATIONALE).assertDoesNotExist()
    }

    private fun setStartScreen(
        state: StartActivityState = StartActivityState(),
        showRationale: Boolean = false,
        onBack: () -> Unit = {},
        onStart: () -> Unit = {},
    ) {
        compose.setContent {
            MueTheme {
                StartActivityContent(
                    state = state,
                    today = TODAY,
                    showNotificationRationale = showRationale,
                    onBack = onBack,
                    onStart = onStart,
                )
            }
        }
    }
}
