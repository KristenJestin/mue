package fr.kristenjestin.mue.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The embedded generic catalogue, exactly as the generator writes it and as the app reads it
 * (PRD_FOOD 9.1 and 20.2).
 *
 * The shape is declared **here**, in the domain, so the two sides of the file agree by
 * construction rather than by convention: the Ciqual extractor emits this and nothing else, the
 * first-run seeding parses this and nothing else, and a field that changes breaks a compile
 * instead of a phone.
 *
 * PRD_FOOD 9.1 keeps only the five constituents Mue uses out of Ciqual's seventy-four, and
 * versions the subset so a regeneration is traceable; [version] is what ends up in
 * `Food.sourceVersion`.
 */
@Serializable
data class CiqualCatalogue(
    /** PRD_FOOD 9.1: the version of the subset, recorded on every food it seeds. */
    val version: String,
    val entries: List<CiqualEntry> = emptyList(),
) {
    companion object {
        /**
         * Lenient about unknown keys on purpose: a regenerated file may carry a field this build
         * predates, and PRD_FOOD 20.2 says an update must never break an installed catalogue.
         */
        private val format = Json { ignoreUnknownKeys = true }

        /**
         * Total and non-throwing. A resource that cannot be read is an empty catalogue, and
         * PRD_FOOD 17 keeps the module usable with an empty one — search simply finds nothing
         * and offers to create a food.
         */
        fun fromJsonOrNull(raw: String): CiqualCatalogue? = try {
            format.decodeFromString(serializer(), raw)
        } catch (_: IllegalArgumentException) {
            null
        }

        fun toJson(catalogue: CiqualCatalogue): String =
            format.encodeToString(serializer(), catalogue)
    }
}

/**
 * One generic aliment of the embedded subset (PRD_FOOD 9.1).
 *
 * Every number is an **integer in this module's canonical unit** — thousandths of the display
 * unit — and never a decimal. Ciqual publishes `89,0` and `1,3` as text; converting once, in the
 * generator, means the phone parses no float at all and that the value shipped is bit-for-bit
 * the value stored. It is the same reason PRD_FOOD 8.6 stores everything in thousandths.
 *
 * A missing constituent is an **absent field**, which decodes to `null` and means unknown.
 * PRD_FOOD 13.1 forbids reading it as zero, and a JSON `0` therefore means a known zero — the
 * distinction survives the file.
 */
@Serializable
data class CiqualEntry(
    /**
     * The [FoodId] the generator wrote down, a name-based UUID of `"ciqual:$code"`.
     *
     * PRD_FOOD 9.4 seeds the same catalogue onto every device, so the id has to come from the
     * asset rather than be minted at seeding time: two phones that each rolled their own would
     * hold the same food under two identities, and PRD_SERVER_SYNC_MCP 13 would then sync it
     * twice instead of converging. This is the argument `ExerciseCatalogSeed` already settled
     * for a handful of exercises, applied to a thousand foods.
     *
     * Absent only in a fixture written before the generator existed; [toFoodOrNull] falls back
     * to its `id` parameter there.
     */
    val id: String? = null,
    /** The Ciqual food code (`alim_code`), which becomes `Food.sourceId`. */
    val code: String,
    /** English, like the rest of the app; the generator translates the French table. */
    val name: String,
    /** [ReferenceUnit.id]; PRD_FOOD 8.6 applies no density, so a liquid says so explicitly. */
    val unit: String = ReferenceUnit.GRAM.id,
    val energyMilliKcal: Int? = null,
    val proteinMilligrams: Int? = null,
    val carbsMilligrams: Int? = null,
    val fatMilligrams: Int? = null,
    val fibreMilligrams: Int? = null,
    /**
     * PRD_FOOD 8.6: derived by the generator from the raw/cooked pair Ciqual already contains,
     * never typed by hand, and absent for the foods where only water does not move.
     */
    val cookedRatioThousandths: Int? = null,
    /** PRD_FOOD 8.2: the usual portion, both halves or neither. */
    val servingLabel: String? = null,
    val servingThousandths: Int? = null,
) {
    /**
     * The catalogue entry this row seeds, or null when the row is not usable.
     *
     * Total and non-throwing, and strict about what it accepts: a name outside PRD_FOOD 15's
     * 1-to-80, a constituent outside its per-100 bounds, a ratio outside 0.3–5, or half a usual
     * serving all reject the whole row rather than being quietly dropped. A generated file is
     * either correct or a bug to see, and a food seeded with a silently missing protein would be
     * indistinguishable from one Ciqual genuinely does not document.
     *
     * The one PRD_FOOD 15 rule deliberately not applied is the 100 g ceiling on the sum of known
     * protein, carbohydrate and fat: that rule guards a form, where a person can be told which
     * field to fix, and applying it here would drop shipped reference rows over a rounding of
     * the source table.
     */
    fun toFoodOrNull(sourceVersion: String, id: FoodId = FoodId.random()): Food? {
        val trimmedName = name.trim()
        if (code.isBlank()) return null
        if (trimmedName.length !in Food.MIN_NAME_LENGTH..Food.MAX_NAME_LENGTH) return null

        if (!energyMilliKcal.absentOrIn(Energy.PER_100_RANGE)) return null
        if (!proteinMilligrams.absentOrIn(Macro.PER_100_RANGE)) return null
        if (!carbsMilligrams.absentOrIn(Macro.PER_100_RANGE)) return null
        if (!fatMilligrams.absentOrIn(Macro.PER_100_RANGE)) return null
        if (!fibreMilligrams.absentOrIn(Macro.PER_100_RANGE)) return null
        if (!cookedRatioThousandths.absentOrIn(CookedRatio.RANGE)) return null
        if (!servingThousandths.absentOrIn(Quantity.USUAL_SERVING_RANGE)) return null

        val label = servingLabel?.trim()?.takeIf { it.isNotEmpty() }
        if ((label == null) != (servingThousandths == null)) return null

        return Food(
            id = this.id?.let(::FoodId) ?: id,
            name = trimmedName,
            source = FoodSource.CIQUAL,
            referenceUnit = ReferenceUnit.fromId(unit),
            per100 = Nutrients(
                energy = energyMilliKcal?.let { Energy.ofMilliKcalOrNull(it.toLong()) },
                protein = proteinMilligrams?.let { Macro.ofMilligramsOrNull(it.toLong()) },
                carbs = carbsMilligrams?.let { Macro.ofMilligramsOrNull(it.toLong()) },
                fat = fatMilligrams?.let { Macro.ofMilligramsOrNull(it.toLong()) },
                fibre = fibreMilligrams?.let { Macro.ofMilligramsOrNull(it.toLong()) },
            ),
            sourceId = code,
            sourceVersion = sourceVersion,
            servingLabel = label,
            servingSize = servingThousandths?.let { Quantity.ofThousandthsOrNull(it.toLong()) },
            cookedRatio = cookedRatioThousandths?.let {
                CookedRatio.ofThousandthsOrNull(it.toLong())
            },
        )
    }
}

/**
 * The one question every optional field of a generated row asks: absent, which is a legitimate
 * unknown, or present and inside the bounds its unit allows?
 *
 * Checking the raw `Int` against the range rather than the constructed value is what lets the
 * builder above use `ofXOrNull` without a second null check: past this test the factories
 * cannot refuse.
 */
private fun Int?.absentOrIn(range: IntRange): Boolean = this == null || this in range
