package fr.kristenjestin.mue.domain.model

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * The identifier of a catalogue entry (PRD_FOOD 8.2), a value class over the `TEXT` UUID that
 * PRD_FOOD 20.1 stores, so a food id can never be handed to a query expecting a recipe id.
 */
@JvmInline
value class FoodId(val value: String) {
    companion object {
        fun random(): FoodId = FoodId(UUID.randomUUID().toString())
    }
}

/**
 * Where a catalogue entry came from (PRD_FOOD 8.2 and 9).
 *
 * It is provenance, never editability by itself, with one exception: PRD_FOOD 9.1 makes the
 * embedded Ciqual subset the only read-only part of the catalogue. A product copied from Open
 * Food Facts keeps [OPEN_FOOD_FACTS] for good — PRD_FOOD 9.2 says editing its values does not
 * turn it into [CUSTOM] — while sharing the life cycle of a personal food.
 */
enum class FoodSource(val id: String) {
    CIQUAL("ciqual"),
    OPEN_FOOD_FACTS("open_food_facts"),
    CUSTOM("custom"),
    ;

    /** PRD_FOOD 9.1: only the shipped reference table refuses edits and deletions. */
    val isReadOnly: Boolean get() = this == CIQUAL

    /** PRD_FOOD 21.1: the reference table is not personal data and is never synchronised. */
    val isSynchronised: Boolean get() = this != CIQUAL

    companion object {
        private val byId: Map<String, FoodSource> = entries.associateBy { it.id }

        /**
         * Total and non-throwing. An unreadable provenance falls back to [CUSTOM] rather than to
         * [CIQUAL]: guessing "reference table" would make the row read-only and undeletable, and
         * an entry nobody can remove is a worse outcome than one that is merely mislabelled.
         */
        fun fromId(id: String): FoodSource = byId[id] ?: CUSTOM
    }
}

/**
 * The basis a food's per-100 values are quoted against (PRD_FOOD 8.6).
 *
 * PRD_FOOD 8.6 applies no implicit density between the two, so this is a real choice made once
 * per food and never converted afterwards.
 */
enum class ReferenceUnit(val id: String, val symbol: String) {
    GRAM("gram", "g"),
    MILLILITRE("millilitre", "ml"),
    ;

    /** The same unit as a journal line reads it, where servings are a third possibility. */
    val asQuantityUnit: QuantityUnit
        get() = when (this) {
            GRAM -> QuantityUnit.GRAM
            MILLILITRE -> QuantityUnit.MILLILITRE
        }

    companion object {
        private val byId: Map<String, ReferenceUnit> = entries.associateBy { it.id }

        /** Total and non-throwing; solids outnumber liquids, so an unreadable unit is a gram. */
        fun fromId(id: String): ReferenceUnit = byId[id] ?: GRAM
    }
}

/**
 * One catalogue entry: what an aliment is worth per 100 g or per 100 ml (PRD_FOOD 8.2).
 *
 * The five nutritional values are [per100], one [Nutrients] bundle rather than five nullable
 * fields, so PRD_FOOD 13.1's strict null rule cannot be forgotten at a call site. They always
 * describe the **reference state** of the food, the one [rawLabel] names.
 *
 * `createdAt` and `updatedAt` of PRD_FOOD 8.2 are audit columns of the stored row alone and are
 * deliberately absent here, exactly as they are from `ActivitySession`: nothing in PRD_FOOD 9 to
 * 13 reads them, and an instant inside this class would make equality depend on the clock and
 * force every fixture to invent one. The synchronisation metadata of PRD_FOOD 20.1 lives beside
 * the row for the same reason.
 */
data class Food(
    val id: FoodId,
    val name: String,
    val source: FoodSource,
    val referenceUnit: ReferenceUnit = ReferenceUnit.GRAM,
    /** PRD_FOOD 15: a food with no value at all is accepted; an incomplete card is nominal. */
    val per100: Nutrients = Nutrients.UNKNOWN,
    val brand: String? = null,
    val barcode: String? = null,
    /** The Ciqual food code or the Open Food Facts identifier this entry was copied from. */
    val sourceId: String? = null,
    /** PRD_FOOD 9.1: the version of the embedded subset an entry was seeded from. */
    val sourceVersion: String? = null,
    /** PRD_FOOD 8.2: the label of a usual portion — "pot", "apple", "handful". */
    val servingLabel: String? = null,
    /** How much one [servingLabel] weighs or measures. */
    val servingSize: Quantity? = null,
    val cookedRatio: CookedRatio? = null,
    val rawLabel: String = DEFAULT_RAW_LABEL,
    val cookedLabel: String = DEFAULT_COOKED_LABEL,
    val imageRef: String? = null,
) {
    /** PRD_FOOD 9.1: a Ciqual entry is duplicable into a personal food, never edited in place. */
    val isReadOnly: Boolean get() = source.isReadOnly

    /**
     * PRD_FOOD FR-FOOD-006: the serving counter is offered only when both halves are there.
     * A label with no weight cannot be turned into grams, and a weight with no label has nothing
     * to put on the button.
     */
    val hasUsualServing: Boolean get() = servingLabel != null && servingSize != null

    /** PRD_FOOD FR-FOOD-006: the raw/cooked selector appears only on a food that carries a ratio. */
    val hasCookedState: Boolean get() = cookedRatio != null

    /**
     * What makes a search insensitive to case and to accents (PRD_FOOD 9.4), and what the index
     * of PRD_FOOD 20.2 stores. Folded with [Locale.ROOT] so a name never folds two ways on two
     * devices — `"I".lowercase()` yields `"ı"` on a Turkish phone.
     */
    val nameFolded: String get() = fold(name)

    /** The brand folded the same way, so `Bjorg` and `Björg` are one search term. */
    val brandFolded: String? get() = brand?.let(::fold)

    companion object {
        /** PRD_FOOD 15: "1 à 80 caractères après nettoyage des espaces". */
        const val MIN_NAME_LENGTH: Int = 1

        const val MAX_NAME_LENGTH: Int = 80

        /**
         * PRD_FOOD 15 sets no bound on a brand. This one only guards a mistyped field, the way
         * `Load.MAX_GRAMS` does, and sits far above any brand printed on a package.
         */
        const val MAX_BRAND_LENGTH: Int = 80

        /**
         * EAN-8 through GTIN-14 — the range every retail barcode ML Kit decodes falls into.
         * PRD_FOOD 15 has no row for it because the scanner supplies it, but PRD_FOOD 9.2 also
         * lets the number be typed by hand when the camera is refused (PRD_FOOD 18).
         */
        val BARCODE_LENGTH_RANGE: IntRange = 8..14

        /** PRD_FOOD 8.2: the default label of the reference state. */
        const val DEFAULT_RAW_LABEL: String = "Raw"

        /** PRD_FOOD 8.2: the default label of the cooked state. */
        const val DEFAULT_COOKED_LABEL: String = "Cooked"

        /**
         * PRD_FOOD 9.4 asks for a search insensitive to case **and to accents**, offline.
         *
         * Decomposing to NFD and dropping the combining marks does both without a collator and
         * without a locale: `Bœuf sauté` and `boeuf saute` differ only by marks the `Mn`
         * category names, and SQLite's own `NOCASE` covers ASCII alone. The lowercase pass is
         * [Locale.ROOT] so the fold is identical on every device, which matters because the
         * folded form is stored and indexed rather than recomputed per query.
         */
        fun fold(name: String): String =
            Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
                .lowercase(Locale.ROOT)

        private val COMBINING_MARKS = Regex("\\p{Mn}+")

        /**
         * The same folded term written with the other spelling of its ligatures — `bœuf` beside
         * `boeuf`, `nævus` beside `naevus`.
         *
         * [fold] cannot do this itself, and that is a fact about **storage** rather than a
         * preference. `œ` is not a letter with a combining mark: NFD leaves it whole, so
         * `Bœuf sauté` folds to `bœuf saute` and a person typing `boeuf` finds nothing. Teaching
         * [fold] to decompose it would be correct for every row written afterwards and wrong for
         * every row already written — `name_folded` is stored and indexed (PRD_FOOD 20.2), so the
         * catalogue would have to be re-folded row by row, which is a migration, and this
         * database is pinned at version 6.
         *
         * So the *query* carries both spellings instead and the table is left exactly as it is:
         * one extra `LIKE` on a scan that a leading `%` already made a scan, and not one stored
         * byte rewritten. The direction is decided by what was typed — a term holding a ligature
         * looks for the expansion, a term without one looks for the ligature — because a term
         * cannot be spelt both ways at once and two searches would find the same rows twice.
         *
         * Returns the term unchanged when it contains neither spelling, which is the ordinary
         * case: the shipped subset holds no ligature at all.
         */
        fun ligatureVariantOf(folded: String): String =
            if (folded.any { it in LIGATURES }) {
                folded.replace("œ", "oe").replace("æ", "ae")
            } else {
                folded.replace("oe", "œ").replace("ae", "æ")
            }

        /** The two the French and the Latin alphabets actually use, both already lower-cased. */
        private const val LIGATURES = "œæ"
    }
}
