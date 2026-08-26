package fr.kristenjestin.mue.ui.food.add

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.ui.food.FoodIcons
import fr.kristenjestin.mue.ui.food.day.FoodDayFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One food in the list, already rendered (PRD_FOOD 9.4 and 13.2). */
@Immutable
internal data class FoodPickerRowUiState(
    val id: String,
    val name: String,
    /** The brand and the provenance, joined — or just the provenance (FR-CATALOG-004). */
    val meta: String,
    /** `≈ 89 kcal`, or `—` when the card does not say (PRD_FOOD 9.2). */
    val energyLabel: String,
    val per100Label: String,
    val iconName: String,
    val description: String,
) {
    companion object {

        private const val SEPARATOR = " · "

        /**
         * One catalogue entry as its row reads.
         *
         * The energy is [FoodLabels]'s, so a card that does not state one reads `—` here exactly
         * as it does everywhere else — an incomplete Open Food Facts product is the nominal case
         * (PRD_FOOD 9.2), not an empty figure to fill in with a zero.
         */
        fun of(food: Food): FoodPickerRowUiState {
            val source = FoodAddMessages.sourceLabel(food.source)
            val meta = listOfNotNull(food.brand, source).joinToString(SEPARATOR)
            val energy = FoodLabels.energy(food.per100.energy)
            val per100 = FoodAddMessages.per100Label(food)
            return FoodPickerRowUiState(
                id = food.id.value,
                name = food.name,
                meta = meta,
                energyLabel = energy,
                per100Label = per100,
                iconName = FoodIcons.forSource(food.source),
                description = FoodDayFormat.sentence(
                    food.name,
                    meta,
                    "${FoodDayFormat.spoken(energy)} $per100",
                ),
            )
        }
    }
}

/** One of PRD_FOOD 9.4's source filters; null is "every source at once". */
@Immutable
internal data class FoodSourceFilterUiState(
    val source: FoodSource?,
    val label: String,
    val selected: Boolean,
)

/**
 * The picker as it is drawn (PRD_FOOD 9.4).
 *
 * [isRecent] says which of the two lists is on screen — the recently used, or the results of a
 * search — because PRD_FOOD 17 words their empty states differently: nothing logged yet is not
 * the same fact as nothing matching.
 */
@Immutable
internal data class FoodPickerUiState(
    val query: String,
    val sources: List<FoodSourceFilterUiState>,
    val results: List<FoodPickerRowUiState>,
    val isRecent: Boolean,
    val sectionTitle: String,
    val emptyMessage: String?,
) {
    val isEmpty: Boolean get() = results.isEmpty()
}

/**
 * The one search bar of PRD_FOOD 9.4, over the whole catalogue at once.
 *
 * **It is usable at the size the catalogue really is.** 1 038 Ciqual entries are seeded on first
 * launch, and four things keep the list responsive at that size without a cache to invalidate:
 * the filtering is SQL and never Kotlin, every query is capped at [SEARCH_LIMIT] rows,
 * `flatMapLatest` cancels the query a keystroke has superseded before its rows arrive, and the
 * folded query is `distinctUntilChanged` so a typed accent — which folds to the same text — does
 * not re-run anything. The list itself is lazy, so a full page of results composes a screenful.
 *
 * The picker holds no selection of its own. What it chooses is handed straight to
 * [FoodAddViewModel], which is where the food is going; `FoodRoute.FoodPicker` carries no
 * parameter and could not carry a destination back.
 */
internal class FoodPickerViewModel(
    private val foods: FoodCatalogueRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")

    private val source: StateFlow<String?> = savedState.getStateFlow(KEY_SOURCE, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val results: Flow<List<Food>> = combine(query, source) { text, sourceId ->
        text.trim() to sourceId?.let(FoodSource::fromId)
    }
        .distinctUntilChanged()
        .flatMapLatest { (text, filter) ->
            if (text.isEmpty() && filter == null) {
                // PRD_FOOD 9.4: "les aliments récemment utilisés apparaissent en tête lorsque la
                // recherche est vide". Recency comes from the journal, not from the catalogue.
                foods.observeRecentlyUsed(RECENT_LIMIT)
            } else {
                foods.search(text, filter, SEARCH_LIMIT)
            }
        }

    val uiState: StateFlow<FoodPickerUiState> = combine(
        query,
        source,
        results,
    ) { text, sourceId, found -> build(text, sourceId, found) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = build(query.value, source.value, emptyList()),
        )

    fun onQueryChange(raw: String) {
        savedState[KEY_QUERY] = raw.take(MAX_QUERY_LENGTH)
    }

    fun onClearQuery() {
        savedState[KEY_QUERY] = ""
    }

    /** PRD_FOOD 9.4: "un filtre restreint à une source"; null puts every source back. */
    fun onSourceSelected(source: FoodSource?) {
        savedState[KEY_SOURCE] = source?.id
    }

    /** What the `Create a food` action of PRD_FOOD 17 would be prefilled with. */
    val searchTerm: String get() = query.value.trim()

    private fun build(
        text: String,
        sourceId: String?,
        found: List<Food>,
    ): FoodPickerUiState {
        val trimmed = text.trim()
        val filter = sourceId?.let(FoodSource::fromId)
        val isRecent = trimmed.isEmpty() && filter == null
        return FoodPickerUiState(
            query = text,
            sources = listOf<FoodSource?>(null) .plus(FoodSource.entries).map { option ->
                FoodSourceFilterUiState(
                    source = option,
                    label = option?.let(FoodAddMessages::sourceLabel)
                        ?: FoodAddMessages.SOURCE_ALL,
                    selected = option == filter,
                )
            },
            results = found.map(FoodPickerRowUiState::of),
            isRecent = isRecent,
            sectionTitle = if (isRecent) {
                FoodAddMessages.RECENT_SECTION
            } else {
                FoodAddMessages.RESULTS_SECTION
            },
            emptyMessage = when {
                found.isNotEmpty() -> null
                isRecent -> FoodAddMessages.NOTHING_RECENT
                else -> FoodAddMessages.NO_RESULTS
            },
        )
    }

    companion object {

        internal const val KEY_QUERY: String = "food.picker.query"
        internal const val KEY_SOURCE: String = "food.picker.source"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** PRD_FOOD 9.4 puts these at the head of an empty search; a screenful is enough. */
        const val RECENT_LIMIT: Int = 12

        /**
         * How many rows one search may return.
         *
         * The embedded catalogue holds 1 038 entries and a bare `a` matches most of them. A cap
         * here is what keeps a keystroke costing a page rather than the table; a search that
         * needs more than fifty results needs a longer word, not a longer list.
         */
        const val SEARCH_LIMIT: Int = 50

        /** Longer than any food name PRD_FOOD 15 allows, so nothing typable is refused. */
        private const val MAX_QUERY_LENGTH = 80

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                FoodPickerViewModel(
                    foods = app.container.food.foodCatalogueRepository,
                    savedState = createSavedStateHandle(),
                )
            }
        }

        internal const val KEY: String = "food.picker"
    }
}

/** The picker's own ViewModel, kept apart from the flow's so a recipe can reuse the screen. */
@Composable
internal fun foodPickerViewModel(): FoodPickerViewModel =
    viewModel(key = FoodPickerViewModel.KEY, factory = FoodPickerViewModel.Factory)
