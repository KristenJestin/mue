package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.logic.MealSlotRules
import fr.kristenjestin.mue.domain.logic.NutritionMath
import fr.kristenjestin.mue.domain.logic.QuickAddValidation
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.logic.errorMessage
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.Servings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * A line being written, exactly as it was typed (PRD_FOOD 11 and 15).
 *
 * Everything here is a **raw input string or a stable id**, never a parsed value, for the reason
 * `ActivityDraft` gives: a half-typed `7,` has to come back unchanged after the process is
 * killed, and a `Quantity` cannot cross a `Bundle`. The whole draft travels as one JSON string
 * under one `SavedStateHandle` key.
 *
 * Two things it deliberately does **not** carry: the [Food] that was chosen, and the
 * [FoodLogEntry] being corrected. Both are read back from their repositories by id, so a food
 * whose fibre was fixed in another screen is not quoted from a stale copy, and a process death
 * costs nothing but a re-read.
 *
 * The rules live in this file with it. `resolve` is a pure function of the draft, the food and
 * the line it started from — which is what lets every rule of PRD_FOOD 15 below be proved on the
 * JVM, with no ViewModel, no database and no emulator in the way.
 */
@Serializable
internal data class FoodAddDraft(
    /** FR-FOOD-008: the line being corrected, or null while a new one is being written. */
    val entryId: String? = null,
    /** Which of PRD_FOOD 10.2's three forms is being written. */
    val kindId: String = FoodLogKind.FOOD.id,
    /** The catalogue entry chosen, resolved against the repository rather than copied here. */
    val foodId: String? = null,
    /**
     * FR-FOOD-004: the recipe chosen, resolved against the repository exactly as [foodId] is.
     *
     * An id and not a `RecipeDetail`, for the reason the food is an id: a recipe corrected between
     * being chosen and being logged has to be quoted as it is now, and an aggregate with its
     * ingredients cannot cross a `Bundle`.
     */
    val recipeId: String? = null,
    /**
     * PRD_FOOD 12: this sheet is posing a **proposal** rather than writing a journal line.
     *
     * One flag and not a second draft, because the two share everything that matters: a recipe,
     * a serving count and a moment. What it switches off is the half a proposal has no room for
     * — PRD_FOOD 8.5 gives `MealPlanEntry` no hour, no quantity in grams and no quick add, and
     * §8.5 is explicit that "un aliment simple se journalise directement et n'est pas planifié".
     *
     * It lives in the draft rather than being re-derived from [consumedOn] against today, and the
     * difference is `Swap`: replacing **today's** dinner proposal is a planning gesture on a day
     * the journal would also accept, so a date alone cannot say which of the two the sheet is.
     * It also has to survive a process death, which is what the draft is for.
     */
    val planning: Boolean = false,
    /** ISO-8601. PRD_FOOD 15 refuses a future day, and the sheet is opened on a day already. */
    val consumedOn: String = "",
    /** `HH:mm` (PRD_FOOD 10.3), stored to the minute like the line it becomes. */
    val consumedAt: String = "",
    val slotId: String = MealSlot.SNACK.id,
    /**
     * The moment was chosen rather than derived (FR-FOOD-007).
     *
     * A `+` pressed inside a moment pins it immediately: the person has already said which one,
     * and the clock must not overrule them.
     */
    val slotPinned: Boolean = false,
    /** The time was typed, so changing the moment no longer moves it (PRD_FOOD 10.3). */
    val timePinned: Boolean = false,
    /** What is in the weight field, character for character. */
    val quantity: String = "",
    /**
     * The usual-portion counter, in thousandths of a portion, while it is the one in charge.
     *
     * Null as soon as a weight is typed: PRD_FOOD 8.6 gives the exact weight the last word —
     * "la saisie exacte en grammes reprend toujours la main sur la portion" — and PRD_FOOD 22
     * then wants the label to keep one reading rather than two.
     */
    val portionThousandths: Int? = null,
    /** PRD_FOOD 8.6: the number in the field was read on the scale in the cooked state. */
    val weighedCooked: Boolean = false,
    val quickTitle: String = "",
    val quickEnergy: String = "",
    val quickProtein: String = "",
    /**
     * FR-FOOD-008 on a recipe line: how many servings were eaten.
     *
     * Defaulted to one rather than left blank. It was a required field that opened empty, so
     * choosing a recipe led straight to a refusal to save until a number had been typed — "il me
     * force à saisir un servings en input. Premièrement on peut renseigner 1 par défaut". One
     * serving is the commonest answer by a distance, it is what the per-serving figures above the
     * field are already stated for, and it is inside the range `FoodValidation` enforces, so the
     * form opens on something true and saveable instead of on an error waiting to happen.
     */
    val servings: String = DEFAULT_SERVINGS,
    /**
     * FR-FOOD-003: the scan path was taken, and no food has come out of it yet.
     *
     * A flag and not a [FoodLogKind]: a scan does not produce a *kind* of line — it produces a
     * catalogue food, and the line that follows is an ordinary `FOOD` one indistinguishable from
     * a searched entry. What it does produce is a stage with no food in it yet, which is exactly
     * one bit of information, and this is that bit.
     */
    val scanning: Boolean = false,
    /**
     * The barcode field, character for character (PRD_FOOD 18).
     *
     * **Both paths write here**, and that is the point rather than an implementation detail.
     * PRD_FOOD 18 makes the typed number "une alternative complète à la caméra", so the camera
     * fills this field instead of bypassing it: what was decoded is on screen, in the same box, in
     * a form that can be corrected before it is looked up — and a screen reader announces the same
     * value whichever of the two produced it.
     */
    val scanBarcode: String = "",
) {

    val kind: FoodLogKind get() = FoodLogKind.fromId(kindId)

    val slot: MealSlot get() = MealSlot.fromId(slotId)

    val food: FoodId? get() = foodId?.let(::FoodId)

    val recipe: RecipeId? get() = recipeId?.let(::RecipeId)

    val entry: FoodLogEntryId? get() = entryId?.let(::FoodLogEntryId)

    val isEditing: Boolean get() = entryId != null

    /** The counter's value, or null when the weight field is the one in charge. */
    val portions: Servings?
        get() = portionThousandths?.toLong()?.let(Servings::ofThousandthsOrNull)

    /**
     * The day this line belongs to.
     *
     * Total and non-throwing, for the reason `FoodRoute.fromKey` is: a value written by another
     * build outlives the code that wrote it, and [fallback] is a better outcome than a crash
     * before the first frame.
     */
    fun date(fallback: LocalDate): LocalDate {
        if (consumedOn.isBlank()) return fallback
        return try {
            LocalDate.parse(consumedOn)
        } catch (_: DateTimeParseException) {
            fallback
        }
    }

    /** The time on the line, read through PRD_FOOD 15's own parser. */
    fun time(fallback: LocalTime): LocalTime =
        FoodValidation.validateConsumedAt(consumedAt).valueOrNull ?: fallback

    fun withDate(date: LocalDate): FoodAddDraft = copy(consumedOn = date.toString())

    fun withTime(time: LocalTime): FoodAddDraft =
        copy(consumedAt = format(FoodValidation.normalizeConsumedAt(time)))

    /**
     * Back to PRD_FOOD 7's ways in, having undone the chosen path and **nothing else**.
     *
     * Everything a path set goes: which of the three forms is being written, the food behind it,
     * the quantity, the counter, the state it was weighed in, the quick add's three fields and a
     * recipe line's servings. Everything the `+` decided stays: the day, the moment, the time and
     * whether either was pinned. Those were not chosen on the path being left — they came in with
     * the moment the sheet was opened from, or were set by hand afterwards — and clearing them
     * would answer "I picked the wrong way in" by also moving the entry to another hour.
     *
     * [entryId] survives too, and is why the sheet only ever offers this on a **new** line: a
     * correction opened on a stored entry has no earlier stage to return to.
     */
    fun backToPaths(): FoodAddDraft = copy(
        kindId = FoodLogKind.FOOD.id,
        foodId = null,
        recipeId = null,
        quantity = "",
        portionThousandths = null,
        weighedCooked = false,
        quickTitle = "",
        quickEnergy = "",
        quickProtein = "",
        servings = DEFAULT_SERVINGS,
        scanning = false,
        scanBarcode = "",
    )

    /**
     * The servings field moved one step, or this draft unchanged at a bound.
     *
     * Every part of the arithmetic belongs to `Servings`: the quarter is
     * [Servings.CONSUMED_STEP_THOUSANDTHS], and whether the result is still a serving count is
     * [FoodValidation.validateConsumedServings]'s answer, not a comparison written here. A step
     * that would leave the range returns `this`, which is what greys the button out.
     *
     * A value that does not parse at all — which only a stored draft from an older build can be —
     * steps to one serving rather than nowhere, so the control is never dead.
     */
    fun steppedServings(up: Boolean): FoodAddDraft {
        val current = FoodValidation.validateConsumedServings(servings).valueOrNull
            ?: return copy(servings = DEFAULT_SERVINGS)
        val step = Servings.CONSUMED_STEP_THOUSANDTHS * if (up) 1 else -1
        val moved = Servings.ofThousandthsOrNull((current.thousandths + step).toLong())
            ?.let { FoodValidation.validateConsumedServings(it.count).valueOrNull }
            ?: return this
        return copy(servings = FoodLabels.servings(moved))
    }

    /** Whether [steppedServings] would move, which is the only thing the buttons need to know. */
    fun canStepServings(up: Boolean): Boolean = steppedServings(up).servings != servings

    /**
     * Whether this draft holds anything the person actually wrote.
     *
     * The question the resume rule turns on. A chosen food is deliberately **not** content: it is
     * one tap in the picker, it costs one tap to make again, and treating it as work to protect is
     * precisely what left the sheet reopening on `How much?` forever — "j'ai plus accès aux 3
     * menus d'avant". A weight, a portion count, a quick add's name or energy, a servings figure:
     * those took typing, and losing them to a `Close` would be the opposite mistake.
     */
    val hasTypedContent: Boolean
        get() = quantity.isNotBlank() ||
            portionThousandths != null ||
            quickTitle.isNotBlank() ||
            quickEnergy.isNotBlank() ||
            quickProtein.isNotBlank() ||
            /*
             * Against the default rather than against blankness. The field now opens holding one
             * serving, so `isNotBlank` would call every untouched recipe line "work in progress"
             * and put the sheet back to reopening on a stage nobody chose — the very complaint
             * ("j'ai plus accès aux 3 menus d'avant") this property was written to end. Moving
             * the stepper *is* typing by this rule's own logic, and moving it back to one is
             * indistinguishable from never having touched it, which is the right answer.
             */
            servings != DEFAULT_SERVINGS ||
            // Thirteen digits off a jar is typing by any measure, and losing them to a `Close`
            // pressed by accident would mean reading the label again. A code the *camera* put
            // there counts the same: what makes it worth keeping is that it is on screen and
            // correct, not how it got there.
            scanBarcode.isNotBlank()

    companion object {

        /**
         * What the servings field opens on: one whole serving.
         *
         * Rendered by [FoodLabels] from `Servings.ONE` rather than written as `"1"`, so the
         * string is the one the rest of the module would print for the same quantity — the
         * comparison in `hasTypedContent` and the stepper's own output both have to match it
         * exactly, and a hand-typed literal would be a second spelling waiting to disagree.
         */
        val DEFAULT_SERVINGS: String = FoodLabels.servings(Servings.ONE)

        /** `HH:mm`, which is what [FoodValidation.validateConsumedAt] reads back. */
        fun format(time: LocalTime): String =
            "%02d:%02d".format(time.hour, time.minute)

        /**
         * A brand new line aimed at one day and one moment (PRD_FOOD 7 and 10.3).
         *
         * [slot] is what the `+` of a moment carries; when nothing carried one, FR-FOOD-007 lets
         * the clock preselect it and [MealSlotRules] is what answers. The default time is that
         * section's as well — now for today, the middle of the moment for a day already gone.
         */
        fun forTarget(
            date: LocalDate?,
            slot: MealSlot?,
            today: LocalDate,
            now: LocalTime,
            /** PRD_FOOD 12: a proposal is being posed rather than a line written. */
            planning: Boolean = false,
        ): FoodAddDraft {
            val day = date ?: today
            val moment = slot ?: MealSlotRules.slotFor(MealSlotRules.normalize(now))
            val time = MealSlotRules.defaultTime(moment, day, today, now)
            return FoodAddDraft(
                planning = planning,
                consumedOn = day.toString(),
                /*
                 * A proposal carries **no hour at all** (PRD_FOOD 8.5), and the clock's moment is
                 * not its moment either. The time is still written so the field round-trips if
                 * this draft is ever read as a journal one, but the moment is left unpinned
                 * unless a slot was carried in: `plannedSlot` below decides it from the dish and
                 * from the day, which is the only honest answer where there is no "now".
                 */
                consumedAt = format(time),
                slotId = moment.id,
                slotPinned = slot != null,
            )
        }

        /**
         * The draft a stored line reopens as (FR-FOOD-008).
         *
         * Everything the line already decided is pinned: its moment, its time, its quantity and
         * the state it was weighed in. Nothing is recomputed here — PRD_FOOD 8.4 froze the
         * values, and a correction changes only what the person changes.
         */
        fun forEntry(entry: FoodLogEntry): FoodAddDraft {
            val amount = entry.amount
            return FoodAddDraft(
                entryId = entry.id.value,
                kindId = entry.kind.id,
                foodId = entry.foodRef?.value,
                /*
                 * FR-FOOD-008: the recipe a stored line came from is **not** put back in the
                 * draft. PRD_FOOD 8.4 froze this line's values and PRD_FOOD 11 makes a recipe
                 * edit non-retroactive, so a correction rescales the snapshot it carries; loading
                 * the recipe here would quietly recompute the line against a preparation that may
                 * have changed since.
                 */
                recipeId = null,
                consumedOn = entry.consumedOn.toString(),
                consumedAt = format(entry.consumedAt),
                slotId = entry.slot.id,
                slotPinned = true,
                timePinned = true,
                quantity = when (amount) {
                    is LoggedAmount.Measured -> FoodLabels.quantity(
                        amount.quantity,
                        amount.referenceUnit,
                    ).substringBefore(' ')

                    else -> ""
                },
                portionThousandths = entry.portions?.thousandths,
                weighedCooked = entry.weighedCooked,
                quickTitle = if (entry.kind == FoodLogKind.QUICK) entry.title else "",
                quickEnergy = entry.nutrients.energy
                    ?.let { FoodLabels.energy(it, approximate = false).substringBefore(' ') }
                    .orEmpty(),
                quickProtein = entry.nutrients.protein
                    ?.let { FoodLabels.macro(it, approximate = false).substringBefore(' ') }
                    .orEmpty(),
                /*
                 * A line that counts no servings — anything but a recipe — falls back to the
                 * default rather than to an empty string, so that a field this stage never shows
                 * cannot make a freshly loaded correction look edited.
                 */
                servings = entry.consumedServings?.let(FoodLabels::servings) ?: DEFAULT_SERVINGS,
            )
        }

        fun toJson(draft: FoodAddDraft): String = format.encodeToString(serializer(), draft)

        /**
         * Total and non-throwing: a draft that cannot be read is a draft that was never there,
         * and the sheet opens on its first stage rather than crashing on restore.
         */
        fun fromJson(raw: String?): FoodAddDraft? {
            if (raw.isNullOrBlank()) return null
            return runCatching { format.decodeFromString(serializer(), raw) }.getOrNull()
        }

        private val format = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

/**
 * What a draft is worth right now: the amount it states, the values that follow from it, and the
 * weight those values were actually computed against.
 *
 * [referenceWeight] is only interesting when it differs from what was typed — a cooked weight
 * brought back to the food's reference state by [NutritionMath.referenceWeightOrNull]. Showing it
 * is the whole point of the raw/cooked selector: 600 g of cooked rice is not 600 g of rice.
 */
internal data class FoodAddAmount(
    val amount: LoggedAmount,
    val nutrients: Nutrients,
    val label: String?,
    val portions: Servings? = null,
    val referenceWeight: Quantity? = null,
    val weighedCooked: Boolean = false,
)

/** Which field a refusal belongs beside (PRD_FOOD 15: "signalée à côté du champ concerné"). */
internal data class FoodAddErrors(
    val quantity: String? = null,
    val portions: String? = null,
    val cookedState: String? = null,
    val title: String? = null,
    val energy: String? = null,
    val protein: String? = null,
    val servings: String? = null,
    val date: String? = null,
    val time: String? = null,
) {
    /** The one shown beside the action, so a reader hears why the save did nothing. */
    val summary: String?
        get() = quantity ?: portions ?: cookedState ?: title ?: energy ?: protein
            ?: servings ?: date ?: time

    companion object {
        val EMPTY: FoodAddErrors = FoodAddErrors()
    }
}

/** Either the line that would be written, or the refusals that stopped it. */
internal sealed interface FoodAddResolution {

    data class Ready(val entry: FoodLogEntry) : FoodAddResolution

    data class Refused(val errors: FoodAddErrors) : FoodAddResolution
}

/**
 * The quantity of a `FOOD` line, and everything that follows from it (PRD_FOOD 13.1 and 15).
 *
 * Two ways in, one stored amount. A count of usual portions is resolved to grams by
 * [NutritionMath.usualServingWeightOrNull] and stored as a [LoggedAmount.Measured] with the count
 * kept beside it — PRD_FOOD 8.6 is explicit that "la quantité reste stockée en grammes" and
 * PRD_FOOD 13.2's `1.5 × apple (225 g)` needs both readings. [LoggedAmount.Portioned] is not that
 * case: it counts *recipe* servings, which PRD_FOOD 8.6 keeps out of the nutritional units
 * altogether.
 *
 * Nothing here divides, multiplies or rounds anything. The cooking conversion is
 * [NutritionMath.referenceWeightOrNull]'s, the per-100 scaling is [NutritionMath.contribution]'s
 * through [NutritionMath.foodContribution], the bounds are [FoodValidation]'s, and the label is
 * [FoodLabels]'s.
 */
internal fun FoodAddDraft.resolveAmount(food: Food): Validated<FoodAddAmount> {
    val counted = portions
    if (counted != null) {
        val weight = NutritionMath.usualServingWeightOrNull(food, counted)
            ?: return Validated.Invalid(FoodValidation.USUAL_PORTIONS_ERROR)
        if (!counted.isUsualCount) return Validated.Invalid(FoodValidation.USUAL_PORTIONS_ERROR)
        val amount = LoggedAmount.Measured(weight, food.referenceUnit)
        return Validated.Valid(
            FoodAddAmount(
                amount = amount,
                /*
                 * A usual portion is an aid to typing and never a cooked reading (PRD_FOOD 8.6),
                 * so no ratio applies and the dedicated domain function is the one asked.
                 */
                nutrients = NutritionMath.usualServingContribution(food, counted),
                label = FoodLabels.amountLabel(amount, food, portions = counted),
                portions = counted,
            ),
        )
    }

    if (quantity.isBlank()) return Validated.Invalid(FoodAddMessages.NO_QUANTITY)

    val weighed = when (val parsed = FoodValidation.validateIngredientQuantity(quantity)) {
        is Validated.Invalid -> return parsed
        is Validated.Valid -> parsed.value
    }
    val amount = LoggedAmount.Measured(weighed, food.referenceUnit)

    val cooked = when (val state = FoodValidation.validateWeighedCooked(amount, weighedCooked, food)) {
        is Validated.Invalid -> return state
        is Validated.Valid -> state.value
    }

    val reference = NutritionMath.referenceWeightOrNull(weighed, food.cookedRatio, cooked)
        ?: return Validated.Invalid(FoodValidation.INGREDIENT_QUANTITY_ERROR)

    return Validated.Valid(
        FoodAddAmount(
            amount = amount,
            nutrients = NutritionMath.foodContribution(food, weighed, cooked),
            label = FoodLabels.amountLabel(amount, food, weighedCooked = cooked),
            referenceWeight = reference.takeIf { cooked },
            weighedCooked = cooked,
        ),
    )
}

/**
 * FR-FOOD-005: what a quick add is worth, or null while its three fields do not parse.
 *
 * PRD_FOOD 15's rule in full is [FoodValidation.validateQuickAdd]'s: a name, an energy from 0 to
 * 5 000 kcal, and an optional protein that stays **null** rather than zero when nobody gave one.
 */
internal fun FoodAddDraft.quickNutrientsOrNull(): Nutrients? =
    (FoodValidation.validateQuickAdd(quickTitle, quickEnergy, quickProtein) as? QuickAddValidation.Valid)
        ?.draft
        ?.nutrients

/**
 * The recipe a **new** line is being built from, with the catalogue its ingredients name.
 *
 * The two travel together for `RecipeDetailViewModel`'s reason: a detail and a catalogue read a
 * frame apart would print `—` over a figure that is known. A food that is simply absent is not an
 * error — PRD_FOOD 21.2 makes it the ordinary way a recipe arrives before the food it references
 * — and its contribution is unknown, which the strict sum of PRD_FOOD 13.1 already propagates.
 *
 * Nothing here adds anything up: [NutritionMath] owns every figure, and this only says which
 * aggregate to ask it about.
 */
internal data class FoodAddRecipe(
    val detail: RecipeDetail,
    val foods: Map<FoodId, Food> = emptyMap(),
) {
    val id: RecipeId get() = detail.id

    val name: String get() = detail.recipe.name

    val baseServings: Int get() = detail.recipe.baseServings

    /** PRD_FOOD 13.1: `valeur par portion = total de la recette / baseServings`. */
    val perServing: Nutrients get() = NutritionMath.perServing(detail, foods)

    /** PRD_FOOD 13.1: `ligne RECIPE = valeur par portion × portions consommées`. */
    fun line(servings: Servings): Nutrients =
        NutritionMath.recipeLine(detail, foods, servings)
}

/**
 * FR-FOOD-004: what a new recipe line is worth, or null while the servings do not parse.
 *
 * The counterpart of [recipeNutrientsOrNull], and deliberately a different function: that one
 * rescales a snapshot a line already carries (PRD_FOOD 8.4), this one computes a snapshot that
 * does not exist yet from the recipe as it is right now.
 */
internal fun FoodAddDraft.newRecipeNutrientsOrNull(recipe: FoodAddRecipe?): Nutrients? {
    val chosen = recipe ?: return null
    val eaten = FoodValidation.validateConsumedServings(servings).valueOrNull ?: return null
    return chosen.line(eaten)
}

/**
 * FR-FOOD-008 on a recipe line: the frozen snapshot rescaled to the servings now stated.
 *
 * PRD_FOOD 8.4 keeps a line's values as they were saved and PRD_FOOD 11 makes a recipe edit
 * non-retroactive, so a correction rescales what the line already carries rather than reopening
 * a recipe that may have changed since. One scaling by the domain's own arithmetic, so the ratio
 * is applied once and rounded once.
 */
internal fun FoodAddDraft.recipeNutrientsOrNull(original: FoodLogEntry?): Nutrients? {
    val before = original?.consumedServings ?: return null
    val eaten = FoodValidation.validateConsumedServings(servings).valueOrNull ?: return null
    return original.nutrients.scaled(eaten.thousandths.toLong(), before.thousandths.toLong())
}

/**
 * The line this draft would write, or the fields that refuse it (PRD_FOOD 15).
 *
 * [food] is the catalogue entry behind a `FOOD` line and [original] the line being corrected;
 * both are null while a new quick add is being written, and both may be null on a correction
 * whose food has since been deleted — PRD_FOOD 17 keeps such a line intact rather than losing it.
 *
 * The values are computed **here and now** and frozen on the line (PRD_FOOD 8.4). Nothing
 * reopens them afterwards.
 */
internal fun FoodAddDraft.resolve(
    food: Food?,
    original: FoodLogEntry?,
    today: LocalDate,
    /** FR-FOOD-004: the recipe a new line is being built from, null on every other path. */
    recipe: FoodAddRecipe? = null,
    id: FoodLogEntryId = FoodLogEntryId.random(),
): FoodAddResolution {
    val day = date(today)
    val dateError = FoodValidation.validateConsumedOn(day, today).errorMessage
    val timeCheck = FoodValidation.validateConsumedAt(consumedAt)
    val timeError = timeCheck.errorMessage

    fun refuse(errors: FoodAddErrors): FoodAddResolution.Refused =
        FoodAddResolution.Refused(errors.copy(date = dateError, time = timeError))

    fun line(
        kind: FoodLogKind,
        title: String,
        amount: LoggedAmount,
        nutrients: Nutrients,
        estimation: Estimation,
        amountLabel: String?,
        sourceRef: String?,
        portions: Servings? = null,
        weighedCooked: Boolean = false,
    ): FoodAddResolution {
        if (dateError != null || timeError != null) return refuse(FoodAddErrors.EMPTY)
        val time = (timeCheck as Validated.Valid).value
        return FoodAddResolution.Ready(
            FoodLogEntry(
                id = original?.id ?: entry ?: id,
                consumedOn = day,
                consumedAt = FoodValidation.normalizeConsumedAt(time),
                slot = slot,
                kind = kind,
                title = title,
                amount = amount,
                nutrients = nutrients,
                estimation = estimation,
                sourceRef = sourceRef,
                amountLabel = amountLabel,
                portions = portions,
                weighedCooked = weighedCooked,
                // PRD_FOOD 12: correcting a confirmed proposal leaves it confirmed.
                fromPlan = original?.fromPlan,
            ),
        )
    }

    return when (kind) {
        FoodLogKind.QUICK -> when (
            val quick = FoodValidation.validateQuickAdd(quickTitle, quickEnergy, quickProtein)
        ) {
            is QuickAddValidation.Invalid -> refuse(
                FoodAddErrors(
                    title = quick.titleError,
                    energy = quick.energyError,
                    protein = quick.proteinError,
                ),
            )

            is QuickAddValidation.Valid -> line(
                kind = FoodLogKind.QUICK,
                title = quick.draft.title,
                amount = LoggedAmount.Unmeasured,
                nutrients = quick.draft.nutrients,
                // PRD_FOOD 8.4: "estimation vaut APPROXIMATE pour un ajout rapide".
                estimation = Estimation.APPROXIMATE,
                amountLabel = FoodLabels.amountLabel(LoggedAmount.Unmeasured),
                sourceRef = null,
            )
        }

        /*
         * FR-FOOD-004 and FR-FOOD-008, in that order, because they are two different lines.
         *
         * A **new** line is computed from the recipe as it stands now: `NutritionMath.recipeLine`
         * over the aggregate and the catalogue behind it. A **correction** rescales the snapshot
         * the stored line already carries — PRD_FOOD 8.4 froze those values and PRD_FOOD 11 makes
         * a recipe edit non-retroactive, so reopening the recipe would silently rewrite history.
         *
         * The correction is tried first, so a line being edited can never be recomputed even if
         * a recipe happens to be loaded. Reaching neither means a recipe line with nothing behind
         * it, which is the state the sheet used to be able to enter and cannot any longer: `Use a
         * recipe` now chooses one before this stage exists.
         */
        FoodLogKind.RECIPE -> {
            val eaten = when (val parsed = FoodValidation.validateConsumedServings(servings)) {
                is Validated.Invalid -> return refuse(FoodAddErrors(servings = parsed.message))
                is Validated.Valid -> parsed.value
            }
            val amount = LoggedAmount.Portioned(eaten)

            when {
                original != null -> {
                    val rescaled = recipeNutrientsOrNull(original) ?: return refuse(
                        FoodAddErrors(servings = FoodValidation.CONSUMED_SERVINGS_ERROR),
                    )
                    line(
                        kind = FoodLogKind.RECIPE,
                        title = original.title,
                        amount = amount,
                        nutrients = rescaled,
                        estimation = original.estimation,
                        amountLabel = FoodLabels.amountLabel(amount),
                        sourceRef = original.sourceRef,
                    )
                }

                recipe != null -> line(
                    kind = FoodLogKind.RECIPE,
                    title = recipe.name,
                    amount = amount,
                    nutrients = recipe.line(eaten),
                    /*
                     * PRD_FOOD 8.4 names two approximate lines: a quick add, and a recipe holding
                     * an approximate ingredient. A `Food` carries no estimation of its own, so a
                     * recipe assembled from catalogue entries is measured — and an ingredient
                     * whose food is missing contributes an *unknown*, which the strict sum of
                     * PRD_FOOD 13.1 propagates as `—` rather than as an approximation.
                     */
                    estimation = Estimation.MEASURED,
                    amountLabel = FoodLabels.amountLabel(amount),
                    sourceRef = recipe.id.value,
                )

                else -> refuse(FoodAddErrors.EMPTY)
            }
        }

        FoodLogKind.FOOD -> {
            if (food == null) {
                /*
                 * PRD_FOOD 17: "aliment supprimé mais journalisé — la ligne reste intacte". Its
                 * values cannot be recomputed against a card that no longer exists, and inventing
                 * them would be worse than keeping them: the moment and the time move, the
                 * numbers do not.
                 */
                val previous = original ?: return refuse(FoodAddErrors.EMPTY)
                return line(
                    kind = FoodLogKind.FOOD,
                    title = previous.title,
                    amount = previous.amount,
                    nutrients = previous.nutrients,
                    estimation = previous.estimation,
                    amountLabel = previous.amountLabel,
                    sourceRef = previous.sourceRef,
                    portions = previous.portions,
                    weighedCooked = previous.weighedCooked,
                )
            }

            when (val resolved = resolveAmount(food)) {
                is Validated.Invalid -> refuse(FoodAddErrors(quantity = resolved.message))
                is Validated.Valid -> {
                    val value = resolved.value
                    line(
                        kind = FoodLogKind.FOOD,
                        title = food.name,
                        amount = value.amount,
                        nutrients = value.nutrients,
                        /*
                         * PRD_FOOD 8.4 names exactly two approximate lines — a quick add, and a
                         * recipe holding an approximate ingredient. A weighed food is neither,
                         * whichever of the two fields the weight came from; the `≈` PRD_FOOD 13.2
                         * puts on every computed figure is a display rule and lives in
                         * `FoodLabels`, not in this flag.
                         */
                        estimation = Estimation.MEASURED,
                        amountLabel = value.label,
                        sourceRef = food.id.value,
                        portions = value.portions,
                        weighedCooked = value.weighedCooked,
                    )
                }
            }
        }
    }
}

/**
 * Which moment this proposal lands on (PRD_FOOD 12).
 *
 * The one function both the screen and the write ask, so the moment printed on the sheet and the
 * moment stored in `(plannedOn, slot)` are the same value by construction rather than by two
 * agreeing derivations.
 *
 * A moment chosen by hand wins outright — [FoodAddDraft.slotPinned] is what the override panel
 * and `Swap` both set. Otherwise [MealSlotRules.plannedSlotFor] answers from the dish's own type
 * and the moments the day already holds, because a day still to come has no clock to ask. Before
 * a recipe has been chosen there is no dish to ask about either, so the draft's own value stands;
 * it is never saved in that state, since PRD_FOOD 8.5 gives a proposal no meaning without one.
 */
internal fun FoodAddDraft.plannedSlot(
    recipe: FoodAddRecipe?,
    taken: Set<MealSlot> = emptySet(),
): MealSlot {
    if (slotPinned || recipe == null) return slot
    return MealSlotRules.plannedSlotFor(recipe.detail.recipe.type, taken)
}

/** Either the proposal that would be posed, or the refusals that stopped it (PRD_FOOD 15). */
internal sealed interface MealPlanResolution {

    data class Ready(val entry: MealPlanEntry) : MealPlanResolution

    data class Refused(val errors: FoodAddErrors) : MealPlanResolution
}

/**
 * The proposal this draft would pose, or the fields that refuse it (PRD_FOOD 12 and 15).
 *
 * Three facts and no more, which is exactly what FR-PLAN-001 asks the manual path to present:
 * a date, a moment and a number of servings — over a recipe, because §8.5 admits nothing else.
 *
 * Every bound is [FoodValidation]'s. The day goes through `validatePlannedOn`, which is the
 * planning rule and **not** the journal's: today or later, up to `MealPlanEntry.MAX_DAYS_AHEAD`.
 * The count goes through `validateConsumedServings`, deliberately the very same counter a
 * consumption uses — PRD_FOOD 15 gives `plannedServings` no range of its own, and inventing a
 * second one would let a proposal be posed that could never be confirmed.
 *
 * Nothing is computed here. A proposal carries no nutritional value at all (PRD_FOOD 12: it
 * "n'entre dans aucun total tant qu'elle n'est pas confirmée"), so there is nothing to freeze —
 * which is the whole difference between this and `FoodAddDraft.resolve`.
 */
internal fun FoodAddDraft.resolvePlan(
    recipe: FoodAddRecipe?,
    slot: MealSlot,
    today: LocalDate,
): MealPlanResolution {
    val day = date(today)
    val dateError = FoodValidation.validatePlannedOn(day, today).errorMessage
    val counted = FoodValidation.validateConsumedServings(servings)

    /*
     * A missing recipe is refused with no message of its own. PRD_FOOD 15 puts a refusal "à côté
     * du champ concerné", and there is no field here to put one beside: the stage that asks for
     * servings is only reached once a recipe has been chosen, so this branch is the unreachable
     * one that must still not write a row.
     */
    if (recipe == null || dateError != null || counted !is Validated.Valid) {
        return MealPlanResolution.Refused(
            FoodAddErrors(servings = counted.errorMessage, date = dateError),
        )
    }

    return MealPlanResolution.Ready(
        MealPlanEntry(
            plannedOn = day,
            slot = slot,
            recipeId = recipe.id,
            plannedServings = counted.value,
        ),
    )
}
