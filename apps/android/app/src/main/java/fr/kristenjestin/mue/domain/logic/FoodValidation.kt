package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.CookedRatio
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.Servings
import java.time.LocalDate
import java.time.LocalTime

/** A usual portion of PRD_FOOD 8.6: a label and the weight it stands for, never one without the other. */
data class UsualServing(val label: String, val size: Quantity)

/**
 * The five per-100 fields of a food form, each judged on its own and the three energy-yielding
 * macronutrients judged together (PRD_FOOD 15).
 *
 * Errors are reported field by field so the screen can highlight two at once, exactly as
 * `ProfileValidation` does for the profile form. [Invalid.sumError] is the one rule that belongs
 * to no single field.
 */
sealed interface NutrientsValidation {

    data class Valid(val nutrients: Nutrients) : NutrientsValidation

    data class Invalid(
        val energyError: String? = null,
        val proteinError: String? = null,
        val carbsError: String? = null,
        val fatError: String? = null,
        val fibreError: String? = null,
        val sumError: String? = null,
    ) : NutrientsValidation
}

/** What a quick add is worth once validated (PRD_FOOD 10.2 and 15): a title and its values. */
data class QuickAddDraft(val title: String, val nutrients: Nutrients)

/** The three fields of a quick add, reported independently. */
sealed interface QuickAddValidation {

    data class Valid(val draft: QuickAddDraft) : QuickAddValidation

    data class Invalid(
        val titleError: String? = null,
        val energyError: String? = null,
        val proteinError: String? = null,
    ) : QuickAddValidation
}

/**
 * PRD_FOOD 15's table in full, returning the [Validated] the base app already uses.
 *
 * Three rules govern the whole file.
 *
 * **A blank optional field is valid and means `null`.** PRD_FOOD 13.1 and 15 both forbid turning
 * a missing value into a zero, and PRD_FOOD 15 says so twice — "un champ vide est enregistre
 * `null`, jamais `0`" for the energy, and "aliment sans aucune valeur : accepte" for the card as
 * a whole. No `?: 0` appears in this file, and none may appear anywhere in the Food domain.
 *
 * **Numbers are parsed accepting both `.` and `,`** whatever the phone's language is, which is
 * why nothing here touches `NumberFormat`. Formatting for display is the opposite trip and lives
 * in [FoodLabels].
 *
 * **Bounds are never restated.** Every ceiling and step of PRD_FOOD 15 is already a constant on
 * the value class that owns it — `Quantity.INGREDIENT_RANGE`, `Servings.CONSUMED_STEP_THOUSANDTHS`,
 * `CookedRatio.RANGE` — so this file only decides which factory to call and which sentence to
 * show. The messages are constants rather than resources, as in [MueValidation] and
 * [ActivityValidation]: the app ships in English only and the tests assert them character for
 * character.
 */
object FoodValidation {

    const val NAME_ERROR: String = "Enter a name of 1 to 80 characters"
    const val BRAND_ERROR: String = "A brand is at most 80 characters"
    const val BARCODE_ERROR: String = "Enter a barcode of 8 to 14 digits"
    const val ENERGY_PER_100_ERROR: String = "Energy must be between 0 and 900 kcal per 100"
    const val MACRO_PER_100_ERROR: String = "A macronutrient must be between 0 and 100 g per 100"
    const val MACRO_SUM_ERROR: String = "Protein, carbs and fat cannot add up to more than 100 g"
    const val COOKED_RATIO_ERROR: String = "Cooking ratio must be between 0.3 and 5"
    const val USUAL_SERVING_SIZE_ERROR: String = "A usual serving must be between 1 and 2000 g or ml"
    const val USUAL_SERVING_PAIR_ERROR: String = "A usual serving needs both a label and a weight"
    const val USUAL_PORTIONS_ERROR: String = "Portions must be between 0.5 and 20, in steps of 0.5"
    const val INGREDIENT_QUANTITY_ERROR: String = "Quantity must be above 0 and at most 5000 g or ml"
    const val INGREDIENT_COUNT_ERROR: String = "A recipe needs between 1 and 40 ingredients"
    const val BASE_SERVINGS_ERROR: String = "A recipe must serve a whole number of 1 to 12"
    const val CONSUMED_SERVINGS_ERROR: String = "Servings must be between 0.25 and 10, in steps of 0.25"
    const val QUICK_ADD_ENERGY_ERROR: String = "Energy must be between 0 and 5000 kcal"
    const val QUICK_ADD_PROTEIN_ERROR: String = "Protein must be between 0 and 100 g"
    const val TIME_ERROR: String = "Enter a valid time of day"
    const val CONSUMED_DATE_ERROR: String = "A meal cannot be logged in the future"
    const val PLANNED_DATE_ERROR: String = "A meal can be planned from today up to 60 days ahead"
    const val STEPS_ERROR: String = "A recipe takes at most 30 steps of 500 characters"
    const val PREP_TIME_ERROR: String = "Preparation time must be between 1 and 1440 minutes"

    /** PRD_FOOD FR-FOOD-006: a serving count has no cooked state to read it in. */
    const val COOKED_STATE_UNIT_ERROR: String = "Cooked weighing applies to a weight, not to a serving count"

    /** PRD_FOOD FR-FOOD-006: the selector appears only on a food carrying a `cookedRatio`. */
    const val COOKED_STATE_UNAVAILABLE_ERROR: String = "This food has no cooked state"

    /** PRD_FOOD 15: "1 a 80 caracteres apres nettoyage des espaces", for a food, a recipe or a quick add. */
    fun validateName(raw: String): Validated<String> {
        val trimmed = raw.trim()
        return if (trimmed.length in Food.MIN_NAME_LENGTH..Food.MAX_NAME_LENGTH) {
            Validated.Valid(trimmed)
        } else {
            Validated.Invalid(NAME_ERROR)
        }
    }

    /** PRD_FOOD 15 sets no bound on a brand; the ceiling only guards a mistyped field. */
    fun validateBrand(raw: String?): Validated<String?> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Validated.Valid(null)
        return if (trimmed.length <= Food.MAX_BRAND_LENGTH) {
            Validated.Valid(trimmed)
        } else {
            Validated.Invalid(BRAND_ERROR)
        }
    }

    /**
     * EAN-8 through GTIN-14, digits only (PRD_FOOD 9.2). Blank means no barcode: PRD_FOOD 18
     * keeps the whole module usable without a camera, so a card with no number is ordinary.
     */
    fun validateBarcode(raw: String?): Validated<String?> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Validated.Valid(null)
        val wellFormed = trimmed.length in Food.BARCODE_LENGTH_RANGE && trimmed.all { it.isDigit() }
        return if (wellFormed) Validated.Valid(trimmed) else Validated.Invalid(BARCODE_ERROR)
    }

    /**
     * PRD_FOOD 15: "0 a 900 kcal, ou inconnue. Un champ vide est enregistre `null`, jamais `0`."
     *
     * A typed `0` is a known zero and comes back as [Energy.ZERO]; only a blank field is unknown.
     * The two are not interchangeable anywhere in the module, and PRD_FOOD 13.2 shows them
     * differently.
     */
    fun validateEnergyPer100(raw: String): Validated<Energy?> {
        if (raw.isBlank()) return Validated.Valid(null)
        val kilocalories = parseDecimal(raw) ?: return Validated.Invalid(ENERGY_PER_100_ERROR)
        return Energy.ofPer100OrNull(kilocalories)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(ENERGY_PER_100_ERROR)
    }

    /** PRD_FOOD 15: "0 a 100 g, ou inconnu", for any one of the four macronutrients. */
    fun validateMacroPer100(raw: String): Validated<Macro?> {
        if (raw.isBlank()) return Validated.Valid(null)
        val grams = parseDecimal(raw) ?: return Validated.Invalid(MACRO_PER_100_ERROR)
        return Macro.ofPer100OrNull(grams)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(MACRO_PER_100_ERROR)
    }

    /**
     * PRD_FOOD 15: "la somme des valeurs **connues** parmi proteines, glucides et lipides ne peut
     * depasser 100 g ; les inconnues sont ignorees par ce controle".
     *
     * This is a **form** rule and not a seeding rule: it judges what a person types on the food
     * card, and the catalogue import applies its own reading of a source that may be internally
     * inconsistent. The predicate itself lives on the bundle — [Nutrients.isMacroSumWithinPer100Limit] —
     * so both readings agree on what the sum is.
     */
    fun validateMacroSum(nutrients: Nutrients): Validated<Nutrients> =
        if (nutrients.isMacroSumWithinPer100Limit) {
            Validated.Valid(nutrients)
        } else {
            Validated.Invalid(MACRO_SUM_ERROR)
        }

    /**
     * The five per-100 fields at once, each reported independently (PRD_FOOD 15).
     *
     * All five blank is [NutrientsValidation.Valid] carrying [Nutrients.UNKNOWN]: PRD_FOOD 15
     * accepts "un aliment sans aucune valeur" and PRD_FOOD 9.2 calls an incomplete Open Food
     * Facts card the nominal case, not a mistake to correct.
     *
     * The sum is only checked once every field parses, because a sum over a field that could not
     * be read would report a second error the person cannot act on.
     */
    fun validatePer100(
        energy: String,
        protein: String,
        carbs: String,
        fat: String,
        fibre: String,
    ): NutrientsValidation {
        val energyField = validateEnergyPer100(energy)
        val proteinField = validateMacroPer100(protein)
        val carbsField = validateMacroPer100(carbs)
        val fatField = validateMacroPer100(fat)
        val fibreField = validateMacroPer100(fibre)

        if (energyField !is Validated.Valid ||
            proteinField !is Validated.Valid ||
            carbsField !is Validated.Valid ||
            fatField !is Validated.Valid ||
            fibreField !is Validated.Valid
        ) {
            return NutrientsValidation.Invalid(
                energyError = energyField.errorMessage,
                proteinError = proteinField.errorMessage,
                carbsError = carbsField.errorMessage,
                fatError = fatField.errorMessage,
                fibreError = fibreField.errorMessage,
            )
        }

        val nutrients = Nutrients(
            energy = energyField.value,
            protein = proteinField.value,
            carbs = carbsField.value,
            fat = fatField.value,
            fibre = fibreField.value,
        )
        return when (val sum = validateMacroSum(nutrients)) {
            is Validated.Valid -> NutrientsValidation.Valid(sum.value)
            is Validated.Invalid -> NutrientsValidation.Invalid(sumError = sum.message)
        }
    }

    /**
     * PRD_FOOD 15: "strictement positif, de 0,3 a 5", and absent on most foods.
     *
     * PRD_FOOD 8.6 says the ratio "n'est jamais saisi a la main" — it is derived from the
     * raw/cooked pair Ciqual already carries — so this exists for the import and for a personal
     * food that legitimately declares one, never as a field on the ordinary path.
     */
    fun validateCookedRatio(raw: String): Validated<CookedRatio?> {
        if (raw.isBlank()) return Validated.Valid(null)
        val ratio = parseDecimal(raw) ?: return Validated.Invalid(COOKED_RATIO_ERROR)
        return CookedRatio.ofRatioOrNull(ratio)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(COOKED_RATIO_ERROR)
    }

    /** PRD_FOOD 15: "Portion usuelle : 1 a 2 000 g ou ml", and blank when the food declares none. */
    fun validateUsualServingSize(raw: String): Validated<Quantity?> {
        if (raw.isBlank()) return Validated.Valid(null)
        val amount = parseDecimal(raw) ?: return Validated.Invalid(USUAL_SERVING_SIZE_ERROR)
        return Quantity.ofUsualServingOrNull(amount)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(USUAL_SERVING_SIZE_ERROR)
    }

    /**
     * PRD_FOOD FR-FOOD-006: the portion counter is offered only when both halves exist.
     *
     * A label with no weight cannot be turned into grams and a weight with no label has nothing
     * to put on the button, so the pair is validated together and both blank is a valid "no usual
     * portion".
     */
    fun validateUsualServing(label: String?, size: String): Validated<UsualServing?> {
        val trimmedLabel = label?.trim().orEmpty()
        if (trimmedLabel.isEmpty() && size.isBlank()) return Validated.Valid(null)
        if (trimmedLabel.isEmpty() || size.isBlank()) {
            return Validated.Invalid(USUAL_SERVING_PAIR_ERROR)
        }
        if (trimmedLabel.length > Food.MAX_NAME_LENGTH) return Validated.Invalid(NAME_ERROR)
        return when (val parsed = validateUsualServingSize(size)) {
            is Validated.Invalid -> parsed
            is Validated.Valid -> parsed.value
                ?.let { Validated.Valid(UsualServing(trimmedLabel, it)) }
                ?: Validated.Invalid(USUAL_SERVING_SIZE_ERROR)
        }
    }

    /** PRD_FOOD 15: "Nombre de portions usuelles saisi : 0,5 a 20, par pas de 0,5". */
    fun validateUsualPortions(raw: String): Validated<Servings> {
        val count = parseDecimal(raw) ?: return Validated.Invalid(USUAL_PORTIONS_ERROR)
        return Servings.ofUsualOrNull(count)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(USUAL_PORTIONS_ERROR)
    }

    /** PRD_FOOD 15: "Quantite d'un ingredient : strictement superieure a 0, maximum 5 000 g ou ml". */
    fun validateIngredientQuantity(raw: String): Validated<Quantity> {
        val amount = parseDecimal(raw) ?: return Validated.Invalid(INGREDIENT_QUANTITY_ERROR)
        return Quantity.ofIngredientOrNull(amount)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(INGREDIENT_QUANTITY_ERROR)
    }

    /** PRD_FOOD 15: "Ingredients d'une recette : 1 a 40. Une recette sans ingredient ne peut pas etre enregistree." */
    fun validateIngredientCount(count: Int): Validated<Int> =
        if (count in Recipe.MIN_INGREDIENTS..Recipe.MAX_INGREDIENTS) {
            Validated.Valid(count)
        } else {
            Validated.Invalid(INGREDIENT_COUNT_ERROR)
        }

    /** The same rule applied to the rows themselves, which is how a recipe form holds them. */
    fun validateIngredients(
        ingredients: List<RecipeIngredient>,
    ): Validated<List<RecipeIngredient>> = validateIngredientCount(ingredients.size)
        .map { ingredients }

    /** PRD_FOOD 15: "Portions d'une recette : entier de 1 a 12". */
    fun validateBaseServings(value: Int): Validated<Int> =
        if (value in Recipe.BASE_SERVINGS_RANGE) {
            Validated.Valid(value)
        } else {
            Validated.Invalid(BASE_SERVINGS_ERROR)
        }

    /** A whole number and nothing else: `2.5` servings of a recipe is not a recipe. */
    fun validateBaseServings(raw: String): Validated<Int> {
        val value = parseInteger(raw) ?: return Validated.Invalid(BASE_SERVINGS_ERROR)
        return validateBaseServings(value)
    }

    /** PRD_FOOD 15: "Portions consommees : 0,25 a 10, par pas de 0,25", planned ones included. */
    fun validateConsumedServings(raw: String): Validated<Servings> {
        val count = parseDecimal(raw) ?: return Validated.Invalid(CONSUMED_SERVINGS_ERROR)
        return validateConsumedServings(count)
    }

    fun validateConsumedServings(count: Double): Validated<Servings> =
        Servings.ofConsumedOrNull(count)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(CONSUMED_SERVINGS_ERROR)

    /** PRD_FOOD 15: a quick add's energy is **required**, from 0 to 5 000 kcal. */
    fun validateQuickAddEnergy(raw: String): Validated<Energy> {
        val kilocalories = parseDecimal(raw) ?: return Validated.Invalid(QUICK_ADD_ENERGY_ERROR)
        return Energy.ofQuickAddOrNull(kilocalories)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(QUICK_ADD_ENERGY_ERROR)
    }

    /**
     * PRD_FOOD 15: a quick add's protein is optional, and PRD_FOOD 13.1 stores it `null` when it
     * is not given — never `Macro.ZERO`, which would claim a plate contains no protein at all.
     *
     * PRD_FOOD 15 declares no ceiling for it, so the only macronutrient bound the module knows —
     * 0 to 100 g — is applied. It sits far above any plate: 100 g of protein is more than a
     * 5 000 kcal meal can plausibly carry.
     */
    fun validateQuickAddProtein(raw: String): Validated<Macro?> {
        if (raw.isBlank()) return Validated.Valid(null)
        val grams = parseDecimal(raw) ?: return Validated.Invalid(QUICK_ADD_PROTEIN_ERROR)
        return Macro.ofGramsOrNull(grams)
            ?.takeIf { it.isPer100Value }
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(QUICK_ADD_PROTEIN_ERROR)
    }

    /**
     * PRD_FOOD 15: "Ajout rapide : nom requis, energie requise de 0 a 5 000 kcal, proteines
     * facultatives."
     *
     * The three metrics a quick add never states — carbs, fat and fibre — stay `null` rather than
     * zero, for the same reason a blank protein field does: nobody claimed they were absent.
     */
    fun validateQuickAdd(title: String, energy: String, protein: String): QuickAddValidation {
        val titleField = validateName(title)
        val energyField = validateQuickAddEnergy(energy)
        val proteinField = validateQuickAddProtein(protein)

        if (titleField !is Validated.Valid ||
            energyField !is Validated.Valid ||
            proteinField !is Validated.Valid
        ) {
            return QuickAddValidation.Invalid(
                titleError = titleField.errorMessage,
                energyError = energyField.errorMessage,
                proteinError = proteinField.errorMessage,
            )
        }

        return QuickAddValidation.Valid(
            QuickAddDraft(
                title = titleField.value,
                nutrients = Nutrients(
                    energy = energyField.value,
                    protein = proteinField.value,
                ),
            ),
        )
    }

    /** PRD_FOOD 15: "Heure de consommation : heure locale valide", typed as `HH:MM`. */
    fun validateConsumedAt(raw: String): Validated<LocalTime> {
        val parts = raw.trim().split(':')
        if (parts.size != 2) return Validated.Invalid(TIME_ERROR)
        val hour = parts[0].trim().toIntOrNull() ?: return Validated.Invalid(TIME_ERROR)
        val minute = parts[1].trim().toIntOrNull() ?: return Validated.Invalid(TIME_ERROR)
        if (hour !in HOUR_RANGE || minute !in MINUTE_RANGE) return Validated.Invalid(TIME_ERROR)
        return Validated.Valid(LocalTime.of(hour, minute))
    }

    /** The picker's own value, brought to the minute the storage keeps (PRD_FOOD 8.4). */
    fun normalizeConsumedAt(time: LocalTime): LocalTime = MealSlotRules.normalize(time)

    /** PRD_FOOD 15: "aujourd'hui ou dans le passe, jamais dans le futur". */
    fun validateConsumedOn(date: LocalDate, today: LocalDate): Validated<LocalDate> =
        if (FoodLogEntry.isLoggableOn(date, today)) {
            Validated.Valid(date)
        } else {
            Validated.Invalid(CONSUMED_DATE_ERROR)
        }

    /** PRD_FOOD 15: "aujourd'hui ou dans le futur, dans les 60 jours" — the mirror image. */
    fun validatePlannedOn(date: LocalDate, today: LocalDate): Validated<LocalDate> =
        if (MealPlanEntry.isPlannableOn(date, today)) {
            Validated.Valid(date)
        } else {
            Validated.Invalid(PLANNED_DATE_ERROR)
        }

    /**
     * PRD_FOOD 15: "Etapes d'une recette : 0 a 30 lignes, 500 caracteres par ligne", typed one
     * per line. Blank lines are dropped rather than counted: they are how a person spaces a
     * textarea, not steps of the dish.
     */
    fun validateSteps(raw: String): Validated<List<String>> = validateSteps(raw.lines())

    fun validateSteps(steps: List<String>): Validated<List<String>> {
        val cleaned = steps.map { it.trim() }.filter { it.isNotEmpty() }
        val tooMany = cleaned.size > Recipe.MAX_STEPS
        val tooLong = cleaned.any { it.length > Recipe.MAX_STEP_LENGTH }
        return if (tooMany || tooLong) Validated.Invalid(STEPS_ERROR) else Validated.Valid(cleaned)
    }

    /** PRD_FOOD 15 bounds no preparation time; the ceiling stops a mistyped one at a full day. */
    fun validatePrepTime(raw: String): Validated<Int?> {
        if (raw.isBlank()) return Validated.Valid(null)
        val minutes = parseInteger(raw) ?: return Validated.Invalid(PREP_TIME_ERROR)
        return if (minutes in Recipe.PREP_TIME_MINUTES_RANGE) {
            Validated.Valid(minutes)
        } else {
            Validated.Invalid(PREP_TIME_ERROR)
        }
    }

    /** Never blocks a save, like the notes of the shipped modules; a blank description is none. */
    fun normalizeDescription(raw: String?): String? = raw
        ?.trim()
        ?.take(Recipe.MAX_DESCRIPTION_LENGTH)
        ?.takeIf { it.isNotEmpty() }

    /**
     * PRD_FOOD 8.6 and FR-FOOD-006: a cooked reading belongs to a weight and to nothing else.
     *
     * A recipe line counts servings and a quick add counts nothing at all; neither has a mass
     * that water could have left or entered, so `weighedCooked` on one of them is meaningless
     * rather than merely unused. And the selector never appears on a food without a
     * `cookedRatio`, so claiming a cooked reading of one is refused as well when the food is
     * known — [NutritionMath.referenceWeightOrNull] still reads such a stored row as an identity,
     * because a leftover flag on a saved line must never change the values it froze.
     */
    fun validateWeighedCooked(
        amount: LoggedAmount,
        weighedCooked: Boolean,
        food: Food? = null,
    ): Validated<Boolean> {
        if (!weighedCooked) return Validated.Valid(false)
        if (amount !is LoggedAmount.Measured) return Validated.Invalid(COOKED_STATE_UNIT_ERROR)
        if (food != null && !food.hasCookedState) {
            return Validated.Invalid(COOKED_STATE_UNAVAILABLE_ERROR)
        }
        return Validated.Valid(true)
    }

    private val HOUR_RANGE: IntRange = 0..23

    private val MINUTE_RANGE: IntRange = 0..59

    /**
     * The one place a hand-typed decimal is read in the Food module. Both separators are accepted
     * whatever the phone's language is, and a half-typed `7,` is worth `7` so a draft survives.
     *
     * It is private, and deliberately not shared with [ActivityValidation]'s twin: extracting a
     * common parser would mean reopening a shipped file for no behavioural gain, and the Food
     * forms speak to this module's validators rather than to a parser of their own.
     */
    private fun parseDecimal(raw: String): Double? {
        val normalized = raw.trim().replace(',', '.')
        if (normalized.isEmpty()) return null
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun parseInteger(raw: String): Int? =
        raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
}
