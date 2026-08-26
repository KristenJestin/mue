package fr.kristenjestin.mue.ui.food.catalogue

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
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The `Foods` view (PRD_FOOD 9.4): one search bar over the three sources, one filter, and the
 * recently used at the top when nothing has been typed.
 *
 * **This screen is written for 1 038 rows, not for the dozen a fixture holds.** Three decisions
 * come from that and from nothing else.
 *
 * *The database does the searching.* `FoodCatalogueRepository.search` is a `LIKE` over the two
 * folded, indexed columns PRD_FOOD 20.2 stores, with the source filter and the row limit inside
 * the statement. Reading the catalogue into memory and filtering it in Kotlin would work
 * perfectly on a fixture of six foods and would allocate a thousand `Food` objects on every
 * keystroke on a real phone.
 *
 * *A keystroke is not a query.* Typing `chicken` is seven state changes, and seven `LIKE '%…%'`
 * scans of which six answer a question nobody finished asking. [searchDelayMillis] holds the
 * query still for a moment first, and `flatMapLatest` cancels the previous read outright rather
 * than racing it — so the list can never settle on the answer to an earlier prefix. An empty
 * query is **not** delayed: clearing the field must give the catalogue back at once.
 *
 * *The list is capped and says so.* [RESULT_LIMIT] rows reach composition, never 1 038, and
 * [FoodsUiState.isCapped] is what lets the screen admit it. A silent truncation is how someone
 * concludes their food is missing.
 *
 * Nothing here formats a value. [FoodRowUiState.of] renders every figure through
 * [fr.kristenjestin.mue.domain.logic.FoodLabels], so PRD_FOOD 13.2's rule — an unknown is `—`
 * and never `0` — is decided once, in the domain, and cannot be undone at the last step.
 */
class FoodCatalogueViewModel(
    private val foods: FoodCatalogueRepository,
    preferences: UserPreferencesRepository,
    private val savedStateHandle: SavedStateHandle,
    private val searchDelayMillis: Long = SEARCH_DELAY_MILLIS,
) : ViewModel() {

    private val queries: Flow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")

    private val sources: Flow<FoodSource?> = savedStateHandle
        .getStateFlow<String?>(KEY_SOURCE, null)
        .map { id -> id?.let(FoodSource::fromId) }
        .distinctUntilChanged()

    /**
     * One reading of the catalogue, carrying the terms it was made for.
     *
     * The pair travels with the rows for the reason `FoodDayViewModel`'s date does: while a new
     * query is still being read the old rows are still arriving, and a state built from a term
     * and rows that disagree would show the results of `chick` under the word `chicken`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val readings: Flow<CatalogueReading> = combine(queries, sources, ::Pair)
        .flatMapLatest { (query, source) ->
            val matches = flow {
                // The debounce. Cancelled with the rest of this flow the moment the term moves.
                if (query.isNotBlank()) delay(searchDelayMillis)
                emitAll(foods.search(query = query, source = source, limit = RESULT_LIMIT))
            }

            /*
             * PRD_FOOD 9.4 puts the recently used at the top "lorsque la recherche est vide".
             * A filter is a search of a kind, so it silences them too: a `Personal` list headed
             * by a recent Ciqual entry would answer a question that was not asked.
             */
            val recent = if (query.isBlank() && source == null) {
                foods.observeRecentlyUsed(RECENT_LIMIT)
            } else {
                flowOf(emptyList())
            }

            combine(matches, recent) { rows, recentRows ->
                CatalogueReading(query, source, rows, recentRows)
            }
        }

    val uiState: StateFlow<FoodsUiState> = combine(
        queries,
        sources,
        readings,
        preferences.preferences,
    ) { query, source, reading, prefs ->
        buildState(query, source, reading, prefs.showEnergy)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = buildState(
            query = savedStateHandle[KEY_QUERY] ?: "",
            source = savedStateHandle.get<String?>(KEY_SOURCE)?.let(FoodSource::fromId),
            reading = CatalogueReading.PENDING,
            showEnergy = true,
        ),
    )

    fun onQueryChange(query: String) {
        savedStateHandle[KEY_QUERY] = query
    }

    fun onClearQuery() {
        onQueryChange("")
    }

    /** PRD_FOOD 9.4's single filter; `null` puts the three sources back in one list. */
    fun onSourceChange(source: FoodSource?) {
        savedStateHandle[KEY_SOURCE] = source?.id
    }

    private fun buildState(
        query: String,
        source: FoodSource?,
        reading: CatalogueReading,
        showEnergy: Boolean,
    ): FoodsUiState {
        val settled = reading.query == query && reading.source == source
        val recent = if (settled) reading.recent else emptyList()
        val recentIds = recent.mapTo(mutableSetOf(), Food::id)

        return FoodsUiState(
            query = query,
            source = source,
            isLoading = !settled,
            recent = recent.map { FoodRowUiState.of(it, showEnergy) },
            /*
             * A food eaten yesterday is also in the catalogue. Drawn under both headings it
             * would be two cards for one food, which is a worse list and — since a row is
             * handled by its food's id — two nodes answering to one handle.
             */
            results = if (settled) {
                reading.foods.filterNot { it.id in recentIds }
                    .map { FoodRowUiState.of(it, showEnergy) }
            } else {
                emptyList()
            },
            resultLimit = RESULT_LIMIT,
            matchCount = if (settled) reading.foods.size else 0,
            showEnergy = showEnergy,
        )
    }

    /**
     * The rows of one reading and the terms they answer.
     *
     * [PENDING] carries no query at all — `null`, which `getStateFlow` can never hand back
     * since its default is the empty string — so the first frame knows it is still reading
     * rather than looking at a catalogue with nothing in it.
     */
    private data class CatalogueReading(
        val query: String?,
        val source: FoodSource?,
        val foods: List<Food> = emptyList(),
        val recent: List<Food> = emptyList(),
    ) {
        companion object {
            val PENDING: CatalogueReading = CatalogueReading(query = null, source = null)
        }
    }

    companion object {

        internal const val KEY_QUERY: String = "food.catalogue.query"

        internal const val KEY_SOURCE: String = "food.catalogue.source"

        /**
         * How many rows of 1 038 reach the screen at once.
         *
         * Far more than fills a phone, so scrolling never stops at a boundary the person can
         * see, and far fewer than the catalogue holds, so no keystroke ever builds a thousand
         * row states. PRD_FOOD 9.5 keeps the catalogue coarse precisely so a search stays
         * practicable; this is the other half of that bargain.
         */
        const val RESULT_LIMIT: Int = 60

        /** PRD_FOOD 9.4: recency is a head-start, not a second catalogue. */
        const val RECENT_LIMIT: Int = 8

        /**
         * Long enough that a typed word is one query rather than seven, short enough that the
         * list never feels detached from the field. It is a constructor parameter as well, so a
         * test can prove the debounce exists instead of waiting for it.
         */
        const val SEARCH_DELAY_MILLIS: Long = 180L

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                FoodCatalogueViewModel(
                    foods = app.container.food.foodCatalogueRepository,
                    preferences = app.container.userPreferencesRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

/** The `Foods` ViewModel, scoped like every other screen's — see `foodDayViewModel`. */
@Composable
fun foodCatalogueViewModel(): FoodCatalogueViewModel =
    viewModel(factory = FoodCatalogueViewModel.Factory)
