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
import fr.kristenjestin.mue.data.remote.openfoodfacts.OpenFoodFactsUrl
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.MealSlotRules
import fr.kristenjestin.mue.domain.logic.NutritionMath
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodLogRepository
import fr.kristenjestin.mue.domain.repository.ProductLookup
import fr.kristenjestin.mue.domain.repository.ProductLookupResult
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
 * **It is shared with the two pickers on purpose.** `FoodRoute.FoodPicker` and
 * `FoodRoute.RecipePicker` are `data object`s — they carry no parameter, so neither can carry a
 * destination for what it chose — and the module's stack has no result channel. All three screens
 * therefore ask for *this* ViewModel, which the activity's store hands out once, exactly as
 * `Log activity` and the strength editor share one draft (PRD 9.1). A picker writes its choice
 * through [onFoodChosen] or [onRecipeChosen] and pops; the sheet underneath finds it already
 * there.
 *
 * [onRecipeChosen] is the half that was missing, and its absence is what `Use a recipe` was
 * answered with: a **view change**, which replaced the whole tab and closed the sheet. A landing
 * place for the choice is all it needed.
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
    /**
     * FR-FOOD-004's other catalogue, behind the same kind of domain interface.
     *
     * The recipe picker writes its choice here exactly as the food picker writes its own, so the
     * whole hand-off — chosen, resolved, rescaled, saved — is proved on the JVM against a fake.
     */
    private val recipes: RecipeRepository,
    /**
     * FR-FOOD-003's one network call, behind the domain interface PRD_FOOD 20.2 requires.
     *
     * A constructor parameter and never a container lookup, so the whole scan flow — the local
     * hit, the four named failures, the missing product, the copy into the catalogue — is proved
     * on the JVM against a fake, with no socket and no emulator.
     */
    private val lookup: ProductLookup,
    private val savedState: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: () -> Locale = Locale::getDefault,
) : ViewModel() {

    /** The request currently out, so a second scan replaces the first rather than racing it. */
    private var lookupJob: Job? = null

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

    /**
     * FR-FOOD-004: the recipe behind the draft, observed as the food is, **with its catalogue**.
     *
     * The ingredients are resolved inside the same emission as the recipe, which is
     * `RecipeDetailViewModel`'s rule and matters for the same reason: a detail and a catalogue
     * read a frame apart would print `—` over a figure that is known, and a line saved on that
     * frame would freeze the dash.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val recipe: StateFlow<FoodAddRecipe?> = _draft
        .map { it.recipe }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                recipes.observeDetail(id).map { detail ->
                    detail?.let { FoodAddRecipe(it, resolveIngredients(it)) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val uiState: StateFlow<FoodAddUiState> = combine(
        _draft,
        food,
        original,
        transient,
        recipe,
    ) { draft, chosen, entry, flags, chosenRecipe -> build(draft, chosen, entry, flags, chosenRecipe) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = build(_draft.value, null, null, transient.value, null),
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
                recipeId = null,
                quantity = "",
                portionThousandths = null,
                weighedCooked = false,
            )
        }
    }

    /**
     * FR-FOOD-004: the recipe the picker came back with.
     *
     * The counterpart of [onFoodChosen], and it exists because the sheet had none: `Use a recipe`
     * had nowhere to hand a choice, so it changed **view** instead — which closed the sheet and
     * left the person on the recipe catalogue with nothing linking back to the meal they were
     * writing. This is that landing place. The picker writes here and pops; the sheet underneath
     * finds the recipe already chosen and asks how many servings.
     *
     * An id, not a `RecipeDetail`, for the reason a food is an id: the aggregate is observed from
     * the repository, so a recipe corrected between being chosen and being logged is quoted as it
     * is now — and PRD_FOOD 8.4 freezes it only at the moment of saving.
     *
     * The servings are cleared rather than kept. A count typed against another recipe would mean
     * a different amount of food, and a number left in the field is a number somebody may not
     * re-read.
     */
    fun onRecipeChosen(id: RecipeId) {
        clearErrors()
        updateDraft {
            it.copy(
                kindId = FoodLogKind.RECIPE.id,
                recipeId = id.value,
                foodId = null,
                scanning = false,
                scanBarcode = "",
                quantity = "",
                portionThousandths = null,
                weighedCooked = false,
                servings = "",
            )
        }
    }

    /** FR-FOOD-005: the path that has a name and an energy and no quantity at all. */
    fun onQuickAddChosen() {
        clearErrors()
        updateDraft { it.copy(kindId = FoodLogKind.QUICK.id, foodId = null, recipeId = null) }
    }

    // endregion

    // region the scan (FR-FOOD-003, PRD_FOOD 9.2, 17 and 18)

    /** FR-FOOD-003: the camera and the typed number, which are the same path (PRD_FOOD 18). */
    fun onScanChosen() {
        clearErrors()
        transient.update { it.copy(scan = FoodScanState.Idle, scanAttempted = false) }
        updateDraft {
            it.copy(
                kindId = FoodLogKind.FOOD.id,
                foodId = null,
                recipeId = null,
                scanning = true,
            )
        }
    }

    /**
     * The barcode field, typed into.
     *
     * Filtered to digits because that is what a barcode is and what `FoodValidation.validateBarcode`
     * accepts, and capped at [Food.BARCODE_LENGTH_RANGE]'s longest so a stuck key cannot build a
     * string nothing will ever look up. Any edit takes back the last outcome: a result that stayed
     * on screen under a *different* number would be a product card attributed to the wrong jar.
     */
    fun onBarcodeChange(raw: String) {
        val digits = raw.filter(Char::isDigit).take(Food.BARCODE_LENGTH_RANGE.last)
        cancelLookup()
        transient.update {
            it.copy(scan = FoodScanState.Idle, scanAttempted = false, scanSaveError = null)
        }
        updateDraft { it.copy(scanBarcode = digits) }
    }

    /**
     * A code the camera read (PRD_FOOD 9.2).
     *
     * It goes into the **same field** the fingers would have filled and then takes the same road,
     * which is PRD_FOOD 18's equality made structural rather than promised: there is one lookup,
     * one validation and one set of outcomes, and no branch anywhere below asks how the digits
     * arrived.
     *
     * Ignored while a lookup is running or while a result is on screen. The analyser delivers
     * frames continuously and PRD_FOOD 17 keeps it running — "le scanner continue" — so without
     * this guard a barcode left in front of the lens would re-issue the request behind the answer
     * it already produced. Re-scanning the *same* number after clearing the field works, because
     * clearing it is an edit and an edit returns the panel to [FoodScanState.Idle].
     */
    fun onBarcodeScanned(code: String) {
        if (transient.value.scan != FoodScanState.Idle) return
        if (_draft.value.scanBarcode == code) return
        updateDraft { it.copy(scanBarcode = code) }
        lookUp(code)
    }

    /** The button under the field. PRD_FOOD 15's refusal appears here rather than while typing. */
    fun onLookUpBarcode() {
        val typed = _draft.value.scanBarcode.trim()
        transient.update { it.copy(scanAttempted = true, scanSaveError = null) }
        if (!OpenFoodFactsUrl.isBarcode(typed)) return
        lookUp(typed)
    }

    /** PRD_FOOD 17: a failed lookup can be tried again; a missing product cannot. */
    fun onRetryLookup() {
        val barcode = when (val scan = transient.value.scan) {
            is FoodScanState.Unavailable -> scan.barcode
            else -> return
        }
        lookUp(barcode)
    }

    /**
     * PRD_FOOD 9.2: "Le produit est **copié** dans le catalogue local au moment de l'ajout."
     *
     * This is that moment, and it is a tap rather than the arrival of a response — which is what
     * lets somebody see an incomplete card, with its dashes, *before* a row exists for it. A card
     * already in the catalogue is chosen and not rewritten: it may have been corrected by hand
     * since, and PRD_FOOD 9.2 is explicit that a later remote edit changes nothing locally.
     *
     * The write is the repository's ordinary [FoodCatalogueRepository.save], so the copy is
     * journalled to the outbox exactly like a hand-written food (PRD_FOOD 21.1) and carries its
     * `source`, its barcode, its `sourceId` and its `sourceVersion` with it.
     */
    fun onUseScannedProduct() {
        val found = transient.value.scan as? FoodScanState.Found ?: return
        if (found.alreadyInCatalogue) {
            onFoodChosen(found.food.id)
            return
        }
        viewModelScope.launch {
            transient.update { it.copy(scanSaveError = null) }
            runCatching { foods.save(found.food) }
                .onSuccess { stored ->
                    if (stored) {
                        onFoodChosen(found.food.id)
                    } else {
                        // `save` refuses exactly one thing: a read-only Ciqual row. An Open Food
                        // Facts copy is never one, so reaching here means the id collided with a
                        // reference entry — vanishingly unlikely, and still not a crash.
                        transient.update { it.copy(scanSaveError = FoodAddMessages.COPY_FAILED) }
                    }
                }
                .onFailure {
                    transient.update { it.copy(scanSaveError = FoodAddMessages.COPY_FAILED) }
                }
        }
    }

    /**
     * The barcode a manual creation should be prefilled with (PRD_FOOD 17), or null.
     *
     * Read by the screen at the moment it navigates, rather than pushed out as an event: the
     * editor is a route and this ViewModel does not know the stack. It is the *looked-up* number
     * and not whatever is in the field, so a code corrected after a failed lookup cannot send the
     * editor a number nobody searched for.
     */
    fun barcodeToCreateFrom(): String? = when (val scan = transient.value.scan) {
        is FoodScanState.NotFound -> scan.barcode
        is FoodScanState.Unavailable -> scan.barcode
        else -> null
    }

    /**
     * One lookup, replacing whichever one was already out.
     *
     * The job is held so a second scan cancels the first: two answers arriving out of order would
     * put the wrong product on screen, and `ProductLookup` is explicitly allowed to be slow.
     * Cancellation is also what makes closing the sheet mid-request free.
     *
     * The **local catalogue is asked first**, and that is PRD_FOOD 9.2 rather than a cache: a
     * scanned product is copied locally and "scanning it again finds this row" — the row the
     * person may since have corrected. It also means re-scanning a kept product works with no
     * network at all, which is the offline half of the same rule.
     */
    private fun lookUp(barcode: String) {
        cancelLookup()
        transient.update {
            it.copy(scan = FoodScanState.LookingUp, scanAttempted = true, scanSaveError = null)
        }
        lookupJob = viewModelScope.launch {
            val local = runCatching { localCopyOf(barcode) }.getOrNull()
            if (local != null) {
                transient.update {
                    it.copy(scan = FoodScanState.Found(local, alreadyInCatalogue = true))
                }
                return@launch
            }
            val outcome = when (val result = lookup.byBarcode(barcode)) {
                is ProductLookupResult.Found ->
                    FoodScanState.Found(result.food, alreadyInCatalogue = false)

                ProductLookupResult.NotFound -> FoodScanState.NotFound(barcode)

                is ProductLookupResult.Unavailable ->
                    FoodScanState.Unavailable(barcode, result.reason)
            }
            transient.update { it.copy(scan = outcome) }
        }
    }

    /**
     * The catalogue row this number already belongs to, by either of the two names it can have.
     *
     * `Food` keeps **two** identifiers for a copied product and PRD_FOOD 9.2 is why: `barcode` is
     * what was scanned, and `sourceId` is what Open Food Facts files the card under. They differ
     * more often than one would guess — a UPC-A reads back as twelve digits where the card is
     * filed under thirteen, which `MlKitBarcodeDecoderTest` measures on a device — so looking up
     * only one of them would fetch, and copy, a product this catalogue already holds.
     *
     * The order matters: the scanned number first, because that is the one this phone recorded
     * and the one a re-scan produces.
     */
    private suspend fun localCopyOf(barcode: String): Food? =
        foods.findByBarcode(barcode)
            ?: foods.findBySourceId(FoodSource.OPEN_FOOD_FACTS, barcode)

    private fun cancelLookup() {
        lookupJob?.cancel()
        lookupJob = null
    }

    /**
     * Back to PRD_FOOD 7's ways in, from whichever one was taken.
     *
     * The step the sheet never had. Choosing a path was final until the line was saved or deleted,
     * so someone who picked the wrong one had no move at all: "je suis là dans add food, et si je
     * fais add what you ate je ne fais que tomber sur add food, j'ai plus accès aux 3 menus
     * d'avant". [FoodAddDraft.backToPaths] undoes exactly the path and leaves the day, the moment
     * and the time where they were.
     */
    fun onBackToPaths() {
        clearErrors()
        updateDraft(FoodAddDraft::backToPaths)
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
     * The moment, overruled by hand rather than derived from the hour (FR-FOOD-007).
     *
     * The panel this comes from is the only way to reach it, and it closes on the choice: the
     * override is one tap on the moment wanted, not a field to fill before saving.
     *
     * A time nobody has touched follows it: PRD_FOOD 10.3 puts a retroactive line at an hour
     * inside its moment, so moving yesterday's line from breakfast to dinner moves `08:00` to
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
        onDismissSlotPicker()
    }

    fun onShowSlotPicker() {
        transient.update { it.copy(isSlotPickerVisible = true) }
    }

    fun onDismissSlotPicker() {
        transient.update { it.copy(isSlotPickerVisible = false) }
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
        val resolved = draft.resolve(
            food = food.value,
            original = original.value,
            today = today(),
            recipe = recipe.value,
        )
        when (val resolution = resolved) {
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
     * A save and the delete below forget the flow unconditionally: the line exists now, and
     * nothing about the draft that made it is worth carrying to the next one.
     */
    fun onSaveConfirmationFinished() {
        forget()
    }

    fun onDeleteConfirmationFinished() {
        forget()
    }

    /**
     * The sheet is being left through `Close` or through back — **not** handed to the picker.
     *
     * The rule this settles is the second half of the owner's report. It used to be that leaving
     * kept the draft whatever it held, copied from `Log activity` where it is right: there, back
     * leaves a form full of typed sets, and wiping it would cost real work. Here it meant that
     * choosing `Search a food`, picking one and closing left a draft carrying that food — so the
     * `+` pressed on the same moment a minute later found `resumed = true`, `stageOf` answered
     * `AMOUNT`, and the three ways in were unreachable for good.
     *
     * So the draft is kept **exactly when it holds something that was typed**
     * ([FoodAddDraft.hasTypedContent]) and forgotten otherwise. That keeps the reason the resume
     * exists — a half-written quick add, a weight mid-entry, a `7,` — and drops the case where
     * resuming buys a person nothing and costs them the choice of path.
     *
     * Returning from the food picker does not come through here at all: the picker is pushed over
     * this sheet rather than closing it, so nothing is left and nothing is forgotten. That path is
     * [start]'s, and it is untouched.
     */
    fun onLeft() {
        if (!_draft.value.hasTypedContent) forget()
    }

    // endregion

    /**
     * The foods an aggregate's ingredients name, resolved in one read.
     *
     * A missing food is not an error (PRD_FOOD 21.2) and is simply absent from the map; its
     * contribution is unknown and the strict sum of PRD_FOOD 13.1 carries that through.
     */
    private suspend fun resolveIngredients(detail: RecipeDetail): Map<FoodId, Food> {
        val ids = detail.foodIds
        if (ids.isEmpty()) return emptyMap()
        return foods.findByIds(ids).associateBy(Food::id)
    }

    private fun build(
        draft: FoodAddDraft,
        chosen: Food?,
        entry: FoodLogEntry?,
        flags: Transient,
        chosenRecipe: FoodAddRecipe?,
    ): FoodAddUiState = FoodAddUiState.of(
        draft = draft,
        food = chosen,
        original = entry,
        recipe = chosenRecipe,
        today = today(),
        errors = flags.errors,
        saveError = flags.saveError,
        justSaved = flags.justSaved,
        justDeleted = flags.justDeleted,
        isTimePickerVisible = flags.isTimePickerVisible,
        isSlotPickerVisible = flags.isSlotPickerVisible,
        isLoading = flags.isLoading,
        scan = flags.scan,
        scanAttempted = flags.scanAttempted,
        scanSaveError = flags.scanSaveError,
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
        cancelLookup()
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
        val isSlotPickerVisible: Boolean = false,
        val isLoading: Boolean = false,
        /** FR-FOOD-003: where the scan path is. Never saved; see [FoodScanState]. */
        val scan: FoodScanState = FoodScanState.Idle,
        val scanAttempted: Boolean = false,
        val scanSaveError: String? = null,
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
                    recipes = app.container.food.recipeRepository,
                    lookup = app.container.food.productLookup,
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

