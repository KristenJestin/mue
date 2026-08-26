package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.ui.field
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The recipe form as it reaches the glass (PRD_FOOD 11, 15, 18 and FR-RECIPE-001 to 003).
 *
 * Driven through the stateless composable: what is asserted is what a state produces on screen,
 * and the writes themselves are settled on the JVM in `RecipeEditorViewModelTest`. Expected
 * sentences are asked of [FoodValidation] by name, so the screen and the domain cannot drift into
 * saying two different things about the same rule.
 */
class RecipeEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var typedName: String? = null
    private var typedQuantity: Pair<Int, String>? = null
    private var chosenType: RecipeType? = null
    private var removed: Int? = null
    private var pickerOpened = 0
    private var picked: String? = null
    private var pickerDismissed = 0
    private var saved = 0

    // region the form (FR-RECIPE-001)

    @Test
    fun everyFieldOfTheFormIsThere() {
        setForm(previewRecipeEditorState())

        compose.onNodeWithTag(FoodTestTags.RECIPE_NAME_FIELD).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.RECIPE_TYPE_PICKER).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.RECIPE_SERVINGS_FIELD).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.RECIPE_PREP_TIME_FIELD).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(RecipeTestTags.RECIPE_DESCRIPTION_FIELD).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.INGREDIENT_LIST).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.STEPS_FIELD).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun typingAName() {
        setForm(previewRecipeEditorState())

        compose.onNodeWithTag(FoodTestTags.RECIPE_NAME_FIELD).performScrollTo()
        compose.field(FoodTestTags.RECIPE_NAME_FIELD).performTextReplacement("Skyr bowl")

        assertEquals("Skyr bowl", typedName)
    }

    @Test
    fun choosingAMoment() {
        setForm(previewRecipeEditorState())

        compose.onNodeWithTag(FoodTestTags.RECIPE_TYPE_PICKER).performScrollTo()
        compose.onNodeWithText(RecipeType.SNACK.label).performClick()

        assertEquals(RecipeType.SNACK, chosenType)
    }

    @Test
    fun typingAnIngredientQuantity() {
        setForm(previewRecipeEditorState())

        compose.onNodeWithTag(FoodTestTags.ingredientQuantity(0)).performScrollTo()
        compose.field(FoodTestTags.ingredientQuantity(0)).performTextReplacement("240")

        assertEquals(0 to "240", typedQuantity)
    }

    @Test
    fun removingAnIngredient() {
        setForm(previewRecipeEditorState())

        compose.onNodeWithTag(FoodTestTags.removeIngredient(1)).performScrollTo().performClick()

        assertEquals(1, removed)
    }

    @Test
    fun savingReportsASave() {
        setForm(previewRecipeEditorState())

        compose.onNodeWithTag(RecipeTestTags.SAVE_RECIPE).performClick()

        assertEquals(1, saved)
    }

    // endregion

    // region what PRD_FOOD 15 refuses

    /**
     * **The empty recipe that would read `0 kcal` for ever.**
     *
     * `Nutrients.strictSum(emptyList())` is a known zero, so a saved recipe with no ingredient
     * would show a number nobody typed. The sentence under `Add an ingredient` is
     * `FoodValidation.validateIngredientCount`'s own, drawn where PRD_FOOD 15 asks for it.
     */
    @Test
    fun aFormWithNoIngredientSaysWhyItCannotBeSaved() {
        setForm(refusedRecipeEditorState())

        compose.onNodeWithTag(RecipeTestTags.INGREDIENT_COUNT_ERROR).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(FoodValidation.INGREDIENT_COUNT_ERROR).assertIsDisplayed()
    }

    @Test
    fun aRefusedServingCountSaysWhy() {
        setForm(refusedRecipeEditorState())

        compose.onNodeWithText(FoodValidation.BASE_SERVINGS_ERROR).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aRefusedNameSaysWhy() {
        setForm(refusedRecipeEditorState())

        compose.onNodeWithText(FoodValidation.NAME_ERROR).performScrollTo().assertIsDisplayed()
    }

    /** Nothing is complained about until a save has been attempted (PRD_FOOD 15). */
    @Test
    fun aBlankFormSaysNothingUntilItIsSaved() {
        setForm(RecipeEditorUiState.of(draft = RecipeDraft()))

        compose.onNodeWithText(FoodValidation.NAME_ERROR).assertDoesNotExist()
        compose.onNodeWithTag(RecipeTestTags.INGREDIENT_COUNT_ERROR).assertDoesNotExist()
    }

    // endregion

    // region the live per-serving block (FR-RECIPE-003)

    @Test
    fun theBlockIsThereAsSoonAsThereIsSomethingToTotal() {
        setForm(previewRecipeEditorState())

        compose.onNodeWithTag(RecipeTestTags.EDITOR_PER_SERVING).performScrollTo()
            .assertIsDisplayed()
    }

    /** An empty form has nothing to total, and shows no block rather than a zero. */
    @Test
    fun anEmptyFormShowsNoBlockAtAll() {
        setForm(RecipeEditorUiState.of(draft = RecipeDraft()))

        compose.onNodeWithTag(RecipeTestTags.EDITOR_PER_SERVING).assertDoesNotExist()
        compose.onNodeWithText(FoodLabels.ENERGY_UNIT, substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    // endregion

    // region the ingredient picker (FR-RECIPE-002)

    @Test
    fun theFormOpensThePicker() {
        setForm(previewRecipeEditorState())

        compose.onNodeWithTag(FoodTestTags.ADD_INGREDIENT).performScrollTo().performClick()

        assertEquals(1, pickerOpened)
    }

    /**
     * **Picking an ingredient must not ask the sheet to close.**
     *
     * A recipe is several foods at once; the activity module already learned what a picker that
     * dismissed on the first selection costs. The row reports the pick and nothing else, and the
     * sheet's own action is the only thing that asks for a dismissal.
     */
    @Test
    fun pickingAnIngredientDoesNotDismissThePicker() {
        setForm(pickerRecipeEditorState())

        val row = RecipeTestTags.pickerRow(RecipePreviewData.coconutMilk().id.value)
        compose.onNodeWithTag(row).performClick()

        assertEquals(RecipePreviewData.coconutMilk().id.value, picked)
        assertEquals("picking an ingredient closed the picker", 0, pickerDismissed)
        compose.onNodeWithText(RecipeMessages.PICKER_TITLE).assertIsDisplayed()
    }

    /** And the sheet says what it has done, so nothing about the pick is invisible. */
    @Test
    fun thePickerSaysHowManyItHasAdded() {
        setForm(pickerRecipeEditorState(addedCount = 2))

        compose.onNodeWithText(RecipeMessages.addedCount(2)).assertIsDisplayed()
    }

    @Test
    fun theSheetIsClosedOnPurpose() {
        setForm(pickerRecipeEditorState())

        compose.onNodeWithTag(RecipeTestTags.PICKER_DONE).performClick()

        assertEquals(1, pickerDismissed)
    }

    // endregion

    // region an ingredient this device does not have (PRD_FOOD 21.2)

    /**
     * A form opened on a recipe whose food never arrived keeps the row, its snapshot name and a
     * dash where its energy would be. It is editable, and it is saveable: refusing would lose the
     * ingredient the snapshot exists to preserve.
     */
    @Test
    fun anOrphanIngredientIsStillARowOfTheForm() {
        setForm(orphanRecipeEditorState())

        val orphan = orphanRecipeEditorState().ingredients.indexOfFirst { it.isOrphan }
        val tag = FoodTestTags.ingredient(orphan)
        compose.onNodeWithTag(tag).performScrollTo()

        assertDrawn(tag, RecipePreviewData.ORPHAN_SNAPSHOT)
        assertDrawn(tag, FoodLabels.UNKNOWN)
        assertDrawn(tag, RecipeMessages.ORPHAN_INGREDIENT)
    }

    // endregion

    // region harness

    private fun assertDrawn(tag: String, text: String) {
        compose.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(text, substring = true)),
            useUnmergedTree = true,
        ).assertExists()
    }

    private val shown = mutableStateOf<RecipeEditorUiState?>(null)

    private fun setForm(state: RecipeEditorUiState) {
        shown.value = state
        compose.setContent {
            MueTheme {
                shown.value?.let { form ->
                    RecipeEditorScreen(
                        state = form,
                        actions = RecipeEditorActions(
                            onNameChange = { typedName = it },
                            onTypeSelected = { chosenType = it },
                            onQuantityChange = { index, raw -> typedQuantity = index to raw },
                            onRemoveIngredient = { removed = it },
                            onOpenPicker = { pickerOpened++ },
                            onPickFood = { picked = it },
                            onClosePicker = { pickerDismissed++ },
                            onSave = { saved++ },
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}
