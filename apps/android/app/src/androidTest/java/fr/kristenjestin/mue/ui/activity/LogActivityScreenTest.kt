package fr.kristenjestin.mue.ui.activity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import fr.kristenjestin.mue.domain.logic.ActivityValidation
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.ui.advanceToTheQuietButton
import fr.kristenjestin.mue.ui.field
import fr.kristenjestin.mue.ui.components.MueSaveConfirmationLabel
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val WAIT_MILLIS = 10_000L

/** As many equipment rows as the real catalogue has, which is what pushed the notice away. */
private const val CATALOGUE_ROWS = 14

/**
 * Compose coverage of PRD FR-ACTIVITY-004 to 011.
 *
 * The three flows the module lives or dies by — creating a session, changing your mind about
 * the preset halfway through, and deleting — are driven through the real screen and the real
 * ViewModel, so what they prove is that the wiring holds. The panels that only appear in one
 * state are driven through the stateless [LogActivityContent] instead.
 */
class LogActivityScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // region the real screen

    @Test
    fun aTreadmillWalkIsCreatedFromEndToEnd() {
        var saved = false
        realScreen(onSaved = { saved = true })

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.TREADMILL_WALK.id))
            .assertIsSelected()

        input(ActivityTestTags.DURATION_MINUTES_FIELD).performTextInput("45")
        input(ActivityTestTags.metricField(MetricKind.DISTANCE.id))
            .performScrollTo()
            .performTextInput("4.2")
        input(ActivityTestTags.metricField(MetricKind.ESTIMATED_ENERGY.id))
            .performScrollTo()
            .performTextInput("280")

        compose.onNodeWithTag(ActivityTestTags.SAVE_BUTTON).performClick()

        // The write, the confirmation and only then the return (contract decision 8).
        compose.waitUntil(WAIT_MILLIS) { saved }
        assertTrue(saved)
    }

    @Test
    fun switchingPresetsMidFormLosesNothing() {
        realScreen()

        input(ActivityTestTags.DURATION_MINUTES_FIELD).performTextInput("45")
        input(ActivityTestTags.metricField(MetricKind.INCLINE.id))
            .performScrollTo()
            .performTextInput("2.5")

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.RUN.id)).performScrollTo()
            .performClick()
        compose.waitForIdle()

        // The common fields are untouched, and the run's own measurements start empty.
        input(ActivityTestTags.DURATION_MINUTES_FIELD).assertTextContains("45")
        compose.onNodeWithText(LogActivityMessages.detailsTitle(ActivityPreset.RUN))
            .assertIsDisplayed()

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.TREADMILL_WALK.id))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        input(ActivityTestTags.metricField(MetricKind.INCLINE.id))
            .performScrollTo()
            .assertTextContains("2.5")
        input(ActivityTestTags.DURATION_MINUTES_FIELD).assertTextContains("45")
    }

    @Test
    fun aMissingDurationIsReportedOnTheFieldAndBesideTheAction() {
        realScreen()

        compose.onNodeWithTag(ActivityTestTags.SAVE_BUTTON).performClick()
        compose.waitForIdle()

        // Once on the field, once beside the action (PRD 12).
        assertEquals(2, nodesReading(ActivityValidation.DURATION_ERROR))
    }

    @Test
    fun theBuilderChoosesAnActivityFromTheCatalogue() {
        realScreen()

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.OTHER.id)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ActivityTestTags.MOVEMENT_PICKER).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(LogActivityMessages.ACTIVITY_PICKER_TITLE).assertIsDisplayed()
        compose.onNodeWithText(Movement.YOGA.displayName).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(ActivityTestTags.MOVEMENT_PICKER)
            .assertTextContains(Movement.YOGA.displayName)
    }

    // endregion

    // region panels that only exist in one state

    @Test
    fun deletingAsksForConfirmationBeforeItCascades() {
        var confirmed = false
        val state = mutableStateOf(LogActivityUiState(isEditing = true, minutes = "45"))
        content(
            state = state,
            actions = LogActivityActions(
                onRequestDelete = { state.value = state.value.copy(deleteConfirmationVisible = true) },
                onConfirmDelete = { confirmed = true },
            ),
        )

        compose.onNodeWithTag(ActivityTestTags.DELETE_BUTTON).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(DELETE_CONFIRMATION_TITLE).assertIsDisplayed()
        compose.onNodeWithText(DELETE_CONFIRMATION_BODY).assertIsDisplayed()
        compose.onNodeWithText(CANCEL).assertIsDisplayed()
        compose.onNodeWithText(DELETE_CONFIRM).performClick()

        assertTrue(confirmed)
    }

    @Test
    fun theDeleteDialogCanBeDismissedWithoutDeletingAnything() {
        var confirmed = false
        var cancelled = false
        content(
            state = LogActivityUiState(
                isEditing = true,
                minutes = "45",
                deleteConfirmationVisible = true,
            ),
            actions = LogActivityActions(
                onConfirmDelete = { confirmed = true },
                onCancelDelete = { cancelled = true },
            ),
        )

        compose.onNodeWithText(CANCEL).performClick()

        assertTrue(cancelled)
        assertTrue(!confirmed)
    }

    @Test
    fun droppingAStoredDetailedSessionToQuickNamesWhatItCosts() {
        var confirmed = false
        content(
            state = LogActivityUiState(
                isEditing = true,
                preset = ActivityPreset.STRENGTH_TRAINING,
                detailed = true,
                storedExerciseCount = 3,
                quickLogConfirmationVisible = true,
            ),
            actions = LogActivityActions(onConfirmQuickLog = { confirmed = true }),
        )

        compose.onNodeWithText(QUICK_LOG_CONFIRMATION_TITLE).assertIsDisplayed()
        compose.onNodeWithText(quickLogConfirmationBody(3)).assertIsDisplayed()
        compose.onNodeWithText(QUICK_LOG_CONFIRM).performClick()

        assertTrue(confirmed)
    }

    /** Contract decisions 1 and 8: the bare word, no glyph, and the return after it. */
    @Test
    fun theConfirmationSaysSavedAndTheReturnFollowsIt() {
        var returned = false
        compose.mainClock.autoAdvance = false
        content(
            state = LogActivityUiState(minutes = "45", justSaved = true),
            actions = LogActivityActions(onSaved = { returned = true }),
        )

        compose.advanceToTheQuietButton()
        compose.onNodeWithText(MueSaveConfirmationLabel).assertIsDisplayed()
        assertTrue(!returned)

        compose.mainClock.autoAdvance = true
        compose.waitUntil(WAIT_MILLIS) { returned }
        assertTrue(returned)
    }

    @Test
    fun aStorageFailureExplainsItselfAndOffersAnotherTry() {
        var retried = 0
        content(
            state = LogActivityUiState(
                minutes = "45",
                saveError = LogActivityMessages.SAVE_FAILED,
            ),
            actions = LogActivityActions(onSave = { retried++ }),
        )

        compose.onNodeWithText(LogActivityMessages.SAVE_FAILED).assertIsDisplayed()
        compose.onNodeWithText(LogActivityMessages.TRY_AGAIN).performClick()

        assertTrue(retried == 1)
    }

    /** PRD 9.1: the quick strength log records equipment, and only the builder used to. */
    @Test
    fun theQuickStrengthLogOffersEquipment() {
        realScreen()

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.STRENGTH_TRAINING.id))
            .performClick()

        compose.onNodeWithTag(ActivityTestTags.EQUIPMENT_PICKER)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(LogActivityMessages.CHOOSE_EQUIPMENT).assertIsDisplayed()
    }

    /**
     * The seam of the build contract's section 5, driven end to end: both screens read and write
     * one draft through the adapter, so a duration typed on the form is the editor's duration.
     */
    @Test
    fun theStrengthEditorEditsTheDraftTheFormIsShowing() {
        val editorOpen = mutableStateOf(false)
        compose.setContent {
            MueTheme {
                val model = logActivityViewModel()
                if (editorOpen.value) {
                    StrengthSessionScreen(
                        onBack = { editorOpen.value = false },
                        onSaved = {},
                        modifier = Modifier.fillMaxSize(),
                        state = rememberSharedStrengthSessionState(model),
                    )
                } else {
                    LogActivityScreen(
                        sessionId = null,
                        onBack = {},
                        onOpenStrengthSession = { editorOpen.value = true },
                        onSaved = {},
                        onDeleted = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        input(ActivityTestTags.DURATION_MINUTES_FIELD).performTextInput("45")
        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.STRENGTH_TRAINING.id))
            .performClick()
        compose.onNodeWithTag(ActivityTestTags.DETAILED_LOG).performScrollTo().performClick()

        compose.waitUntil(WAIT_MILLIS) { editorOpen.value }
        compose.onNodeWithText(STRENGTH_SCREEN_TITLE).assertIsDisplayed()
        input(ActivityTestTags.DURATION_MINUTES_FIELD).assertTextContains("45")
    }

    /** A refusal nobody can see is a refusal nobody understands (PRD FR-ACTIVITY-008). */
    @Test
    fun aRefusedDuplicateIsExplainedWhereTheListIs() {
        content(
            state = LogActivityUiState(
                preset = ActivityPreset.OTHER,
                picker = CatalogPickerState(
                    target = CatalogTarget.EQUIPMENT,
                    results = List(CATALOGUE_ROWS) { index ->
                        CatalogEntry("id-$index", "Equipment $index", "Meta $index")
                    },
                    notice = LogActivityMessages.ALREADY_ADDED,
                ),
            ),
            actions = LogActivityActions(),
        )

        compose.onNodeWithText(LogActivityMessages.ALREADY_ADDED).assertIsDisplayed()
    }

    @Test
    fun everyPresetIsOnScreenWithoutAHiddenGesture() {
        content(state = LogActivityUiState(), actions = LogActivityActions())

        ActivityPreset.entries.forEach { preset ->
            compose.onNodeWithTag(ActivityTestTags.preset(preset.id)).assertIsDisplayed()
        }
    }

    // endregion

    // region harness

    private fun nodesReading(text: String): Int =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /** The tag sits on the field container for a text field, on the box for a clock pair. */
    private fun input(tag: String): SemanticsNodeInteraction = compose.field(tag)

    private fun realScreen(
        onBack: () -> Unit = {},
        onOpenStrengthSession: () -> Unit = {},
        onSaved: () -> Unit = {},
        onDeleted: () -> Unit = {},
    ) {
        compose.setContent {
            MueTheme {
                LogActivityScreen(
                    sessionId = null,
                    onBack = onBack,
                    onOpenStrengthSession = onOpenStrengthSession,
                    onSaved = onSaved,
                    onDeleted = onDeleted,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun content(state: LogActivityUiState, actions: LogActivityActions) =
        content(mutableStateOf(state), actions)

    private fun content(state: MutableState<LogActivityUiState>, actions: LogActivityActions) {
        compose.setContent {
            MueTheme {
                LogActivityContent(
                    state = state.value,
                    actions = actions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // endregion
}
