package fr.kristenjestin.mue.ui.food.add

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.logic.MealSlotRules
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.logic.errorMessage
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.domain.repository.LookupFailure
import fr.kristenjestin.mue.ui.food.FoodIcons
import fr.kristenjestin.mue.ui.food.day.FoodDayFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Which part of the flow the sheet is showing (PRD_FOOD 7: four ways in, then how much and when). */
internal enum class FoodAddStage {
    /** PRD_FOOD 7's ways in, shown while nothing has been chosen yet. */
    PATHS,

    /** FR-FOOD-003: the camera and the typed barcode, until one of them yields a food. */
    SCAN,

    /** FR-FOOD-006: a catalogue food, weighed or counted in its usual portions. */
    AMOUNT,

    /** FR-FOOD-005: a name and an energy, and no quantity at all. */
    QUICK,

    /** FR-FOOD-008 on a recipe line: the servings eaten, rescaled from the frozen snapshot. */
    SERVINGS,

    /** PRD_FOOD 17: a line whose food has been deleted — moment and time only. */
    FROZEN,
}

/**
 * One nutritional figure, already rendered (PRD_FOOD 13.2).
 *
 * [value] comes from [FoodLabels] and from nowhere else, which is what keeps an unknown a `—` and
 * a known zero a `≈ 0.0 g` all the way to the glass. [spoken] is the same fact for the ear:
 * PRD_FOOD 18 asks for values to be announced "avec leur unité et la mention d'approximation",
 * and a dash is a drawing that TalkBack reads as punctuation or skips outright.
 */
@Immutable
internal data class FoodNutrientUiState(
    /** The metric's stable name, which is also the handle a test reads the row by. */
    val key: String,
    val label: String,
    val value: String,
    val spoken: String,
)

/**
 * The five metrics of a food or of a line, in the order PRD_FOOD 8.2 lists them.
 *
 * [header] says what they are quoted against — `Per 100 g raw`, or `In this entry` — because the
 * same five numbers mean two different things either side of a quantity being typed.
 */
@Immutable
internal data class FoodNutrientsUiState(
    val header: String,
    val rows: List<FoodNutrientUiState>,
    val description: String,
) {
    companion object {

        /** The five keys of `FoodTestTags.nutrientField`, which name the same five metrics. */
        const val ENERGY: String = "energy"
        const val PROTEIN: String = "protein"
        const val CARBS: String = "carbs"
        const val FAT: String = "fat"
        const val FIBRE: String = "fibre"

        fun of(header: String, nutrients: Nutrients): FoodNutrientsUiState {
            val rows = listOf(
                row(ENERGY, FoodAddMessages.ENERGY_NOUN, FoodLabels.energy(nutrients.energy)),
                row(PROTEIN, FoodAddMessages.PROTEIN_NOUN, FoodLabels.macro(nutrients.protein)),
                row(CARBS, FoodAddMessages.CARBS_NOUN, FoodLabels.macro(nutrients.carbs)),
                row(FAT, FoodAddMessages.FAT_NOUN, FoodLabels.macro(nutrients.fat)),
                row(FIBRE, FoodAddMessages.FIBRE_NOUN, FoodLabels.macro(nutrients.fibre)),
            )
            return FoodNutrientsUiState(
                header = header,
                rows = rows,
                description = FoodDayFormat.sentence(
                    header,
                    *rows.map { "${it.label} ${it.spoken}" }.toTypedArray(),
                ),
            )
        }

        private fun row(key: String, label: String, value: String): FoodNutrientUiState =
            FoodNutrientUiState(
                key = key,
                label = label,
                value = value,
                spoken = FoodDayFormat.spoken(value),
            )
    }
}

/** The catalogue entry a line is being built from, as its card reads (FR-CATALOG-004). */
@Immutable
internal data class FoodAddFoodUiState(
    val name: String,
    /** The brand and the provenance under the name, joined as a picker row joins them. */
    val meta: String,
    val iconName: String,
    val description: String,
)

/** One of PRD_FOOD 10.1's four moments as an option (FR-FOOD-007). */
@Immutable
internal data class FoodAddSlotUiState(
    val slot: MealSlot,
    val label: String,
    /**
     * PRD_FOOD 10.3's window under the name — `05:00 – 10:00` — or `Any other time` for the snack.
     *
     * The moment and the hour were two controls with nothing between them, and a moment is not a
     * word anyone can define by looking at it. Its hours are the definition, they are already in
     * the domain, and this is where they belong: on the thing being chosen, before the choice.
     */
    val hoursLabel: String,
    val iconName: String,
    val selected: Boolean,
)

/**
 * How much of a food (FR-FOOD-006), in the two readings PRD_FOOD 8.6 allows at once.
 *
 * Both controls are on screen together and neither is a mode: touching the counter fills the
 * weight, and typing a weight drops the counter — "la saisie exacte en grammes reprend toujours
 * la main sur la portion". [portions] is null exactly while the weight field is in charge, which
 * is also what makes the saved label keep one reading rather than two (PRD_FOOD 22).
 */
@Immutable
internal data class FoodAmountUiState(
    val quantity: String,
    val quantityLabel: String,
    val unitSymbol: String,
    /** The food's own portion word — `1 apple`, `1 pot` — or null when it declares none. */
    val servingLabel: String?,
    val portions: Servings?,
    val portionsValue: String,
    val canAddPortion: Boolean,
    val canRemovePortion: Boolean,
    /** FR-FOOD-006: the two state words, or null on a food that cannot be cooked into another. */
    val cookedStates: List<String>?,
    val cookedStateSelected: String?,
    val weighedCooked: Boolean,
    /** PRD_FOOD 13.1: the weight the values were actually computed from, when it differs. */
    val referenceNote: String?,
)

/**
 * Where the scan path has got to (PRD_FOOD 9.2 and 17).
 *
 * Five states, and every one of them is a different screen in PRD_FOOD 17's table. Collapsing any
 * two would be the failure `LookupFailure` and `ProductLookupResult` were both written to prevent
 * one layer down: "Open Food Facts has never heard of this jar" sends somebody to type a label,
 * and "your train went into a tunnel" must not.
 *
 * It is deliberately **not** in the draft. None of it was typed, none of it survives a process
 * death worth keeping — a lookup made twenty minutes ago on a network that has since changed is
 * not news — and a `Food` cannot cross a `Bundle` anyway.
 */
internal sealed interface FoodScanState {

    /** Nothing has been looked up yet: the camera is live, the field is empty or being typed. */
    data object Idle : FoodScanState

    /** A request is out. PRD_FOOD 17 keeps the other three ways in reachable while it is. */
    data object LookingUp : FoodScanState

    /**
     * A product card came back, or the local catalogue already held one for this number.
     *
     * [food] is a **candidate** until [alreadyInCatalogue] says otherwise: PRD_FOOD 9.2 copies the
     * product locally "au moment de l'ajout", so nothing is written until the person accepts what
     * they are looking at. That matters most on an incomplete card — the values are on screen,
     * with their dashes, *before* a row exists.
     */
    data class Found(val food: Food, val alreadyInCatalogue: Boolean) : FoodScanState

    /** The service answered and knows no such barcode. PRD_FOOD 17 prefills a manual creation. */
    data class NotFound(val barcode: String) : FoodScanState

    /** Nothing was learned about the barcode, and [reason] says which of the four ways. */
    data class Unavailable(val barcode: String, val reason: LookupFailure) : FoodScanState
}

/**
 * The product a lookup found, rendered (PRD_FOOD 9.2, 13.2 and 17).
 *
 * [per100] goes through [FoodNutrientsUiState] like every other set of five figures in this
 * module, which is what guarantees that a card with no fibre shows `—` and never `≈ 0 g`. The
 * mapper already refuses to invent the value; this is the half that refuses to draw one.
 */
@Immutable
internal data class FoodScanFoundUiState(
    val name: String,
    val meta: String,
    val iconName: String,
    val description: String,
    val per100: FoodNutrientsUiState,
    val actionLabel: String,
    /** PRD_FOOD 17: said out loud when the card documents fewer than the five metrics. */
    val incompleteNote: String?,
)

/**
 * What the scan panel says when it has no product to show (PRD_FOOD 17).
 *
 * One shape for the two cases because what the screen *does* with them is identical — print the
 * sentence, offer what is possible — while what it *says* is never the same twice: that is
 * [FoodAddMessages]' job, and the four [LookupFailure] values reach four different sentences
 * there.
 */
@Immutable
internal data class FoodScanNoticeUiState(
    val message: String,
    val detail: String?,
    /** Only a failure can be retried; a product that does not exist will not exist next time. */
    val canRetry: Boolean,
    /** PRD_FOOD 17's "bascule vers la création manuelle pré-remplie". */
    val canCreate: Boolean,
)

/**
 * The scan stage, whole (FR-FOOD-003, PRD_FOOD 17 and 18).
 *
 * [cameraExplanation] is the sentence PRD_FOOD 17 asks for when the camera is unavailable, and
 * its presence is never a reason to hide anything else: the field, the button and every outcome
 * below work identically with no camera on the device at all.
 */
@Immutable
internal data class FoodScanUiState(
    val barcode: String,
    /** PRD_FOOD 15's own refusal, shown after an attempt rather than during typing. */
    val barcodeError: String?,
    val canLookUp: Boolean,
    val isLookingUp: Boolean,
    /** True while the preview should be on screen: granted, and this device has a camera. */
    val isCameraLive: Boolean,
    val cameraExplanation: String?,
    /** PRD_FOOD 18: the only offer of the system prompt, and it is made at most once. */
    val cameraActionLabel: String?,
    val found: FoodScanFoundUiState?,
    val notice: FoodScanNoticeUiState?,
    /** A write that failed on the way into the catalogue. Nothing was lost. */
    val saveError: String?,
) {

    /**
     * The camera's three facts, folded in by the screen that can see them.
     *
     * They arrive here rather than through the ViewModel because a permission is a property of
     * the Android process, not of a draft: `rememberFoodCameraPermission` reads it, re-reads it on
     * every resume, and hands the answer down. Keeping it out of [FoodAddUiState.of] is what lets
     * that function stay a pure function of a draft, and keeping the *wording* in here — rather
     * than in the composable — is what lets PRD_FOOD 17's three sentences be proved on the JVM.
     *
     * The order of the branches is the order of the truths a person is owed:
     *
     * 1. **there is no camera on this device.** Nothing to grant, nothing to explain about
     *    permissions, and offering a prompt that would change nothing would be a lie;
     * 2. **granted.** The preview runs and there is nothing to say;
     * 3. **never asked.** The offer is made, once, and only from here;
     * 4. **refused.** The explanation PRD_FOOD 17 requires, and **no second prompt** — Android
     *    would not show one, and pretending otherwise would hand somebody a button that does
     *    nothing. Settings is the way back, and the field below never needed either.
     */
    fun withCamera(
        isGranted: Boolean,
        isAvailable: Boolean,
        canRequest: Boolean,
    ): FoodScanUiState = when {
        !isAvailable -> copy(
            isCameraLive = false,
            cameraExplanation = FoodAddMessages.CAMERA_ABSENT,
            cameraActionLabel = null,
        )

        isGranted -> copy(isCameraLive = true, cameraExplanation = null, cameraActionLabel = null)

        canRequest -> copy(
            isCameraLive = false,
            cameraExplanation = FoodAddMessages.CAMERA_NOT_YET_ALLOWED,
            cameraActionLabel = FoodAddMessages.ALLOW_CAMERA,
        )

        else -> copy(
            isCameraLive = false,
            cameraExplanation = FoodAddMessages.CAMERA_REFUSED,
            cameraActionLabel = FoodAddMessages.OPEN_CAMERA_SETTINGS,
        )
    }
}

/** FR-FOOD-005: the three fields of a quick add, and nothing else. */
@Immutable
internal data class FoodQuickUiState(
    val title: String,
    val energy: String,
    val protein: String,
)

/**
 * The whole `Add food` sheet (PRD_FOOD 7, 10.3, 13 and 15).
 *
 * Every figure here is already computed and already rendered, exactly as on the shipped `Day`
 * screen: the arithmetic is [fr.kristenjestin.mue.domain.logic.NutritionMath]'s, the bounds are
 * [fr.kristenjestin.mue.domain.logic.FoodValidation]'s, the strings are [FoodLabels]'s. The
 * screen chooses a position and a colour, never a value — which is what makes PRD_FOOD 13.1's
 * `null`-is-never-`0` rule provable on the JVM instead of only visible on a phone.
 */
@Immutable
internal data class FoodAddUiState(
    val stage: FoodAddStage,
    val isEditing: Boolean,
    val screenTitle: String,
    val date: LocalDate,
    val today: LocalDate,
    val dateLabel: String,
    val dateDescription: String,
    val slot: MealSlot,
    val slots: List<FoodAddSlotUiState>,
    val time: LocalTime,
    val timeLabel: String,
    /**
     * PRD_FOOD 10.3: what to say when the hour and the moment disagree, and null when they don't.
     *
     * Never a refusal and never a correction. The windows "ne créent aucune contrainte", so a
     * breakfast at six in the evening is saved exactly as chosen — the screen only stops letting
     * the pair look like an accident.
     */
    val slotTimeNote: String?,
    val food: FoodAddFoodUiState?,
    val amount: FoodAmountUiState?,
    /** FR-FOOD-003, and null on every stage but [FoodAddStage.SCAN]. */
    val scan: FoodScanUiState?,
    val quick: FoodQuickUiState?,
    val servings: String,
    /** PRD_FOOD 8.2: what the food is worth per 100, shown before any quantity is typed. */
    val per100: FoodNutrientsUiState?,
    /** PRD_FOOD 13.1: what this line contributes, once there is a quantity to contribute from. */
    val contribution: FoodNutrientsUiState?,
    val errors: FoodAddErrors,
    val saveLabel: String,
    val saveDescription: String,
    val saveError: String?,
    val justSaved: Boolean,
    val justDeleted: Boolean,
    val isTimePickerVisible: Boolean,
    val isLoading: Boolean,
) {

    /** PRD_FOOD 17: the line's food has been deleted, so its values are the frozen ones. */
    val isFoodMissing: Boolean get() = stage == FoodAddStage.FROZEN

    val canDelete: Boolean get() = isEditing

    /**
     * Whether the sheet can go back to PRD_FOOD 7's ways in.
     *
     * On a new line, from every stage but the first: choosing a path used to be irreversible, and
     * a person who changed their mind had no move short of saving something they did not eat.
     *
     * Never while correcting a stored line (FR-FOOD-008). That sheet was not opened on the ways
     * in and has no earlier stage to return to — its line already has a form, and offering to
     * unmake it would offer to turn a weighed food into a quick add.
     */
    val canReturnToPaths: Boolean get() = !isEditing && stage != FoodAddStage.PATHS

    /**
     * Whether the sticky `Save entry` belongs on screen.
     *
     * Not on the ways in, and **not on the scan**: neither stage has a line to write yet, and a
     * primary button that can only refuse is worse than no button — it makes the screen look
     * finished when the person has not chosen a food. The scan has its own action, on its own
     * result, and it says what it does.
     */
    val showsSaveAction: Boolean
        get() = stage != FoodAddStage.PATHS && stage != FoodAddStage.SCAN

    /** The figures under the fields: the contribution once there is one, the per-100 until then. */
    val figures: FoodNutrientsUiState? get() = contribution ?: per100

    companion object {

        /**
         * The sheet from the draft, the food behind it and the line it is correcting.
         *
         * A pure function of four values and a clock, so every rule below is tested without a
         * ViewModel, a database or an emulator.
         */
        fun of(
            draft: FoodAddDraft,
            food: Food? = null,
            original: FoodLogEntry? = null,
            today: LocalDate = LocalDate.now(),
            errors: FoodAddErrors = FoodAddErrors.EMPTY,
            saveError: String? = null,
            justSaved: Boolean = false,
            justDeleted: Boolean = false,
            isTimePickerVisible: Boolean = false,
            isLoading: Boolean = false,
            /** FR-FOOD-003. [FoodScanState.Idle] on every stage that is not the scan. */
            scan: FoodScanState = FoodScanState.Idle,
            /** PRD_FOOD 15: the barcode's refusal appears after an attempt, not while typing. */
            scanAttempted: Boolean = false,
            scanSaveError: String? = null,
            locale: Locale = Locale.getDefault(),
        ): FoodAddUiState {
            val date = draft.date(today)
            val time = draft.time(MealSlot.fromId(draft.slotId).defaultTime)
            val stage = stageOf(draft, food, original)
            val amount = food?.let { amountOf(draft, it) }

            return FoodAddUiState(
                stage = stage,
                isEditing = draft.isEditing,
                screenTitle = if (draft.isEditing) {
                    FoodAddMessages.EDIT_TITLE
                } else {
                    FoodAddMessages.ADD_TITLE
                },
                date = date,
                today = today,
                dateLabel = FoodDayFormat.dayLabel(date, today, locale),
                dateDescription = FoodDayFormat.dayDescription(date, today, locale),
                slot = draft.slot,
                slots = MealSlot.ORDERED.map { slot ->
                    FoodAddSlotUiState(
                        slot = slot,
                        label = slot.label,
                        hoursLabel = hoursOf(slot, locale),
                        iconName = FoodIcons.forSlot(slot),
                        selected = slot == draft.slot,
                    )
                },
                time = time,
                timeLabel = FoodDayFormat.time(time, locale),
                slotTimeNote = slotTimeNote(draft.slot, time, locale),
                food = food?.let(::foodOf),
                amount = amount,
                scan = scanOf(draft, scan, scanAttempted, scanSaveError)
                    .takeIf { stage == FoodAddStage.SCAN },
                quick = FoodQuickUiState(
                    title = draft.quickTitle,
                    energy = draft.quickEnergy,
                    protein = draft.quickProtein,
                ).takeIf { stage == FoodAddStage.QUICK },
                servings = draft.servings,
                per100 = food?.let {
                    FoodNutrientsUiState.of(FoodAddMessages.per100Label(it), it.per100)
                },
                contribution = contributionOf(draft, food, original, stage),
                errors = errors,
                saveLabel = if (draft.isEditing) {
                    FoodAddMessages.SAVE_CHANGES
                } else {
                    FoodAddMessages.SAVE_ENTRY
                },
                saveDescription = FoodAddMessages.saveDescription(
                    label = if (draft.isEditing) {
                        FoodAddMessages.SAVE_CHANGES
                    } else {
                        FoodAddMessages.SAVE_ENTRY
                    },
                    slot = draft.slot,
                    dateLabel = FoodDayFormat.dayLabel(date, today, locale),
                ),
                saveError = saveError,
                justSaved = justSaved,
                justDeleted = justDeleted,
                isTimePickerVisible = isTimePickerVisible,
                isLoading = isLoading,
            )
        }

        /**
         * PRD_FOOD 10.3's window for a moment, rendered.
         *
         * The bounds are [MealSlotRules.windowOf]'s and the clock face is [FoodDayFormat.time]'s,
         * so the hours under `Breakfast` and the hours the clock actually preselects it for are
         * the same two numbers. A null window is [MealSlot.SNACK]'s, which PRD_FOOD 10.3 defines
         * as the complement of the other three — not an interval, and never drawn as one.
         */
        private fun hoursOf(slot: MealSlot, locale: Locale): String {
            val window = MealSlotRules.windowOf(slot) ?: return FoodAddMessages.ANY_OTHER_TIME
            return FoodAddMessages.slotHours(
                from = FoodDayFormat.time(window.from, locale),
                untilExclusive = FoodDayFormat.time(window.untilExclusive, locale),
            )
        }

        /**
         * The sentence shown when the chosen hour is not in the chosen moment's window.
         *
         * [MealSlotRules.isWithinWindow] is the whole test, half-open ends included, so ten
         * o'clock sharp is a snack here for exactly the reason PRD_FOOD 22 says it is one in the
         * journal. Null while the two agree: there is nothing to explain, and a line of prose
         * under every entry would be noise.
         */
        private fun slotTimeNote(slot: MealSlot, time: LocalTime, locale: Locale): String? {
            if (MealSlotRules.isWithinWindow(slot, time)) return null
            return FoodAddMessages.timeOutsideSlot(
                timeLabel = FoodDayFormat.time(time, locale),
                byTheClock = MealSlotRules.slotFor(time),
                chosen = slot,
            )
        }

        private fun stageOf(
            draft: FoodAddDraft,
            food: Food?,
            original: FoodLogEntry?,
        ): FoodAddStage = when (draft.kind) {
            FoodLogKind.QUICK -> FoodAddStage.QUICK
            FoodLogKind.RECIPE -> FoodAddStage.SERVINGS
            FoodLogKind.FOOD -> when {
                food != null -> FoodAddStage.AMOUNT
                // PRD_FOOD 17: the line survives its food; the sheet says so rather than emptying.
                original != null -> FoodAddStage.FROZEN
                /*
                 * Below the food and not above it, deliberately. Accepting a scanned product
                 * writes the catalogue row and then the id, and the food is re-read from the
                 * repository — so for a frame or two after the tap there is an id and no `Food`
                 * yet. Ranking the scan last keeps the scanner on screen across that gap instead
                 * of flashing PRD_FOOD 7's three cards over a choice that has just been made.
                 */
                draft.scanning -> FoodAddStage.SCAN
                else -> FoodAddStage.PATHS
            }
        }

        /**
         * The scan panel, from the draft and whatever the lookup last said.
         *
         * Pure, like everything else in this file, which is what lets PRD_FOOD 17's five outcomes
         * be checked without a camera, a socket or an emulator — the three things that make the
         * rest of this feature hard to test.
         */
        private fun scanOf(
            draft: FoodAddDraft,
            scan: FoodScanState,
            attempted: Boolean,
            saveError: String?,
        ): FoodScanUiState {
            val typed = draft.scanBarcode
            val refusal = FoodValidation.validateBarcode(typed).errorMessage
            return FoodScanUiState(
                barcode = typed,
                // Only after an attempt: PRD_FOOD 15 wants a refusal "à côté du champ concerné",
                // not a complaint at the fourth digit of thirteen.
                barcodeError = refusal.takeIf { attempted },
                // Enabled on anything typed rather than on anything valid, so pressing it is what
                // produces the explanation. A button that greys itself out has told the person
                // nothing about which of the thirteen digits is wrong.
                canLookUp = typed.isNotBlank() && scan != FoodScanState.LookingUp,
                isLookingUp = scan == FoodScanState.LookingUp,
                // Filled in by the screen through `withCamera`; the ViewModel cannot see a
                // permission and does not pretend to.
                isCameraLive = false,
                cameraExplanation = null,
                cameraActionLabel = null,
                found = (scan as? FoodScanState.Found)?.let(::foundOf),
                notice = noticeOf(scan),
                saveError = saveError,
            )
        }

        /** The product card a lookup produced, with its five figures drawn as figures. */
        private fun foundOf(found: FoodScanState.Found): FoodScanFoundUiState {
            val row = FoodPickerRowUiState.of(found.food)
            return FoodScanFoundUiState(
                name = row.name,
                meta = row.meta,
                iconName = row.iconName,
                description = FoodDayFormat.sentence(row.name, row.meta),
                per100 = FoodNutrientsUiState.of(
                    FoodAddMessages.per100Label(found.food),
                    found.food.per100,
                ),
                actionLabel = if (found.alreadyInCatalogue) {
                    FoodAddMessages.USE_THIS_FOOD
                } else {
                    FoodAddMessages.ADD_THIS_PRODUCT
                },
                /*
                 * PRD_FOOD 17: "Fiche produit incomplète → valeurs manquantes vides et
                 * modifiables, jamais estimées." The dashes above already say which values are
                 * missing; this says what they mean and what can be done about them, because a
                 * dash on its own reads as a defect in Mue rather than a gap in a card written by
                 * somebody else.
                 */
                incompleteNote = FoodAddMessages.INCOMPLETE_CARD
                    .takeIf { !found.food.per100.isFullyKnown },
            )
        }

        /**
         * The sentence shown when there is no product to show, or null when there is one.
         *
         * Every branch names itself. [LookupFailure] carries four values because PRD_FOOD 17 asks
         * for "un message explicite", and the four things a person would do next — wait for
         * signal, try again in a moment, give up on this product, type it in — are different
         * enough that one sentence for all of them would be a wrong instruction three times out
         * of four.
         */
        private fun noticeOf(scan: FoodScanState): FoodScanNoticeUiState? = when (scan) {
            FoodScanState.Idle, FoodScanState.LookingUp, is FoodScanState.Found -> null

            is FoodScanState.NotFound -> FoodScanNoticeUiState(
                message = FoodAddMessages.PRODUCT_NOT_FOUND,
                detail = FoodAddMessages.productNotFoundDetail(scan.barcode),
                // It will not be there next time either. Offering `Try again` would be an
                // invitation to repeat a request whose answer is already known.
                canRetry = false,
                canCreate = true,
            )

            is FoodScanState.Unavailable -> FoodScanNoticeUiState(
                message = FoodAddMessages.lookupFailure(scan.reason),
                detail = FoodAddMessages.lookupFailureDetail(scan.reason),
                canRetry = true,
                // PRD_FOOD 17 keeps every other path open during a network failure, and creating
                // the food by hand is one of them: the number is known, the label is in the
                // person's hand, and nothing about a broken network makes that impossible.
                canCreate = true,
            )
        }

        /**
         * The chosen food's card, worded exactly as its row in the picker was.
         *
         * The same two facts under the same name: what it is, and where it came from. A second
         * wording here would make the food look like a different one across a single tap.
         */
        private fun foodOf(food: Food): FoodAddFoodUiState {
            val row = FoodPickerRowUiState.of(food)
            return FoodAddFoodUiState(
                name = row.name,
                meta = row.meta,
                iconName = row.iconName,
                description = FoodDayFormat.sentence(row.name, row.meta),
            )
        }

        /**
         * The quantity block, with the raw/cooked reading beside it (FR-FOOD-006).
         *
         * The steps of the counter are [Servings]' own — half a portion, between 0.5 and 20 —
         * so the two buttons disable exactly where PRD_FOOD 15 stops, and nothing here restates
         * a bound.
         */
        private fun amountOf(draft: FoodAddDraft, food: Food): FoodAmountUiState {
            val portions = draft.portions?.takeIf { food.hasUsualServing }
            val resolved = draft.resolveAmount(food)
            val reference = (resolved as? Validated.Valid)?.value?.referenceWeight
            return FoodAmountUiState(
                quantity = draft.quantity,
                quantityLabel = FoodAddMessages.quantityLabel(food, draft.weighedCooked),
                unitSymbol = food.referenceUnit.symbol,
                servingLabel = food.servingLabel?.takeIf { food.hasUsualServing },
                portions = portions,
                portionsValue = FoodLabels.servings(portions),
                canAddPortion = food.hasUsualServing && stepped(portions, up = true) != null,
                canRemovePortion = food.hasUsualServing && stepped(portions, up = false) != null,
                cookedStates = if (food.hasCookedState) {
                    listOf(
                        FoodAddMessages.stateWord(food, cooked = false),
                        FoodAddMessages.stateWord(food, cooked = true),
                    )
                } else {
                    null
                },
                cookedStateSelected = if (food.hasCookedState) {
                    FoodAddMessages.stateWord(food, draft.weighedCooked)
                } else {
                    null
                },
                weighedCooked = draft.weighedCooked && food.hasCookedState,
                referenceNote = reference?.let {
                    FoodAddMessages.countedAs(
                        referenceWeight = FoodLabels.quantity(it, food.referenceUnit),
                        rawWord = FoodAddMessages.stateWord(food, cooked = false),
                    )
                },
            )
        }

        /**
         * What this line would contribute, or null while there is nothing to contribute from.
         *
         * Null is not an unknown here and must not be drawn as one: it means no quantity has been
         * given yet, and the screen shows the per-100 card in its place. An unknown value inside
         * a contribution stays a `—`, which is a different fact and a different drawing.
         */
        private fun contributionOf(
            draft: FoodAddDraft,
            food: Food?,
            original: FoodLogEntry?,
            stage: FoodAddStage,
        ): FoodNutrientsUiState? {
            val nutrients = when (stage) {
                FoodAddStage.AMOUNT -> (draft.resolveAmount(food ?: return null) as? Validated.Valid)
                    ?.value
                    ?.nutrients
                    ?: return null

                FoodAddStage.FROZEN -> original?.nutrients ?: return null

                FoodAddStage.QUICK -> draft.quickNutrientsOrNull() ?: return null

                FoodAddStage.SERVINGS -> draft.recipeNutrientsOrNull(original) ?: return null

                // Neither stage has a food, a quantity or a line yet; the panel is the figures.
                FoodAddStage.PATHS, FoodAddStage.SCAN -> return null
            }
            return FoodNutrientsUiState.of(FoodAddMessages.CONTRIBUTION_SECTION, nutrients)
        }

        /** One step of the usual-portion counter, or null when PRD_FOOD 15's range ends there. */
        fun stepped(portions: Servings?, up: Boolean): Servings? {
            val current = portions?.count ?: 0.0
            val step = Servings.USUAL_STEP_THOUSANDTHS / Servings.THOUSANDTHS_PER_SERVING.toDouble()
            return Servings.ofUsualOrNull(if (up) current + step else current - step)
        }
    }
}
