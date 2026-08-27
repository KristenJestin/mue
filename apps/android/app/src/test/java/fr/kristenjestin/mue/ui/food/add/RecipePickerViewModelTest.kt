package fr.kristenjestin.mue.ui.food.add

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.ui.food.recipes.FakeRecipeRepository
import fr.kristenjestin.mue.ui.food.recipes.RecipePreviewData
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
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-FOOD-004's picker: what `Use a recipe` opens now that it opens something.
 *
 * The rules asserted here are the ones a person meets: every saved recipe is offered, a search
 * narrows it, and the two empty lists PRD_FOOD 17 keeps apart stay apart — a catalogue nobody has
 * written in is invited to write one, a filter that matches none of several recipes is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipePickerViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an empty search offers every saved recipe`() = pickerTest { picker ->
        val state = state(picker)

        assertEquals(
            RecipePreviewData.recipes().map { it.name },
            state.results.map { it.name },
        )
        assertEquals(FoodAddMessages.RECIPE_RESULTS_SECTION, state.sectionTitle)
        assertNull(state.emptyMessage)
        assertTrue(state.hasAnyRecipe)
    }

    @Test
    fun `typing narrows the list to the recipes that match`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("lentil")

        val state = state(picker)
        assertEquals(listOf(RecipePreviewData.CURRY_NAME), state.results.map { it.name })
        assertEquals(FoodAddMessages.RESULTS_SECTION, state.sectionTitle)
    }

    @Test
    fun `clearing the search brings every recipe back`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("lentil")
        state(picker)

        picker.viewModel.onClearQuery()

        assertEquals(RecipePreviewData.recipes().size, state(picker).results.size)
    }

    /**
     * A row says what a recipe card says, and **no energy at all**.
     *
     * PRD_FOOD 8.3 stores no nutritional value on a recipe and `observeAll` returns them without
     * their ingredients, so any figure here could only be invented — which PRD_FOOD 13.1 forbids
     * more plainly than anything else in the module.
     */
    @Test
    fun `a row shows the facts a recipe actually carries`() = pickerTest { picker ->
        val row = state(picker).results.first { it.id == RecipePreviewData.SALMON_ID.value }

        assertEquals("Main · Serves 2 · 25 min", row.meta)
        assertTrue(row.description.contains(RecipePreviewData.LONGEST_NAME))
        assertFalse(row.meta.contains("kcal"), "a recipe row invented an energy")
    }

    /** PRD_FOOD 17: "aucune recette enregistrée" — the invitation, and no fake recipe. */
    @Test
    fun `a person with no recipes is invited to write one`() = pickerTest(recipes = emptyList()) {
        picker ->
        val state = state(picker)

        assertTrue(state.isEmpty)
        assertFalse(state.hasAnyRecipe)
        assertEquals(FoodAddMessages.NO_RECIPES, state.emptyMessage)
    }

    /** The other empty list: recipes exist, this word simply matches none of them. */
    @Test
    fun `a search that matches nothing says so instead`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("sauerkraut")

        val state = state(picker)
        assertTrue(state.isEmpty)
        assertTrue(state.hasAnyRecipe)
        assertEquals(FoodAddMessages.NO_RECIPE_MATCHES, state.emptyMessage)
    }

    /** PRD 16.4: what was typed comes back after the process dies. */
    @Test
    fun `a search comes back after the process dies`() {
        val savedState = SavedStateHandle()

        pickerTest(savedState = savedState) { picker ->
            picker.viewModel.onQueryChange("lentil")
            state(picker)
        }

        pickerTest(savedState = savedState) { picker ->
            assertEquals("lentil", state(picker).query)
        }
    }

    // region harness

    private class Picker(
        val viewModel: RecipePickerViewModel,
        val recipes: FakeRecipeRepository,
    )

    private fun pickerTest(
        recipes: List<RecipeDetail> = RecipePreviewData.details(),
        savedState: SavedStateHandle = SavedStateHandle(),
        body: suspend TestScope.(Picker) -> Unit,
    ) = runTest(mainDispatcher) {
        val store = FakeRecipeRepository(recipes)
        val picker = Picker(
            viewModel = RecipePickerViewModel(recipes = store, savedState = savedState),
            recipes = store,
        )

        val collector = launch { picker.viewModel.uiState.collect { } }
        advanceUntilIdle()

        body(picker)

        collector.cancel()
    }

    private fun TestScope.state(picker: Picker): RecipePickerUiState {
        advanceUntilIdle()
        return picker.viewModel.uiState.value
    }

    // endregion
}
