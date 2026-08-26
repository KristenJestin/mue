package fr.kristenjestin.mue.ui.food.add

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Nutrients
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
import kotlin.test.assertTrue

/**
 * The one search bar of PRD_FOOD 9.4, and what it asks the catalogue for.
 *
 * The assertions are about the **query**, not only about the rows: which limit, which source, and
 * whether an empty search reads the recently used or the catalogue. Those are what keep the
 * search usable over 1 038 seeded entries, and a test that only looked at the result list would
 * pass just as happily with the whole table pulled into memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodPickerViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region what an empty search shows (PRD_FOOD 9.4)

    @Test
    fun `an empty search shows what was logged most recently`() = pickerTest { picker ->
        val state = state(picker)

        assertTrue(state.isRecent)
        assertEquals(FoodAddMessages.RECENT_SECTION, state.sectionTitle)
        assertEquals(listOf(FoodAddPreviewData.APPLE_NAME), state.results.map { it.name })
        assertEquals(listOf(FoodPickerViewModel.RECENT_LIMIT), picker.foods.recentLimits)
        assertTrue(picker.foods.searches.isEmpty(), "an empty search queried the catalogue")
    }

    /** PRD_FOOD 17: nothing logged yet is a fact, not an error, and it says where to go next. */
    @Test
    fun `an empty search with nothing logged says so`() = pickerTest(recent = emptyList()) {
        picker ->
        val state = state(picker)

        assertTrue(state.isEmpty)
        assertEquals(FoodAddMessages.NOTHING_RECENT, state.emptyMessage)
    }

    // endregion

    // region searching (PRD_FOOD 9.4)

    @Test
    fun `typing searches the catalogue, capped and unfiltered`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("rice")

        val state = state(picker)
        assertFalse(state.isRecent)
        assertEquals(FoodAddMessages.RESULTS_SECTION, state.sectionTitle)
        assertEquals(listOf(FoodAddPreviewData.RICE_NAME), state.results.map { it.name })
        assertEquals(
            listOf(Triple<String, FoodSource?, Int>("rice", null, FoodPickerViewModel.SEARCH_LIMIT)),
            picker.foods.searches,
        )
    }

    /** PRD_FOOD 9.4: "la recherche est insensible à la casse et aux accents". */
    @Test
    fun `case and accents do not change what is found`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("ÀPPLE")

        assertEquals(listOf(FoodAddPreviewData.APPLE_NAME), state(picker).results.map { it.name })
    }

    /** Surrounding spaces are not a search term, and must not cost a query of their own. */
    @Test
    fun `a query is trimmed before it reaches the catalogue`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("  rice  ")

        state(picker)
        assertEquals(listOf("rice"), picker.foods.searches.map { it.first })
    }

    @Test
    fun `clearing the search goes back to the recently used`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("rice")
        state(picker)

        picker.viewModel.onClearQuery()

        assertTrue(state(picker).isRecent)
    }

    /** PRD_FOOD 17: a search that matches nothing offers the creation instead. */
    @Test
    fun `a search with no result says so and keeps the term`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("sauerkraut ice cream")

        val state = state(picker)
        assertTrue(state.isEmpty)
        assertEquals(FoodAddMessages.NO_RESULTS, state.emptyMessage)
        assertEquals("sauerkraut ice cream", picker.viewModel.searchTerm)
    }

    // endregion

    // region the source filter (PRD_FOOD 9.4)

    @Test
    fun `a source filter restricts the search`() = pickerTest { picker ->
        picker.viewModel.onSourceSelected(FoodSource.CUSTOM)

        val state = state(picker)
        assertEquals(listOf(FoodAddPreviewData.LONGEST_NAME), state.results.map { it.name })
        assertEquals(
            listOf<Triple<String, FoodSource?, Int>>(Triple("", FoodSource.CUSTOM, FoodPickerViewModel.SEARCH_LIMIT)),
            picker.foods.searches,
        )
        assertTrue(
            picker.foods.recentLimits.size <= 1,
            "a filtered empty search still read the recently used",
        )
    }

    @Test
    fun `every source is offered, with the chosen one marked`() = pickerTest { picker ->
        picker.viewModel.onSourceSelected(FoodSource.CIQUAL)

        val sources = state(picker).sources
        assertEquals(
            listOf(null, FoodSource.CIQUAL, FoodSource.OPEN_FOOD_FACTS, FoodSource.CUSTOM),
            sources.map { it.source },
        )
        assertEquals(
            listOf(FoodSource.CIQUAL),
            sources.filter { it.selected }.map { it.source },
        )
    }

    // endregion

    // region what a row says (PRD_FOOD 13.2 and 9.2)

    /**
     * PRD_FOOD 9.2: "une fiche incomplète est acceptée : les valeurs manquantes restent `null`".
     *
     * So a product nobody has filled in reads `—` in the list, never `0 kcal` — which would claim
     * a packet of biscuits was free of energy.
     */
    @Test
    fun `a product with no values reads as unknown and never as zero`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("Own brand")

        val row = state(picker).results.single()
        assertEquals(FoodLabels.UNKNOWN, row.energyLabel)
        assertTrue(row.description.contains("unknown"))
    }

    @Test
    fun `a row says where its food came from`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("rice")

        val row = assertNotNull(state(picker).results.firstOrNull())
        assertEquals(FoodAddMessages.SOURCE_CIQUAL, row.meta)
        assertEquals("≈ 349 kcal", row.energyLabel)
        assertEquals("Per 100 g raw", row.per100Label)
    }

    @Test
    fun `a branded product shows its brand beside its provenance`() = pickerTest { picker ->
        picker.viewModel.onQueryChange("Own brand")

        val row = state(picker).results.single()
        assertEquals("Own brand · ${FoodAddMessages.SOURCE_OPEN_FOOD_FACTS}", row.meta)
    }

    // endregion

    // region what survives (PRD 16.4)

    @Test
    fun `a search comes back after the process dies`() {
        val savedState = SavedStateHandle()

        pickerTest(savedState = savedState) { picker ->
            picker.viewModel.onQueryChange("rice")
            state(picker)
        }

        pickerTest(savedState = savedState) { picker ->
            assertEquals("rice", state(picker).query)
        }
    }

    // endregion

    // region harness

    private class Picker(
        val viewModel: FoodPickerViewModel,
        val foods: RecordingFoodCatalogueRepository,
    )

    private fun pickerTest(
        catalogue: List<Food> = FoodAddPreviewData.catalogue() + ownBrand(),
        recent: List<Food> = listOf(FoodAddPreviewData.apple()),
        savedState: SavedStateHandle = SavedStateHandle(),
        body: suspend TestScope.(Picker) -> Unit,
    ) = runTest(mainDispatcher) {
        val foods = RecordingFoodCatalogueRepository(catalogue, recent)
        val picker = Picker(
            viewModel = FoodPickerViewModel(foods = foods, savedState = savedState),
            foods = foods,
        )

        val collector = launch { picker.viewModel.uiState.collect { } }
        advanceUntilIdle()

        body(picker)

        collector.cancel()
    }

    private fun TestScope.state(picker: Picker): FoodPickerUiState {
        advanceUntilIdle()
        return picker.viewModel.uiState.value
    }

    /** PRD_FOOD 9.2's nominal case: a scanned product whose card states nothing at all. */
    private fun ownBrand(): Food = Food(
        id = FoodId("preview-own-brand"),
        name = "Own brand oat biscuits",
        source = FoodSource.OPEN_FOOD_FACTS,
        per100 = Nutrients.UNKNOWN,
        brand = "Own brand",
        barcode = "3123456789012",
    )

    // endregion
}
