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
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.repository.RecipeRepository
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

/**
 * One recipe in the picker, already rendered (PRD_FOOD 11 and 13.2).
 *
 * **It carries no energy**, and that is not an omission. A [Recipe] stores no nutritional value
 * at all (PRD_FOOD 8.3) and `RecipeRepository.search` returns recipes without their ingredients,
 * so a figure here could only be invented — and PRD_FOOD 13.1 has one thing to say about an
 * invented number. The facts under the name are the ones the row actually knows.
 */
@Immutable
internal data class RecipePickerRowUiState(
    val id: String,
    val name: String,
    /** `Main · Serves 4 · 25 min` — what a recipe card says about itself in the list. */
    val meta: String,
    val iconName: String,
    val description: String,
) {
    companion object {

        private const val SEPARATOR = " · "

        fun of(recipe: Recipe): RecipePickerRowUiState {
            val facts = listOfNotNull(
                recipe.type.label,
                FoodAddMessages.serves(recipe.baseServings),
                recipe.prepTimeMinutes?.let { FoodAddMessages.prepTime(it) },
            )
            val meta = facts.joinToString(SEPARATOR)
            return RecipePickerRowUiState(
                id = recipe.id.value,
                name = recipe.name,
                meta = meta,
                iconName = FoodIcons.CHEF_HAT,
                description = FoodDayFormat.sentence(recipe.name, meta),
            )
        }
    }
}

/**
 * The recipe picker as it is drawn (FR-FOOD-004, PRD_FOOD 11 and 17).
 *
 * [hasAnyRecipe] is what tells PRD_FOOD 17's two empty lists apart, exactly as it does on the
 * `Recipes` view: a person who has never written a recipe is invited to write one, and a search
 * that matches none of the recipes they do have is told so. They are different facts and they
 * read differently.
 */
@Immutable
internal data class RecipePickerUiState(
    val query: String,
    val results: List<RecipePickerRowUiState>,
    val sectionTitle: String,
    val emptyMessage: String?,
    val hasAnyRecipe: Boolean,
) {
    val isEmpty: Boolean get() = results.isEmpty()
}

/**
 * The picker `Use a recipe` opens (FR-FOOD-004), built as the food picker is built.
 *
 * It is deliberately the *same shape* as [FoodPickerViewModel]: a query in [SavedStateHandle], a
 * `flatMapLatest` that cancels the read a keystroke has superseded, and no selection of its own.
 * What it chooses is handed straight to [FoodAddViewModel] — `FoodRoute.RecipePicker` carries no
 * parameter and could not carry a destination back — and the sheet underneath is what the choice
 * is for.
 *
 * There is **no debounce and no row cap** here, and both are the same reason: a person has tens
 * of recipes, not 1 038, so `RecipeRepository.search` over a folded, indexed name is already a
 * page rather than a scan, and a cap would be a truncation nobody would ever reach.
 */
internal class RecipePickerViewModel(
    private val recipes: RecipeRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")

    @OptIn(ExperimentalCoroutinesApi::class)
    private val results: Flow<List<Recipe>> = query
        .map(String::trim)
        .distinctUntilChanged()
        .flatMapLatest { text ->
            if (text.isEmpty()) recipes.observeAll() else recipes.search(text)
        }

    val uiState: StateFlow<RecipePickerUiState> = combine(
        query,
        results,
        recipes.observeCount(),
    ) { text, found, total -> build(text, found, total) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = build(query.value, emptyList(), total = 0),
        )

    fun onQueryChange(raw: String) {
        savedState[KEY_QUERY] = raw.take(MAX_QUERY_LENGTH)
    }

    fun onClearQuery() {
        savedState[KEY_QUERY] = ""
    }

    private fun build(text: String, found: List<Recipe>, total: Int): RecipePickerUiState =
        RecipePickerUiState(
            query = text,
            results = found.map(RecipePickerRowUiState::of),
            sectionTitle = if (text.isBlank()) {
                FoodAddMessages.RECIPE_RESULTS_SECTION
            } else {
                FoodAddMessages.RESULTS_SECTION
            },
            emptyMessage = when {
                found.isNotEmpty() -> null
                total == 0 -> FoodAddMessages.NO_RECIPES
                else -> FoodAddMessages.NO_RECIPE_MATCHES
            },
            hasAnyRecipe = total > 0,
        )

    companion object {

        internal const val KEY_QUERY: String = "food.recipePicker.query"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Longer than any recipe name PRD_FOOD 15 allows, so nothing typable is refused. */
        private const val MAX_QUERY_LENGTH = 80

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                RecipePickerViewModel(
                    recipes = app.container.food.recipeRepository,
                    savedState = createSavedStateHandle(),
                )
            }
        }

        internal const val KEY: String = "food.recipePicker"
    }
}

/** The picker's own ViewModel, kept apart from the flow's exactly as the food picker's is. */
@Composable
internal fun recipePickerViewModel(): RecipePickerViewModel =
    viewModel(key = RecipePickerViewModel.KEY, factory = RecipePickerViewModel.Factory)
