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
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The card of one recipe, and the three writes it performs (PRD_FOOD 11 and FR-RECIPE-004
 * to 006).
 *
 * **Nothing here adds anything up.** [RecipeDetailUiState.of] is the single call that turns a
 * `RecipeDetail` and a catalogue into strings, and every figure in it comes out of
 * [fr.kristenjestin.mue.domain.logic.NutritionMath]. This class only decides *which* recipe is
 * on screen, how many servings the reader asked to see, and when to write.
 *
 * The recipe being shown is passed to [start] rather than taken from a route parameter: the Food
 * tab has no navigation library, so the id travels as an argument and is remembered under a
 * marker — the arrangement `LogActivityViewModel` uses for the session it edits. That marker is
 * dropped once the row has been deleted, so the next card that opens is read afresh.
 */
internal class RecipeDetailViewModel(
    private val recipes: RecipeRepository,
    private val foods: FoodCatalogueRepository,
    private val savedStateHandle: SavedStateHandle,
    private val locale: () -> Locale = Locale::getDefault,
) : ViewModel() {

    private val deletion = MutableStateFlow<RecipeDeletionUiState>(RecipeDeletionUiState.Idle)

    private val ids: Flow<RecipeId?> = savedStateHandle
        .getStateFlow<String?>(KEY_RECIPE, null)
        .map { it?.let(::RecipeId) }
        .distinctUntilChanged()

    /**
     * The recipe and the foods its ingredients name, re-read whenever either moves.
     *
     * The catalogue is resolved inside the same emission as the recipe rather than beside it, so
     * a card is never drawn from an ingredient list and a catalogue that disagree — which would
     * show a resolved row as an orphan for one frame and print `—` over a figure that is known.
     *
     * A food that is simply absent is **not** an error: PRD_FOOD 21.2 makes it the ordinary way
     * a recipe arrives before the food it references does.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val readings: Flow<Reading> = ids.flatMapLatest { id ->
        if (id == null) {
            flowOf(Reading.PENDING)
        } else {
            recipes.observeDetail(id).map { detail ->
                Reading(id = id, detail = detail, foods = resolve(detail), isRead = true)
            }
        }
    }

    val uiState: StateFlow<RecipeDetailUiState> = combine(
        ids,
        readings,
        savedStateHandle.getStateFlow(KEY_SERVINGS, 0),
        deletion,
    ) { id, reading, servings, deleting ->
        buildState(id, reading, servings, deleting)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = RecipeDetailUiState(isLoading = true),
    )

    /**
     * Called by the screen on every entry, and idempotent by design.
     *
     * Returning from the editor recomposes this screen from scratch, and a naive reset would
     * throw away the servings the reader had dialled in. The marker tells a genuinely new card
     * from a return.
     */
    fun start(recipeId: RecipeId) {
        if (savedStateHandle.get<String>(KEY_RECIPE) == recipeId.value) return
        savedStateHandle[KEY_RECIPE] = recipeId.value
        savedStateHandle[KEY_SERVINGS] = 0
        deletion.value = RecipeDeletionUiState.Idle
    }

    // region servings (PRD_FOOD FR-RECIPE-004)

    fun onMoreServings() {
        step(up = true)
    }

    fun onFewerServings() {
        step(up = false)
    }

    // endregion

    /** FR-RECIPE-005: the star, which is part of the recipe and journals like any other change. */
    fun onToggleFavourite() {
        val id = currentId() ?: return
        val next = !uiState.value.isFavourite
        viewModelScope.launch { recipes.setFavourite(id, next) }
    }

    // region deletion (PRD_FOOD FR-RECIPE-006 and 17)

    fun onRequestDelete() {
        if (currentId() != null) deletion.value = RecipeDeletionUiState.Confirming
    }

    fun onCancelDelete() {
        if (deletion.value == RecipeDeletionUiState.Confirming) {
            deletion.value = RecipeDeletionUiState.Idle
        }
    }

    /**
     * FR-RECIPE-006: deleting touches neither the journal nor the meals already eaten, and the
     * proposals that referenced the recipe are freed.
     *
     * The keys `RecipeRepository.delete` returns are the whole reason this method has a result
     * to show. Each one is a `(date, moment)` pair whose proposal has just stopped pointing at
     * anything (PRD_FOOD 8.5), and PRD_FOOD 17 requires that the freed moment be **signalled**
     * rather than silently emptied — so they are kept, worded, and put on screen before the card
     * closes. A delete that freed nothing has nothing to say and the screen leaves at once.
     */
    fun onConfirmDelete() {
        val id = currentId() ?: return
        if (deletion.value !is RecipeDeletionUiState.Confirming) return
        viewModelScope.launch {
            val freed = recipes.delete(id)
            deletion.value = RecipeDeletionUiState.deleted(freed, locale())
        }
    }

    // endregion

    /**
     * One serving up or down from what is **on screen**.
     *
     * A card that has not been read yet has no count to step from — the state's default belongs
     * to no recipe — so the step is refused rather than applied to a placeholder. Without the
     * guard a control pressed before the first emission would walk from one serving instead of
     * from the number the recipe is written for, which an assistive service can do and a thumb
     * cannot.
     */
    private fun step(up: Boolean) {
        val current = uiState.value
        if (current.isLoading || current.isMissing) return
        val next = RecipeDetailUiState.stepped(current.servings, up) ?: return
        savedStateHandle[KEY_SERVINGS] = next.thousandths
    }

    private suspend fun resolve(detail: RecipeDetail?): Map<FoodId, Food> {
        val ids = detail?.foodIds.orEmpty()
        if (ids.isEmpty()) return emptyMap()
        return foods.findByIds(ids).associateBy(Food::id)
    }

    private fun buildState(
        id: RecipeId?,
        reading: Reading,
        servingsThousandths: Int,
        deleting: RecipeDeletionUiState,
    ): RecipeDetailUiState {
        val settled = reading.id == id && reading.isRead
        return RecipeDetailUiState.of(
            detail = if (settled) reading.detail else null,
            foods = if (settled) reading.foods else emptyMap(),
            servings = Servings.ofThousandthsOrNull(servingsThousandths.toLong()),
            isLoading = !settled,
            deletion = deleting,
            recipeId = id,
        )
    }

    private fun currentId(): RecipeId? =
        savedStateHandle.get<String>(KEY_RECIPE)?.let(::RecipeId)

    /**
     * One reading and the recipe it was made for.
     *
     * [PENDING] belongs to no recipe, so the screen knows it is still reading rather than
     * looking at a recipe that no longer exists — the difference between a first frame and
     * PRD_FOOD 17's "cette recette n'existe plus".
     */
    private data class Reading(
        val id: RecipeId?,
        val detail: RecipeDetail? = null,
        val foods: Map<FoodId, Food> = emptyMap(),
        val isRead: Boolean = false,
    ) {
        companion object {
            val PENDING: Reading = Reading(id = null)
        }
    }

    companion object {

        internal const val KEY_RECIPE: String = "food.recipe.id"

        /** The chosen count in the thousandths `Servings` stores; `0` means "not chosen yet". */
        internal const val KEY_SERVINGS: String = "food.recipe.servings"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                RecipeDetailViewModel(
                    recipes = app.container.food.recipeRepository,
                    foods = app.container.food.foodCatalogueRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }

        const val KEY: String = "food.recipe.detail"
    }
}

/** The recipe card's ViewModel, scoped to the hosting activity's store like every other. */
@Composable
internal fun recipeDetailViewModel(): RecipeDetailViewModel =
    viewModel(key = RecipeDetailViewModel.KEY, factory = RecipeDetailViewModel.Factory)
