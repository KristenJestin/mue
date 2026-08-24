package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.espresso.Espresso
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.ui.advanceToTheQuietButton
import fr.kristenjestin.mue.ui.components.MueSaveConfirmationLabel
import fr.kristenjestin.mue.ui.components.MueStickyActionRamp
import fr.kristenjestin.mue.ui.field
import fr.kristenjestin.mue.ui.setWheel
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.wheelValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The detailed strength editor, driven through the real `StrengthDraftEditor` so that a tap on
 * screen and the rule it stands for are checked together (PRD FR-ACTIVITY-009).
 *
 * Nothing here reaches the database: the catalogue and the last performances are supplied, and
 * the draft lives in the test, exactly as the log form's ViewModel will supply them.
 */
class StrengthSessionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 8, 23)

    private val squat = ExerciseDefinition(
        id = ExerciseDefinitionId("squat"),
        name = "Barbell squat",
        trackingMode = TrackingMode.WEIGHT_AND_REPS,
        equipment = EquipmentType.BARBELL,
    )

    private val plank = ExerciseDefinition(
        id = ExerciseDefinitionId("plank"),
        name = "Plank",
        trackingMode = TrackingMode.DURATION,
        equipment = EquipmentType.BODYWEIGHT,
    )

    private val pullUp = ExerciseDefinition(
        id = ExerciseDefinitionId("pull-up"),
        name = "Pull-up",
        trackingMode = TrackingMode.REPS_ONLY,
        equipment = EquipmentType.BODYWEIGHT,
    )

    private val catalogue = listOf(squat, plank, pullUp)

    private var draft = ActivityDraft()
    private var saves = 0
    private var backs = 0
    private var returns = 0

    private fun setScreen(
        initial: ActivityDraft = ActivityDraft(
            presetId = ActivityPreset.STRENGTH_TRAINING.id,
            hours = "1",
            minutes = "05",
            detailed = true,
        ),
        catalogue: List<ExerciseDefinition> = this.catalogue,
        lastPerformances: Map<String, LastPerformance> = emptyMap(),
    ) {
        composeRule.setContent {
            var current by remember { mutableStateOf(initial) }
            var saved by remember { mutableStateOf(false) }
            draft = current
            MueTheme(reduceMotion = false) {
                StrengthSessionScreen(
                    draft = current,
                    catalogue = catalogue,
                    lastPerformances = lastPerformances,
                    saved = saved,
                    onEdit = { edit ->
                        current = StrengthDraftEditor.apply(current, edit)
                        draft = current
                    },
                    onSave = {
                        saves++
                        saved = true
                    },
                    onBack = { backs++ },
                    onSaved = { returns++ },
                    today = today,
                    locale = Locale.ENGLISH,
                )
            }
        }
    }

    private fun withOneSquat(): ActivityDraft = StrengthDraftEditor.apply(
        ActivityDraft(presetId = ActivityPreset.STRENGTH_TRAINING.id, hours = "1", detailed = true),
        StrengthEdit.AddExercise(squat),
    )

    // region The empty editor

    @Test
    fun theEditorOpensOnItsInvitation() {
        setScreen()

        composeRule.onNodeWithText(STRENGTH_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(NO_EXERCISE_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText("0 exercises · 0 sets").assertIsDisplayed()
    }

    /** PRD FR-ACTIVITY-009: a detailed session needs one valid set before it can be saved. */
    @Test
    fun aSessionWithoutAValidSetCannotBeSaved() {
        setScreen()

        composeRule.onNodeWithTag(ActivityTestTags.SAVE_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithText(NEEDS_A_VALID_SET).assertIsDisplayed()
    }

    @Test
    fun theBackControlLeavesTheEditor() {
        setScreen()

        composeRule.onNodeWithContentDescription("Back to the activity form").performClick()

        assertEquals(1, backs)
    }

    // endregion

    // region The picker

    @Test
    fun pickingFromTheCatalogueAddsTheExerciseWithOneEmptySet() {
        setScreen()

        composeRule.onNodeWithTag(ActivityTestTags.ADD_EXERCISE).performClick()
        composeRule.onNodeWithContentDescription(EXERCISE_SECTION_DEFAULT).assertIsDisplayed()
        composeRule.onNodeWithTag(exercisePickerRowTag(squat)).performClick()

        composeRule.onNodeWithText("Barbell squat").assertIsDisplayed()
        composeRule.onNodeWithText("1 exercise · 0 sets").assertIsDisplayed()
        assertEquals(SetDraft(), draft.exercises.single().sets.single())
    }

    @Test
    fun theSearchNarrowsTheCatalogueToWhatWasTyped() {
        setScreen()

        composeRule.onNodeWithTag(ActivityTestTags.ADD_EXERCISE).performClick()
        composeRule.onNodeWithContentDescription("Search the exercise catalogue")
            .performTextReplacement("plank")

        composeRule.onNodeWithContentDescription(EXERCISE_SECTION_RESULTS).assertIsDisplayed()
        composeRule.onNodeWithTag(exercisePickerRowTag(plank)).assertIsDisplayed()
        composeRule.onNodeWithTag(exercisePickerRowTag(squat)).assertDoesNotExist()
    }

    @Test
    fun aSearchThatFindsNothingSaysSoAndStillOffersToCreateIt() {
        setScreen()

        composeRule.onNodeWithTag(ActivityTestTags.ADD_EXERCISE).performClick()
        composeRule.onNodeWithContentDescription("Search the exercise catalogue")
            .performTextReplacement("Zercher squat")

        composeRule.onNodeWithText(EXERCISE_PICKER_EMPTY).assertIsDisplayed()

        // Typing raised the keyboard, and the panel is resized by it rather than panned over it.
        // A tap aimed before that settles lands where the footer used to be, which is what made
        // this the one test in the suite that failed only under load.
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EXERCISE_CREATE_TAG).performScrollTo().performClick()

        assertEquals("Zercher squat", draft.exercises.single().name)
        assertTrue(draft.exercises.single().isCustom)
    }

    /** PRD 9.2: the same name, whatever its case, never becomes a second definition. */
    @Test
    fun aNameAlreadyInTheCatalogueIsReusedRatherThanCreatedTwice() {
        setScreen()

        composeRule.onNodeWithTag(ActivityTestTags.ADD_EXERCISE).performClick()
        composeRule.onNodeWithContentDescription("Search the exercise catalogue")
            .performTextReplacement("  barbell SQUAT ")
        composeRule.onNodeWithTag(EXERCISE_CREATE_TAG).performScrollTo().performClick()

        assertEquals(squat.id.value, draft.exercises.single().definitionId)
        assertEquals("Barbell squat", draft.exercises.single().name)
    }

    @Test
    fun theCreateActionWaitsForANameBeforeItDoesAnything() {
        setScreen()

        composeRule.onNodeWithTag(ActivityTestTags.ADD_EXERCISE).performClick()

        composeRule.onNodeWithText(CREATE_PROMPT).assertIsNotEnabled()
    }

    // endregion

    // region Sets

    @Test
    fun addingASetAppendsAnEmptyRow() {
        setScreen(withOneSquat())

        composeRule.onNodeWithText("Add set").performScrollTo().performClick()

        assertEquals(2, draft.exercises.single().sets.size)
        assertEquals(SetDraft(), draft.exercises.single().sets.last())
    }

    @Test
    fun duplicatingRepeatsTheValuesOfTheSetBeforeIt() {
        setScreen(withOneSquat())

        composeRule.onNodeWithContentDescription("Set 1, Load in kilograms")
            .performScrollTo()
            .performTextReplacement("60")
        composeRule.onNodeWithContentDescription("Set 1, Repetitions")
            .performTextReplacement("8")
        composeRule.onNodeWithText("Duplicate last set").performScrollTo().performClick()

        val sets = draft.exercises.single().sets
        assertEquals(2, sets.size)
        assertEquals("60", sets.last().loadKg)
        assertEquals("8", sets.last().reps)
    }

    @Test
    fun aSetIsRemovedByItsOwnTarget() {
        setScreen(withOneSquat())

        composeRule.onNodeWithText("Add set").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Remove set 2 of Barbell squat")
            .performScrollTo()
            .performClick()

        assertEquals(1, draft.exercises.single().sets.size)
    }

    /** PRD 12: a value typed into a cell is the value the draft holds, comma and all. */
    @Test
    fun aTypedLoadReachesTheDraftExactlyAsItWasTyped() {
        setScreen(withOneSquat())

        composeRule.onNodeWithContentDescription("Set 1, Load in kilograms")
            .performScrollTo()
            .performTextReplacement("62,5")

        assertEquals("62,5", draft.exercises.single().sets.single().loadKg)
    }

    /** Contract decision 3: the effort column is offered only where a column is free. */
    @Test
    fun perSetEffortIsOfferedWhereNoLoadTakesTheColumn() {
        setScreen(StrengthDraftEditor.apply(ActivityDraft(detailed = true), StrengthEdit.AddExercise(pullUp)))

        composeRule.onNodeWithContentDescription(SET_EFFORT_CELL)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** The other half of decision 3, in its own test: a rule takes one screen per case. */
    @Test
    fun perSetEffortIsNotOfferedUnderALoad() {
        setScreen(withOneSquat())

        composeRule.onAllNodesWithContentDescription(SET_EFFORT_CELL).assertCountEquals(0)
    }

    // endregion

    // region Reordering

    @Test
    fun anExerciseMovesDownAndBackUpAgain() {
        setScreen(
            StrengthDraftEditor.apply(
                StrengthDraftEditor.apply(ActivityDraft(detailed = true), StrengthEdit.AddExercise(squat)),
                StrengthEdit.AddExercise(plank),
            ),
        )

        reveal("Move Barbell squat down").performClick()
        assertEquals(listOf("Plank", "Barbell squat"), draft.exercises.map { it.name })

        // The card has moved below the fold, and a lazy item off screen is not composed at
        // all: only scrolling the list itself can bring the control back.
        reveal("Move Barbell squat up").performClick()
        assertEquals(listOf("Barbell squat", "Plank"), draft.exercises.map { it.name })
    }

    @Test
    fun theEndsOfTheListAnnounceThatTheyCannotMove() {
        setScreen(withOneSquat())

        reveal("Move Barbell squat up").assertIsNotEnabled()
        reveal("Move Barbell squat down").assertIsNotEnabled()
    }

    // endregion

    // region The tracking mode

    /**
     * PRD 9.2 keeps the mode on the definition, so it can only be chosen while that definition
     * is still new — a catalogue exercise shows its mode and does not offer to change it.
     */
    @Test
    fun aCatalogueExerciseShowsItsModeWithoutOfferingToChangeIt() {
        setScreen(withOneSquat())

        composeRule.onNodeWithText(TrackingMode.WEIGHT_AND_REPS.label)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(
            "Tracking mode, ${TrackingMode.WEIGHT_AND_REPS.label}",
        ).assertCountEquals(0)
    }

    @Test
    fun aNewDefinitionCanStillChangeHowItsSetsAreTracked() {
        val custom = ExerciseDefinition(
            id = ExerciseDefinitionId("zercher"),
            name = "Zercher squat",
            trackingMode = TrackingMode.WEIGHT_AND_REPS,
            isCustom = true,
        )
        setScreen(
            StrengthDraftEditor.apply(
                ActivityDraft(detailed = true),
                StrengthEdit.AddExercise(custom),
            ),
        )

        composeRule.onNodeWithContentDescription("Set 1, Load in kilograms")
            .performScrollTo()
            .performTextReplacement("60")
        composeRule.onNodeWithContentDescription(
            "Tracking mode, ${TrackingMode.WEIGHT_AND_REPS.label}",
        ).performScrollTo().performClick()
        composeRule.onNodeWithText(TRACKING_MODE_SHEET_TITLE).assertIsDisplayed()
        composeRule.onAllNodesWithText(TrackingMode.REPS_ONLY.label).onFirst().performClick()

        assertEquals(TrackingMode.REPS_ONLY.id, draft.exercises.single().trackingModeId)
        assertEquals("", draft.exercises.single().sets.single().loadKg)
    }

    // endregion

    // region The last performance

    /** PRD 11.4, rendered under the name of an exercise already practised. */
    @Test
    fun anExerciseAlreadyPractisedQuotesItsLastSet() {
        setScreen(
            initial = withOneSquat(),
            lastPerformances = mapOf(
                squat.id.value to LastPerformance(
                    performedOn = today.minusDays(3),
                    trackingMode = TrackingMode.WEIGHT_AND_REPS,
                    set = StrengthSet(
                        id = StrengthSetId("set"),
                        position = 0,
                        repetitions = 8,
                        load = Load.ofKilogramsOrNull(60.0),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Last time · 60 kg × 8").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anExerciseNeverPractisedQuotesNothing() {
        setScreen(withOneSquat())

        composeRule.onAllNodesWithText(LastPerformanceFormat.PREFIX, substring = true)
            .assertCountEquals(0)
    }

    // endregion

    // region The session trio

    /** The editor and the log form show one duration, on the same two wheels (PRD 9.1). */
    @Test
    fun theDurationIsChosenInHoursAndMinutesAsOnTheLogForm() {
        setScreen()

        composeRule.setWheel(ActivityTestTags.DURATION_HOURS_FIELD, 2)
        composeRule.setWheel(ActivityTestTags.DURATION_MINUTES_FIELD, 15)

        assertEquals("2", draft.hours)
        assertEquals("15", draft.minutes)
    }

    /**
     * PRD 12, and the log form's own hint.
     *
     * The optional measurements still spell an absence rather than a zero. The duration is the
     * one field that cannot: it is required, and a wheel has to rest on something. It rests on
     * `0`, which the save refuses with the range in the message rather than writing a session
     * nobody logged — the honest reading of PRD 12, whose rule is about *optional* values.
     */
    @Test
    fun anUnfilledOptionalMeasurementIsAnAbsenceRatherThanAZero() {
        setScreen(ActivityDraft(presetId = ActivityPreset.STRENGTH_TRAINING.id, detailed = true))

        composeRule.onAllNodesWithText(EMPTY_NUMBER_HINT).assertCountEquals(1)
        assertEquals(0, composeRule.wheelValue(ActivityTestTags.DURATION_HOURS_FIELD))
        assertEquals(0, composeRule.wheelValue(ActivityTestTags.DURATION_MINUTES_FIELD))
    }

    /** A wheel says its value out loud, unit and all, because it only draws the digits. */
    @Test
    fun eachDurationWheelIsAnAdjustableControlThatSpeaksItsValue() {
        setScreen()

        composeRule.setWheel(ActivityTestTags.DURATION_HOURS_FIELD, 1)
        composeRule.setWheel(ActivityTestTags.DURATION_MINUTES_FIELD, 45)

        composeRule.onNodeWithTag(ActivityTestTags.DURATION_HOURS_FIELD)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 hour"))
        composeRule.onNodeWithTag(ActivityTestTags.DURATION_MINUTES_FIELD)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "45 minutes"),
            )
    }

    /** PRD FR-ACTIVITY-005: neither wheel can be pushed past the range the PRD sets. */
    @Test
    fun neitherDurationWheelCanLeaveTheRangeThePrdSets() {
        setScreen()

        composeRule.setWheel(ActivityTestTags.DURATION_HOURS_FIELD, 1_000)
        composeRule.setWheel(ActivityTestTags.DURATION_MINUTES_FIELD, 1_000)
        assertEquals("99", draft.hours)
        assertEquals("59", draft.minutes)

        composeRule.setWheel(ActivityTestTags.DURATION_HOURS_FIELD, -5)
        composeRule.setWheel(ActivityTestTags.DURATION_MINUTES_FIELD, -5)
        assertEquals("0", draft.hours)
        assertEquals("0", draft.minutes)
    }

    @Test
    fun theSessionEffortStaysOfferedWhateverTheModesBelowIt() {
        setScreen(withOneSquat())

        composeRule.onNodeWithTag(ActivityTestTags.EFFORT_SLIDER).assertIsDisplayed()
    }

    // endregion

    // region Saving

    @Test
    fun oneCompleteSetIsEnoughToSave() {
        setScreen(withOneSquat())

        composeRule.onNodeWithContentDescription("Set 1, Repetitions")
            .performScrollTo()
            .performTextReplacement("8")

        composeRule.onNodeWithTag(ActivityTestTags.SAVE_BUTTON).assertIsEnabled()
        composeRule.onNodeWithText(SAVE_NEW_SESSION).assertIsDisplayed()
    }

    @Test
    fun editingASessionSavesChangesRatherThanCreatingOne() {
        setScreen(withOneSquat().copy(editingSessionId = "session-1"))

        composeRule.onNodeWithText(SAVE_EXISTING_SESSION).assertIsDisplayed()
    }

    /**
     * Contract decisions 1 and 8: the word is `Saved`, there is no tick, and the return happens
     * only once the discharge has finished.
     */
    @Test
    fun theConfirmationPlaysBeforeTheEditorReturns() {
        setScreen(withOneSquat())
        composeRule.onNodeWithContentDescription("Set 1, Repetitions")
            .performScrollTo()
            .performTextReplacement("8")

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(ActivityTestTags.SAVE_BUTTON).performClick()
        composeRule.advanceToTheQuietButton()

        assertEquals(1, saves)
        assertEquals(0, returns)

        composeRule.mainClock.advanceTimeBy(MueMotion.SaveConfirmationMillis.toLong())
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(1, returns)
        composeRule.onAllNodesWithText("✓", substring = true).assertCountEquals(0)
    }

    @Test
    fun theQuietButtonSaysTheOneWordEverySaveButtonSays() {
        setScreen(withOneSquat())
        composeRule.onNodeWithContentDescription("Set 1, Repetitions")
            .performScrollTo()
            .performTextReplacement("8")

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(ActivityTestTags.SAVE_BUTTON).performClick()
        composeRule.advanceToTheQuietButton()

        composeRule.onNodeWithText(MueSaveConfirmationLabel).assertIsDisplayed()
    }

    // endregion

    // region Accessibility

    /** PRD 15: a move, an addition and a removal are all announced. */
    @Test
    fun aMoveIsAnnouncedToTheAccessibilityServices() {
        setScreen(
            StrengthDraftEditor.apply(
                StrengthDraftEditor.apply(ActivityDraft(detailed = true), StrengthEdit.AddExercise(squat)),
                StrengthEdit.AddExercise(plank),
            ),
        )

        composeRule.onNodeWithContentDescription("Move Barbell squat down")
            .performScrollTo()
            .performClick()

        composeRule.onNode(hasContentDescription("Barbell squat moved to position 2 of 2"))
            .assertExists()
    }

    @Test
    fun anAddedExerciseIsAnnouncedToo() {
        setScreen()

        composeRule.onNodeWithTag(ActivityTestTags.ADD_EXERCISE).performClick()
        composeRule.onNodeWithTag(exercisePickerRowTag(plank)).performClick()

        composeRule.onNode(hasContentDescription("Plank added")).assertExists()
    }

    @Test
    fun everyExerciseActionCarriesTheNameItActsOn() {
        setScreen(withOneSquat())

        composeRule.onNode(hasContentDescription("Remove Barbell squat")).assertExists()
        composeRule.onNode(hasContentDescription("Remove set 1 of Barbell squat")).assertExists()
        composeRule.onNode(hasText("Add set")).assertExists()
    }

    // endregion

    /** The set duration of a `duration` exercise is read either way (PRD 11.4). */
    @Test
    fun aHoldIsTypedAsMinutesAndSeconds() {
        setScreen(
            StrengthDraftEditor.apply(ActivityDraft(detailed = true), StrengthEdit.AddExercise(plank)),
        )

        composeRule.onNodeWithContentDescription("Set 1, Duration")
            .performScrollTo()
            .performTextReplacement("1:30")

        assertEquals("1:30", draft.exercises.single().sets.single().durationSeconds)
        assertEquals(
            ActivityDuration.SECONDS_PER_MINUTE + 30,
            StrengthDraftEditor.persistableExercises(draft).single().sets.single().duration?.seconds,
        )
    }

    /**
     * The same promise the log form makes: the fade above the save action is drawn over the
     * list, so a drag that starts in it belongs to the list.
     */
    @Test
    fun aDragThatStartsInTheFadeAboveTheSaveActionScrollsTheExerciseList() {
        setScreen(withOneSquat())

        val before = listOffset()
        val start = with(composeRule.density) {
            val band = composeRule.onNodeWithTag(ActivityTestTags.SAVE_AREA).getBoundsInRoot()
            (band.top + MueStickyActionRamp / 2f).toPx()
        }
        composeRule.onRoot().performTouchInput {
            swipe(Offset(centerX, start), Offset(centerX, start - DRAG_PIXELS), DRAG_MILLIS)
        }
        composeRule.waitForIdle()

        val after = listOffset()
        assertTrue("the list did not move: $before then $after", after > before)
    }

    /**
     * How far the list has travelled, read from the range it publishes to assistive services.
     *
     * A row's bounds would not do: a `LazyColumn` drops an item that scrolls off, so the very
     * node a before-and-after would compare is the one that stops existing.
     */
    private fun listOffset(): Float = composeRule.onNodeWithTag(ActivityTestTags.EXERCISE_LIST)
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()

    /**
     * Scrolls the exercise list until [description] is on screen, then returns it.
     *
     * `performScrollTo` cannot do this: it asks for the node first, and a `LazyColumn` item
     * that is off screen has not been composed, so there is no node to ask about.
     */
    private fun reveal(description: String): SemanticsNodeInteraction {
        composeRule.onNodeWithTag(ActivityTestTags.EXERCISE_LIST)
            .performScrollToNode(hasContentDescription(description))
        return composeRule.onNodeWithContentDescription(description)
    }
}

/** The per-set effort cell of contract decision 3, named once. */
private const val SET_EFFORT_CELL = "Set 1, Perceived effort, 1 to 10"

/** Long enough to clear the touch slop several times over, short enough to stay on screen. */
private const val DRAG_PIXELS = 400f
private const val DRAG_MILLIS = 200L
