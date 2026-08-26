package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.QuantityUnit
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import java.util.Locale

/**
 * How a nutritional value and a quantity read on screen (PRD_FOOD 13.2).
 *
 * The one rule this file exists to enforce is the visible half of PRD_FOOD 13.1: **an unknown
 * value is `—`, never `0`**. A known zero and an unknown are different facts about the world —
 * black coffee really has no energy, an incomplete Open Food Facts card simply does not say — and
 * they must be distinguishable at a glance. [UNKNOWN] and `"≈ 0 kcal"` are the two renderings,
 * and no code path can produce one from the other because nothing here ever substitutes a
 * fallback for a null.
 *
 * Every number is assembled from its canonical integer, digit by digit, with no
 * [java.text.NumberFormat] and no default [Locale] in sight, exactly as `CsvExport` does. A
 * decimal separator that follows the phone's region would make the same total read `133.5` on one
 * device and `133,5` on another, and PRD_FOOD 13.2 asks for `tabular-nums`, not for a locale.
 *
 * Rounding is half-up and matches PRD_FOOD 13.2 exactly: the kilocalorie for an energy, the tenth
 * of a gram for a macronutrient. Values are stored a thousand times finer than that (PRD_FOOD
 * 8.6) so a total rounded once never disagrees with the lines above it.
 */
object FoodLabels {

    /** PRD_FOOD 13.2: "Une valeur inconnue est affichee `—`, jamais `0`." */
    const val UNKNOWN: String = "—"

    /** PRD_FOOD 13.2 and 22: "toute valeur calculee est affichee avec `≈`". */
    const val APPROXIMATE_PREFIX: String = "≈ "

    /** The multiplication sign of PRD_FOOD 13.2's `1.5 × apple (225 g)`. */
    const val TIMES: String = "×"

    const val ENERGY_UNIT: String = "kcal"

    const val MACRO_UNIT: String = "g"

    /** PRD_FOOD 13.2: rounded to the unit. */
    const val ENERGY_DECIMALS: Int = 0

    /** PRD_FOOD 13.2: rounded to the tenth of a gram, and the tenth is shown even when it is 0. */
    const val MACRO_DECIMALS: Int = 1

    /**
     * An energy, or [UNKNOWN] when it is not known.
     *
     * [approximate] defaults to true because almost every energy Food shows came out of a
     * calculation or out of an external table, which PRD_FOOD 13.2 marks `≈` in both cases. A
     * caller that is displaying a figure a person typed unchanged passes false.
     */
    fun energy(value: Energy?, approximate: Boolean = true): String {
        if (value == null) return UNKNOWN
        val digits = decimal(value.milliKcal, Energy.MILLI_PER_KCAL, ENERGY_DECIMALS, trimZeros = false)
        return prefixed("$digits $ENERGY_UNIT", approximate)
    }

    /** A macronutrient to the tenth of a gram, or [UNKNOWN]. */
    fun macro(value: Macro?, approximate: Boolean = true): String {
        if (value == null) return UNKNOWN
        val digits = decimal(
            value.milligrams,
            Macro.MILLIGRAMS_PER_GRAM,
            MACRO_DECIMALS,
            trimZeros = false,
        )
        return prefixed("$digits $MACRO_UNIT", approximate)
    }

    /**
     * A weight or a volume with its unit — `150 g`, `208.333 g`, `225 ml`.
     *
     * A quantity is a measurement rather than a computed nutritional value, so it carries no `≈`.
     * Trailing zeros are dropped: `225.000 g` is noise, and the thousandth only shows up when a
     * cooking conversion actually produced one.
     */
    fun quantity(value: Quantity?, unit: ReferenceUnit): String {
        if (value == null) return UNKNOWN
        val digits = decimal(
            value.thousandths,
            Quantity.THOUSANDTHS_PER_UNIT,
            QUANTITY_DECIMALS,
            trimZeros = true,
        )
        return "$digits ${unit.symbol}"
    }

    /** A count of servings or of usual portions — `1`, `1.5`, `0.25` — with no unit of its own. */
    fun servings(value: Servings?): String {
        if (value == null) return UNKNOWN
        return decimal(
            value.thousandths,
            Servings.THOUSANDTHS_PER_SERVING,
            SERVINGS_DECIMALS,
            trimZeros = true,
        )
    }

    /**
     * PRD_FOOD 13.2: "le libelle d'une quantite conserve les deux lectures quand elles existent :
     * `1.5 × apple (225 g)`, `150 g cooked`."
     *
     * The two readings are supplied, never guessed. PRD_FOOD 22 requires that typing an exact
     * weight drop the portion reading — "le libelle n'en garde qu'une" — so a caller that has
     * left the counter passes a null [portions] and gets `225 g` alone.
     *
     * A quick add has no quantity at all (PRD_FOOD 10.2) and gets null rather than an empty
     * string, so a screen shows no quantity row instead of a blank one.
     */
    fun amountLabel(
        amount: LoggedAmount,
        food: Food? = null,
        portions: Servings? = null,
        weighedCooked: Boolean = false,
    ): String? = when (amount) {
        is LoggedAmount.Unmeasured -> null
        is LoggedAmount.Portioned ->
            "${servings(amount.servings)} $TIMES ${QuantityUnit.SERVING.symbol}"
        is LoggedAmount.Measured -> {
            val weight = quantity(amount.quantity, amount.referenceUnit)
            val servingLabel = food?.servingLabel
            val both = if (portions != null && servingLabel != null) {
                "${servings(portions)} $TIMES $servingLabel ($weight)"
            } else {
                weight
            }
            if (weighedCooked && food != null) "$both ${cookedSuffix(food)}" else both
        }
    }

    /**
     * The word a food uses for its cooked state, as PRD_FOOD 13.2's `150 g cooked` reads it.
     *
     * Lower-cased through [Locale.ROOT] rather than the phone's locale, for the reason
     * `Food.fold` gives: a Turkish device lower-cases `I` to `ı`, and a label must not change
     * shape with the region.
     */
    fun cookedSuffix(food: Food): String = food.cookedLabel.trim().lowercase(Locale.ROOT)

    /**
     * The four macronutrients of a bundle in the order PRD_FOOD 8.2 lists them, each already
     * rendered — including the ones that are unknown, which read `—` and are not dropped.
     */
    fun macros(nutrients: Nutrients, approximate: Boolean = true): List<String> = listOf(
        macro(nutrients.protein, approximate),
        macro(nutrients.carbs, approximate),
        macro(nutrients.fat, approximate),
        macro(nutrients.fibre, approximate),
    )

    /** Quantities are stored to the thousandth; the digits only appear when they carry meaning. */
    private const val QUANTITY_DECIMALS: Int = 3

    /** A quarter of a serving is the finest step PRD_FOOD 15 allows, so two decimals suffice. */
    private const val SERVINGS_DECIMALS: Int = 2

    private fun prefixed(text: String, approximate: Boolean): String =
        if (approximate) "$APPROXIMATE_PREFIX$text" else text

    /**
     * `canonical / scale` written with [decimals] decimal places, rounded half-up.
     *
     * Every canonical unit of the module is a non-negative whole thousandth of its display unit,
     * which is what lets this be integer arithmetic end to end: no `Double` takes part, so no
     * value drifts to `0.0000001` and no rounding depends on a floating-point mode.
     */
    private fun decimal(canonical: Int, scale: Int, decimals: Int, trimZeros: Boolean): String {
        var factor = 1L
        repeat(decimals) { factor *= 10L }
        val scaled = (canonical.toLong() * factor + scale / 2) / scale
        val whole = scaled / factor
        if (decimals == 0) return whole.toString()
        val fraction = (scaled % factor).toString().padStart(decimals, '0')
        val shown = if (trimZeros) fraction.trimEnd('0') else fraction
        return if (shown.isEmpty()) whole.toString() else "$whole.$shown"
    }
}
