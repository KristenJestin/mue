package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The state of the `Recipes` view and the one write it performs (PRD_FOOD 11, FR-RECIPE-005).
 *
 * The three filters live in [SavedStateHandle] rather than in the route: PRD_FOOD 7 makes the
 * four views siblings reached by a switcher, and putting a search term in a stack key would mint
 * a new entry on every keystroke. They survive a rotation and a process death without ever
 * appearing in the module's stack — the arrangement `FoodDayViewModel` uses for the day on
 * screen.
 *
 * Nothing here sorts and nothing here totals. `RecipeRepository.observeAll` already returns
 * favourites first then by name, and a recipe card carries no nutritional value at all
 * (PRD_FOOD 8.3).
 */
internal class RecipeListViewModel(
    private val recipes: RecipeRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val filters: Flow<Filters> = combine(
        savedStateHandle.getStateFlow(KEY_QUERY, ""),
        savedStateHandle.getStateFlow<String?>(KEY_TYPE, null),
        savedStateHandle.getStateFlow(KEY_FAVOURITES, false),
    ) { query, typeId, favouritesOnly ->
        Filters(query, typeId?.let(RecipeType::fromId), favouritesOnly)
    }.distinctUntilChanged()

    /**
     * One reading of the catalogue, re-subscribed whenever a filter moves.
     *
     * The reading carries the filters it was made for, exactly as the day's rows carry their
     * date: while the new query is still being answered the old rows are still arriving, and a
     * list built from filters and rows that disagree would show the previous search under the
     * new one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val listings: Flow<Listing> = filters.flatMapLatest { active ->
        source(active).map { found -> Listing(active, found.filteredBy(active)) }
    }

    val uiState: StateFlow<RecipeListUiState> = combine(
        filters,
        listings,
        recipes.observeCount(),
    ) { active, listing, total ->
        buildState(active, listing, total)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = buildState(storedFilters(), Listing.PENDING, total = 0),
    )

    // region filters (PRD_FOOD FR-RECIPE-005)

    fun onQueryChange(query: String) {
        savedStateHandle[KEY_QUERY] = query
    }

    fun onClearQuery() {
        onQueryChange("")
    }

    /** A null [type] is "every type", which is a filter cleared rather than a fourth value. */
    fun onTypeSelected(type: RecipeType?) {
        savedStateHandle[KEY_TYPE] = type?.id
    }

    fun onToggleFavourites() {
        savedStateHandle[KEY_FAVOURITES] = !(savedStateHandle[KEY_FAVOURITES] ?: false)
    }

    // endregion

    /** FR-RECIPE-005: a recipe is a favourite or it is not, and the star is how it changes. */
    fun onToggleFavourite(id: RecipeId, isFavourite: Boolean) {
        viewModelScope.launch { recipes.setFavourite(id, isFavourite) }
    }

    private fun source(active: Filters): Flow<List<Recipe>> =
        if (active.query.isBlank()) {
            recipes.observeAll(type = active.type, favouritesOnly = active.favouritesOnly)
        } else {
            recipes.search(query = active.query, type = active.type)
        }

    /**
     * The favourites filter, applied to a search.
     *
     * `RecipeRepository.observeAll` takes it and `search` does not — the frozen contract offers
     * `search(query, type)` and nothing else — so a search restricted to favourites has to be
     * narrowed here. It is a **filter** and never a computation: no ordering changes, and
     * `observeAll` has already applied the same predicate before this runs.
     */
    private fun List<Recipe>.filteredBy(active: Filters): List<Recipe> =
        if (active.favouritesOnly) filter { it.isFavourite } else this

    private fun buildState(active: Filters, listing: Listing, total: Int): RecipeListUiState {
        val settled = listing.filters == active
        return RecipeListUiState.of(
            query = active.query,
            type = active.type,
            favouritesOnly = active.favouritesOnly,
            recipes = if (settled) listing.recipes else emptyList(),
            totalCount = total,
            isLoading = !settled,
        )
    }

    private fun storedFilters(): Filters = Filters(
        query = savedStateHandle[KEY_QUERY] ?: "",
        type = savedStateHandle.get<String?>(KEY_TYPE)?.let(RecipeType::fromId),
        favouritesOnly = savedStateHandle[KEY_FAVOURITES] ?: false,
    )

    /** The three filters FR-RECIPE-005 offers, read as one value so a reading can carry them. */
    private data class Filters(
        val query: String = "",
        val type: RecipeType? = null,
        val favouritesOnly: Boolean = false,
    )

    /**
     * One reading and the filters it answers.
     *
     * [PENDING] matches no filters at all, so the screen knows it is still reading rather than
     * looking at an empty catalogue — which is the difference between PRD_FOOD 17's invitation
     * and a blank first frame.
     */
    private data class Listing(
        val filters: Filters?,
        val recipes: List<Recipe> = emptyList(),
    ) {
        companion object {
            val PENDING: Listing = Listing(filters = null)
        }
    }

    companion object {

        internal const val KEY_QUERY: String = "food.recipes.query"
        internal const val KEY_TYPE: String = "food.recipes.type"
        internal const val KEY_FAVOURITES: String = "food.recipes.favourites"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The one store this view reads, off `AppContainer.food`, in the arrangement every other
         * screen of the app uses. The constructor still takes the *interface*, which is what
         * keeps `RecipeListViewModelTest` on the JVM against a fake rather than against Room.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                RecipeListViewModel(
                    recipes = app.container.food.recipeRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }

        const val KEY: String = "food.recipes"
    }
}

/** The `Recipes` view's ViewModel, scoped to the hosting activity's store like every other. */
@Composable
internal fun recipeListViewModel(): RecipeListViewModel =
    viewModel(key = RecipeListViewModel.KEY, factory = RecipeListViewModel.Factory)
