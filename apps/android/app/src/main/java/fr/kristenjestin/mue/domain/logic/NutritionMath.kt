package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.CookedRatio
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.Servings

/**
 * The whole of PRD_FOOD 13.1, and nothing else:
 *
 * ```text
 * poids de reference        = poids pese / cookedRatio si pese cuit, sinon poids pese
 * contribution d'un aliment = poids de reference x valeurPour100 / 100
 * total d'une recette       = somme stricte des contributions de ses ingredients
 * valeur par portion        = total de la recette / baseServings
 * ligne FOOD                = contribution de l'aliment
 * ligne RECIPE              = valeur par portion x portions consommees
 * ligne QUICK               = valeurs saisies
 * total d'un moment         = somme stricte de ses lignes
 * ```
 *
 * Two properties hold of every function here, and the tests assert both rather than trust them.
 *
 * **Nothing throws.** Every operation is total: a missing food, an unrepresentable product, a
 * recipe written for zero servings — each comes back [Nutrients.UNKNOWN] rather than raising.
 * A day screen recomputes on every keystroke, and an exception there is a crash on the main
 * screen of the module.
 *
 * **Nothing turns an unknown into a zero.** `null` is unknown and `0` is a measured zero
 * (PRD_FOOD 13.1); the strict propagation lives in [Nutrients.plus], [Nutrients.scaled] and
 * [Nutrients.strictSum], and this file only ever chooses *which* pair of numbers to scale by.
 * No `?: 0` appears anywhere in the Food domain, and that is a review gate rather than a style
 * preference.
 */
object NutritionMath {

    /**
     * PRD_FOOD 13.1, line one: `poids de reference = poids pese / cookedRatio si pese cuit`.
     *
     * The conversion is applied **once**, **before** the per-100 contribution, and **only to the
     * quantity** — never to a nutritional value. A food's per-100 figures describe its reference
     * state (PRD_FOOD 8.2, the one `rawLabel` names), so bringing the weight back to that state
     * is the entire correction; applying it to the nutrients as well would apply it twice.
     *
     * Three cases collapse to the identity, and all three are real:
     *
     * - the quantity was weighed in the reference state, which is the ordinary case;
     * - the food carries no ratio at all, so no cooked state exists to convert from — a stored
     *   `weighedCooked` flag on such a line is a leftover, not a reason to invent a divisor;
     * - the ratio is exactly `1.000`, a food whose mass cooking does not change.
     *
     * Returns null only when the quotient cannot be represented, which needs a weighed quantity
     * of some 644 kg — beyond every bound PRD_FOOD 15 sets. Null rather than a fallback: an
     * unrepresentable weight is not known, and [foodContribution] turns it into unknown values
     * rather than into a wrong number.
     *
     * The division itself lives on [Quantity.toReferenceWeightOrNull], which rounds half-up like
     * [Nutrients.scaled] does to every metric. This function owns only the three identity cases
     * above; one rounding rule for the module is what keeps a weight and the nutrients derived
     * from it on the same side of a thousandth.
     */
    fun referenceWeightOrNull(
        weighed: Quantity,
        cookedRatio: CookedRatio?,
        weighedCooked: Boolean,
    ): Quantity? {
        if (!weighedCooked || cookedRatio == null) return weighed
        return weighed.toReferenceWeightOrNull(cookedRatio)
    }

    /**
     * The inverse of [referenceWeightOrNull]: what a reference weight reads on the scale once
     * cooked.
     *
     * PRD_FOOD 8.6 derives a ratio from a raw/cooked pair, so both directions of the same
     * division are legitimate readings of one number. A round trip through the two is accurate
     * to the thousandth of a gram, which is the resolution the whole module stores at.
     */
    fun cookedWeightOrNull(reference: Quantity, cookedRatio: CookedRatio?): Quantity? {
        if (cookedRatio == null) return reference
        return Quantity.ofThousandthsOrNull(
            roundedDiv(
                reference.thousandths.toLong() * cookedRatio.thousandths,
                CookedRatio.THOUSANDTHS_PER_UNIT.toLong(),
            ),
        )
    }

    /**
     * PRD_FOOD 13.1, line two: `contribution = poids de reference x valeurPour100 / 100`.
     *
     * [referenceWeight] is already in the reference state; the cooking correction is not applied
     * here and must not be applied twice.
     */
    fun contribution(per100: Nutrients, referenceWeight: Quantity): Nutrients =
        per100.scaled(referenceWeight.thousandths.toLong(), Nutrients.PER_100_THOUSANDTHS)

    /**
     * A `FOOD` line of PRD_FOOD 10.2: the two formulas above applied in order, for a quantity
     * read on a scale.
     *
     * [weighedCooked] is the flag `FoodLogEntry` stores, and it is honoured only when the food
     * actually declares a ratio — PRD_FOOD FR-FOOD-006 offers the selector nowhere else.
     */
    fun foodContribution(
        food: Food,
        weighed: Quantity,
        weighedCooked: Boolean = false,
    ): Nutrients {
        val reference = referenceWeightOrNull(weighed, food.cookedRatio, weighedCooked)
            ?: return Nutrients.UNKNOWN
        return contribution(food.per100, reference)
    }

    /**
     * PRD_FOOD 8.6: how much a count of usual portions weighs — `1.5 x apple` at `150 g` each.
     *
     * Null when the food declares no portion size, because there is then nothing to multiply and
     * no weight to guess at.
     */
    fun usualServingWeightOrNull(food: Food, portions: Servings): Quantity? {
        val size = food.servingSize ?: return null
        return Quantity.ofThousandthsOrNull(
            roundedDiv(
                size.thousandths.toLong() * portions.thousandths,
                Servings.THOUSANDTHS_PER_SERVING.toLong(),
            ),
        )
    }

    /**
     * The contribution of a `FOOD` line entered through the portion counter (PRD_FOOD
     * FR-FOOD-006).
     *
     * A usual portion is an aid to typing, never a cooked reading: PRD_FOOD 8.6 resolves it to
     * grams of the food as the catalogue describes it, so no ratio applies.
     */
    fun usualServingContribution(food: Food, portions: Servings): Nutrients {
        val weight = usualServingWeightOrNull(food, portions) ?: return Nutrients.UNKNOWN
        return contribution(food.per100, weight)
    }

    /**
     * One ingredient row of PRD_FOOD 8.3, whose quantity is stated for the **whole recipe** and
     * in the food's reference state.
     *
     * A null [food] is not an error to reject: PRD_FOOD 21.2 lets a recipe arrive from the server
     * referencing a food this device has not received yet, and the ingredient still has to appear
     * by its name-and-quantity snapshot. Its contribution is simply unknown.
     */
    fun ingredientContribution(ingredient: RecipeIngredient, food: Food?): Nutrients =
        if (food == null) Nutrients.UNKNOWN else contribution(food.per100, ingredient.quantity)

    /** PRD_FOOD 13.1: `total d'une recette = somme stricte des contributions de ses ingredients`. */
    fun recipeTotal(contributions: Iterable<Nutrients>): Nutrients =
        Nutrients.strictSum(contributions)

    /**
     * The same total, resolved against a catalogue.
     *
     * A recipe with no ingredient sums to [Nutrients.ZERO], the known zero of an empty strict
     * sum. That is not a hole: PRD_FOOD 15 refuses to save such a recipe in the first place, and
     * [FoodValidation.validateIngredientCount] is where it is refused.
     */
    fun recipeTotal(detail: RecipeDetail, foods: Map<FoodId, Food>): Nutrients =
        recipeTotal(detail.ingredients.map { ingredientContribution(it, foods[it.foodId]) })

    /**
     * PRD_FOOD 13.1: `valeur par portion = total de la recette / baseServings`.
     *
     * A non-positive [baseServings] cannot pass PRD_FOOD 15, and yields unknown values rather
     * than a division by zero if a malformed row ever reaches this far.
     */
    fun perServing(recipeTotal: Nutrients, baseServings: Int): Nutrients =
        recipeTotal.scaled(1L, baseServings.toLong())

    /** [perServing] over a whole aggregate, which is the `Per serving` block of PRD_FOOD 11. */
    fun perServing(detail: RecipeDetail, foods: Map<FoodId, Food>): Nutrients =
        perServing(recipeTotal(detail, foods), detail.recipe.baseServings)

    /** PRD_FOOD 13.1: `ligne RECIPE = valeur par portion x portions consommees`. */
    fun recipeLine(perServing: Nutrients, servings: Servings): Nutrients =
        perServing.scaled(
            servings.thousandths.toLong(),
            Servings.THOUSANDTHS_PER_SERVING.toLong(),
        )

    /** A `RECIPE` line straight from the aggregate, the form the journal freezes it in. */
    fun recipeLine(
        detail: RecipeDetail,
        foods: Map<FoodId, Food>,
        servings: Servings,
    ): Nutrients = recipeLine(perServing(detail, foods), servings)

    /**
     * PRD_FOOD FR-RECIPE-004: varying the servings on a recipe card rescales its ingredients.
     *
     * A display-only rescaling — the stored quantity stays the one written for [baseServings] —
     * and null when the rescaled amount cannot be represented or the recipe serves nobody.
     */
    fun scaledIngredientQuantityOrNull(
        quantity: Quantity,
        baseServings: Int,
        servings: Servings,
    ): Quantity? {
        if (baseServings <= 0) return null
        return Quantity.ofThousandthsOrNull(
            roundedDiv(
                quantity.thousandths.toLong() * servings.thousandths,
                baseServings.toLong() * Servings.THOUSANDTHS_PER_SERVING,
            ),
        )
    }

    /**
     * PRD_FOOD 13.1: `total d'un moment = somme stricte de ses lignes`, and the day total
     * PRD_FOOD 10.5 charts is the same addition over a wider set.
     *
     * Frozen values only: a line carries the snapshot it was saved with (PRD_FOOD 8.4), and
     * nothing here reopens the food or the recipe it came from.
     */
    fun total(entries: Iterable<FoodLogEntry>): Nutrients =
        Nutrients.strictSum(entries.map { it.nutrients })

    /**
     * PRD_FOOD 8.4: a recipe is [Estimation.APPROXIMATE] as soon as one of its ingredients is.
     *
     * An empty list is [Estimation.MEASURED] for the reason an empty strict sum is a known zero:
     * nothing approximate took part.
     */
    fun estimationOf(estimations: Iterable<Estimation>): Estimation =
        if (estimations.any { it == Estimation.APPROXIMATE }) {
            Estimation.APPROXIMATE
        } else {
            Estimation.MEASURED
        }

    /**
     * `numerator / denominator` rounded half-up, for non-negative operands only.
     *
     * The same rounding [Nutrients.scaled] applies to every metric, so a weight and the values
     * derived from it never disagree about which side of a thousandth they fall on.
     */
    private fun roundedDiv(numerator: Long, denominator: Long): Long =
        (numerator + denominator / 2) / denominator
}
