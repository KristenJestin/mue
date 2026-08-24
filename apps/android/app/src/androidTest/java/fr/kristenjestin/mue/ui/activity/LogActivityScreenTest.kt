package fr.kristenjestin.mue.ui.activity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso
import fr.kristenjestin.mue.domain.logic.ActivityValidation
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.ui.advanceToTheQuietButton
import fr.kristenjestin.mue.ui.field
import fr.kristenjestin.mue.ui.components.MueSaveConfirmationLabel
import fr.kristenjestin.mue.ui.components.MueWheelPickerDefaults
import fr.kristenjestin.mue.ui.setWheel
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.wheelValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime

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

        compose.setWheel(ActivityTestTags.DURATION_MINUTES_FIELD, 45)
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

        compose.setWheel(ActivityTestTags.DURATION_MINUTES_FIELD, 45)
        input(ActivityTestTags.metricField(MetricKind.INCLINE.id))
            .performScrollTo()
            .performTextInput("2.5")

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.RUN.id)).performScrollTo()
            .performClick()
        compose.waitForIdle()

        // The common fields are untouched, and the run's own measurements start empty.
        assertEquals(45, compose.wheelValue(ActivityTestTags.DURATION_MINUTES_FIELD))
        compose.onNodeWithText(LogActivityMessages.detailsTitle(ActivityPreset.RUN))
            .assertIsDisplayed()

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.TREADMILL_WALK.id))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        input(ActivityTestTags.metricField(MetricKind.INCLINE.id))
            .performScrollTo()
            .assertTextContains("2.5")
        assertEquals(45, compose.wheelValue(ActivityTestTags.DURATION_MINUTES_FIELD))
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

        compose.setWheel(ActivityTestTags.DURATION_MINUTES_FIELD, 45)
        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.STRENGTH_TRAINING.id))
            .performClick()
        compose.onNodeWithTag(ActivityTestTags.DETAILED_LOG).performScrollTo().performClick()

        compose.waitUntil(WAIT_MILLIS) { editorOpen.value }
        compose.onNodeWithText(STRENGTH_SCREEN_TITLE).assertIsDisplayed()
        assertEquals(45, compose.wheelValue(ActivityTestTags.DURATION_MINUTES_FIELD))
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

    // region The schedule row

    /**
     * The two fields sit side by side and have to read as one control, so they are measured
     * together rather than each to its own content. They used to be two different components —
     * a picker row beside a pair of number boxes — and ended a few dp apart.
     *
     * All three states of the row are checked, because the message under one of them is exactly
     * what used to push that half past the other.
     */
    @Test
    fun theDateAndTheStartTimeAreAlwaysTheSameHeight() {
        val state = mutableStateOf(LogActivityUiState())
        content(state, LogActivityActions())

        assertSameFieldHeight("with no time")

        state.value = state.value.copy(startTime = LocalTime.of(18, 30))
        compose.waitForIdle()
        assertSameFieldHeight("with a time")

        state.value = state.value.copy(dateError = ActivityValidation.DATE_ERROR)
        compose.waitForIdle()
        assertSameFieldHeight("with a message under the date")

        state.value = state.value.copy(
            dateError = null,
            startTimeError = LogActivityMessages.START_TIME_ERROR,
        )
        compose.waitForIdle()
        assertSameFieldHeight("with a message under the time")
    }

    /** PRD 12: an untouched optional value is never drawn as a plausible midnight. */
    @Test
    fun anUnsetStartTimeSaysSoRatherThanReadingAsMidnight() {
        content(state = LogActivityUiState(), actions = LogActivityActions())

        compose.onNodeWithTag(ActivityTestTags.START_TIME_FIELD)
            .assertTextContains(LogActivityMessages.NO_START_TIME)
        assertEquals(0, nodesReading("00:00"))
    }

    @Test
    fun theStartTimeFieldRaisesItsPanelAndTheDateRaisesItsOwn() {
        var timeOpened = 0
        var dateOpened = 0
        content(
            state = LogActivityUiState(),
            actions = LogActivityActions(
                onOpenTimePicker = { timeOpened++ },
                onOpenDatePicker = { dateOpened++ },
            ),
        )

        compose.onNodeWithTag(ActivityTestTags.START_TIME_FIELD).performClick()
        compose.onNodeWithTag(ActivityTestTags.DATE_FIELD).performClick()

        assertEquals(1, timeOpened)
        assertEquals(1, dateOpened)
    }

    /** PRD 8.2: the panel can always take the time back off, so null stays reachable. */
    @Test
    fun theStartTimePanelCanClearTheTimeItIsShowing() {
        var cleared = false
        var picked: LocalTime? = null
        content(
            state = LogActivityUiState(
                startTime = LocalTime.of(18, 30),
                timePickerVisible = true,
            ),
            actions = LogActivityActions(
                onStartTimeSelected = { time ->
                    picked = time
                    if (time == null) cleared = true
                },
            ),
        )

        compose.onNodeWithTag(ActivityTestTags.START_TIME_PICKER).assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.CLEAR_START_TIME).performClick()

        assertTrue(cleared)
        assertEquals(null, picked)
    }

    /** Nothing to clear when nothing is set: the action would claim a state that already holds. */
    @Test
    fun theStartTimePanelOffersNoClearWhenNoTimeIsSet() {
        content(
            state = LogActivityUiState(timePickerVisible = true),
            actions = LogActivityActions(),
        )

        compose.onNodeWithTag(ActivityTestTags.START_TIME_PICKER).assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.CONFIRM_START_TIME).assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.CLEAR_START_TIME).assertDoesNotExist()
    }

    // endregion

    // region The duration wheels

    /** PRD_ACTIVITIES 15: the wheel is an adjustable control and never a gesture-only one. */
    @Test
    fun eachDurationWheelIsAnAdjustableControlAnyAssistiveServiceCanMove() {
        realScreen()

        listOf(
            ActivityTestTags.DURATION_HOURS_FIELD to LogActivityMessages.DURATION_HOURS_LABEL,
            ActivityTestTags.DURATION_MINUTES_FIELD to LogActivityMessages.DURATION_MINUTES_LABEL,
        ).forEach { (tag, label) ->
            compose.onNodeWithTag(tag)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)),
                )
        }

        compose.setWheel(ActivityTestTags.DURATION_HOURS_FIELD, 1)
        compose.setWheel(ActivityTestTags.DURATION_MINUTES_FIELD, 45)

        compose.onNodeWithTag(ActivityTestTags.DURATION_HOURS_FIELD)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 hour"))
        compose.onNodeWithTag(ActivityTestTags.DURATION_MINUTES_FIELD)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "45 minutes"))
    }

    /** Every row of the wheel is the touch target PRD 15 sets, and the wheel is three of them. */
    @Test
    fun aDurationWheelIsBuiltOfFortyEightDpRows() {
        realScreen()

        val height = compose.onNodeWithTag(ActivityTestTags.DURATION_MINUTES_FIELD)
            .getBoundsInRoot()
            .height
        assertEquals(
            MueWheelPickerDefaults.RowHeight.value * MueWheelPickerDefaults.VisibleRows,
            height.value,
            1f,
        )
        assertEquals(48f, MueWheelPickerDefaults.RowHeight.value, 0f)
    }

    // endregion

    // region The equipment catalogue

    /**
     * The sheet says `Select one or more`, so a row is a switch and the panel stays open around
     * it. Three additions and one removal, without the panel leaving once.
     */
    @Test
    fun theEquipmentSheetAccumulatesSelectionsWithoutClosing() {
        realScreen()

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.OTHER.id)).performClick()
        compose.onNodeWithTag(ActivityTestTags.EQUIPMENT_PICKER).performScrollTo().performClick()

        listOf(EquipmentType.YOGA_MAT, EquipmentType.KETTLEBELL, EquipmentType.RESISTANCE_BANDS)
            .forEach { type ->
                catalogRow(type).performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithText(LogActivityMessages.EQUIPMENT_PICKER_TITLE)
                    .assertIsDisplayed()
                catalogRow(type).assertIsSelected()
            }

        // Tapping a chosen row takes it back off, and the panel still does not leave.
        catalogRow(EquipmentType.KETTLEBELL).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LogActivityMessages.EQUIPMENT_PICKER_TITLE).assertIsDisplayed()
        catalogRow(EquipmentType.KETTLEBELL).assertIsNotSelected()

        // It closes on a back press, one of the three ways out it has (× and a drag are the others).
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(LogActivityMessages.EQUIPMENT_PICKER_TITLE).assertDoesNotExist()

        compose.onNodeWithTag(ActivityTestTags.equipmentChip(0)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.equipmentChip(1)).assertIsDisplayed()
        compose.onNodeWithTag(ActivityTestTags.equipmentChip(2)).assertDoesNotExist()
    }

    /** The movement is a single choice, so its own sheet still leaves on the row that answers it. */
    @Test
    fun theMovementSheetStillClosesOnThePick() {
        realScreen()

        compose.onNodeWithTag(ActivityTestTags.preset(ActivityPreset.OTHER.id)).performClick()
        compose.onNodeWithTag(ActivityTestTags.MOVEMENT_PICKER).performScrollTo().performClick()
        compose.onNodeWithText(Movement.YOGA.displayName).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(LogActivityMessages.ACTIVITY_PICKER_TITLE).assertDoesNotExist()
        compose.onNodeWithText(Movement.YOGA.displayName).assertIsDisplayed()
    }

    // endregion

    // region harness

    private fun nodesReading(text: String): Int =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /**
     * One row of the open catalogue sheet, told apart from the chip of the same name behind it.
     *
     * The tag lands on the row's container so that its divider travels with it; the selectable
     * part — the one that answers `assertIsSelected` — is the row inside.
     */
    private fun catalogRow(type: EquipmentType): SemanticsNodeInteraction = compose.onNode(
        isSelectable() and hasAnyAncestor(hasTestTag(ActivityTestTags.catalogEntry(type.id))),
    )

    /** The two halves of the schedule row measure the same, whatever either of them is showing. */
    private fun assertSameFieldHeight(state: String) {
        val date = compose.onNodeWithTag(ActivityTestTags.DATE_FIELD).getBoundsInRoot()
        val time = compose.onNodeWithTag(ActivityTestTags.START_TIME_FIELD).getBoundsInRoot()
        assertEquals("height $state", date.height.value, time.height.value, 0.5f)
        assertEquals("top $state", date.top.value, time.top.value, 0.5f)
    }

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
