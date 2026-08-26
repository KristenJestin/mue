package fr.kristenjestin.mue.ui.food.add

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
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.MealSlotRules
import fr.kristenjestin.mue.domain.logic.NutritionMath
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodLogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * The `Add food` sheet: PRD_FOOD 7's ways in, then the quantity, the moment and the write.
 *
 * **It is shared with the food picker on purpose.** `FoodRoute.FoodPicker` is a `data object` —
 * it carries no parameter, so it cannot carry a destination for what it chose — and the module's
 * stack has no result channel. Both screens therefore ask for *this* ViewModel, which the
 * activity's store hands out once, exactly as `Log activity` and the strength editor share one
 * draft (PRD 9.1). The picker writes the chosen food through [onFoodChosen] and pops; the sheet
 * underneath finds it already there.
 *
 * Nothing here computes a nutritional value, a bound, a label or a moment.
 * [fr.kristenjestin.mue.domain.logic.NutritionMath],
 * [fr.kristenjestin.mue.domain.logic.FoodValidation], [FoodLabels] and [MealSlotRules] own all
 * four, and [FoodAddDraft.resolve] is the single call that puts them together — so PRD_FOOD 13.1's
 * strict `null` rule is proved in the domain and cannot be undone on the way to the glass.
 *
 * The typed draft crosses [SavedStateHandle] as one JSON string, the arrangement
 * `LogActivityViewModel` uses: a half-typed `7,` comes back unchanged after a process death,
 * while the food behind it and the line being corrected are re-read from their repositories
 * rather than copied into the bundle.
 */
internal class FoodAddViewModel(
    private val logs: FoodLogRepository,
    private val foods: FoodCatalogueRepository,
    private val savedState: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: () -> Locale = Locale::getDefault,
) : ViewModel() {

    private val _draft = MutableStateFlow(
        FoodAddDraft.fromJson(savedState[KEY_DRAFT])
            ?: FoodAddDraft.forTarget(date = null, slot = null, today = today(), now = now()),
    )

    /**
     * What a save attempt found and which panel is open — never the typed values.
     *
     * Deliberately outside [savedState], for `LogActivityViewModel`'s reason: a message is the
     * result of pressing `Save`, not something anyone typed, and a picker reopening itself after
     * a process death would be noise.
     */
    private val transient = MutableStateFlow(Transient())

    /** FR-FOOD-008: the stored line being corrected, re-read rather than carried in the draft. */
    private val original = MutableStateFlow<FoodLogEntry?>(null)

    /**
     * The catalogue entry behind the draft, observed rather than copied.
     *
     * A food corrected in another screen redraws this sheet, which matters: the values a line
     * freezes must be the ones on screen when it is saved.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val food: StateFlow<Food?> = _draft
        .map { it.food }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else foods.observeById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val uiState: StateFlow<FoodAddUiState> = combine(
        _draft,
        food,
        original,
        transient,
    ) { draft, chosen, entry, flags -> build(draft, chosen, entry, flags) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = build(_draft.value, null, null, transient.value),
        )

    /**
     * Aims the sheet at a day, a moment, or a line to correct — idempotently.
     *
     * The target is remembered in [savedState], so returning from the picker recomposes the sheet
     * without wiping what has been typed, while a `+` pressed on another moment starts afresh.
     * A restored process finds the same target and keeps its draft; only the stored line, which
     * is not in the draft, is read again.
     */
    fun start(date: LocalDate?, slot: MealSlot?, entryId: FoodLogEntryId?) {
        val target = targetOf(date, slot, entryId)
        val resumed = savedState.get<String>(KEY_TARGET) == target
        savedState[KEY_TARGET] = target

        if (!resumed) {
            transient.value = Transient(isLoading = entryId != null)
            original.value = null
            if (entryId == null) {
                replaceDraft(FoodAddDraft.forTarget(date, slot, today(), now()))
            }
        }
        if (entryId != null && original.value?.id != entryId) load(entryId, seed = !resumed)
    }

    // region choosing what to log (PRD_FOOD 7)

    /**
     * FR-FOOD-002: the food the picker came back with.
     *
     * An id and not a `Food`: the card itself is observed from the catalogue, so a food corrected
     * between being chosen and being logged is quoted as it is now rather than as the picker
     * happened to read it.
     */
    fun onFoodChosen(id: FoodId) {
        clearErrors()
        updateDraft {
            it.copy(
                kindId = FoodLogKind.FOOD.id,
                foodId = id.value,
                quantity = "",
                portionThousandths = null,
                weighedCooked = false,
            )
        }
    }

    /** FR-FOOD-005: the path that has a name and an energy and no quantity at all. */
    fun onQuickAddChosen() {
        clearErrors()
        updateDraft { it.copy(kindId = FoodLogKind.QUICK.id, foodId = null) }
    }

    // endregion

    // region how much (FR-FOOD-006)

    /**
     * PRD_FOOD 8.6: "la saisie exacte en grammes reprend toujours la main sur la portion".
     *
     * Typing here drops the portion counter, which is also what makes the saved label keep one
     * reading rather than two (PRD_FOOD 22).
     */
    fun onQuantityChange(raw: String) {
        clearErrors()
        updateDraft { it.copy(quantity = number(raw), portionThousandths = null) }
    }

    /**
     * One step of the usual-portion counter, which fills the weight field with what it resolves
     * to (PRD_FOOD 8.6: the quantity is stored in grams either way).
     *
     * The weight shown is [NutritionMath.usualServingWeightOrNull]'s own answer, so the number
     * under the counter and the number the values are computed from are the same number.
     */
    fun onPortionStep(up: Boolean) {
        val chosen = food.value ?: return
        val next = FoodAddUiState.stepped(_draft.value.portions, up) ?: return
        clearErrors()
        updateDraft {
            it.copy(
                portionThousandths = next.thousandths,
                quantity = weightText(chosen, next),
            )
        }
    }

    /** FR-FOOD-006: which state the number in the field was read in. */
    fun onCookedStateChange(cooked: Boolean) {
        clearErrors()
        updateDraft { it.copy(weighedCooked = cooked) }
    }

    // endregion

    // region the quick add (FR-FOOD-005)

    fun onQuickTitleChange(raw: String) {
        clearErrors()
        updateDraft { it.copy(quickTitle = raw.take(FoodLogEntry.MAX_TITLE_LENGTH)) }
    }

    fun onQuickEnergyChange(raw: String) {
        clearErrors()
        updateDraft { it.copy(quickEnergy = number(raw)) }
    }

    fun onQuickProteinChange(raw: String) {
        clearErrors()
        updateDraft { it.copy(quickProtein = number(raw)) }
    }

    // endregion

    /** FR-FOOD-008 on a recipe line: how many servings were eaten. */
    fun onServingsChange(raw: String) {
        clearErrors()
        updateDraft { it.copy(servings = number(raw)) }
    }

    // region when and where (PRD_FOOD 10.3, FR-FOOD-007)

    /**
     * The moment, chosen rather than derived.
     *
     * A time nobody has touched follows it: PRD_FOOD 10.3 puts a retroactive line in the middle
     * of its moment, so moving yesterday's line from breakfast to dinner moves `08:00` to
     * `20:00`. A time that *was* typed stays exactly where it was put.
     */
    fun onSlotSelected(slot: MealSlot) {
        clearErrors()
        updateDraft { draft ->
            val moved = draft.copy(slotId = slot.id, slotPinned = true)
            if (draft.timePinned) {
                moved
            } else {
                moved.withTime(
                    MealSlotRules.defaultTime(
                        slot = slot,
                        date = draft.date(today()),
                        today = today(),
                        now = now(),
                    ),
                )
            }
        }
    }

    fun onShowTimePicker() {
        transient.update { it.copy(isTimePickerVisible = true) }
    }

    fun onDismissTimePicker() {
        transient.update { it.copy(isTimePickerVisible = false) }
    }

    /**
     * A time chosen on the dial (PRD_FOOD 10.3).
     *
     * FR-FOOD-007 preselects the moment from the hour, so a moment nobody has chosen follows the
     * time — an apple typed at ten o'clock lands in the snack. Once a moment has been chosen by
     * hand, or carried in by the `+` of a moment, the clock no longer overrules it.
     */
    fun onTimePicked(time: LocalTime) {
        clearErrors()
        updateDraft { draft ->
            val moved = draft.withTime(time).copy(timePinned = true)
            if (draft.slotPinned) moved else moved.copy(slotId = MealSlotRules.slotFor(time).id)
        }
        onDismissTimePicker()
    }

    // endregion

    // region writing the line (PRD_FOOD 8.4)

    /**
     * Freezes the line and stores it.
     *
     * The values are computed once, here, from the food as it is now, and never reopened
     * afterwards (PRD_FOOD 8.4). A refusal lands beside the field it belongs to and leaves every
     * character typed exactly where it was (PRD_FOOD 15 and 17).
     */
    fun save() {
        val draft = _draft.value
        when (val resolution = draft.resolve(food.value, original.value, today())) {
            is FoodAddResolution.Refused ->
                transient.update { it.copy(errors = resolution.errors, saveError = null) }

            is FoodAddResolution.Ready -> viewModelScope.launch {
                transient.update { it.copy(errors = FoodAddErrors.EMPTY, saveError = null) }
                runCatching { logs.save(resolution.entry) }
                    .onSuccess { transient.update { flags -> flags.copy(justSaved = true) } }
                    .onFailure {
                        transient.update { flags ->
                            flags.copy(saveError = FoodAddMessages.SAVE_FAILED)
                        }
                    }
            }
        }
    }

    /** FR-FOOD-008: the same sheet removes the line, which frees the proposal it confirmed. */
    fun delete() {
        val entryId = original.value?.id ?: _draft.value.entry ?: return
        viewModelScope.launch {
            transient.update { it.copy(saveError = null) }
            runCatching { logs.delete(entryId) }
                .onSuccess { transient.update { flags -> flags.copy(justDeleted = true) } }
                .onFailure {
                    transient.update { flags ->
                        flags.copy(saveError = FoodAddMessages.DELETE_FAILED)
                    }
                }
        }
    }

    /**
     * Called once the button's confirmation has played out; the sheet is about to close.
     *
     * This, and the delete below, are the **only** two things that forget a draft. Leaving
     * through back or through `Close` keeps what was typed, which is the rule `Log activity`
     * already sets — "leaving through back keeps the draft; only a save turns it into a session"
     * — and it means the two ways out of this sheet behave the same rather than differing
     * silently. A `+` pressed on another moment is a different line and starts afresh, which
     * [start] is what decides.
     */
    fun onSaveConfirmationFinished() {
        forget()
    }

    fun onDeleteConfirmationFinished() {
        forget()
    }

    // endregion

    private fun build(
        draft: FoodAddDraft,
        chosen: Food?,
        entry: FoodLogEntry?,
        flags: Transient,
    ): FoodAddUiState = FoodAddUiState.of(
        draft = draft,
        food = chosen,
        original = entry,
        today = today(),
        errors = flags.errors,
        saveError = flags.saveError,
        justSaved = flags.justSaved,
        justDeleted = flags.justDeleted,
        isTimePickerVisible = flags.isTimePickerVisible,
        isLoading = flags.isLoading,
        locale = locale(),
    )

    /**
     * Reads the line being corrected back from the journal.
     *
     * A line that has gone — deleted from another screen, or from a stale id — leaves the sheet
     * on its first stage rather than showing a form over nothing.
     */
    private fun load(entryId: FoodLogEntryId, seed: Boolean) {
        viewModelScope.launch {
            val entry = logs.findById(entryId)
            original.value = entry
            if (entry != null && seed) replaceDraft(FoodAddDraft.forEntry(entry))
            transient.update { it.copy(isLoading = false) }
        }
    }

    private fun weightText(food: Food, portions: Servings): String {
        val weight = NutritionMath.usualServingWeightOrNull(food, portions) ?: return ""
        return FoodLabels.quantity(weight, food.referenceUnit).substringBefore(' ')
    }

    private fun replaceDraft(draft: FoodAddDraft) {
        _draft.value = draft
        savedState[KEY_DRAFT] = FoodAddDraft.toJson(draft)
    }

    private fun updateDraft(block: (FoodAddDraft) -> FoodAddDraft) =
        replaceDraft(block(_draft.value))

    /** Any edit takes back the refusal a save attempt left behind (PRD_FOOD 15). */
    private fun clearErrors() {
        transient.update { it.copy(errors = FoodAddErrors.EMPTY, saveError = null) }
    }

    /** Forgets the flow entirely, so the next opening of the sheet starts from nothing. */
    private fun forget() {
        savedState[KEY_TARGET] = null
        savedState[KEY_DRAFT] = null
        original.value = null
        transient.value = Transient()
        _draft.value = FoodAddDraft.forTarget(date = null, slot = null, today = today(), now = now())
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun now(): LocalTime = LocalTime.now(clock)

    /** What the sheet was opened for, as one string a `Bundle` can hold. */
    private fun targetOf(date: LocalDate?, slot: MealSlot?, entryId: FoodLogEntryId?): String =
        entryId?.value ?: "${date ?: ""}/${slot?.id ?: ""}"

    /**
     * Everything a save attempt or a panel decides, and nothing anyone typed.
     */
    private data class Transient(
        val errors: FoodAddErrors = FoodAddErrors.EMPTY,
        val saveError: String? = null,
        val justSaved: Boolean = false,
        val justDeleted: Boolean = false,
        val isTimePickerVisible: Boolean = false,
        val isLoading: Boolean = false,
    )

    companion object {

        internal const val KEY_DRAFT: String = "food.add.draft"
        internal const val KEY_TARGET: String = "food.add.target"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Long enough for any quantity PRD_FOOD 15 allows, short enough to bound the parsing. */
        private const val MAX_NUMBER_LENGTH = 8

        /**
         * A hand-typed number, filtered to what PRD_FOOD 15's parser can read.
         *
         * Both separators survive, because `FoodValidation` accepts both whatever the phone's
         * language is; a half-typed `7,` is kept as it was typed and simply does not parse yet.
         */
        internal fun number(raw: String): String = raw
            .filter { it.isDigit() || it == '.' || it == ',' }
            .take(MAX_NUMBER_LENGTH)

        /**
         * The journal and the catalogue, off `AppContainer.food`, as every other screen reads
         * their own stores.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                FoodAddViewModel(
                    logs = app.container.food.foodLogRepository,
                    foods = app.container.food.foodCatalogueRepository,
                    savedState = createSavedStateHandle(),
                )
            }
        }

        /** One instance for the sheet and the picker both, keyed so neither can mint its own. */
        internal const val KEY: String = "food.add"
    }
}

/**
 * The shared instance of the add flow's ViewModel.
 *
 * The `Add food` sheet and the food picker call this and get the same object: the picker's only
 * output is the food it chose, and the sheet is where that food is going.
 */
@Composable
internal fun foodAddViewModel(): FoodAddViewModel =
    viewModel(key = FoodAddViewModel.KEY, factory = FoodAddViewModel.Factory)

