package fr.kristenjestin.mue.ui.food.catalogue

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.FoodDeletion
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NEW_ID = FoodId("minted")

/**
 * The `Food editor`'s four verbs (FR-CATALOG-003): create, correct, duplicate, delete.
 *
 * The deletion half is the reason this file is long. `FoodCatalogueRepository.delete` answers with
 * a value rather than an exception precisely because three of its four answers are things a
 * person has to read and act on, and PRD_FOOD 17 and 22 turn two of them into acceptance criteria.
 * Each branch is driven here, on the JVM, and asserted on the sentence it produces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodEditorViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region creating (PRD_FOOD 9.3)

    @Test
    fun `a new food is written with a fresh id and as a personal food`() = editorTest { editor ->
        editor.viewModel.onNameChange("Aunt Simone's walnut cake")
        editor.viewModel.onEnergyChange("412")
        editor.viewModel.onSave()
        advanceUntilIdle()

        val saved = editor.foods.saved.single()
        assertEquals(NEW_ID, saved.id)
        assertEquals("Aunt Simone's walnut cake", saved.name)
        assertEquals(FoodSource.CUSTOM, saved.source)
        assertTrue(state(editor).isFinished)
    }

    /** PRD_FOOD 15: a refused form writes nothing and keeps everything. */
    @Test
    fun `a refused form is not written and is not emptied`() = editorTest { editor ->
        editor.viewModel.onNameChange("")
        editor.viewModel.onEnergyChange("1200")
        editor.viewModel.onSave()
        advanceUntilIdle()

        assertTrue(editor.foods.saved.isEmpty())
        assertFalse(state(editor).isFinished)
        assertEquals("1200", state(editor).energy)
        assertNotNull(state(editor).nameError)
        assertNotNull(state(editor).energyError)
    }

    /** PRD_FOOD 17: a fruitless search hands its term straight to the form. */
    @Test
    fun `the term a search failed on arrives in the name field`() = editorTest(
        prefillName = "kombucha",
    ) { editor ->
        assertEquals("kombucha", state(editor).name)
    }

    /** PRD_FOOD 20.2: what was typed comes back after the process is killed. */
    @Test
    fun `a half-written card survives a process death`() = editorTest(
        savedState = SavedStateHandle(
            mapOf(
                FoodEditorViewModel.KEY_DRAFT to
                    FoodEditorDraft(name = "Half typed", energy = "7,").toJson(),
            ),
        ),
    ) { editor ->
        assertEquals("Half typed", state(editor).name)
        assertEquals("7,", state(editor).energy)
    }

    /** A restored draft is never overwritten by the row it was opened from. */
    @Test
    fun `a restored draft outranks the stored values`() = editorTest(
        foodId = FoodCataloguePreviewData.greekYoghurt().id,
        foods = listOf(FoodCataloguePreviewData.greekYoghurt()),
        savedState = SavedStateHandle(
            mapOf(
                FoodEditorViewModel.KEY_DRAFT to
                    FoodEditorDraft(name = "Renamed by hand").toJson(),
            ),
        ),
    ) { editor ->
        assertEquals("Renamed by hand", state(editor).name)
    }

    // endregion

    // region correcting and duplicating (PRD_FOOD 9.1 and 9.2)

    @Test
    fun `an existing food opens on its own values`() = editorTest(
        foodId = FoodCataloguePreviewData.greekYoghurt().id,
        foods = listOf(FoodCataloguePreviewData.greekYoghurt()),
    ) { editor ->
        val shown = state(editor)

        assertEquals(FoodEditorMode.EDIT, shown.mode)
        assertEquals(FoodCataloguePreviewData.YOGHURT_NAME, shown.name)
        assertEquals(FoodCataloguePreviewData.YOGHURT_BRAND, shown.brand)
        assertEquals(FoodCataloguePreviewData.YOGHURT_BARCODE, shown.barcode)
        assertEquals("", shown.fibre, "an unknown value opens blank, never as a zero")
        assertFalse(shown.isLoading)
    }

    /** PRD_FOOD 9.2: a correction keeps the row's own id, so it replaces rather than twins. */
    @Test
    fun `correcting a packaged product writes over the same row`() = editorTest(
        foodId = FoodCataloguePreviewData.greekYoghurt().id,
        foods = listOf(FoodCataloguePreviewData.greekYoghurt()),
    ) { editor ->
        editor.viewModel.onFibreChange("0.4")
        editor.viewModel.onSave()
        advanceUntilIdle()

        val saved = editor.foods.saved.single()
        assertEquals(FoodCataloguePreviewData.greekYoghurt().id, saved.id)
        assertEquals(FoodSource.OPEN_FOOD_FACTS, saved.source)
        assertEquals(FoodCataloguePreviewData.YOGHURT_BARCODE, saved.barcode)
    }

    /** PRD_FOOD 9.1: a reference entry is read here, duplicated, and never written. */
    @Test
    fun `a reference entry opens read-only and saves as a personal copy`() = editorTest(
        foodId = FoodCataloguePreviewData.rolledOats().id,
        foods = listOf(FoodCataloguePreviewData.rolledOats()),
    ) { editor ->
        val shown = state(editor)
        assertEquals(FoodEditorMode.REFERENCE, shown.mode)
        assertTrue(shown.isReadOnly)
        assertTrue(shown.canDuplicate)
        assertFalse(shown.canDelete)
        assertEquals(FoodCatalogueMessages.DUPLICATE, shown.primaryLabel)

        editor.viewModel.onSave()
        advanceUntilIdle()

        val copy = editor.foods.saved.single()
        assertEquals(NEW_ID, copy.id, "a duplicate is a new row, never a write onto the original")
        assertEquals(FoodSource.CUSTOM, copy.source)
        assertNull(copy.sourceId)
        assertEquals(FoodCataloguePreviewData.OATS_NAME, copy.name)
    }

    /**
     * PRD_FOOD 9.1 from the other side.
     *
     * The repository is the authority, and it can refuse a write this screen believed was
     * allowed — a stale row, another device, an MCP write. The refusal is shown rather than
     * swallowed, and the sheet stays open with everything still in it.
     */
    @Test
    fun `a write the repository refuses is explained and nothing is lost`() = editorTest(
        foodId = FoodCataloguePreviewData.greekYoghurt().id,
        foods = listOf(FoodCataloguePreviewData.greekYoghurt()),
    ) { editor ->
        editor.foods.saveAccepts = false

        editor.viewModel.onSave()
        advanceUntilIdle()

        assertTrue(state(editor).saveRefused)
        assertFalse(state(editor).isFinished)
        assertEquals(FoodCataloguePreviewData.YOGHURT_NAME, state(editor).name)
    }

    /** A refusal is about the last attempt, so the next keystroke clears it. */
    @Test
    fun `editing a field clears a stale refusal`() = editorTest(
        foodId = FoodCataloguePreviewData.greekYoghurt().id,
        foods = listOf(FoodCataloguePreviewData.greekYoghurt()),
    ) { editor ->
        editor.foods.saveAccepts = false
        editor.viewModel.onSave()
        advanceUntilIdle()
        assertTrue(state(editor).saveRefused)

        editor.viewModel.onNameChange("Greek yoghurt")

        assertFalse(state(editor).saveRefused)
    }

    // endregion

    // region deleting — the four answers (PRD_FOOD 9.3, 17 and 22)

    @Test
    fun `deleting asks first`() = editorTest(
        foodId = FoodCataloguePreviewData.blackCoffee().id,
        foods = listOf(FoodCataloguePreviewData.blackCoffee()),
    ) { editor ->
        assertNull(state(editor).deletion)

        editor.viewModel.onDeleteRequested()

        assertEquals(FoodDeletionUiState.Confirming, state(editor).deletion)
        assertTrue(editor.foods.deleted.isEmpty(), "asking is not deleting")
    }

    @Test
    fun `a confirmed deletion removes the food and closes the sheet`() = editorTest(
        foodId = FoodCataloguePreviewData.blackCoffee().id,
        foods = listOf(FoodCataloguePreviewData.blackCoffee()),
    ) { editor ->
        editor.viewModel.onDeleteRequested()
        editor.viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertEquals(listOf(FoodCataloguePreviewData.blackCoffee().id), editor.foods.deleted)
        assertNull(state(editor).deletion)
        assertTrue(state(editor).isFinished)
    }

    /**
     * PRD_FOOD 17 and 22: "un aliment utilisé par une recette ne peut pas être supprimé **et les
     * recettes concernées sont nommées**".
     *
     * Named, not counted. `UsedByRecipes` carries the list for exactly this sentence, and a
     * message saying "used by 2 recipes" would leave the person to open every recipe they own.
     */
    @Test
    fun `a food a recipe uses is refused, and the recipes are named`() = editorTest(
        foodId = FoodCataloguePreviewData.blackCoffee().id,
        foods = listOf(FoodCataloguePreviewData.blackCoffee()),
    ) { editor ->
        editor.foods.deletion = FoodDeletion.UsedByRecipes(
            listOf("Tiramisu", "Overnight oats"),
        )

        editor.viewModel.onDeleteRequested()
        editor.viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        val refusal = state(editor).deletion as FoodDeletionUiState.Refused
        assertTrue(refusal.message.contains("Tiramisu"), refusal.message)
        assertTrue(refusal.message.contains("Overnight oats"), refusal.message)
        assertEquals(
            "“Tiramisu” and “Overnight oats” recipes use this food. Remove it from those " +
                "recipes first, then delete it.",
            refusal.message,
        )
        assertFalse(state(editor).isFinished, "the sheet stays open on a refusal")
    }

    /** One recipe reads as one recipe, not as a list of one. */
    @Test
    fun `a single recipe is named in the singular`() = editorTest(
        foodId = FoodCataloguePreviewData.blackCoffee().id,
        foods = listOf(FoodCataloguePreviewData.blackCoffee()),
    ) { editor ->
        editor.foods.deletion = FoodDeletion.UsedByRecipes(listOf("Tiramisu"))

        editor.viewModel.onDeleteRequested()
        editor.viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertEquals(
            "“Tiramisu” recipe uses this food. Remove it from that recipe first, then delete it.",
            (state(editor).deletion as FoodDeletionUiState.Refused).message,
        )
    }

    /**
     * PRD_FOOD 9.1: reference data is not the person's to remove — and the way out is named.
     *
     * The control is hidden on a reference entry, so this branch is only reachable through a
     * stale screen, an assistive service or an MCP write. It is handled all the same: the
     * repository is the authority, and a refusal nobody rendered would be a button that did
     * nothing.
     */
    @Test
    fun `a reference entry cannot be deleted, and the refusal offers the duplicate`() = editorTest(
        foodId = FoodCataloguePreviewData.rolledOats().id,
        foods = listOf(FoodCataloguePreviewData.rolledOats()),
    ) { editor ->
        editor.foods.deletion = FoodDeletion.ReadOnly

        editor.viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        val refusal = state(editor).deletion as FoodDeletionUiState.Refused
        assertEquals(FoodCatalogueMessages.READ_ONLY_REFUSAL, refusal.message)
        assertTrue(refusal.message.contains("Duplicate"), refusal.message)
        assertFalse(state(editor).isFinished)
    }

    /** PRD_FOOD 17: a row somebody else already removed. Nothing was deleted and nothing lost. */
    @Test
    fun `a food that is already gone says so`() = editorTest(
        foodId = FoodCataloguePreviewData.blackCoffee().id,
        foods = listOf(FoodCataloguePreviewData.blackCoffee()),
    ) { editor ->
        editor.foods.deletion = FoodDeletion.NotFound

        editor.viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertEquals(
            FoodCatalogueMessages.NOT_FOUND,
            (state(editor).deletion as FoodDeletionUiState.Refused).message,
        )
    }

    /** A food that was never saved has nothing to delete, and the repository is not troubled. */
    @Test
    fun `deleting a food that has never been saved asks the repository nothing`() =
        editorTest { editor ->
            editor.viewModel.onDeleteConfirmed()
            advanceUntilIdle()

            assertTrue(editor.foods.deleted.isEmpty())
            assertEquals(
                FoodCatalogueMessages.NOT_FOUND,
                (state(editor).deletion as FoodDeletionUiState.Refused).message,
            )
        }

    @Test
    fun `dismissing the question leaves the food alone`() = editorTest(
        foodId = FoodCataloguePreviewData.blackCoffee().id,
        foods = listOf(FoodCataloguePreviewData.blackCoffee()),
    ) { editor ->
        editor.viewModel.onDeleteRequested()
        editor.viewModel.onDeletionDismissed()

        assertNull(state(editor).deletion)
        assertTrue(editor.foods.deleted.isEmpty())
    }

    // endregion

    // region harness

    private class Editor(val viewModel: FoodEditorViewModel, val foods: FakeFoodCatalogue)

    private fun editorTest(
        foodId: FoodId? = null,
        foods: List<Food> = emptyList(),
        prefillName: String? = null,
        savedState: SavedStateHandle = SavedStateHandle(),
        body: suspend TestScope.(Editor) -> Unit,
    ) = runTest(mainDispatcher) {
        val catalogue = FakeFoodCatalogue(foods = foods)
        val editor = Editor(
            viewModel = FoodEditorViewModel(
                foods = catalogue,
                foodId = foodId,
                prefillName = prefillName,
                savedStateHandle = savedState,
                newId = { NEW_ID },
            ),
            foods = catalogue,
        )

        val collector = launch { editor.viewModel.uiState.collect { } }
        advanceUntilIdle()

        body(editor)

        collector.cancel()
    }

    private fun TestScope.state(editor: Editor): FoodEditorUiState {
        advanceUntilIdle()
        return editor.viewModel.uiState.value
    }

    // endregion
}
