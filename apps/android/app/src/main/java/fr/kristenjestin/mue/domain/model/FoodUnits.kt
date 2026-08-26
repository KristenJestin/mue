package fr.kristenjestin.mue.domain.model

import kotlin.math.roundToLong

/**
 * The ceiling every canonical unit of this file shares.
 *
 * Every quantity in the Food module is a whole **thousandth of its display unit**: a thousandth
 * of a gram, of a millilitre, of a kilocalorie, of a serving, of a ratio. Nothing here is ever a
 * float in memory or at rest, exactly as `Weight` and `ActivityDuration` already are for the two
 * shipped modules; a `Double` appears only in the display-boundary accessors below.
 *
 * The reason is not taste. PRD_FOOD 13.1 makes a total the strict sum of contributions, and a
 * contribution the product of a weight by a per-100 value. Floating point would let two devices
 * disagree on the last digit of the same day, and the `null`-is-never-`0` rule of PRD_FOOD 13.1
 * cannot survive a value that drifts to `0.0000001`.
 *
 * Every product is therefore computed in `Long` and narrowed back through a null-on-overflow
 * guard, the guard `MetricKind.toCanonicalOrNull` already uses. The worst case this module can
 * reach is a maximum ingredient (5 000 g, i.e. 5 000 000 thousandths) against a maximum energy
 * (900 kcal/100 g, i.e. 900 000 milli-kcal): 4.5e12, which an `Int` wraps silently.
 */
private const val CANONICAL_MAX: Long = 2_147_483_647L

/**
 * A mass or a volume, stored as whole thousandths of a gram or of a millilitre (PRD_FOOD 8.6).
 *
 * Which of the two it is never lives here: it is the [ReferenceUnit] or [QuantityUnit] that
 * travels beside it. PRD_FOOD 8.6 forbids any implicit density, so the two never convert into
 * one another and a single scale serves both.
 *
 * The class itself only refuses zero and negatives — a quantity of nothing is not a quantity —
 * and each of PRD_FOOD 15's own bounds is applied where its own kind of quantity is validated,
 * the way `ActivityDuration` separates a manual session length from a timed one. A reference
 * weight derived from a cooked weight legitimately exceeds the ingredient ceiling: 5 000 g of
 * something that lost water at a ratio of 0.3 came from 16 666 g of it.
 */
@JvmInline
value class Quantity private constructor(val thousandths: Int) : Comparable<Quantity> {

    /** Grams or millilitres, per the unit carried beside it. The display boundary, and only it. */
    val amount: Double get() = thousandths / THOUSANDTHS_PER_UNIT.toDouble()

    /** PRD_FOOD 15: strictly above 0 and at most 5 000 g or ml, for a recipe ingredient. */
    val isIngredientAmount: Boolean get() = thousandths in INGREDIENT_RANGE

    /** PRD_FOOD 15: 1 to 2 000 g or ml, for the usual serving an aliment declares. */
    val isUsualServingSize: Boolean get() = thousandths in USUAL_SERVING_RANGE

    /**
     * The reference weight of PRD_FOOD 13.1, for a quantity that was weighed cooked:
     * `poids de référence = poids pesé / cookedRatio`.
     *
     * Counted in `Long` before it is narrowed. The intermediate product by a thousand passes an
     * `Int` on its own well before the division brings it back down.
     *
     * The division rounds half-up, which is what [Nutrients.scaled] applies to every metric. One
     * rounding rule for the whole module is the point: a truncating division here would make
     * 250 g of pasta at 2.3 read `108.695 g` where every nutrient derived from it was computed
     * against `108.696 g`, and the two would disagree about which side of a thousandth they fall
     * on.
     */
    fun toReferenceWeightOrNull(cookedRatio: CookedRatio): Quantity? {
        val numerator = thousandths.toLong() * CookedRatio.THOUSANDTHS_PER_UNIT
        val denominator = cookedRatio.thousandths.toLong()
        return ofThousandthsOrNull((numerator + denominator / 2) / denominator)
    }

    override fun compareTo(other: Quantity): Int = thousandths.compareTo(other.thousandths)

    companion object {
        const val THOUSANDTHS_PER_UNIT: Int = 1_000

        /** PRD_FOOD 15: "strictement supérieure à 0". */
        const val INGREDIENT_MIN_THOUSANDTHS: Int = 1

        /** PRD_FOOD 15: 5 000 g or ml. */
        const val INGREDIENT_MAX_THOUSANDTHS: Int = 5_000_000

        val INGREDIENT_RANGE: IntRange = INGREDIENT_MIN_THOUSANDTHS..INGREDIENT_MAX_THOUSANDTHS

        /** PRD_FOOD 15: a usual serving weighs from 1 to 2 000 g or ml. */
        const val USUAL_SERVING_MIN_THOUSANDTHS: Int = 1_000

        const val USUAL_SERVING_MAX_THOUSANDTHS: Int = 2_000_000

        val USUAL_SERVING_RANGE: IntRange =
            USUAL_SERVING_MIN_THOUSANDTHS..USUAL_SERVING_MAX_THOUSANDTHS

        /**
         * Strictly positive, and null rather than a wrapped `Int` above the canonical ceiling.
         *
         * The argument is a `Long` on purpose: every caller either read a column, parsed a text
         * field or divided a product, and all three can hand over what an `Int` cannot hold.
         */
        fun ofThousandthsOrNull(thousandths: Long): Quantity? =
            if (thousandths in 1L..CANONICAL_MAX) Quantity(thousandths.toInt()) else null

        /** Rounds to the nearest thousandth; the caller's locale parsing is its own business. */
        fun ofAmountOrNull(amount: Double): Quantity? {
            if (!amount.isFinite()) return null
            return ofThousandthsOrNull((amount * THOUSANDTHS_PER_UNIT).roundToLong())
        }

        /** [ofAmountOrNull] judged by PRD_FOOD 15's ingredient bounds. */
        fun ofIngredientOrNull(amount: Double): Quantity? =
            ofAmountOrNull(amount)?.takeIf { it.isIngredientAmount }

        /** [ofAmountOrNull] judged by PRD_FOOD 15's usual-serving bounds. */
        fun ofUsualServingOrNull(amount: Double): Quantity? =
            ofAmountOrNull(amount)?.takeIf { it.isUsualServingSize }
    }
}

/**
 * An energy, stored as whole milli-kilocalories (PRD_FOOD 8.2).
 *
 * A per-100 value is quoted to the kilocalorie and a contribution is displayed rounded to the
 * unit (PRD_FOOD 13.2), but the *stored* value has to be finer than the display: 150 g of an
 * 89 kcal/100 g apple is 133.5 kcal, and a dozen such lines rounded one by one would drift
 * against the same total computed once.
 *
 * Zero is a real energy — water has one — and is never interchangeable with the absence of a
 * value, which is `null` and which nothing in this module may replace with `0`.
 */
@JvmInline
value class Energy private constructor(val milliKcal: Int) : Comparable<Energy> {

    val kilocalories: Double get() = milliKcal / MILLI_PER_KCAL.toDouble()

    /** PRD_FOOD 15: 0 to 900 kcal for 100 g or 100 ml. */
    val isPer100Value: Boolean get() = milliKcal in PER_100_RANGE

    /** PRD_FOOD 15: 0 to 5 000 kcal for a quick add, which quotes a whole plate at once. */
    val isQuickAddValue: Boolean get() = milliKcal in QUICK_ADD_RANGE

    override fun compareTo(other: Energy): Int = milliKcal.compareTo(other.milliKcal)

    companion object {
        const val MILLI_PER_KCAL: Int = 1_000

        const val PER_100_MIN_MILLI_KCAL: Int = 0

        /** 900 kcal: pure fat is 900 kcal per 100 g, so nothing edible goes above it. */
        const val PER_100_MAX_MILLI_KCAL: Int = 900_000

        val PER_100_RANGE: IntRange = PER_100_MIN_MILLI_KCAL..PER_100_MAX_MILLI_KCAL

        const val QUICK_ADD_MIN_MILLI_KCAL: Int = 0

        /** PRD_FOOD 15: "énergie requise de 0 à 5 000 kcal". */
        const val QUICK_ADD_MAX_MILLI_KCAL: Int = 5_000_000

        val QUICK_ADD_RANGE: IntRange = QUICK_ADD_MIN_MILLI_KCAL..QUICK_ADD_MAX_MILLI_KCAL

        /** A known zero. [Nutrients.ZERO] is built on it, and it never stands in for null. */
        val ZERO: Energy = Energy(0)

        /** Zero is allowed, a negative energy is not, and an overflowed total is null. */
        fun ofMilliKcalOrNull(milliKcal: Long): Energy? =
            if (milliKcal in 0L..CANONICAL_MAX) Energy(milliKcal.toInt()) else null

        fun ofKilocaloriesOrNull(kilocalories: Double): Energy? {
            if (!kilocalories.isFinite()) return null
            return ofMilliKcalOrNull((kilocalories * MILLI_PER_KCAL).roundToLong())
        }

        /** [ofKilocaloriesOrNull] judged by PRD_FOOD 15's per-100 bounds. */
        fun ofPer100OrNull(kilocalories: Double): Energy? =
            ofKilocaloriesOrNull(kilocalories)?.takeIf { it.isPer100Value }

        /** [ofKilocaloriesOrNull] judged by PRD_FOOD 15's quick-add bounds. */
        fun ofQuickAddOrNull(kilocalories: Double): Energy? =
            ofKilocaloriesOrNull(kilocalories)?.takeIf { it.isQuickAddValue }
    }
}

/**
 * One macronutrient — protein, carbohydrate, fat or fibre — stored as whole milligrams
 * (PRD_FOOD 8.2).
 *
 * Displayed to the tenth of a gram (PRD_FOOD 13.2) and stored a hundred times finer, for the
 * reason [Energy] is: a per-100 value scaled by a weight lands between two tenths far more often
 * than on one, and rounding each contribution before summing them would show a recipe total that
 * no longer equals the sum of the lines above it.
 */
@JvmInline
value class Macro private constructor(val milligrams: Int) : Comparable<Macro> {

    val grams: Double get() = milligrams / MILLIGRAMS_PER_GRAM.toDouble()

    /** PRD_FOOD 15: 0 to 100 g for 100 g or 100 ml. */
    val isPer100Value: Boolean get() = milligrams in PER_100_RANGE

    override fun compareTo(other: Macro): Int = milligrams.compareTo(other.milligrams)

    companion object {
        const val MILLIGRAMS_PER_GRAM: Int = 1_000

        const val PER_100_MIN_MILLIGRAMS: Int = 0

        /** 100 g of a macronutrient in 100 g of food: the physical ceiling of PRD_FOOD 15. */
        const val PER_100_MAX_MILLIGRAMS: Int = 100_000

        val PER_100_RANGE: IntRange = PER_100_MIN_MILLIGRAMS..PER_100_MAX_MILLIGRAMS

        val ZERO: Macro = Macro(0)

        fun ofMilligramsOrNull(milligrams: Long): Macro? =
            if (milligrams in 0L..CANONICAL_MAX) Macro(milligrams.toInt()) else null

        fun ofGramsOrNull(grams: Double): Macro? {
            if (!grams.isFinite()) return null
            return ofMilligramsOrNull((grams * MILLIGRAMS_PER_GRAM).roundToLong())
        }

        /** [ofGramsOrNull] judged by PRD_FOOD 15's per-100 bounds. */
        fun ofPer100OrNull(grams: Double): Macro? =
            ofGramsOrNull(grams)?.takeIf { it.isPer100Value }
    }
}

/**
 * A count of servings, stored as whole thousandths of a serving (PRD_FOOD 8.6).
 *
 * A serving is not a nutritional unit: it is a fraction of a whole recipe, or a count of the
 * usual portion an aliment declares. Two steps apply to two different fields, and the class
 * carries both the way `ActivityDuration` carries a manual floor and a timed one:
 *
 * - **consumed and planned servings of a recipe**, 0.25 to 10 by 0.25 (PRD_FOOD 15);
 * - **usual portions of an aliment**, 0.5 to 20 by 0.5 (PRD_FOOD 15).
 *
 * The number of servings a recipe is *written for* is not one of these: PRD_FOOD 15 makes it a
 * whole number from 1 to 12, and it lives on [Recipe] as an `Int`.
 */
@JvmInline
value class Servings private constructor(val thousandths: Int) : Comparable<Servings> {

    val count: Double get() = thousandths / THOUSANDTHS_PER_SERVING.toDouble()

    /** In range **and** on the quarter step: PRD_FOOD 15 states both, so both are checked. */
    val isConsumedCount: Boolean
        get() = thousandths in CONSUMED_RANGE && thousandths % CONSUMED_STEP_THOUSANDTHS == 0

    /** In range and on the half step. */
    val isUsualCount: Boolean
        get() = thousandths in USUAL_RANGE && thousandths % USUAL_STEP_THOUSANDTHS == 0

    override fun compareTo(other: Servings): Int = thousandths.compareTo(other.thousandths)

    companion object {
        const val THOUSANDTHS_PER_SERVING: Int = 1_000

        /** PRD_FOOD 15: "Portions consommées : 0,25 à 10, par pas de 0,25". */
        const val CONSUMED_MIN_THOUSANDTHS: Int = 250

        const val CONSUMED_MAX_THOUSANDTHS: Int = 10_000

        const val CONSUMED_STEP_THOUSANDTHS: Int = 250

        val CONSUMED_RANGE: IntRange = CONSUMED_MIN_THOUSANDTHS..CONSUMED_MAX_THOUSANDTHS

        /** PRD_FOOD 15: "Nombre de portions usuelles saisi : 0,5 à 20, par pas de 0,5". */
        const val USUAL_MIN_THOUSANDTHS: Int = 500

        const val USUAL_MAX_THOUSANDTHS: Int = 20_000

        const val USUAL_STEP_THOUSANDTHS: Int = 500

        val USUAL_RANGE: IntRange = USUAL_MIN_THOUSANDTHS..USUAL_MAX_THOUSANDTHS

        /** One whole serving; the neutral element of a per-serving scaling. */
        val ONE: Servings = Servings(THOUSANDTHS_PER_SERVING)

        /** Strictly positive: no serving at all of something is not a line of the journal. */
        fun ofThousandthsOrNull(thousandths: Long): Servings? =
            if (thousandths in 1L..CANONICAL_MAX) Servings(thousandths.toInt()) else null

        fun ofCountOrNull(count: Double): Servings? {
            if (!count.isFinite()) return null
            return ofThousandthsOrNull((count * THOUSANDTHS_PER_SERVING).roundToLong())
        }

        /**
         * Rounds to the nearest quarter serving, then range-checks — the order `Weight` uses.
         *
         * Both bounds are themselves multiples of the step, so rounding first can never push a
         * value that was inside the range back out of it. The step count is a `Long` because a
         * text field will happily offer `1e30`.
         *
         * PRD_FOOD 8.5 plans a meal with the counter it consumes one with, so a planned number
         * of servings is validated here too.
         */
        fun ofConsumedOrNull(count: Double): Servings? =
            onStep(count, CONSUMED_STEP_THOUSANDTHS, CONSUMED_MIN_STEPS, CONSUMED_MAX_STEPS)

        /** The same, on the half step and the wider range of an aliment's usual portion. */
        fun ofUsualOrNull(count: Double): Servings? =
            onStep(count, USUAL_STEP_THOUSANDTHS, USUAL_MIN_STEPS, USUAL_MAX_STEPS)

        private fun onStep(count: Double, step: Int, minSteps: Long, maxSteps: Long): Servings? {
            if (!count.isFinite()) return null
            val steps = (count * THOUSANDTHS_PER_SERVING / step).roundToLong()
            if (steps !in minSteps..maxSteps) return null
            return Servings((steps * step).toInt())
        }

        private val CONSUMED_MIN_STEPS: Long =
            (CONSUMED_MIN_THOUSANDTHS / CONSUMED_STEP_THOUSANDTHS).toLong()
        private val CONSUMED_MAX_STEPS: Long =
            (CONSUMED_MAX_THOUSANDTHS / CONSUMED_STEP_THOUSANDTHS).toLong()
        private val USUAL_MIN_STEPS: Long =
            (USUAL_MIN_THOUSANDTHS / USUAL_STEP_THOUSANDTHS).toLong()
        private val USUAL_MAX_STEPS: Long =
            (USUAL_MAX_THOUSANDTHS / USUAL_STEP_THOUSANDTHS).toLong()
    }
}

/**
 * A cooking ratio, `cooked mass / reference mass`, stored as whole thousandths (PRD_FOOD 8.6).
 *
 * It models the one transformation where only water moves: dry pasta at 2.3 absorbs it, a
 * chicken breast at 0.72 drives it off, and a single number serves both directions because the
 * dry matter is conserved either way. What it deliberately cannot model is anything *added or
 * removed* — pan oil, dripped fat, discarded cooking water — which PRD_FOOD 8.6 logs as a
 * separate line instead.
 *
 * Unlike the other units of this file the class enforces PRD_FOOD 15's bounds itself: outside
 * 0.3 to 5 a number is not a stricter or looser ratio, it is not a ratio at all, and no second
 * set of bounds exists for it anywhere in the module. It is never typed by hand — PRD_FOOD 8.6
 * derives it from the raw/cooked pair Ciqual already contains.
 */
@JvmInline
value class CookedRatio private constructor(val thousandths: Int) : Comparable<CookedRatio> {

    val ratio: Double get() = thousandths / THOUSANDTHS_PER_UNIT.toDouble()

    /** True when cooking adds mass — pasta, rice, pulses — and false when it drives water off. */
    val absorbsWater: Boolean get() = thousandths > THOUSANDTHS_PER_UNIT

    override fun compareTo(other: CookedRatio): Int = thousandths.compareTo(other.thousandths)

    companion object {
        const val THOUSANDTHS_PER_UNIT: Int = 1_000

        /** PRD_FOOD 15: "strictement positif, de 0,3 à 5". */
        const val MIN_THOUSANDTHS: Int = 300

        const val MAX_THOUSANDTHS: Int = 5_000

        val RANGE: IntRange = MIN_THOUSANDTHS..MAX_THOUSANDTHS

        fun ofThousandthsOrNull(thousandths: Long): CookedRatio? =
            if (thousandths in MIN_THOUSANDTHS.toLong()..MAX_THOUSANDTHS.toLong()) {
                CookedRatio(thousandths.toInt())
            } else {
                null
            }

        fun ofRatioOrNull(ratio: Double): CookedRatio? {
            if (!ratio.isFinite()) return null
            return ofThousandthsOrNull((ratio * THOUSANDTHS_PER_UNIT).roundToLong())
        }
    }
}
