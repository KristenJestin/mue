package fr.kristenjestin.mue.ui.food.recipes

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.ui.entry.FakeUserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The recipe form (PRD_FOOD 11, 15 and FR-RECIPE-001 to 003).
 *
 * The bounds are never restated here either: an expected message is asked of [FoodValidation] by
 * name, so a test cannot go on passing against a rule that has changed underneath it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEditorViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region creating (FR-RECIPE-001)

    @Test
    fun `a new form opens blank and creating is what it says it does`() = editorTest { editor ->
        editor.viewModel.start(null)

        val state = state(editor)
        assertEquals("", state.name)
        assertFalse(state.isEditing)
        assertEquals(RecipeMessages.SAVE_RECIPE, state.saveLabel)
        assertEquals(RecipeMessages.CREATE_TITLE, state.screenTitle)
    }

    @Test
    fun `a filled form writes the recipe it was given`() = editorTest { editor ->
        editor.viewModel.start(null)
        fill(editor)

        editor.viewModel.onSave()
        advanceUntilIdle()

        val written = assertNotNull(editor.recipes.saved.singleOrNull())
        assertEquals("Skyr bowl", written.recipe.name)
        assertEquals(RecipeType.BREAKFAST, written.recipe.type)
        assertEquals(2, written.recipe.baseServings)
        assertEquals(listOf("Spoon it out.", "Add the berries."), written.recipe.steps)
        assertEquals(1, written.ingredients.size)
        assertEquals(200.0, written.ingredients.first().quantity.amount)
        assertTrue(state(editor).justSaved)
    }

    /**
     * PRD_FOOD 21.2's snapshot is written **on the way in**, not only read on the way out.
     *
     * `RecipeIngredient.foodName` is what another device renders the row by when it has never
     * received the food, so a recipe saved without it would be the one that arrives unreadable.
     */
    @Test
    fun `a saved ingredient carries the food's name as its snapshot`() = editorTest { editor ->
        editor.viewModel.start(null)
        fill(editor)

        editor.viewModel.onSave()
        advanceUntilIdle()

        val ingredient = editor.recipes.saved.single().ingredients.single()
        assertEquals(skyr().name, ingredient.foodName)
    }

    // endregion

    // region what PRD_FOOD 15 refuses

    /**
     * **The empty recipe that would read `0 kcal` for ever.**
     *
     * `Nutrients.strictSum(emptyList())` is a *known* zero, so a recipe saved with no ingredient
     * would show `≈ 0 kcal` on its card and nothing would ever say otherwise.
     * `FoodValidation.validateIngredientCount` is what stops it, and this is the wire.
     */
    @Test
    fun `a recipe with no ingredient is refused, and says why`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onNameChange("Sunday roast")

        editor.viewModel.onSave()
        advanceUntilIdle()

        assertEquals(emptyList<RecipeDetail>(), editor.recipes.saved)
        assertEquals(
            FoodValidation.INGREDIENT_COUNT_ERROR,
            state(editor).ingredientCountError,
        )
    }

    @Test
    fun `a recipe with no name is refused, and says why`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()
        editor.viewModel.onQuantityChange(0, "200")

        editor.viewModel.onSave()
        advanceUntilIdle()

        assertEquals(emptyList<RecipeDetail>(), editor.recipes.saved)
        assertEquals(FoodValidation.NAME_ERROR, state(editor).nameError)
    }

    @Test
    fun `an impossible serving count is refused, and says why`() = editorTest { editor ->
        editor.viewModel.start(null)
        fill(editor)
        editor.viewModel.onBaseServingsChange("99")

        editor.viewModel.onSave()
        advanceUntilIdle()

        assertEquals(emptyList<RecipeDetail>(), editor.recipes.saved)
        assertEquals(FoodValidation.BASE_SERVINGS_ERROR, state(editor).baseServingsError)
    }

    @Test
    fun `an ingredient with no quantity is refused, and says why`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onNameChange("Skyr bowl")
        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()

        editor.viewModel.onSave()
        advanceUntilIdle()

        assertEquals(emptyList<RecipeDetail>(), editor.recipes.saved)
        assertEquals(
            FoodValidation.INGREDIENT_QUANTITY_ERROR,
            state(editor).ingredients.single().quantityError,
        )
    }

    /** PRD_FOOD 15: a refused value is signalled beside its field and the form is never emptied. */
    @Test
    fun `a refusal keeps everything that was typed`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onNameChange("Sunday roast")

        editor.viewModel.onSave()
        advanceUntilIdle()

        assertEquals("Sunday roast", state(editor).name)
    }

    /** Nothing is complained about before a save has been attempted. */
    @Test
    fun `a blank form says nothing until it is saved`() = editorTest { editor ->
        editor.viewModel.start(null)

        assertNull(state(editor).nameError)
        assertNull(state(editor).ingredientCountError)
        assertFalse(state(editor).hasError)
    }

    // endregion

    // region the live per-serving block (FR-RECIPE-003)

    /** A form with nothing in it has nothing to total, and shows no block rather than a zero. */
    @Test
    fun `an empty form shows no per-serving block at all`() = editorTest { editor ->
        editor.viewModel.start(null)

        assertNull(state(editor).perServing)
    }

    /**
     * A quantity nobody has typed yet is unknown, not absent.
     *
     * Totalling only the rows that parse would show half a recipe as if it were the whole one.
     */
    @Test
    fun `a row without a quantity leaves the block unknown`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()

        assertEquals(FoodLabels.UNKNOWN, assertNotNull(state(editor).perServing).energyLabel)
    }

    @Test
    fun `typing a quantity fills the block in`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()
        editor.viewModel.onBaseServingsChange("2")
        editor.viewModel.onQuantityChange(0, "200")

        // 200 g of a 63 kcal/100 g skyr is 126 kcal for the dish, so 63 kcal a serving.
        assertEquals("≈ 63 kcal", assertNotNull(state(editor).perServing).energyLabel)
    }

    /** A serving count that does not parse divides by nothing, so the block stays unknown. */
    @Test
    fun `an unreadable serving count leaves the block unknown`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()
        editor.viewModel.onQuantityChange(0, "200")
        editor.viewModel.onBaseServingsChange("")

        assertEquals(FoodLabels.UNKNOWN, assertNotNull(state(editor).perServing).energyLabel)
    }

    /**
     * FR-FOOD-010: the figures go and the form stays.
     *
     * The live block disappears, every row loses its energy, and the name, the quantity and the
     * ability to save are untouched — "le reste du module continue de fonctionner à l'identique".
     */
    @Test
    fun `hiding the energy takes the block and leaves the form`() = editorTest(
        preferences = UserPreferences(showEnergy = false),
    ) { editor ->
        editor.viewModel.start(null)
        fill(editor)

        val state = state(editor)
        assertNull(state.perServing)
        assertTrue(
            "a figure survived the preference",
            state.ingredients.all { it.energyLabel == null },
        )
        assertEquals("200", state.ingredients.single().quantity)

        editor.viewModel.onSave()
        advanceUntilIdle()
        assertEquals(1, editor.recipes.saved.size)
    }

    // endregion

    // region the ingredient picker (FR-RECIPE-002)

    /**
     * **The picker does not close on the first pick.**
     *
     * A recipe is several foods at once, and the activity module already learned what a sheet
     * that dismissed on selection costs. It counts instead, and closes when it is told to.
     */
    @Test
    fun `picking an ingredient leaves the picker open and counts it`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onOpenPicker()

        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()

        val picker = state(editor).picker
        assertTrue("the picker closed on the first pick", picker.visible)
        assertEquals(1, picker.addedCount)
        assertEquals(1, state(editor).ingredients.size)

        editor.viewModel.onClosePicker()
        assertFalse(state(editor).picker.visible)
    }

    /**
     * PRD_FOOD 8.3's `RecipeIngredientId` exists because the same food may appear twice — a
     * marinade and a sauce from the same oil — so a second pick is a second row, never a toggle.
     */
    @Test
    fun `picking the same food twice adds two rows`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onOpenPicker()

        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()
        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()

        assertEquals(2, state(editor).ingredients.size)
        assertEquals(2, state(editor).picker.addedCount)
    }

    /** PRD_FOOD 18: the addition is announced, and the announcement changes on every pick. */
    @Test
    fun `every pick is announced afresh`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onOpenPicker()

        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()
        val first = state(editor).picker.lastAdded

        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()
        val second = state(editor).picker.lastAdded

        assertTrue("the announcement did not change", first != second)
        assertTrue(assertNotNull(first).contains(skyr().name))
    }

    @Test
    fun `an ingredient can be taken back off the list`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()

        editor.viewModel.onRemoveIngredient(0)

        assertEquals(emptyList<RecipeEditorIngredientUiState>(), state(editor).ingredients)
    }

    /** The picker offers the recently used first, then the rest of the catalogue by name. */
    @Test
    fun `an empty search offers the recent then the catalogue`() = editorTest { editor ->
        editor.viewModel.start(null)
        editor.viewModel.onOpenPicker()

        val names = state(editor).picker.results.map { it.name }
        assertEquals(oats().name, names.first())
        assertTrue("the catalogue was not offered behind the recent", names.contains(skyr().name))
    }

    // endregion

    // region editing (FR-RECIPE-006)

    @Test
    fun `an existing recipe reopens as itself`() = editorTest { editor ->
        editor.viewModel.start(RecipePreviewData.SALMON_ID)
        advanceUntilIdle()

        val state = state(editor)
        assertTrue(state.isEditing)
        assertEquals(RecipePreviewData.LONGEST_NAME, state.name)
        assertEquals(3, state.ingredients.size)
        assertEquals("260", state.ingredients.first().quantity)
        assertEquals(RecipeMessages.SAVE_CHANGES, state.saveLabel)
    }

    @Test
    fun `saving an edit keeps the identity rather than creating a second recipe`() =
        editorTest { editor ->
            editor.viewModel.start(RecipePreviewData.SALMON_ID)
            advanceUntilIdle()

            editor.viewModel.onNameChange("Sheet-pan salmon")
            editor.viewModel.onSave()
            advanceUntilIdle()

            val written = editor.recipes.saved.single()
            assertEquals(RecipePreviewData.SALMON_ID, written.recipe.id)
            assertEquals("Sheet-pan salmon", written.recipe.name)
        }

    /**
     * FR-RECIPE-005 and PRD_FOOD 14: an edit keeps what this form does not offer to change.
     *
     * The star and the cover are both on the recipe and neither is a field of this screen, so
     * both travel through the draft untouched. A form that rebuilt the recipe from its own boxes
     * alone would silently unstar it and throw its picture away — a loss nothing on screen would
     * have announced, and nothing later could undo.
     */
    @Test
    fun `an edit keeps the star and the cover it was opened with`() = editorTest(
        details = listOf(withCover()),
    ) { editor ->
        editor.viewModel.start(RecipePreviewData.SALMON_ID)
        advanceUntilIdle()

        editor.viewModel.onSave()
        advanceUntilIdle()

        val written = editor.recipes.saved.single().recipe
        assertTrue(written.isFavourite)
        assertEquals(COVER, written.imageRef)
    }

    /**
     * PRD_FOOD 21.2, the other way round: a form opened on a recipe whose food never arrived
     * keeps the row and its snapshot, and can still be saved.
     *
     * Refusing would lose the very ingredient the snapshot exists to preserve.
     */
    @Test
    fun `a form can still be saved with an ingredient this device does not have`() =
        editorTest { editor ->
            editor.viewModel.start(RecipePreviewData.CURRY_ID)
            advanceUntilIdle()

            val orphan = state(editor).ingredients.first { it.isOrphan }
            assertEquals(RecipePreviewData.ORPHAN_SNAPSHOT, orphan.name)
            assertEquals(FoodLabels.UNKNOWN, orphan.energyLabel)

            editor.viewModel.onSave()
            advanceUntilIdle()

            val written = editor.recipes.saved.single()
            assertEquals(
                RecipePreviewData.ORPHAN_SNAPSHOT,
                written.ingredients.first { it.foodId == RecipePreviewData.ORPHAN_FOOD_ID }.foodName,
            )
        }

    // endregion

    // region what a draft survives

    /** The draft is one JSON string in the saved state, so a killed process loses nothing. */
    @Test
    fun `a half-typed form comes back from the saved state`() {
        val saved = SavedStateHandle()

        editorTest(savedState = saved) { editor ->
            editor.viewModel.start(null)
            editor.viewModel.onNameChange("Skyr bowl")
            editor.viewModel.onQuantityChange(0, "7,")
            advanceUntilIdle()
        }

        editorTest(savedState = saved) { editor ->
            // No `start`: the process died and came back on the same screen.
            assertEquals("Skyr bowl", state(editor).name)
        }
    }

    /** A draft written by another build is a draft that was never there, not a crash. */
    @Test
    fun `an unreadable draft opens a blank form`() {
        val saved = SavedStateHandle(mapOf(RecipeEditorViewModel.KEY_DRAFT to "{ not json"))

        editorTest(savedState = saved) { editor ->
            assertEquals("", state(editor).name)
        }
    }

    // endregion

    // region harness

    private class Editor(
        val viewModel: RecipeEditorViewModel,
        val recipes: FakeRecipeRepository,
    )

    private fun editorTest(
        details: List<RecipeDetail> = RecipePreviewData.details(),
        preferences: UserPreferences = UserPreferences.DEFAULT,
        savedState: SavedStateHandle = SavedStateHandle(),
        body: suspend TestScope.(Editor) -> Unit,
    ) = runTest(mainDispatcher) {
        val repository = FakeRecipeRepository(details)
        val editor = Editor(
            viewModel = RecipeEditorViewModel(
                recipes = repository,
                foods = FakeFoodCatalogueRepository(
                    foods = RecipePreviewData.catalogue() + listOf(skyr(), oats()),
                    recentlyUsed = listOf(oats()),
                ),
                preferences = FakeUserPreferencesRepository(preferences),
                savedStateHandle = savedState,
            ),
            recipes = repository,
        )

        val collector = launch { editor.viewModel.uiState.collect { } }
        advanceUntilIdle()

        body(editor)

        collector.cancel()
    }

    private fun TestScope.state(editor: Editor): RecipeEditorUiState {
        advanceUntilIdle()
        return editor.viewModel.uiState.value
    }

    /** The smallest form PRD_FOOD 15 accepts, plus the two optional fields it also allows. */
    private suspend fun TestScope.fill(editor: Editor) {
        editor.viewModel.onNameChange("Skyr bowl")
        editor.viewModel.onTypeSelected(RecipeType.BREAKFAST)
        editor.viewModel.onBaseServingsChange("2")
        editor.viewModel.onPrepTimeChange("5")
        editor.viewModel.onStepsChange("Spoon it out.\n\nAdd the berries.")
        editor.viewModel.onPickFood(skyr().id.value)
        advanceUntilIdle()
        editor.viewModel.onQuantityChange(0, "200")
    }

    private fun skyr(): Food = RecipeEditorTestFoods.SKYR

    private fun oats(): Food = RecipeEditorTestFoods.OATS

    /** PRD_FOOD 14's `files/recipe-images/{uuid}.webp`, as a stored recipe carries it. */
    private fun withCover(): RecipeDetail {
        val detail = RecipePreviewData.salmon()
        return detail.copy(recipe = detail.recipe.copy(imageRef = COVER))
    }

    private companion object {
        const val COVER: String = "recipe-images/3f1c9e2a.webp"
    }

    // endregion
}
