package fr.kristenjestin.mue.ui.food.add

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.recipes.RecipePreviewData
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

/**
 * The `Add food` sheet as it reaches the glass (PRD_FOOD 7, 13, 15, 17 and 18).
 *
 * The screen is driven through its stateless composable, so every assertion is about what is
 * drawn rather than about how a ViewModel got there — the split `FoodDayScreenTest` uses.
 * Expected strings come from [FoodLabels], [FoodValidation] and [FoodAddMessages] rather than
 * being spelled out, so a rule that changes cannot leave a test agreeing with a copy of itself.
 *
 * Every figure assertion is **scoped to its own handle**. Five nutrient rows are on screen at
 * once and four of them legitimately read `—` on a quick add, so a sweep of the tree for a dash
 * would prove nothing about which row it found.
 */
class FoodAddScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var searched = 0
    private var recipes = 0
    private var quick = 0
    private var slotChosen: MealSlot? = null
    private var saved = 0
    private var deleted = 0
    private var timeOpened = 0
    private var backedOut = 0
    private var closed = 0
    private var stepped = mutableListOf<Boolean>()

    // region the ways in (PRD_FOOD 7)

    @Test
    fun theWaysInAreOfferedAndEachOneLeads() {
        show(previewPathsState())

        compose.onNodeWithTag(FoodTestTags.ADD_BY_SEARCH).assertIsDisplayed().performClick()
        compose.onNodeWithTag(FoodTestTags.ADD_BY_RECIPE).assertIsDisplayed().performClick()
        compose.onNodeWithTag(FoodTestTags.ADD_QUICK).assertIsDisplayed().performClick()

        assertEquals(1, searched)
        assertEquals(1, recipes)
        assertEquals(1, quick)
    }

    /** Nothing is chosen yet, so there is nothing to save and no pinned band to save it with. */
    @Test
    fun theWaysInCarryNoSaveAction() {
        show(previewPathsState())

        compose.onNodeWithTag(FoodTestTags.CONFIRM_BUTTON).assertDoesNotExist()
    }

    /**
     * A line being corrected is read back from the journal, and the sheet waits for it.
     *
     * Drawing the ways in for that instant would flash `What did you eat?` over an entry that
     * already answers the question.
     */
    @Test
    fun aSheetStillReadingItsLineShowsNeitherTheWaysInNorASaveAction() {
        show(previewServingsState().copy(isLoading = true))

        compose.onNodeWithTag(FoodTestTags.ADD_BY_SEARCH).assertDoesNotExist()
        compose.onNodeWithTag(FoodTestTags.CONFIRM_BUTTON).assertDoesNotExist()
        compose.onNodeWithTag(FoodTestTags.ADD_SHEET).assertExists()
    }

    /**
     * The way out of a chosen path, on the glass (PRD_FOOD 7).
     *
     * "j'ai plus accès aux 3 menus d'avant." The sheet had no step back at all: once a path was
     * taken the three cards were unreachable until a line was saved or deleted.
     *
     * It is now the **header's own arrow**, and there is nothing else. It used to be a second
     * control under a cross that closed the whole sheet — "j'ai le « add food » mais avec une
     * croix et un autre bouton en dessous pour revenir en arrière" — so the assertion is that the
     * one exit does the step, not that a second control exists to do it.
     */
    @Test
    fun theHeaderArrowStepsBackToThePathsOnceAPathIsTaken() {
        show(previewCookedState())

        compose.onNodeWithContentDescription(FoodAddMessages.BACK)
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, backedOut)
        assertEquals(0, closed)
    }

    /**
     * One exit, and it is an arrow.
     *
     * The cross is gone from every stage. Two ways out with two meanings, side by side and
     * distinguished only by a glyph, is the defect; a header that offers `Close` as well as
     * `Back` would be it returning.
     */
    @Test
    fun everyStageOffersExactlyOneWayOut() {
        show(previewPathsState())

        listOf(
            previewPathsState(),
            previewScanRefusedState(),
            previewCookedState(),
            previewQuickState(),
            previewRecipeServingsState(),
        ).forEach { state ->
            showState(state)

            compose.onAllNodesWithContentDescription(FoodAddMessages.BACK).assertCountEquals(1)
            compose.onNodeWithContentDescription(FormerCloseLabel).assertDoesNotExist()
            compose.onNodeWithText(FormerChangePathLabel).assertDoesNotExist()
        }
    }

    /** Nothing has been chosen yet on the first stage, so back leaves the sheet instead. */
    @Test
    fun theWaysInThemselvesStepBackOutOfTheSheet() {
        show(previewPathsState())

        compose.onNodeWithContentDescription(FoodAddMessages.BACK).performClick()

        assertEquals(0, backedOut)
        assertEquals(1, closed)
    }

    /**
     * FR-FOOD-008: a correction was not opened on the ways in and has no earlier stage.
     *
     * Offering the step there would offer to turn a weighed food into a quick add, which is the
     * loss of the line rather than a correction of it. So its arrow closes the sheet.
     */
    @Test
    fun aCorrectionIsNeverOfferedTheWayBack() {
        show(previewServingsState())

        compose.onNodeWithContentDescription(FoodAddMessages.BACK).performClick()

        assertEquals(0, backedOut)
        assertEquals(1, closed)
        compose.onNodeWithTag(FoodTestTags.DELETE_BUTTON).assertExists()
    }

    /** PRD_FOOD 18: the step is a control, so it is a target of at least 48 dp with a name. */
    @Test
    fun theWayBackIsANamedTargetOfTheRightSize() {
        show(previewCookedState())

        assertTallEnough(FoodAddMessages.BACK)
    }

    /**
     * The header names the screen it is on, and not the one it was opened from.
     *
     * "quand je rentre dans « scan a barcode », j'ai le « add food »" — the sheet used to carry
     * `Add food` over all five of its stages. A title that is the same on every screen is not a
     * title.
     */
    @Test
    fun everyStageNamesItselfInTheHeader() {
        show(previewPathsState())
        assertHeaderTitle(FoodAddMessages.ADD_TITLE)

        showState(previewScanRefusedState())
        assertHeaderTitle(FoodAddMessages.SCAN_PATH)

        showState(previewQuickState())
        assertHeaderTitle(FoodAddMessages.QUICK_PATH)

        showState(previewCookedState())
        assertHeaderTitle(FoodAddMessages.AMOUNT_SECTION)

        showState(previewRecipeServingsState())
        assertHeaderTitle(FoodAddMessages.SERVINGS_SECTION)
    }

    /** FR-FOOD-008: a correction is one screen whatever form the line it corrects has. */
    @Test
    fun aCorrectionNamesItselfAnEdit() {
        show(previewServingsState())

        assertHeaderTitle(FoodAddMessages.EDIT_TITLE)
    }

    // endregion

    // region raw and cooked (FR-FOOD-006, PRD_FOOD 8.6)

    /**
     * The finding this screen was built around: **the state the number is read in is beside the
     * field**, not only behind the arithmetic.
     *
     * 600 g of cooked rice typed against a raw reference counts nearly three times the energy
     * actually eaten, and nothing on a field labelled `Weight` would say so.
     */
    @Test
    fun theQuantityFieldNamesTheStateTheNumberIsReadIn() {
        show(previewCookedState())

        assertDrawn(FoodTestTags.QUANTITY_FIELD, "Weight, cooked")
        assertDrawn(FoodTestTags.UNIT_PICKER, "cooked")
        assertDrawn(FoodTestTags.UNIT_PICKER, "raw")
    }

    /** PRD_FOOD 13.1, on the glass: what a cooked weight is actually counted as. */
    @Test
    fun aCookedWeightSaysWhatItIsCountedAs() {
        show(previewCookedState())

        compose.onNodeWithTag(FoodTestTags.ADD_SHEET)
            .assert(hasAnyDescendant(hasText(FoodAddMessages.countedAs("265.487 g", "raw"))))
    }

    /** PRD_FOOD 22: "le sélecteur cru/cuit n'apparaît que sur les aliments portant un ratio". */
    @Test
    fun theCookedSelectorAppearsOnlyOnAFoodThatCarriesARatio() {
        show(previewCookedState())
        compose.onNodeWithTag(FoodTestTags.UNIT_PICKER).assertExists()

        showState(previewPortionsState())
        compose.onNodeWithTag(FoodTestTags.UNIT_PICKER).assertDoesNotExist()
    }

    // endregion

    // region a known zero, an unknown, and the difference between them

    /**
     * **The last step of the module's null discipline** (PRD_FOOD 13.1 and 13.2).
     *
     * A quick add states an energy and nothing else, so four of its five rows read `—`. A black
     * espresso states every one of them as zero. The two are put on the *same* glass one after
     * the other, so what is compared is what was drawn rather than two runs that might have
     * differed for some other reason.
     */
    @Test
    fun anUnknownAndAKnownZeroAreDrawnDifferently() {
        show(previewQuickState())

        assertDrawn(nutrient(FoodNutrientsUiState.ENERGY), "≈ 300 kcal")
        assertDrawn(nutrient(FoodNutrientsUiState.PROTEIN), FoodLabels.UNKNOWN)
        assertDrawn(nutrient(FoodNutrientsUiState.FIBRE), FoodLabels.UNKNOWN)
        val unknown = drawnText()

        showState(previewKnownZeroState())

        assertDrawn(nutrient(FoodNutrientsUiState.ENERGY), "≈ 0 kcal")
        assertDrawn(nutrient(FoodNutrientsUiState.PROTEIN), "≈ 0.0 g")
        val zero = drawnText()

        assertTrue(
            "an unknown was drawn somewhere as a zero: $unknown",
            unknown.none { it == "≈ 0.0 g" || it == "≈ 0 kcal" },
        )
        assertTrue(
            "a known zero was drawn as an unknown: $zero",
            zero.none { it == FoodLabels.UNKNOWN },
        )
        assertNotEquals("an unknown and a known zero drew the same screen", unknown, zero)
    }

    /** PRD_FOOD 22, metric by metric: an unknown fibre leaves the energy a number. */
    @Test
    fun oneUnknownMetricLeavesTheOthersKnown() {
        show(previewCookedState())

        assertDrawn(nutrient(FoodNutrientsUiState.FIBRE), FoodLabels.UNKNOWN)
        assertDrawn(nutrient(FoodNutrientsUiState.ENERGY), "≈ 927 kcal")
    }

    /**
     * PRD_FOOD 18: what is heard is never the punctuation that is drawn.
     *
     * The card's heading is **announced and not drawn as text**: `FoodSectionCard` clears its
     * title's semantics and puts the whole sentence on it as a description, so the five figures
     * under it are heard as one fact rather than as five fragments. `hasText` looks at
     * `SemanticsProperties.Text` and never at a content description, so it could not match this
     * heading and never did — the assertion was wrong the first time it ran, against a screen that
     * was right.
     *
     * So the two halves are read where each of them actually lives: the announcement on the
     * heading, and the dash in the row the eye sees.
     */
    @Test
    fun anUnknownIsAnnouncedAsUnknownRatherThanAsADash() {
        show(previewQuickState())

        compose
            .onNodeWithContentDescription(FoodAddMessages.CONTRIBUTION_SECTION, substring = true)
            .assertExists()
        compose.onNodeWithContentDescription("unknown", substring = true).assertExists()
        assertDrawn(nutrient(FoodNutrientsUiState.PROTEIN), FoodLabels.UNKNOWN)
    }

    // endregion

    // region the moment and the hour (PRD_FOOD 10.3)

    /**
     * The override: the six moments, in the panel, and one tap choosing one.
     *
     * The panel is where the choosing happens now. On the form itself there is nothing to choose —
     * see `theFormShowsTheMomentTheHourChoseAndAsksForNothing`, which is the other half of this
     * pair and asserts that the picker is not there at all until it is asked for.
     */
    @Test
    fun theSixMomentsAreOfferedInThePanelAndTappingOneChoosesIt() {
        show(previewCookedState().copy(isSlotPickerVisible = true))

        compose.onNodeWithTag(FoodTestTags.SLOT_PICKER).assertIsDisplayed()
        MealSlot.ORDERED.forEach { slot ->
            assertDrawn(FoodTestTags.SLOT_PICKER, slot.label)
        }

        compose.onNodeWithText(MealSlot.BREAKFAST.label).performClick()
        assertEquals(MealSlot.BREAKFAST, slotChosen)
    }

    @Test
    fun theHourIsShownAndOpensItsPicker() {
        show(previewCookedState())

        compose.onNodeWithTag(FoodTestTags.TIME_FIELD).performScrollTo().assertIsDisplayed()
        assertDrawn(FoodTestTags.TIME_FIELD, FoodAddMessages.TIME_LABEL)

        compose.onNodeWithTag(FoodTestTags.TIME_FIELD).performClick()
        assertEquals(1, timeOpened)
    }

    /** PRD_FOOD 10.3: the dial is a panel over the sheet, and it is closed until it is asked for. */
    @Test
    fun theTimePickerIsOpenedRatherThanAlwaysThere() {
        show(previewCookedState())
        compose.onNodeWithTag(FoodTestTags.TIME_PICKER).assertDoesNotExist()

        showState(previewCookedState().copy(isTimePickerVisible = true))
        compose.onNodeWithTag(FoodTestTags.TIME_PICKER).assertExists()
    }

    /*
     * "« which moment » on comprend pas, je peux sélectionner breakfast, mais avoir un time à 18h,
     * je comprends pas ?" — and then, once the hours were printed on the tiles: "je définis mon
     * heure de bouffer, le système a déjà en mémoire les plages… ça affiche bien lunch dans
     * l'interface mais pas à la création".
     *
     * The moment is no longer asked for. The hour decides it, the form shows what it decided, and
     * the six moments live in a panel that is closed until somebody wants to overrule the clock —
     * which stays allowed, because PRD_FOOD 10.3 says the windows "ne créent aucune contrainte"
     * and a lunch eaten at three is a real meal.
     */

    /** FR-FOOD-007: the moment is a value on the form, not a control to fill in. */
    @Test
    fun theFormShowsTheMomentTheHourChoseAndAsksForNothing() {
        show(momentState(MealSlot.LUNCH, LocalTime.of(13, 0)))

        compose.onNodeWithTag(FoodTestTags.SLOT_FIELD)
            .performScrollTo()
            .assertIsDisplayed()
        assertDrawn(FoodTestTags.SLOT_FIELD, "Lunch · 12:00 – 14:30")

        // No grid of moments anywhere on the form: the picker only exists inside the panel.
        compose.onNodeWithTag(FoodTestTags.SLOT_PICKER).assertDoesNotExist()
    }

    /** PRD_FOOD 18: the row is a target and says what it is, not just which moment it names. */
    @Test
    fun theMomentRowIsAReachableControlThatSaysWhatItDoes() {
        show(momentState(MealSlot.LUNCH, LocalTime.of(13, 0)))

        compose.onNodeWithTag(FoodTestTags.SLOT_FIELD).performScrollTo()
        compose.onNodeWithTag(FoodTestTags.SLOT_FIELD)
            .assertContentDescriptionContains(MealSlot.LUNCH.label, substring = true)
        assertTallEnough(FoodAddMessages.changeSlotDescription(MealSlot.LUNCH.label))
    }

    /** The override is one panel away, and every moment in it carries its own window. */
    @Test
    fun theOverrideOffersEveryMomentWithTheHoursItCovers() {
        show(momentState(MealSlot.DINNER, LocalTime.of(20, 0)))
        compose.onNodeWithTag(FoodTestTags.SLOT_PICKER).assertDoesNotExist()

        showState(momentState(MealSlot.DINNER, LocalTime.of(20, 0)).copy(isSlotPickerVisible = true))

        assertDrawn(FoodTestTags.SLOT_PICKER, "05:00 – 10:00")
        assertDrawn(FoodTestTags.SLOT_PICKER, "10:00 – 12:00")
        assertDrawn(FoodTestTags.SLOT_PICKER, "12:00 – 14:30")
        assertDrawn(FoodTestTags.SLOT_PICKER, "14:30 – 18:30")
        assertDrawn(FoodTestTags.SLOT_PICKER, "18:30 – 22:00")
        // The one that crosses midnight, drawn as the one interval it is.
        assertDrawn(FoodTestTags.SLOT_PICKER, "22:00 – 05:00")
    }

    @Test
    fun aMomentAndAnHourThatDisagreeSaySoOnScreen() {
        show(momentState(MealSlot.BREAKFAST, LocalTime.of(20, 0)))

        // The sheet scrolls, and the note lives under the moment row near its foot.
        compose.onNodeWithTag(FoodTestTags.SLOT_TIME_NOTE)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(
                hasText(
                    FoodAddMessages.timeOutsideSlot("20:00", MealSlot.DINNER, MealSlot.BREAKFAST),
                ),
            )
    }

    /** A moment and an hour that agree have nothing to explain, so the line is not drawn. */
    @Test
    fun anHourInsideItsMomentSaysNothing() {
        show(momentState(MealSlot.DINNER, LocalTime.of(20, 0)))

        compose.onNodeWithTag(FoodTestTags.SLOT_TIME_NOTE).assertDoesNotExist()
    }

    // endregion

    // region saving and correcting (FR-FOOD-008)

    @Test
    fun theSaveActionSaysWhereTheLineIsGoing() {
        show(previewCookedState())

        compose.onNodeWithTag(FoodTestTags.CONFIRM_BUTTON)
            .assertContentDescriptionContains(MealSlot.DINNER.label, substring = true)
        compose.onNodeWithTag(FoodTestTags.CONFIRM_BUTTON).performClick()
        assertEquals(1, saved)
    }

    @Test
    fun deletingIsOfferedOnlyWhenCorrectingALineThatExists() {
        show(previewCookedState())
        compose.onNodeWithTag(FoodTestTags.DELETE_BUTTON).assertDoesNotExist()

        showState(previewServingsState())
        compose.onNodeWithTag(FoodTestTags.DELETE_BUTTON).assertIsDisplayed().performClick()
        assertEquals(1, deleted)
    }

    /**
     * PRD_FOOD 15 and 17: "erreur à côté du champ, formulaire conservé".
     *
     * Scoped to the field, which is what the rule is about. A bare `onNodeWithText` could never
     * pass here and never did: the sheet draws this sentence **twice** by design — once under the
     * quantity, and once beside `Save entry` as `FoodAddErrors.summary`, so a reader who has
     * scrolled past the field still hears why the save did nothing. Two nodes, one string, and an
     * unscoped matcher that demands exactly one.
     */
    @Test
    fun aRefusedQuantityIsShownBesideItsFieldAndKeepsWhatWasTyped() {
        show(
            previewCookedState().copy(
                errors = FoodAddErrors(quantity = FoodValidation.INGREDIENT_QUANTITY_ERROR),
            ),
        )

        assertDrawn(FoodTestTags.QUANTITY_FIELD, FoodValidation.INGREDIENT_QUANTITY_ERROR)
        assertDrawn(FoodTestTags.QUANTITY_FIELD, "600")
    }

    /** The same refusal beside the action, so it is heard without scrolling back (PRD_FOOD 18). */
    @Test
    fun aRefusalIsAlsoSaidBesideTheSaveAction() {
        show(
            previewCookedState().copy(
                errors = FoodAddErrors(quantity = FoodValidation.INGREDIENT_QUANTITY_ERROR),
            ),
        )

        compose
            .onAllNodesWithText(FoodValidation.INGREDIENT_QUANTITY_ERROR)
            .assertCountEquals(2)
    }

    /** PRD_FOOD 17: a line whose food is gone keeps its values and says why. */
    @Test
    fun aLineWhoseFoodIsGoneSaysSo() {
        show(previewOrphanedState())

        compose.onNodeWithText(FoodAddMessages.MISSING_FOOD).assertExists()
        compose.onNodeWithTag(FoodTestTags.QUANTITY_FIELD).assertDoesNotExist()
        assertDrawn(nutrient(FoodNutrientsUiState.ENERGY), "≈ 211 kcal")
        assertDrawn(nutrient(FoodNutrientsUiState.CARBS), FoodLabels.UNKNOWN)
    }

    // endregion

    // region logging a recipe (FR-FOOD-004)

    /**
     * The recipe is on the sheet, and tapping it goes back to the picker.
     *
     * The card's own texts are cleared from the merged tree by `clearAndSetSemantics`, so the
     * name is read from the **announcement** rather than looked up as a text node — the mistake
     * eight earlier tests in this module made.
     */
    @Test
    fun aChosenRecipeIsShownOnTheSheetAndLeadsBackToThePicker() {
        show(previewRecipeServingsState())

        compose.onNodeWithTag(FoodTestTags.CHOSEN_RECIPE)
            .assertIsDisplayed()
            .assertContentDescriptionContains(RecipePreviewData.LONGEST_NAME, substring = true)
        compose.onNodeWithTag(FoodTestTags.CHOSEN_RECIPE).performClick()

        assertEquals(1, recipes)
    }

    /**
     * FR-FOOD-004: a new recipe line is computed from the recipe, not rescaled from a snapshot.
     *
     * The footnote is what says which of the two a reader is looking at, and the figures above it
     * are `In this entry` once a count has been typed.
     */
    @Test
    fun aNewRecipeLineSaysWhereItsFiguresComeFrom() {
        show(previewRecipeServingsState())

        compose.onNodeWithText(FoodAddMessages.SERVINGS_FROM_RECIPE).assertExists()
        compose.onNodeWithText(FoodAddMessages.SERVINGS_FROZEN).assertDoesNotExist()
        /*
         * The figures' heading is **not a text node**: `FoodSectionCard` clears the semantics of
         * its title and speaks the whole card through one description, so the merged tree holds
         * `In this entry` as a `contentDescription` and the text tree does not hold it at all.
         */
        compose.onNodeWithContentDescription(FoodAddMessages.CONTRIBUTION_SECTION, substring = true)
            .assertExists()
    }

    /** FR-FOOD-008: a correction has no recipe card and says it rescales what was saved. */
    @Test
    fun aCorrectedRecipeLineStillRescalesItsFrozenSnapshot() {
        show(previewServingsState())

        compose.onNodeWithTag(FoodTestTags.CHOSEN_RECIPE).assertDoesNotExist()
        compose.onNodeWithText(FoodAddMessages.SERVINGS_FROZEN).assertExists()
    }

    // endregion

    // region the portion counter (FR-FOOD-006)

    @Test
    fun theCounterIsOfferedByAFoodThatDeclaresAPortion() {
        show(previewPortionsState())

        compose.onNodeWithTag(FoodTestTags.SERVINGS_STEPPER).assertIsDisplayed()
        assertDrawn(FoodTestTags.SERVINGS_STEPPER, "1.5 ${FoodLabels.TIMES} 1 apple")

        compose.onNodeWithContentDescription(FoodAddMessages.MORE_PORTIONS).performClick()
        assertEquals(listOf(true), stepped)
    }

    @Test
    fun aFoodWithNoUsualPortionOffersNoCounter() {
        show(previewCookedState())

        compose.onNodeWithTag(FoodTestTags.SERVINGS_STEPPER).assertDoesNotExist()
    }

    /**
     * PRD_FOOD 18: the count is announced as a **value**, not as two unexplained buttons.
     *
     * `MueStepper` puts the label on the readout as its name and the count as its
     * `stateDescription` — the arrangement `MueEffortSlider` uses to publish its own number — so a
     * reader lands on one node that says `Usual portions, 1.5 × 1 apple` instead of on a bare
     * figure sitting between a `−` and a `+` whose relationship to it has to be guessed.
     */
    @Test
    fun theCounterAnnouncesItsCountAsAValue() {
        show(previewPortionsState())

        compose
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "1.5 ${FoodLabels.TIMES} 1 apple",
                ),
            )
            .assertContentDescriptionContains(FoodAddMessages.PORTIONS_LABEL)
    }

    // endregion

    // region touch targets (PRD_FOOD 18)

    @Test
    fun everyControlClearsTheTouchMinimum() {
        show(previewPortionsState())

        assertTallEnough(FoodAddMessages.MORE_PORTIONS)
        assertTallEnough(FoodAddMessages.FEWER_PORTIONS)

        compose.onNodeWithTag(FoodTestTags.TIME_FIELD).performScrollTo()
        val time = compose.onNodeWithTag(FoodTestTags.TIME_FIELD)
            .getUnclippedBoundsInRoot()
            .height
        assertTrue("the time field is $time, under $MueMinTouchTarget", time >= MueMinTouchTarget)
    }

    // endregion

    // region harness

    private fun nutrient(key: String): String = FoodTestTags.nutrientField(key)

    /**
     * The sheet aimed at one moment and one hour, in a locale that writes a clock as PRD_FOOD 10.3
     * writes it.
     *
     * The times drawn on the moment cards are formatted for the reader's own locale, so a fixture
     * that left it to the device would assert `18:00` on one emulator image and `6:00 PM` on
     * another. `Locale.UK` is the one the module's JVM suites already pin for the same reason.
     */
    private fun momentState(slot: MealSlot, time: LocalTime): FoodAddUiState = FoodAddUiState.of(
        draft = FoodAddPreviewData.draft(slot).withTime(time).copy(timePinned = true),
        food = FoodAddPreviewData.rice(),
        today = FoodAddPreviewData.TODAY,
        locale = Locale.UK,
    )

    /** Asserts that the node handled by [tag] draws [text] somewhere inside itself. */
    private fun assertDrawn(tag: String, text: String) {
        compose.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(text)),
            useUnmergedTree = true,
        ).assertExists()
    }

    /**
     * The string in the scaffold's header slot, told apart from the same words on a card.
     *
     * `How much?` and `How many servings?` are each drawn twice — once as the screen's title and
     * once as the section's — so a bare `onNodeWithText` would find two nodes and fail on the
     * ambiguity rather than on the fact. `MueSubScreenScaffold` marks its title a heading and the
     * section cards below do not, which is the difference this reads.
     */
    private fun assertHeaderTitle(title: String) {
        compose
            .onNode(
                hasText(title) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            )
            .assertExists()
    }

    /**
     * The two strings the sheet's second exit used to draw.
     *
     * Spelled out here rather than referenced, because both constants are gone from the
     * production source — which is the point. A test that named them would keep them alive.
     */
    private val FormerCloseLabel: String = "Close"
    private val FormerChangePathLabel: String = "Choose another way"

    private fun assertTallEnough(contentDescription: String) {
        val height = compose
            .onNodeWithContentDescription(contentDescription)
            .getUnclippedBoundsInRoot()
            .height
        assertTrue("$contentDescription is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    /** Every string the sheet is currently drawing, glyph for glyph. */
    private fun drawnText(): List<String> =
        compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }

    /**
     * The sheet on screen, held in state rather than closed over.
     *
     * `setContent` may only be called once per test, and two of the tests above have to compare
     * *two* sheets on the same glass — so the state is swapped instead of the content being set
     * again.
     */
    private val shown = mutableStateOf<FoodAddUiState?>(null)

    private fun show(state: FoodAddUiState, fontScale: Float = 1f) {
        shown.value = state
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    shown.value?.let { sheet ->
                        FoodAddScreen(
                            state = sheet,
                            actions = FoodAddActions(
                                onSearchFood = { searched++ },
                                onUseRecipe = { recipes++ },
                                onQuickAdd = { quick++ },
                                onClose = { closed++ },
                                onBackToPaths = { backedOut++ },
                                onPortionStep = { up -> stepped += up },
                                onSlotSelected = { slotChosen = it },
                                onOpenTimePicker = { timeOpened++ },

                                onSave = { saved++ },
                                onDelete = { deleted++ },
                            ),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Puts another sheet on the same screen, for the comparisons that need both. */
    private fun showState(state: FoodAddUiState) {
        shown.value = state
        compose.waitForIdle()
    }

    // endregion
}
