package fr.kristenjestin.mue.ui.food.recipes

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
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

/**
 * The `Recipes` view's filters and its one write (PRD_FOOD 11, FR-RECIPE-005 and PRD_FOOD 17).
 *
 * The repository is faked, so the search, the two filters and the star are settled on the JVM
 * before an emulator ever runs. Nothing here asserts an energy: a recipe card carries none, for
 * the reason `RecipeListUiState` gives — `Recipe` holds no nutritional value at all, so a figure
 * on a card could only be invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeListViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region the list itself

    @Test
    fun `the view opens on every saved recipe`() = listTest { list ->
        assertEquals(3, state(list).recipes.size)
        assertFalse(state(list).isFiltered)
        assertTrue(state(list).hasAnyRecipe)
    }

    /** `observeAll` promises favourites first, then by name, and nothing here re-sorts it. */
    @Test
    fun `the order is the repository's`() = listTest { list ->
        val names = state(list).recipes.map { it.name }

        assertEquals(RecipePreviewData.LONGEST_NAME, names.first())
    }

    // endregion

    // region filters (FR-RECIPE-005)

    @Test
    fun `searching narrows the list to what matches`() = listTest { list ->
        list.viewModel.onQueryChange("curry")

        assertEquals(listOf(RecipePreviewData.CURRY_NAME), state(list).recipes.map { it.name })
        assertTrue(state(list).isFiltered)
    }

    /** PRD_FOOD 9.4's fold is `Food.fold`'s, so accents and case do not hide a recipe. */
    @Test
    fun `a search ignores case and accents`() = listTest { list ->
        list.viewModel.onQueryChange("CÜRRY")

        assertEquals(listOf(RecipePreviewData.CURRY_NAME), state(list).recipes.map { it.name })
    }

    @Test
    fun `clearing the search brings the list back`() = listTest { list ->
        list.viewModel.onQueryChange("curry")
        list.viewModel.onClearQuery()

        assertEquals(3, state(list).recipes.size)
        assertFalse(state(list).isFiltered)
    }

    @Test
    fun `a type filter keeps only that type`() = listTest(details = mixedTypes()) { list ->
        list.viewModel.onTypeSelected(RecipeType.BREAKFAST)

        assertEquals(listOf("Overnight oats"), state(list).recipes.map { it.name })

        list.viewModel.onTypeSelected(null)
        assertEquals(2, state(list).recipes.size)
    }

    @Test
    fun `favourites only keeps the starred ones`() = listTest { list ->
        list.viewModel.onToggleFavourites()

        assertEquals(listOf(RecipePreviewData.LONGEST_NAME), state(list).recipes.map { it.name })
        assertTrue(state(list).favouritesOnly)
    }

    /**
     * The one filter the frozen contract does not offer.
     *
     * `RecipeRepository.search(query, type)` takes no `favouritesOnly`, so a search restricted to
     * favourites has to be narrowed in the ViewModel. It is a filter and never a computation, and
     * this is what proves the two paths agree.
     */
    @Test
    fun `favourites still apply while searching`() = listTest { list ->
        list.viewModel.onToggleFavourites()
        list.viewModel.onQueryChange("a")

        val found = state(list).recipes
        assertTrue("the search found nothing at all, so it proves nothing", found.isNotEmpty())
        assertTrue(
            "a search with favourites on returned an unstarred recipe",
            found.all { it.isFavourite },
        )
    }

    // endregion

    // region the two empty lists (PRD_FOOD 17)

    /** Nobody has written a recipe: an invitation, and no fake recipe. */
    @Test
    fun `an empty catalogue invites rather than reports`() = listTest(details = emptyList()) { list ->
        assertTrue(state(list).showsInvitation)
        assertFalse(state(list).showsNoMatch)
    }

    /** Recipes exist; this filter matches none. Telling someone they have none would be a lie. */
    @Test
    fun `a filter that matches nothing says so instead`() = listTest { list ->
        list.viewModel.onQueryChange("bouillabaisse")

        assertFalse(state(list).showsInvitation)
        assertTrue(state(list).showsNoMatch)
    }

    // endregion

    // region favourites (FR-RECIPE-005)

    @Test
    fun `the star writes through the repository`() = listTest { list ->
        val curry = RecipePreviewData.CURRY_ID

        list.viewModel.onToggleFavourite(curry, isFavourite = true)
        advanceUntilIdle()

        assertEquals(listOf(curry to true), list.recipes.favourited)
        assertTrue(state(list).recipes.first { it.id == curry }.isFavourite)
    }

    // endregion

    // region what survives a rotation

    @Test
    fun `the filters come back from the saved state`() {
        val saved = SavedStateHandle(
            mapOf(
                RecipeListViewModel.KEY_QUERY to "curry",
                RecipeListViewModel.KEY_TYPE to RecipeType.MAIN.id,
                RecipeListViewModel.KEY_FAVOURITES to false,
            ),
        )

        listTest(savedState = saved) { list ->
            assertEquals("curry", state(list).query)
            assertEquals(RecipeType.MAIN, state(list).type)
        }
    }

    // endregion

    // region harness

    private class Listing(
        val viewModel: RecipeListViewModel,
        val recipes: FakeRecipeRepository,
    )

    /**
     * Subscribes the state before the body runs, because `WhileSubscribed` only reads the store
     * while something is listening — exactly as the screen does.
     */
    private fun listTest(
        details: List<RecipeDetail> = RecipePreviewData.details(),
        savedState: SavedStateHandle = SavedStateHandle(),
        body: suspend TestScope.(Listing) -> Unit,
    ) = runTest(mainDispatcher) {
        val repository = FakeRecipeRepository(details)
        val listing = Listing(
            viewModel = RecipeListViewModel(recipes = repository, savedStateHandle = savedState),
            recipes = repository,
        )

        val collector = launch { listing.viewModel.uiState.collect { } }
        advanceUntilIdle()

        body(listing)

        collector.cancel()
    }

    private fun TestScope.state(listing: Listing): RecipeListUiState {
        advanceUntilIdle()
        return listing.viewModel.uiState.value
    }

    /** Two recipes of two different moments, for the type filter to have something to do. */
    private fun mixedTypes(): List<RecipeDetail> = listOf(
        RecipeDetail(
            recipe = Recipe(
                id = RecipeId("test-oats"),
                name = "Overnight oats",
                type = RecipeType.BREAKFAST,
                baseServings = 1,
            ),
        ),
        RecipePreviewData.curry(),
    )

    // endregion
}
