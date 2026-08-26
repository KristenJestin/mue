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
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import fr.kristenjestin.mue.ui.food.FoodIcons
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The recipe form: what it holds, what it refuses, and the one write it performs (PRD_FOOD 11,
 * FR-RECIPE-001 to 003).
 *
 * The draft lives in [SavedStateHandle] as one JSON string, exactly as `ActivityDraft` does: an
 * unbounded ingredient list cannot be flattened into `Bundle` keys, and a half-typed quantity
 * has to come back unchanged after the process is killed.
 *
 * **No bound is decided here.** [FoodValidation] answers every one of them — the name, the base
 * servings, the preparation time, the steps, each ingredient quantity, and the *number* of
 * ingredients, which is what stops PRD_FOOD 13.1's empty strict sum from being saved as a
 * `0 kcal` recipe. [RecipeEditorUiState.of] runs the same validators for what is shown, so the
 * sentence beside a field and the reason a save was refused can never disagree.
 *
 * Deletion is deliberately absent: FR-RECIPE-006 asks for one confirmation and one place that
 * names the proposals it frees, and that place is the recipe card. A second delete button here
 * would be a second copy of that flow.
 */
internal class RecipeEditorViewModel(
    private val recipes: RecipeRepository,
    private val foods: FoodCatalogueRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val transient = MutableStateFlow(Transient())

    /** The foods the draft's rows name, as far as this device has them (PRD_FOOD 21.2). */
    private val catalogue = MutableStateFlow<Map<FoodId, Food>>(emptyMap())

    private val picker = MutableStateFlow(PickerState())

    private val drafts: Flow<RecipeDraft> = savedStateHandle
        .getStateFlow<String?>(KEY_DRAFT, null)
        .map { RecipeDraft.fromJson(it) ?: RecipeDraft() }

    /**
     * What the picker offers.
     *
     * An empty search shows the foods most recently eaten, which PRD_FOOD 9.4 puts at the top of
     * an empty query, then the rest of the catalogue in name order behind them — a device that
     * has logged nothing yet still has 3 484 Ciqual entries to choose from, and a picker that
     * showed nothing until something was typed would be unusable on day one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val offered: Flow<List<Food>> = picker
        .map { it.visible to it.query }
        .distinctUntilChanged()
        .flatMapLatest { (visible, query) ->
            when {
                !visible -> flowOf(emptyList())
                query.isBlank() -> combine(
                    foods.observeRecentlyUsed(PICKER_LIMIT),
                    foods.search(query = "", limit = PICKER_LIMIT),
                ) { recent, all ->
                    val known = recent.map(Food::id).toSet()
                    (recent + all.filterNot { it.id in known }).take(PICKER_LIMIT)
                }

                else -> foods.search(query = query, limit = PICKER_LIMIT)
            }
        }

    val uiState: StateFlow<RecipeEditorUiState> = combine(
        drafts,
        catalogue,
        transient,
        picker,
        offered,
    ) { draft, foods, state, pickerState, results ->
        RecipeEditorUiState.of(
            draft = draft,
            foods = foods,
            showErrors = state.showErrors,
            isLoading = state.isLoading,
            isSaving = state.isSaving,
            justSaved = state.justSaved,
            picker = pickerState.toUiState(results),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = RecipeEditorUiState.of(currentDraft()),
    )

    init {
        /*
         * A draft restored after a process death carries food *ids* and no foods, so every row
         * would read as an orphan until something else moved. Watching the set of ids rather
         * than the draft itself means a keystroke in a quantity box fetches nothing.
         */
        viewModelScope.launch {
            drafts
                .map { draft -> draft.ingredients.map { FoodId(it.foodId) }.toSet() }
                .distinctUntilChanged()
                .collect(::resolve)
        }
    }

    /**
     * Called by the screen on every entry, and idempotent by design.
     *
     * The marker is what tells a genuinely new form from a recomposition — the arrangement
     * `LogActivityViewModel.start` uses. It is dropped once a save has landed, so the next
     * `New recipe` opens on a blank form rather than on the recipe just written.
     */
    fun start(recipeId: RecipeId?) {
        if (transient.value.justSaved) return
        val marker = recipeId?.value.orEmpty()
        if (savedStateHandle.get<String>(KEY_STARTED_FOR) == marker) return
        savedStateHandle[KEY_STARTED_FOR] = marker
        transient.value = Transient(isLoading = recipeId != null)
        catalogue.value = emptyMap()
        picker.value = PickerState()
        if (recipeId == null) {
            replaceDraft(RecipeDraft())
        } else {
            viewModelScope.launch { prefill(recipeId) }
        }
    }

    // region the form (PRD_FOOD 11 and FR-RECIPE-001)

    fun onNameChange(raw: String) = edit { it.copy(name = raw.take(Recipe.MAX_NAME_LENGTH)) }

    fun onTypeSelected(type: RecipeType) = edit { it.copy(typeId = type.id) }

    fun onBaseServingsChange(raw: String) =
        edit { it.copy(baseServings = digits(raw, MAX_SERVINGS_DIGITS)) }

    fun onPrepTimeChange(raw: String) =
        edit { it.copy(prepTimeMinutes = digits(raw, MAX_PREP_TIME_DIGITS)) }

    fun onDescriptionChange(raw: String) =
        edit { it.copy(description = raw.take(Recipe.MAX_DESCRIPTION_LENGTH)) }

    fun onStepsChange(raw: String) = edit { it.copy(steps = raw) }

    fun onQuantityChange(index: Int, raw: String) = edit { draft ->
        draft.withIngredient(index) { it.copy(quantity = decimal(raw)) }
    }

    fun onRemoveIngredient(index: Int) = edit { draft ->
        draft.copy(ingredients = draft.ingredients.filterIndexed { at, _ -> at != index })
    }

    // endregion

    // region the ingredient picker (PRD_FOOD 11 and FR-RECIPE-002)

    fun onOpenPicker() {
        picker.value = PickerState(visible = true)
    }

    fun onPickerQueryChange(query: String) {
        picker.update { it.copy(query = query, lastAdded = null) }
    }

    /**
     * FR-RECIPE-002: an ingredient is always a `Food` of the catalogue, never free text.
     *
     * **The sheet stays open.** A recipe is several foods at once, so dismissing on the first
     * pick would mean reopening the picker for every ingredient — the complaint the activity
     * module's exercise picker earned. The same food may be picked twice on purpose:
     * `RecipeIngredientId` exists because a marinade and a sauce can legitimately draw on the
     * same oil, so `(recipeId, foodId)` is not a key.
     */
    fun onPickFood(foodId: String) {
        viewModelScope.launch {
            val food = foods.findById(FoodId(foodId)) ?: return@launch
            catalogue.update { it + (food.id to food) }
            edit { draft ->
                draft.copy(
                    ingredients = draft.ingredients + RecipeIngredientDraft.newRow(
                        foodId = food.id.value,
                        foodName = food.name,
                        unit = food.referenceUnit,
                    ),
                )
            }
            picker.update {
                it.copy(addedCount = it.addedCount + 1, lastAdded = it.announcementFor(food.name))
            }
        }
    }

    fun onClosePicker() {
        picker.value = PickerState()
    }

    // endregion

    /**
     * FR-RECIPE-001: name, servings and at least one ingredient are required.
     *
     * A refused save reveals the sentences PRD_FOOD 15 wants beside the fields and changes
     * nothing else — the form is never emptied and nothing is written.
     */
    fun onSave() {
        if (transient.value.isSaving || transient.value.justSaved) return
        val draft = currentDraft()
        val prepared = prepare(draft)
        if (prepared == null) {
            transient.update { it.copy(showErrors = true) }
            return
        }
        transient.update { it.copy(isSaving = true, showErrors = false) }
        viewModelScope.launch {
            recipes.save(prepared)
            /*
             * The written recipe becomes the draft, so a form left open after a save is editing
             * what exists rather than about to create a second copy of it. The marker is dropped
             * on the same beat, which is what makes the next `New recipe` open blank.
             */
            replaceDraft(RecipeDraft.of(prepared.recipe, prepared.ingredients))
            savedStateHandle.remove<String>(KEY_STARTED_FOR)
            transient.update { it.copy(isSaving = false, justSaved = true) }
        }
    }

    /** Fired once the save button has finished discharging, as on `Log activity`. */
    fun onSaved() {
        transient.update { it.copy(justSaved = false) }
    }

    private suspend fun prefill(id: RecipeId) {
        val detail = runCatching { recipes.findDetail(id) }.getOrNull()
        if (detail == null) {
            /*
             * The row is gone — deleted from its card while this form was being opened. Keeping
             * the id means a save recreates it under the same identity rather than quietly
             * writing a second recipe under a new one.
             */
            replaceDraft(RecipeDraft(recipeId = id.value))
            transient.update { it.copy(isLoading = false) }
            return
        }
        val found = foods.findByIds(detail.foodIds).associateBy(Food::id)
        catalogue.value = found
        replaceDraft(
            RecipeDraft.of(
                recipe = detail.recipe,
                ingredients = detail.ingredients,
                names = found.mapKeys { (id, _) -> id.value }.mapValues { (_, food) -> food.name },
            ),
        )
        transient.update { it.copy(isLoading = false) }
    }

    /**
     * The typed form turned into the aggregate `RecipeRepository.save` writes, or null when
     * anything at all is refused.
     *
     * Every judgement is [FoodValidation]'s, including the one that matters most here:
     * [FoodValidation.validateIngredients] refuses a recipe with no ingredient. Without it a
     * saved empty recipe would read `0 kcal` on its card for ever after, because
     * `Nutrients.strictSum(emptyList())` is a **known** zero and not an unknown — the one place
     * in PRD_FOOD 13.1 where an absence really does produce a number.
     */
    private fun prepare(draft: RecipeDraft): RecipeDetail? {
        val name = FoodValidation.validateName(draft.name).valueOrNull ?: return null
        val servings = FoodValidation.validateBaseServings(draft.baseServings).valueOrNull
            ?: return null
        val prepTime = FoodValidation.validatePrepTime(draft.prepTimeMinutes)
        if (prepTime !is Validated.Valid) return null
        val steps = FoodValidation.validateSteps(draft.steps).valueOrNull ?: return null

        val ingredients = draft.ingredients.mapIndexed { index, row ->
            val quantity = FoodValidation.validateIngredientQuantity(row.quantity).valueOrNull
                ?: return null
            RecipeIngredient(
                id = RecipeIngredientId(row.id),
                foodId = FoodId(row.foodId),
                quantity = quantity,
                unit = row.unit,
                position = index,
                // PRD_FOOD 21.2: the snapshot a device without this food renders the row by.
                foodName = catalogue.value[FoodId(row.foodId)]?.name ?: row.foodName,
            )
        }
        FoodValidation.validateIngredients(ingredients).valueOrNull ?: return null

        return RecipeDetail(
            recipe = Recipe(
                id = draft.recipeId?.let(::RecipeId) ?: RecipeId.random(),
                name = name,
                type = draft.type,
                baseServings = servings,
                description = FoodValidation.normalizeDescription(draft.description),
                prepTimeMinutes = prepTime.value,
                steps = steps,
                imageRef = draft.imageRef,
                isFavourite = draft.isFavourite,
            ),
            ingredients = ingredients,
        )
    }

    private suspend fun resolve(ids: Set<FoodId>) {
        val missing = ids - catalogue.value.keys
        if (missing.isEmpty()) return
        val found = foods.findByIds(missing).associateBy(Food::id)
        if (found.isNotEmpty()) catalogue.update { it + found }
    }

    private fun edit(block: (RecipeDraft) -> RecipeDraft) {
        replaceDraft(block(currentDraft()))
    }

    private fun replaceDraft(draft: RecipeDraft) {
        savedStateHandle[KEY_DRAFT] = draft.toJson()
    }

    private fun currentDraft(): RecipeDraft =
        RecipeDraft.fromJson(savedStateHandle[KEY_DRAFT]) ?: RecipeDraft()

    /** Screen state that is not the draft: what is loading, saving, and being complained about. */
    private data class Transient(
        val isLoading: Boolean = false,
        val isSaving: Boolean = false,
        val justSaved: Boolean = false,
        val showErrors: Boolean = false,
    )

    private data class PickerState(
        val visible: Boolean = false,
        val query: String = "",
        val addedCount: Int = 0,
        val lastAdded: String? = null,
    ) {
        /**
         * A fresh announcement even when the same food is added twice.
         *
         * A live region only speaks when its text changes, and picking the same oil for a
         * marinade and for a sauce is exactly the case `RecipeIngredientId` exists for — so the
         * count that follows it is what makes the second one audible.
         */
        fun announcementFor(name: String): String =
            "${RecipeMessages.addedAnnouncement(name)} (${addedCount + 1})"

        fun toUiState(results: List<Food>): RecipePickerUiState = RecipePickerUiState(
            visible = visible,
            query = query,
            results = results.map { food ->
                RecipePickerRowUiState(
                    id = food.id.value,
                    name = food.name,
                    meta = food.brand,
                    iconName = FoodIcons.forSource(food.source),
                )
            },
            addedCount = addedCount,
            lastAdded = lastAdded,
        )
    }

    companion object {

        internal const val KEY_DRAFT: String = "food.recipeEditor.draft"
        internal const val KEY_STARTED_FOR: String = "food.recipeEditor.startedFor"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Long enough to browse, short enough that a 3 484-entry catalogue never all arrives. */
        internal const val PICKER_LIMIT: Int = 40

        /** PRD_FOOD 15 stops a recipe at twelve servings; two boxes is all the field can hold. */
        private const val MAX_SERVINGS_DIGITS = 2

        /** PRD_FOOD 15 sets no preparation time; four digits stops a mistyped one at 1 440. */
        private const val MAX_PREP_TIME_DIGITS = 4

        /** A quantity is stored to the thousandth (PRD_FOOD 8.6), so three decimals is all of it. */
        private const val MAX_QUANTITY_DECIMALS = 3

        private const val MAX_QUANTITY_LENGTH = 8

        internal fun digits(raw: String, max: Int): String = raw.filter(Char::isDigit).take(max)

        /**
         * Both separators reach the draft, as everywhere else in the app (PRD_FOOD 15's parser
         * accepts `.` and `,`); everything else is refused at the keystroke so no box can hold
         * text a save would later have to explain.
         */
        internal fun decimal(raw: String): String {
            val filtered = raw
                .filter { it.isDigit() || it == '.' || it == ',' }
                .take(MAX_QUANTITY_LENGTH)
            val separator = filtered.indexOfFirst { it == '.' || it == ',' }
            return if (separator < 0) {
                filtered
            } else {
                filtered.take(separator + 1 + MAX_QUANTITY_DECIMALS)
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                RecipeEditorViewModel(
                    recipes = app.container.food.recipeRepository,
                    foods = app.container.food.foodCatalogueRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }

        const val KEY: String = "food.recipeEditor"
    }
}

/** The recipe form's ViewModel, scoped to the hosting activity's store like every other. */
@Composable
internal fun recipeEditorViewModel(): RecipeEditorViewModel =
    viewModel(key = RecipeEditorViewModel.KEY, factory = RecipeEditorViewModel.Factory)
