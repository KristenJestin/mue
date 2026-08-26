package fr.kristenjestin.mue.domain.model

/**
 * The five nutritional metrics of PRD_FOOD 8.2, carried as one bundle.
 *
 * They are a bundle rather than five loose fields on `Food`, on `FoodLogEntry` and on every
 * total, because PRD_FOOD 13.1's strict rule is the kind that is forgotten one call site at a
 * time. `null` means **unknown**, `0` means a known zero, and no conversion between the two is
 * allowed anywhere in the module — not on entry, not on display, not in a sum. With five
 * separate nullable fields that rule has to be re-applied by hand five times per operation;
 * with a bundle it is applied once, here, and [plus] and [scaled] are the only places that can
 * get it wrong.
 *
 * Propagation is strict **metric by metric**: a known energy coexists with unknown protein, and
 * a single unknown contribution makes only *its own* metric unknown for the whole total. That
 * is why the fields are independently nullable rather than the bundle itself.
 *
 * Every arithmetic result is null-on-overflow rather than a wrapped `Int`. An unrepresentable
 * total is genuinely not known, and PRD_FOOD 13.2 already shows an unknown value as `—`; a
 * wrapped one would be shown as a number, and would be wrong.
 */
data class Nutrients(
    val energy: Energy? = null,
    val protein: Macro? = null,
    val carbs: Macro? = null,
    val fat: Macro? = null,
    val fibre: Macro? = null,
) {

    /** True when nothing at all is known — the nominal state of an incomplete Open Food Facts card. */
    val isUnknown: Boolean
        get() = energy == null && protein == null && carbs == null && fat == null && fibre == null

    /** True when every one of the five is known, which is what an exact total requires. */
    val isFullyKnown: Boolean
        get() = energy != null && protein != null && carbs != null && fat != null && fibre != null

    /** The three macronutrients PRD_FOOD 15 bounds together, unknowns dropped. */
    val knownEnergyMacros: List<Macro> get() = listOfNotNull(protein, carbs, fat)

    /**
     * PRD_FOOD 15: "la somme des valeurs **connues** parmi protéines, glucides et lipides ne peut
     * dépasser 100 g ; les inconnues sont ignorées par ce contrôle".
     *
     * Fibre is deliberately outside the sum: PRD_FOOD 15 names three constituents and Ciqual
     * counts most fibre inside the carbohydrate figure, so adding it would reject honest cards.
     */
    val isMacroSumWithinPer100Limit: Boolean
        get() = knownEnergyMacros.sumOf { it.milligrams } <= Macro.PER_100_MAX_MILLIGRAMS

    /**
     * The strict addition of PRD_FOOD 13.1: `null` as soon as one side of a metric is unknown,
     * the sum otherwise.
     *
     * Total, commutative and associative. Associativity survives the overflow guard because every
     * value here is non-negative: a partial sum that does not fit cannot be rescued by adding
     * more to it, so every grouping of the same terms reaches the same null.
     */
    operator fun plus(other: Nutrients): Nutrients = Nutrients(
        energy = sumOrNull(energy, other.energy),
        protein = sumOrNull(protein, other.protein),
        carbs = sumOrNull(carbs, other.carbs),
        fat = sumOrNull(fat, other.fat),
        fibre = sumOrNull(fibre, other.fibre),
    )

    /**
     * This bundle multiplied by `numerator / denominator`, metric by metric.
     *
     * It is the whole of PRD_FOOD 13.1's arithmetic, and the three formulas of that section are
     * three choices of the pair:
     *
     * - a food's contribution is `scaled(referenceWeight.thousandths, `[PER_100_THOUSANDTHS]`)`;
     * - a recipe's per-serving value is `scaled(1, baseServings)`;
     * - a recipe line is the per-serving value `scaled(servings.thousandths, `
     *   [Servings.THOUSANDTHS_PER_SERVING]`)`.
     *
     * The product is taken in `Long` and rounded half-up before being narrowed back, so a
     * 5 000 g ingredient of a 900 kcal/100 g fat — 4.5e12 in canonical units — is exact rather
     * than wrapped. A metric that still does not fit an `Int` afterwards comes back `null`.
     *
     * A non-positive denominator has no meaning in any of the three formulas — `baseServings` is
     * at least 1 by PRD_FOOD 15 — and yields [UNKNOWN] rather than a crash or a zero.
     */
    fun scaled(numerator: Long, denominator: Long): Nutrients {
        if (numerator < 0L || denominator <= 0L) return UNKNOWN
        return Nutrients(
            energy = energy
                ?.let { scaleOrNull(it.milliKcal, numerator, denominator) }
                ?.let { Energy.ofMilliKcalOrNull(it) },
            protein = scaleMacro(protein, numerator, denominator),
            carbs = scaleMacro(carbs, numerator, denominator),
            fat = scaleMacro(fat, numerator, denominator),
            fibre = scaleMacro(fibre, numerator, denominator),
        )
    }

    companion object {
        /**
         * Everything unknown. What PRD_FOOD 9.2 calls the nominal state of an incomplete product
         * card, and what a strict sum collapses to as soon as one contribution is missing.
         */
        val UNKNOWN: Nutrients = Nutrients()

        /**
         * Everything known and equal to zero — water, black coffee, an empty day.
         *
         * It is the identity of [plus] and therefore the seed of [strictSum]. It is emphatically
         * not [UNKNOWN]: summing no lines at all yields a total of zero that is *known*, while
         * summing one unknown line yields a total that is not.
         */
        val ZERO: Nutrients = Nutrients(
            energy = Energy.ZERO,
            protein = Macro.ZERO,
            carbs = Macro.ZERO,
            fat = Macro.ZERO,
            fibre = Macro.ZERO,
        )

        /**
         * A per-100 value is quoted for 100 g or 100 ml, which is 100 000 thousandths.
         *
         * Exposed so no caller of [scaled] re-derives it, and so the one place the `/ 100` of
         * PRD_FOOD 13.1 meets the thousandth of PRD_FOOD 8.6 is this constant.
         */
        const val PER_100_THOUSANDTHS: Long = 100_000L

        /**
         * The strict sum of PRD_FOOD 13.1, over the lines of a recipe, of a slot or of a day.
         *
         * An empty list sums to [ZERO], because a total of nothing is a known nothing; a list
         * containing one [UNKNOWN] sums to all-null, because a single missing contribution is
         * exactly what PRD_FOOD 13.1 says makes the total unknown.
         */
        fun strictSum(items: Iterable<Nutrients>): Nutrients = items.fold(ZERO, Nutrients::plus)

        private fun sumOrNull(a: Energy?, b: Energy?): Energy? =
            if (a == null || b == null) {
                null
            } else {
                Energy.ofMilliKcalOrNull(a.milliKcal.toLong() + b.milliKcal.toLong())
            }

        private fun sumOrNull(a: Macro?, b: Macro?): Macro? =
            if (a == null || b == null) {
                null
            } else {
                Macro.ofMilligramsOrNull(a.milligrams.toLong() + b.milligrams.toLong())
            }

        private fun scaleMacro(value: Macro?, numerator: Long, denominator: Long): Macro? =
            value
                ?.let { scaleOrNull(it.milligrams, numerator, denominator) }
                ?.let { Macro.ofMilligramsOrNull(it) }

        /**
         * `value × numerator / denominator`, rounded half-up, in `Long`, or null when even a
         * `Long` cannot hold the product.
         *
         * The overflow test is a division rather than a `Math.multiplyExact`: nothing in this
         * domain throws, and every operand is known non-negative here, which makes the test exact.
         */
        private fun scaleOrNull(value: Int, numerator: Long, denominator: Long): Long? {
            if (numerator == 0L) return 0L
            val headroom = (Long.MAX_VALUE - denominator / 2) / numerator
            if (value.toLong() > headroom) return null
            return (value.toLong() * numerator + denominator / 2) / denominator
        }
    }
}
