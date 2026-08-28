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
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodDeletion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The `Food editor` (PRD_FOOD 7, 9.1, 9.3, 15 and FR-CATALOG-003): one form that creates a food,
 * corrects one, duplicates a reference entry, and deletes one.
 *
 * **Not one bound of PRD_FOOD 15 is written here.** Every field is judged by the validator that
 * owns its row — `validateName`, `validateBrand`, `validateBarcode`, `validatePer100` and the
 * `validateMacroSum` inside it, `validateUsualServing` — and the sentences shown beside the
 * fields are [fr.kristenjestin.mue.domain.logic.FoodValidation]'s own constants. A refused value
 * never empties the form, which is why the draft holds strings rather than parsed values.
 *
 * `validateCookedRatio` is the one §15 row this screen deliberately does **not** call: PRD_FOOD
 * 8.6 says the ratio "n'est jamais saisi à la main", so the form offers no field for it, and
 * [toFoodOrNull] carries an existing one through untouched instead.
 *
 * Deletion is a value and not an exception, and this class does nothing with it but choose a
 * sentence. `ReadOnly` and `UsedByRecipes` are refusals a person has to be able to act on —
 * duplicate this one, or free it from those recipes first — and PRD_FOOD 17 makes naming the
 * recipes the requirement rather than counting them.
 *
 * A read-only food never shows the delete control, and this class still handles
 * [FoodDeletion.ReadOnly]: a control that is merely hidden is one accessibility service, one
 * stale screen or one MCP write away from being reached, and the repository is the authority
 * either way.
 */
class FoodEditorViewModel(
    private val foods: FoodCatalogueRepository,
    private val foodId: FoodId?,
    prefillName: String? = null,
    /** PRD_FOOD 17: the code an Open Food Facts lookup found nothing for. */
    prefillBarcode: String? = null,
    private val savedStateHandle: SavedStateHandle,
    private val newId: () -> FoodId = FoodId::random,
) : ViewModel() {

    private val draft = MutableStateFlow(
        FoodEditorDraft.fromJson(savedStateHandle[KEY_DRAFT])
            ?: FoodEditorDraft.blank(prefillName, prefillBarcode),
    )

    /**
     * The stored row this form is editing, once it has been read.
     *
     * It is held rather than merely copied into the draft because a save has to put back what a
     * form cannot express — PRD_FOOD 9.2's `sourceId`, PRD_FOOD 8.6's `cookedRatio` — and
     * because a process death has to be able to find them again.
     */
    private val existing = MutableStateFlow<Food?>(null)

    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<FoodEditorUiState> = combine(
        draft,
        existing,
        transient,
    ) { form, stored, flags ->
        build(form, stored, flags)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = build(draft.value, null, transient.value),
    )

    init {
        if (foodId != null) load(foodId)
    }

    // region the form (PRD_FOOD 15)

    fun onNameChange(value: String) = update { it.copy(name = value) }

    fun onBrandChange(value: String) = update { it.copy(brand = value) }

    fun onBarcodeChange(value: String) = update { it.copy(barcode = value) }

    fun onReferenceUnitChange(unit: ReferenceUnit) = update { it.copy(unitId = unit.id) }

    fun onEnergyChange(value: String) = update { it.copy(energy = value) }

    fun onProteinChange(value: String) = update { it.copy(protein = value) }

    fun onCarbsChange(value: String) = update { it.copy(carbs = value) }

    fun onFatChange(value: String) = update { it.copy(fat = value) }

    fun onFibreChange(value: String) = update { it.copy(fibre = value) }

    fun onServingLabelChange(value: String) = update { it.copy(servingLabel = value) }

    fun onServingSizeChange(value: String) = update { it.copy(servingSize = value) }

    // endregion

    /**
     * `Save food`, and `Duplicate` on a reference entry — one action, because they differ only
     * in which id and which provenance the row is written under.
     *
     * PRD_FOOD 9.1: a duplicate is a **new** `FoodId` and [FoodSource.CUSTOM]. Writing it under
     * the Ciqual id would be refused by the repository anyway, which is the second half of the
     * same rule and the reason [FoodEditorUiState.saveRefused] exists.
     */
    fun onSave() {
        update { it.copy(attempted = true) }
        transient.value = transient.value.copy(saveRefused = false)

        val stored = existing.value
        val duplicating = stored != null && stored.isReadOnly
        val source = if (duplicating) FoodSource.CUSTOM else stored?.source ?: FoodSource.CUSTOM
        val id = if (duplicating || foodId == null) newId() else requireNotNull(foodId)

        val food = draft.value.toFoodOrNull(id = id, source = source, existing = stored)
            ?: return

        viewModelScope.launch {
            if (foods.save(food)) {
                transient.value = transient.value.copy(finished = true)
            } else {
                transient.value = transient.value.copy(saveRefused = true)
            }
        }
    }

    // region deletion (PRD_FOOD 9.3 and 17)

    fun onDeleteRequested() {
        transient.value = transient.value.copy(deletion = FoodDeletionUiState.Confirming)
    }

    fun onDeletionDismissed() {
        transient.value = transient.value.copy(deletion = null)
    }

    /**
     * The one place the four branches of [FoodDeletion] become four things a person reads.
     *
     * `Deleted` closes the sheet — there is nothing left to edit. The three others keep it open
     * with a sentence, because every one of them names something that can still be done: duplicate
     * the reference entry, free the food from the recipes that hold it, or simply close a screen
     * that was looking at a row somebody else had already removed.
     */
    fun onDeleteConfirmed() {
        val id = foodId ?: run {
            transient.value = transient.value.copy(
                deletion = FoodDeletionUiState.Refused(FoodCatalogueMessages.NOT_FOUND),
            )
            return
        }

        viewModelScope.launch {
            transient.value = when (val outcome = foods.delete(id)) {
                is FoodDeletion.Deleted -> transient.value.copy(deletion = null, finished = true)

                is FoodDeletion.NotFound -> transient.value.copy(
                    deletion = FoodDeletionUiState.Refused(FoodCatalogueMessages.NOT_FOUND),
                )

                is FoodDeletion.ReadOnly -> transient.value.copy(
                    deletion = FoodDeletionUiState.Refused(
                        FoodCatalogueMessages.READ_ONLY_REFUSAL,
                    ),
                )

                is FoodDeletion.UsedByRecipes -> transient.value.copy(
                    deletion = FoodDeletionUiState.Refused(
                        FoodCatalogueMessages.usedByRecipes(outcome.recipeNames),
                    ),
                )
            }
        }
    }

    // endregion

    private fun load(id: FoodId) {
        viewModelScope.launch {
            val stored = foods.findById(id)
            existing.value = stored

            /*
             * The stored values seed the form only once. After a process death the draft is
             * already back from the `Bundle`, and overwriting it here would silently discard
             * whatever had been typed — which PRD_FOOD 15 forbids even for a refused value.
             */
            if (stored != null && savedStateHandle.get<String>(KEY_DRAFT) == null) {
                replace(FoodEditorDraft.of(stored))
            }
            transient.value = transient.value.copy(loading = false)
        }
    }

    private fun build(
        form: FoodEditorDraft,
        stored: Food?,
        flags: Transient,
    ): FoodEditorUiState {
        val mode = when {
            foodId == null -> FoodEditorMode.CREATE
            stored?.isReadOnly == true -> FoodEditorMode.REFERENCE
            else -> FoodEditorMode.EDIT
        }
        return FoodEditorUiState.of(
            draft = form,
            mode = mode,
            source = stored?.source ?: FoodSource.CUSTOM,
            isLoading = flags.loading && foodId != null,
            saveRefused = flags.saveRefused,
            isFinished = flags.finished,
            deletion = flags.deletion,
        )
    }

    private fun update(block: (FoodEditorDraft) -> FoodEditorDraft) {
        replace(block(draft.value))
    }

    private fun replace(next: FoodEditorDraft) {
        draft.value = next
        savedStateHandle[KEY_DRAFT] = next.toJson()
        if (transient.value.saveRefused) {
            transient.value = transient.value.copy(saveRefused = false)
        }
    }

    /**
     * What is true of this sheet right now and is not worth surviving a process death.
     *
     * A refusal is the result of pressing a button; a form restored from a `Bundle` has pressed
     * nothing, and greeting it with a refusal it cannot connect to anything would be a message
     * about an action that never happened here.
     */
    private data class Transient(
        val loading: Boolean = true,
        val saveRefused: Boolean = false,
        val finished: Boolean = false,
        val deletion: FoodDeletionUiState? = null,
    )

    companion object {

        internal const val KEY_DRAFT: String = "food.editor.draft"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The editor's stores, off `AppContainer.food`.
         *
         * A function rather than the usual `val Factory`, because this screen takes two things
         * the container cannot know: which food is being edited, and the term a fruitless search
         * offered to create. `FoodRoute.FoodEditor` carries the id; the term reaches the sheet
         * from `FoodNavHost`, which is the note in this module's report.
         */
        fun factory(
            foodId: FoodId?,
            prefillName: String? = null,
            prefillBarcode: String? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                FoodEditorViewModel(
                    foods = app.container.food.foodCatalogueRepository,
                    foodId = foodId,
                    prefillName = prefillName,
                    prefillBarcode = prefillBarcode,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

/**
 * The editor's ViewModel, keyed by what it is editing.
 *
 * The key matters: creating a food and correcting one are two sheets that may be opened one
 * after the other, and a shared store entry would hand the second the first's abandoned draft.
 */
@Composable
fun foodEditorViewModel(
    foodId: FoodId?,
    prefillName: String? = null,
    prefillBarcode: String? = null,
): FoodEditorViewModel =
    viewModel(
        key = "foodEditor:${foodId?.value ?: "new"}",
        factory = FoodEditorViewModel.factory(foodId, prefillName, prefillBarcode),
    )
