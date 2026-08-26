package fr.kristenjestin.mue.data.remote.openfoodfacts

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.repository.LookupFailure
import fr.kristenjestin.mue.domain.repository.ProductLookupResult
import java.util.Locale

/**
 * One Open Food Facts response body turned into a [ProductLookupResult]. Pure, offline, total.
 *
 * ## The rule the rest of this file serves
 *
 * A product card is written by volunteers and a manufacturer, not by Mue, and it is routinely
 * incomplete: no fat on a spread, no fibre on a drink, no protein on a condiment. PRD_FOOD 9.2
 * accepts that as nominal — "les valeurs manquantes restent `null` … et ne sont jamais devinées" —
 * and PRD_FOOD 13.1 forbids the shortcut that would follow: `null` means unknown, `0` means a
 * known zero, and no conversion between the two is allowed anywhere. A single `?: 0` in this file
 * would fabricate a measurement, carry it into a journal line where PRD_FOOD 8.4 freezes it
 * forever, and show it to somebody as a fact. Every read below therefore fails to `null`.
 *
 * PRD_FOOD 17 sharpens the same rule one notch further, and API v3.6 makes it enforceable:
 * missing values are "jamais **estimées**". Open Food Facts states, per nutrient, where its
 * figure came from, and marks the ones it computed out of the ingredient list `estimate`. Those
 * are guesses about the product, so Mue drops them — the flagship Nutella card is exactly this
 * case, with a manufacturer's energy, protein, carbohydrate and fat beside an *estimated* fibre.
 *
 * ## Energy, and the kilojoule
 *
 * European labels quote kilojoules, Mue stores kilocalories, and the two differ by a factor of
 * 4.184. That factor is the one place this mapper computes anything, and it is a unit conversion
 * of a documented number rather than an invention of a missing one — the difference that makes it
 * legitimate where `?: 0` is not. The order is:
 *
 * 1. `energy-kcal`, when its `unit` says kcal;
 * 2. otherwise `energy-kj`, converted;
 * 3. otherwise the generic `energy`, converted, and only because its own `unit` says kilojoules.
 *
 * The unit is **read, never assumed**. The generic `energy` field is quoted in kilojoules even
 * for a card that also carries kilocalories — 2 252 for the Nutella whose label says 539 kcal —
 * so a mapper that took `energy` at face value would multiply every packaged product in the
 * catalogue by 4.184. Hence [kilocaloriesOrNull] returning null for any unit it does not
 * recognise: a number whose unit we cannot read is not a number we know.
 *
 * ## Provenance
 *
 * PRD_FOOD 9.2 and 16.3 want the copy to remember where it came from, so every mapped food
 * carries `source = OPEN_FOOD_FACTS`, the barcode, the source id and a `sourceVersion` naming
 * both the API schema that produced the values and the revision of the card they were read from.
 * A later edit upstream changes none of it (PRD_FOOD 9.2: "une modification ultérieure de la
 * fiche distante ne change rien"), and the recorded revision is what says so.
 */
object OpenFoodFactsMapper {

    /** 1 kcal = 4.184 kJ, the conversion the labelling rules and Open Food Facts both use. */
    const val KILOJOULES_PER_KILOCALORIE: Double = 4.184

    /** The nutrient source PRD_FOOD 17 excludes: a figure derived, not reported. */
    const val ESTIMATED_SOURCE: String = "estimate"

    /** The preparation state a `Food`'s per-100 values describe (PRD_FOOD 8.6). */
    const val AS_SOLD_PREPARATION: String = "as_sold"

    const val PER_100_GRAMS: String = "100g"

    const val PER_100_MILLILITRES: String = "100ml"

    /** Open Food Facts spells it the American way; PRD_FOOD 8.2 calls the same thing fibre. */
    const val FIBRE_KEY: String = "fiber"

    const val PROTEIN_KEY: String = "proteins"

    /**
     * Available carbohydrate, the European convention Ciqual also uses.
     *
     * `carbohydrates-total` exists beside it and means the American gross figure, fibre
     * included. The two are different quantities, so a card that documents only the total leaves
     * [Nutrients.carbs] unknown rather than borrowing a number that means something else.
     */
    const val CARBS_KEY: String = "carbohydrates"

    const val FAT_KEY: String = "fat"

    /** Tried in order; the first that yields a readable energy wins. See the class doc. */
    val ENERGY_KEYS: List<String> = listOf("energy-kcal", "energy-kj", "energy")

    private val GRAM_UNITS: Set<String> = setOf("g", "gram", "grams")

    private val KILOCALORIE_UNITS: Set<String> = setOf("kcal")

    private val KILOJOULE_UNITS: Set<String> = setOf("kj")

    /**
     * Reads one response body for the barcode that was scanned.
     *
     * Never throws and never returns a half-built food: the three outcomes are the three
     * PRD_FOOD 17 gives three different screens to, and every path below reaches one of them.
     *
     * [id] is a parameter so a test can pin it. In production it is a fresh identity, because
     * PRD_FOOD 9.2 makes the result a *candidate* — it becomes a catalogue row only when the
     * person adds it.
     */
    fun read(
        barcode: String,
        body: String,
        id: FoodId = FoodId.random(),
    ): ProductLookupResult {
        val response = OpenFoodFactsResponse.fromJsonOrNull(body)
            ?: return ProductLookupResult.Unavailable(LookupFailure.MALFORMED_RESPONSE)

        val product = response.product
        if (product != null) {
            // A card with no name is a card nothing can be built from, and PRD_FOOD 17 already
            // has a screen for that: the manual creation prefilled with the barcode, which is
            // what `NotFound` routes to. Calling it a failure would tell somebody to try again
            // on a network that is working perfectly.
            val food = toFoodOrNull(product, barcode, id)
            return if (food == null) {
                ProductLookupResult.NotFound
            } else {
                ProductLookupResult.Found(food)
            }
        }

        if (response.result?.id == OpenFoodFactsResponse.RESULT_PRODUCT_NOT_FOUND) {
            return ProductLookupResult.NotFound
        }

        // A stated failure that is not a missing product — a rejected API version, a server that
        // gave up mid-request. Nothing is known about the barcode either way, which is what
        // separates `Unavailable` from `NotFound`.
        if (response.status == OpenFoodFactsResponse.STATUS_FAILURE) {
            return ProductLookupResult.Unavailable(LookupFailure.SERVICE_ERROR)
        }

        // Valid JSON, but not an answer: a success with no card, or an object of the wrong shape.
        return ProductLookupResult.Unavailable(LookupFailure.MALFORMED_RESPONSE)
    }

    /**
     * The catalogue candidate one card describes, or null when it describes nothing usable.
     *
     * The only thing that can make it null is a missing name, which PRD_FOOD 15 requires and
     * PRD_FOOD 9.3 repeats. Everything else that is absent, out of range or unreadable becomes
     * `null` on the food and stays editable in the copy (PRD_FOOD 9.2).
     */
    fun toFoodOrNull(
        product: OpenFoodFactsProduct,
        barcode: String,
        id: FoodId = FoodId.random(),
    ): Food? {
        val name = product.productName?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val set = product.nutrition?.aggregatedSet

        // Both halves of a usual portion or neither: `Food.hasUsualServing` needs a label to put
        // on the button and a weight to turn it into grams, and PRD_FOOD 15 bounds the weight.
        val servingLabel = product.servingSize?.trim()?.takeIf(String::isNotEmpty)
        val servingSize = product.servingQuantity.asFiniteDoubleOrNull()
            ?.let { Quantity.ofUsualServingOrNull(it) }
        val hasUsualServing = servingLabel != null && servingSize != null

        return Food(
            id = id,
            // PRD_FOOD 15 caps a name at 80 characters. A third party's name is truncated rather
            // than refused: refusing would send somebody to type a whole label by hand over a
            // long one, and PRD_FOOD 9.2 makes the copy editable for exactly this.
            name = name.take(Food.MAX_NAME_LENGTH).trimEnd(),
            source = FoodSource.OPEN_FOOD_FACTS,
            referenceUnit = referenceUnitOf(set),
            per100 = nutrientsOf(set),
            brand = primaryBrandOrNull(product.brands),
            // The number that was scanned, so scanning it again finds this row.
            barcode = barcode,
            // The identifier Open Food Facts files the card under, which is not always the number
            // that was scanned: it normalises some codes. PRD_FOOD 9.2 keeps both.
            sourceId = product.code?.trim()?.takeIf(String::isNotEmpty) ?: barcode,
            sourceVersion = sourceVersionOf(product.rev),
            servingLabel = servingLabel.takeIf { hasUsualServing },
            servingSize = servingSize.takeIf { hasUsualServing },
            // PRD_FOOD 14: an Open Food Facts image stays a remote URL with a display cache;
            // nothing is copied into Room and nothing is downloaded here.
            imageRef = product.imageFrontUrl?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    /**
     * PRD_FOOD 16.3's provenance: which remote schema, and which revision of the card.
     *
     * Both halves earn their place. The schema says how the values were shaped — a v3.4 body and
     * a v3.6 body describe the same product with different fields — and the revision says which
     * version of the card was copied, so a later upstream edit is visibly *not* what this row
     * holds. A card with no revision still records the schema, because that much is known.
     */
    fun sourceVersionOf(rev: Long?): String =
        if (rev == null) OpenFoodFactsUrl.API_VERSION else "${OpenFoodFactsUrl.API_VERSION}/$rev"

    /**
     * `Food.brand` is one brand and `brands` is a comma separated list — "Nutella, Ferrero, Yum
     * yum" — whose first entry is the one Open Food Facts treats as primary.
     */
    fun primaryBrandOrNull(brands: String?): String? =
        brands?.substringBefore(',')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(Food.MAX_BRAND_LENGTH)
            ?.trimEnd()

    /**
     * PRD_FOOD 8.6: a drink is quoted per 100 ml and everything else per 100 g.
     *
     * Grams is the fallback because it is the reference unit `Food` itself defaults to, and
     * because a set whose basis cannot be read carries no nutrients anyway — see [nutrientsOf].
     */
    fun referenceUnitOf(set: OpenFoodFactsNutrientSet?): ReferenceUnit =
        if (set?.per?.trim()?.lowercase(Locale.ROOT) == PER_100_MILLILITRES) {
            ReferenceUnit.MILLILITRE
        } else {
            ReferenceUnit.GRAM
        }

    /**
     * The five metrics PRD_FOOD 9.1 keeps, each independently known or unknown.
     *
     * Two whole-set guards come first, and both fail to [Nutrients.UNKNOWN] rather than to a
     * wrong basis:
     *
     * - the set must be quoted **per 100 g or per 100 ml**, since that is what `Food.per100`
     *   means and what every formula in PRD_FOOD 13.1 divides by;
     * - the set must describe the product **as sold**, since a `prepared` set describes a
     *   reconstituted food — a different thing, which PRD_FOOD 8.6 models with a `cookedRatio`
     *   and not by quietly swapping the reference state.
     */
    fun nutrientsOf(set: OpenFoodFactsNutrientSet?): Nutrients {
        if (set == null) return Nutrients.UNKNOWN

        val per = set.per?.trim()?.lowercase(Locale.ROOT)
        if (per != PER_100_GRAMS && per != PER_100_MILLILITRES) return Nutrients.UNKNOWN

        val preparation = set.preparation?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        if (preparation != null && preparation != AS_SOLD_PREPARATION) return Nutrients.UNKNOWN

        return Nutrients(
            energy = ENERGY_KEYS.firstNotNullOfOrNull { key ->
                set.nutrients[key]?.let(::kilocaloriesOrNull)
            },
            protein = macroOrNull(set.nutrients[PROTEIN_KEY]),
            carbs = macroOrNull(set.nutrients[CARBS_KEY]),
            fat = macroOrNull(set.nutrients[FAT_KEY]),
            fibre = macroOrNull(set.nutrients[FIBRE_KEY]),
        )
    }

    /**
     * One macronutrient, or null.
     *
     * Absent, estimated, unreadable, in a unit that is not grams, or outside PRD_FOOD 15's 0 to
     * 100 g — every one of those is the same answer, "not known", and none of them is zero.
     *
     * An absent or empty `unit` is read as grams: PRD_FOOD 15 quotes macronutrients in grams and
     * Open Food Facts normalises the aggregated set to grams for every weight, so the field going
     * missing is a gap in the record rather than a change of scale. A unit that *is* stated and
     * is not grams is refused, because that one really would be a different number.
     */
    fun macroOrNull(nutrient: OpenFoodFactsNutrient?): Macro? {
        if (nutrient == null || nutrient.isEstimated) return null
        val unit = nutrient.unit?.trim()?.lowercase(Locale.ROOT)
        if (!unit.isNullOrEmpty() && unit !in GRAM_UNITS) return null
        val grams = nutrient.value.asFiniteDoubleOrNull() ?: return null
        return Macro.ofPer100OrNull(grams)
    }

    /**
     * One energy in kilocalories, or null.
     *
     * Unlike a macronutrient, the unit is **required**: kcal and kJ differ by 4.184, there is no
     * safe default between them, and the generic `energy` field of a card that also states
     * kilocalories is quoted in kilojoules. An unstated or unrecognised unit is therefore an
     * unknown energy, not a guessed one.
     */
    fun kilocaloriesOrNull(nutrient: OpenFoodFactsNutrient?): Energy? {
        if (nutrient == null || nutrient.isEstimated) return null
        val value = nutrient.value.asFiniteDoubleOrNull() ?: return null
        val unit = nutrient.unit?.trim()?.lowercase(Locale.ROOT) ?: return null
        val kilocalories = when (unit) {
            in KILOCALORIE_UNITS -> value
            in KILOJOULE_UNITS -> value / KILOJOULES_PER_KILOCALORIE
            else -> return null
        }
        return Energy.ofPer100OrNull(kilocalories)
    }

    /** PRD_FOOD 17: a value Open Food Facts derived rather than read is a value Mue does not have. */
    private val OpenFoodFactsNutrient.isEstimated: Boolean
        get() = source?.trim()?.lowercase(Locale.ROOT) == ESTIMATED_SOURCE
}
