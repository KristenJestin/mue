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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The name whose fold is `bœuf saute`, which no amount of NFD turns into `boeuf saute`. */
private const val BEEF_NAME: String = "Bœuf sauté, maison"

/**
 * The one search bar of PRD_FOOD 9.4, and what it asks the catalogue for.
 *
 * The assertions are about the **query**, not only about the rows: which limit, which source, and
 * that an empty search reads the recently used *and* the catalogue rather than one instead of the
 * other. Those are what keep the search usable over 1 038 seeded entries, and a test that only
 * looked at the result list would pass just as happily with the whole table pulled into memory.
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
    fun `an empty search puts the recently used at the head of the catalogue`() = pickerTest {
        picker ->
        val state = state(picker)

        assertTrue(state.isRecent)
        assertEquals(FoodAddMessages.RECENT_SECTION, state.recentTitle)
        assertEquals(listOf(FoodAddPreviewData.APPLE_NAME), state.recent.map { it.name })
        assertEquals(listOf(FoodPickerViewModel.RECENT_LIMIT), picker.foods.recentLimits)

        // The catalogue is under it, not instead of it (PRD_FOOD 9.4: "en tête").
        assertEquals(FoodAddMessages.CATALOGUE_SECTION, state.sectionTitle)
        assertTrue(state.results.isNotEmpty(), "the catalogue was not read at all")
        assertEquals(
            listOf(Triple<String, FoodSource?, Int>("", null, FoodPickerViewModel.SEARCH_LIMIT)),
            picker.foods.searches,
        )
    }

    /**
     * The first defect, in one assertion.
     *
     * Recency comes from the journal, so a phone that has logged nothing has none — and the
     * picker used to answer that with `Nothing logged yet` over a catalogue holding 1 038 seeded
     * entries, while `Foods` two taps away listed them all. An empty head is not an empty list.
     */
    @Test
    fun `a journal with nothing in it still shows the whole catalogue`() = pickerTest(
        recent = emptyList(),
    ) { picker ->
        val state = state(picker)

        assertTrue(state.recent.isEmpty())
        assertFalse(state.isEmpty, "a full catalogue was drawn as an empty picker")
        assertEquals(
            FoodAddPreviewData.catalogue().map { it.name } + "Own brand oat biscuits",
            state.results.map { it.name },
        )
        assertNull(state.emptyMessage)
    }

    /** A food eaten yesterday is also in the catalogue; one food is never two cards. */
    @Test
    fun `a recently used food is not repeated under the catalogue`() = pickerTest { picker ->
        val state = state(picker)

        val recentIds = state.recent.map { it.id }
        assertEquals(listOf(FoodAddPreviewData.apple().id.value), recentIds)
        assertTrue(
            state.results.none { it.id in recentIds },
            "the same food was drawn under both headings",
        )
    }

    /**
     * PRD_FOOD 17: nothing logged yet is a fact, not an error — but it can only be *said* when
     * there is genuinely nothing to choose, which on a seeded phone never happens.
     */
    @Test
    fun `only an empty catalogue and an empty journal together say nothing is here`() = pickerTest(
        catalogue = emptyList(),
        recent = emptyList(),
    ) { picker ->
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
        assertTrue(state.recent.isEmpty(), "a typed search was still headed by the recents")
        assertEquals(listOf(FoodAddPreviewData.RICE_NAME), state.results.map { it.name })
        assertEquals(
            Triple<String, FoodSource?, Int>("rice", null, FoodPickerViewModel.SEARCH_LIMIT),
            picker.foods.searches.last(),
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
        assertEquals("rice", picker.foods.searches.last().first)
    }

    /**
     * PRD_FOOD 9.4's insensitivity, on the one letter NFD cannot decompose.
     *
     * `œ` is a letter in its own right, not an `o` with a mark, so `Bœuf sauté` folds to
     * `bœuf saute` and `boeuf` misses it. The equivalence is carried by the *query* — see
     * `Food.ligatureVariantOf` — so nothing stored has to be re-folded, and it works in both
     * directions: the row here is written with the ligature and is found without one.
     */
    @Test
    fun `a ligature is found by either of its spellings`() = pickerTest(
        catalogue = listOf(sauteedBeef()),
    ) { picker ->
        picker.viewModel.onQueryChange("boeuf")
        assertEquals(listOf(BEEF_NAME), state(picker).results.map { it.name })

        picker.viewModel.onQueryChange("bœuf")
        assertEquals(listOf(BEEF_NAME), state(picker).results.map { it.name })
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
    fun `a source filter restricts the search and silences the head`() = pickerTest { picker ->
        picker.viewModel.onSourceSelected(FoodSource.CUSTOM)

        val state = state(picker)
        assertEquals(listOf(FoodAddPreviewData.LONGEST_NAME), state.results.map { it.name })
        assertTrue(state.recent.isEmpty(), "a filtered list was headed by an unfiltered recent")
        assertEquals(
            Triple<String, FoodSource?, Int>("", FoodSource.CUSTOM, FoodPickerViewModel.SEARCH_LIMIT),
            picker.foods.searches.last(),
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

    /** A personal food carrying the one ligature the fold cannot decompose. */
    private fun sauteedBeef(): Food = Food(
        id = FoodId("preview-beef"),
        name = BEEF_NAME,
        source = FoodSource.CUSTOM,
        per100 = Nutrients.UNKNOWN,
    )

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
