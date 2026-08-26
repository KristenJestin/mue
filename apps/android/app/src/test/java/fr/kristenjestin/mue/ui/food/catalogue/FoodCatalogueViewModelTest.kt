package fr.kristenjestin.mue.ui.food.catalogue

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.ui.entry.FakeUserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `Foods` view against a catalogue the size of the one PRD_FOOD 9.1 seeds.
 *
 * Two things are proved here that no screenshot and no instrumented test could show: **which**
 * query the screen issues, and **how many** rows it asks for. Both are the difference between a
 * catalogue of 1 038 entries that is pleasant to search and one that stutters on every keystroke,
 * and both are invisible in the result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodCatalogueViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region searching 1 038 foods (PRD_FOOD 9.4 and 9.5)

    /**
     * The screen never asks the database for the whole catalogue.
     *
     * 1 038 rows exist, 60 reach the state. This is the assertion the browse view is built
     * around: a `LazyColumn` composes lazily, but a thousand `FoodRowUiState` objects would have
     * been rendered — five `FoodLabels` calls each — before it ever got the chance.
     */
    @Test
    fun `a catalogue of 1038 foods reaches the screen 60 at a time`() = catalogueTest(
        foods = aLargeCatalogue(),
    ) { harness ->
        val state = state(harness)

        assertEquals(FoodCatalogueViewModel.RESULT_LIMIT, state.results.size)
        assertTrue(state.isCapped, "a full page has to admit that it is one")
        assertEquals(
            FoodCatalogueViewModel.RESULT_LIMIT,
            harness.foods.searches.last().limit,
            "the limit belongs in the statement, not in a `take` after it",
        )
    }

    /**
     * PRD_FOOD 9.4: typing a word is one query, not one per letter.
     *
     * Seven keystrokes, one `LIKE '%…%'` over 1 038 rows. `flatMapLatest` cancels each pending
     * read as the next letter lands, so the six abandoned prefixes never reach the database at
     * all — which is also why the list can never settle on the answer to `chick`.
     */
    @Test
    fun `typing a word issues one query and not one per letter`() = catalogueTest(
        foods = aLargeCatalogue(),
    ) { harness ->
        val before = harness.foods.searches.size

        "chicken".forEachIndexed { index, _ ->
            harness.viewModel.onQueryChange("chicken".take(index + 1))
            advanceTimeBy(20)
        }
        advanceUntilIdle()

        val issued = harness.foods.searches.drop(before)
        assertEquals(1, issued.size, "issued $issued")
        assertEquals("chicken", issued.single().query)
    }

    /** The debounce is a delay and not a coincidence: before it elapses, nothing has been asked. */
    @Test
    fun `nothing is asked of the database until the typing settles`() = catalogueTest(
        foods = aLargeCatalogue(),
    ) { harness ->
        val before = harness.foods.searches.size

        harness.viewModel.onQueryChange("oat")
        advanceTimeBy(FoodCatalogueViewModel.SEARCH_DELAY_MILLIS - 1)
        assertEquals(before, harness.foods.searches.size)

        advanceTimeBy(2)
        assertEquals(before + 1, harness.foods.searches.size)
    }

    /** Clearing the field is not typing: the catalogue comes back without a pause. */
    @Test
    fun `clearing the search asks at once`() = catalogueTest(foods = aLargeCatalogue()) { harness ->
        harness.viewModel.onQueryChange("oat")
        advanceUntilIdle()
        val before = harness.foods.searches.size

        harness.viewModel.onClearQuery()
        // No time is advanced at all beyond running what is already runnable.
        runCurrent()

        assertEquals(before + 1, harness.foods.searches.size)
        assertEquals("", harness.foods.searches.last().query)
    }

    /** PRD_FOOD 9.4: the search is insensitive to case and to accents, and offline. */
    @Test
    fun `the search folds case and accents`() = catalogueTest(
        foods = listOf(
            FoodCataloguePreviewData.rolledOats().copy(name = "Purée de pommes de terre"),
            FoodCataloguePreviewData.greekYoghurt(),
        ),
    ) { harness ->
        harness.viewModel.onQueryChange("PUREE DE POMMES")
        advanceUntilIdle()

        assertEquals(
            listOf("Purée de pommes de terre"),
            state(harness).results.map { it.name },
        )
    }

    // endregion

    // region the filter and the recents (PRD_FOOD 9.4)

    @Test
    fun `the filter restricts the list to one source and gives it back`() = catalogueTest(
        foods = FoodCataloguePreviewData.catalogue(),
    ) { harness ->
        harness.viewModel.onSourceChange(FoodSource.CUSTOM)
        advanceUntilIdle()

        val filtered = state(harness)
        assertEquals(FoodSource.CUSTOM, filtered.source)
        assertTrue(filtered.results.all { it.source == FoodSource.CUSTOM })
        assertEquals(FoodSource.CUSTOM, harness.foods.searches.last().source)

        harness.viewModel.onSourceChange(null)
        advanceUntilIdle()

        assertEquals(FoodCataloguePreviewData.catalogue().size, state(harness).results.size)
    }

    @Test
    fun `the recently used head an empty search`() = catalogueTest(
        foods = FoodCataloguePreviewData.catalogue(),
        recent = listOf(FoodCataloguePreviewData.greekYoghurt()),
    ) { harness ->
        assertEquals(
            listOf(FoodCataloguePreviewData.YOGHURT_NAME),
            state(harness).recent.map { it.name },
        )
    }

    /** A search is a question about the catalogue, not about what was eaten lately. */
    @Test
    fun `the recently used step aside as soon as something is typed`() = catalogueTest(
        foods = FoodCataloguePreviewData.catalogue(),
        recent = listOf(FoodCataloguePreviewData.greekYoghurt()),
    ) { harness ->
        harness.viewModel.onQueryChange("oats")
        advanceUntilIdle()

        assertFalse(state(harness).hasRecent)
    }

    /** And a filter is a search of a kind, so it silences them too. */
    @Test
    fun `the recently used step aside under a filter`() = catalogueTest(
        foods = FoodCataloguePreviewData.catalogue(),
        recent = listOf(FoodCataloguePreviewData.greekYoghurt()),
    ) { harness ->
        harness.viewModel.onSourceChange(FoodSource.CIQUAL)
        advanceUntilIdle()

        assertFalse(state(harness).hasRecent)
    }

    // endregion

    // region `Show energy` (PRD_FOOD 13.2, FR-FOOD-010)

    /**
     * PRD_FOOD 22: hiding the energy takes every figure off the catalogue and breaks nothing.
     *
     * The rows are still there, still named, still searchable — which is the second half of the
     * criterion and the half a screenshot of an empty list would fail.
     */
    @Test
    fun `hiding energy empties the figures and leaves the catalogue usable`() = catalogueTest(
        foods = FoodCataloguePreviewData.catalogue(),
        preferences = UserPreferences(showEnergy = false),
    ) { harness ->
        val state = state(harness)

        assertFalse(state.showEnergy)
        assertEquals(FoodCataloguePreviewData.catalogue().size, state.results.size)
        assertTrue(state.results.none { it.hasFigures })
        assertTrue(state.results.all { it.name.isNotBlank() })
    }

    /** The preference is read live: turning it back on refills the rows without a reload. */
    @Test
    fun `turning the preference back on brings the figures back`() = catalogueTest(
        foods = FoodCataloguePreviewData.catalogue(),
        preferences = UserPreferences(showEnergy = false),
    ) { harness ->
        assertTrue(state(harness).results.none { it.hasFigures })

        harness.preferences.setShowEnergy(true)
        advanceUntilIdle()

        assertTrue(state(harness).results.all { it.hasFigures })
    }

    // endregion

    // region what survives (PRD_FOOD 20.2)

    @Test
    fun `the search term and the filter survive a process death`() = catalogueTest(
        foods = FoodCataloguePreviewData.catalogue(),
        savedState = SavedStateHandle(
            mapOf(
                FoodCatalogueViewModel.KEY_QUERY to "oats",
                FoodCatalogueViewModel.KEY_SOURCE to FoodSource.CIQUAL.id,
            ),
        ),
    ) { harness ->
        val state = state(harness)

        assertEquals("oats", state.query)
        assertEquals(FoodSource.CIQUAL, state.source)
    }

    /** Nothing has been read yet, and the screen says so rather than showing an empty catalogue. */
    @Test
    fun `the first state is a loading one`() = catalogueTest(
        foods = FoodCataloguePreviewData.catalogue(),
        subscribe = false,
    ) { harness ->
        val first = harness.viewModel.uiState.value

        assertTrue(first.isLoading)
        assertTrue(first.results.isEmpty())
        assertEquals(null, first.emptyMessage, "a catalogue still being read says nothing")
    }

    // endregion

    // region harness

    private class Harness(
        val viewModel: FoodCatalogueViewModel,
        val foods: FakeFoodCatalogue,
        val preferences: FakeUserPreferencesRepository,
    )

    /**
     * Subscribes the state before the body runs, because `WhileSubscribed` only reads the stores
     * while something is listening — exactly as the screen does.
     */
    private fun catalogueTest(
        foods: List<Food> = emptyList(),
        recent: List<Food> = emptyList(),
        preferences: UserPreferences = UserPreferences.DEFAULT,
        savedState: SavedStateHandle = SavedStateHandle(),
        subscribe: Boolean = true,
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(mainDispatcher) {
        val catalogue = FakeFoodCatalogue(foods = foods, recent = recent)
        val preferenceStore = FakeUserPreferencesRepository(preferences)
        val harness = Harness(
            viewModel = FoodCatalogueViewModel(
                foods = catalogue,
                preferences = preferenceStore,
                savedStateHandle = savedState,
            ),
            foods = catalogue,
            preferences = preferenceStore,
        )

        val collector = if (subscribe) launch { harness.viewModel.uiState.collect { } } else null
        advanceUntilIdle()

        body(harness)

        collector?.cancel()
    }

    private fun TestScope.state(harness: Harness): FoodsUiState {
        advanceUntilIdle()
        return harness.viewModel.uiState.value
    }

    // endregion
}
