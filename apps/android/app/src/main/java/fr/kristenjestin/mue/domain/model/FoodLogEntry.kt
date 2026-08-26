package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** The identifier of one journal line (PRD_FOOD 8.4), stored as a `TEXT` UUID. */
@JvmInline
value class FoodLogEntryId(val value: String) {
    companion object {
        fun random(): FoodLogEntryId = FoodLogEntryId(UUID.randomUUID().toString())
    }
}

/**
 * A moment of the day (PRD_FOOD 8.1).
 *
 * **There is no meal object.** A slot is a label each line carries and a grouping on screen; it
 * has no identity, no name of its own and no values, which is precisely what lets four
 * heterogeneous lines sit under the same heading and what makes a slot total a local addition
 * rather than a stored one.
 */
enum class MealSlot(val id: String, val label: String, val defaultTime: LocalTime) {
    /** PRD_FOOD 10.3: the middle of the slot, the default time of a retroactive entry. */
    BREAKFAST("breakfast", "Breakfast", LocalTime.of(8, 0)),
    LUNCH("lunch", "Lunch", LocalTime.of(13, 0)),
    SNACK("snack", "Snack", LocalTime.of(16, 30)),
    DINNER("dinner", "Dinner", LocalTime.of(20, 0)),
    ;

    companion object {
        private val byId: Map<String, MealSlot> = entries.associateBy { it.id }

        /**
         * Total and non-throwing. [SNACK] absorbs an unreadable id because PRD_FOOD 10.3 already
         * makes it the catch-all of the clock: everything that is not one of the three named
         * windows is a snack.
         */
        fun fromId(id: String): MealSlot = byId[id] ?: SNACK

        /** PRD_FOOD 10.1: the four slots always appear, in this order, filled or not. */
        val ORDERED: List<MealSlot> = listOf(BREAKFAST, LUNCH, SNACK, DINNER)

        /** PRD_FOOD 10.3. */
        val BREAKFAST_FROM: LocalTime = LocalTime.of(5, 0)
        val BREAKFAST_UNTIL: LocalTime = LocalTime.of(10, 0)
        val LUNCH_FROM: LocalTime = LocalTime.of(11, 30)
        val LUNCH_UNTIL: LocalTime = LocalTime.of(14, 30)
        val DINNER_FROM: LocalTime = LocalTime.of(18, 0)
        val DINNER_UNTIL: LocalTime = LocalTime.of(22, 0)

        /**
         * The slot PRD_FOOD 10.3 and FR-FOOD-007 preselect from a time of day. A default and
         * nothing more: every slot stays reachable in one gesture, and no entry is ever refused
         * for being logged outside its window.
         *
         * Each window is **closed at its start and open at its end**. The table of PRD_FOOD 10.3
         * writes them as `05:00 – 10:00`, but the sentence under it and the acceptance criterion
         * of PRD_FOOD 22 both require that "une pomme à dix heures tombe en collation" — so ten
         * o'clock sharp belongs to the snack that follows breakfast, not to breakfast. Read the
         * other way the two statements contradict each other; read half-open they agree, and
         * `14:00` still falls in lunch as the same sentence demands.
         */
        fun forTime(time: LocalTime): MealSlot = when {
            time >= BREAKFAST_FROM && time < BREAKFAST_UNTIL -> BREAKFAST
            time >= LUNCH_FROM && time < LUNCH_UNTIL -> LUNCH
            time >= DINNER_FROM && time < DINNER_UNTIL -> DINNER
            else -> SNACK
        }
    }
}

/**
 * Which of the three forms a journal line takes (PRD_FOOD 10.2).
 *
 * The three mix freely inside one slot: a yoghurt and a banana at breakfast are two lines, never
 * a recipe to create.
 */
enum class FoodLogKind(val id: String) {
    FOOD("food"),
    RECIPE("recipe"),
    QUICK("quick"),
    ;

    companion object {
        private val byId: Map<String, FoodLogKind> = entries.associateBy { it.id }

        /**
         * Total and non-throwing, falling back to [QUICK]. It is the only self-contained form:
         * PRD_FOOD 10.2 gives it a title and values and nothing else, so a line read back as a
         * quick add still shows everything it stored. Guessing [FOOD] or [RECIPE] would promise
         * a source card that the unreadable row may never have had.
         */
        fun fromId(id: String): FoodLogKind = byId[id] ?: QUICK
    }
}

/**
 * How a journal line's quantity is counted (PRD_FOOD 8.4).
 *
 * Persisted beside the amount, which is why it is an enum with a stable id rather than a
 * property derived from [LoggedAmount].
 */
enum class QuantityUnit(val id: String, val symbol: String) {
    GRAM("gram", "g"),
    MILLILITRE("millilitre", "ml"),
    SERVING("serving", "serving"),
    ;

    companion object {
        private val byId: Map<String, QuantityUnit> = entries.associateBy { it.id }

        /** Total and non-throwing; the gram is the unit of most of the catalogue. */
        fun fromId(id: String): QuantityUnit = byId[id] ?: GRAM
    }
}

/**
 * How much confidence a line's values deserve (PRD_FOOD 8.4).
 *
 * PRD_FOOD 8.4 sets [APPROXIMATE] for a quick add and for any recipe one of whose ingredients is
 * itself approximate, and PRD_FOOD 13.2 prefixes every such value with `≈` on screen.
 */
enum class Estimation(val id: String) {
    MEASURED("measured"),
    APPROXIMATE("approximate"),
    ;

    companion object {
        private val byId: Map<String, Estimation> = entries.associateBy { it.id }

        /**
         * Total and non-throwing, falling back to [APPROXIMATE]. Of the two possible mistakes,
         * showing a `≈` on a measured value costs nothing, while claiming a precision the row
         * never had is the failure PRD_FOOD 13.1 is written to avoid.
         */
        fun fromId(id: String): Estimation = byId[id] ?: APPROXIMATE
    }
}

/**
 * What a journal line was measured in (PRD_FOOD 8.4 and 10.2).
 *
 * PRD_FOOD 8.4 stores it as a `quantity` and a `quantityUnit`, but the two columns can express
 * states that no line of PRD_FOOD 10.2 has: a quick add has **no quantity at all** — PRD_FOOD 15
 * asks it for a name and an energy and nothing else — and a nullable quantity next to a non-null
 * unit would be exactly the `0`-standing-in-for-nothing this module forbids. Three cases, one of
 * which carries no number, make every state legal by construction.
 */
sealed interface LoggedAmount {

    /** The unit this amount is stored under, or null when there is nothing to store. */
    val unit: QuantityUnit?

    /** A weight or a volume: an apple, 150 g of skyr, a scanned product. */
    data class Measured(val quantity: Quantity, val referenceUnit: ReferenceUnit) : LoggedAmount {
        override val unit: QuantityUnit get() = referenceUnit.asQuantityUnit
    }

    /** A count of recipe servings, which PRD_FOOD 8.6 keeps out of the nutritional units. */
    data class Portioned(val servings: Servings) : LoggedAmount {
        override val unit: QuantityUnit get() = QuantityUnit.SERVING
    }

    /** A quick add: a restaurant plate known only by its order of magnitude (PRD_FOOD 10.2). */
    data object Unmeasured : LoggedAmount {
        override val unit: QuantityUnit? get() = null
    }
}

/**
 * One line of the journal: what was actually eaten, when, and how much of it (PRD_FOOD 8.4).
 *
 * Its [nutrients] are **copied at the moment of saving and frozen**. Editing or deleting the
 * food or the recipe afterwards never changes a line already written (PRD_FOOD 8.4), and
 * correcting a food later never retroactively completes an unknown value (PRD_FOOD 13.1).
 * [sourceRef] survives only to offer "the same again" and to open the original card if it still
 * exists.
 *
 * Lines are independent by design, which is also their conflict rule: PRD_FOOD 21.3 says two
 * lines created separately coexist and never merge.
 */
data class FoodLogEntry(
    val id: FoodLogEntryId,
    val consumedOn: LocalDate,
    /** PRD_FOOD 10.3: orders the lines of one slot, and prepares a chronological view. */
    val consumedAt: LocalTime,
    val slot: MealSlot,
    val kind: FoodLogKind,
    val title: String,
    val amount: LoggedAmount,
    /** Frozen at save time. PRD_FOOD 13.1: each metric is independently nullable. */
    val nutrients: Nutrients,
    val estimation: Estimation,
    /** The [FoodId] or [RecipeId] this line was built from, as the single column that stores it. */
    val sourceRef: String? = null,
    /** PRD_FOOD 13.2: "1.5 × apple (225 g)", "150 g cooked" — both readings kept when both exist. */
    val amountLabel: String? = null,
    /** PRD_FOOD 8.4: how many usual portions were typed, when the counter was used. */
    val portions: Servings? = null,
    /** PRD_FOOD 8.6: the quantity was read on the scale in the cooked state. */
    val weighedCooked: Boolean = false,
    /**
     * The proposal this line confirmed (PRD_FOOD 12).
     *
     * A business key rather than a UUID, because that is what identifies a proposal at all: see
     * [MealPlanKey]. Deleting this line puts that proposal back in waiting.
     */
    val fromPlan: MealPlanKey? = null,
) {
    /** The source card, when this line came from the catalogue and the id can still be read. */
    val foodRef: FoodId? get() = sourceRef?.takeIf { kind == FoodLogKind.FOOD }?.let(::FoodId)

    /** The source recipe, when this line came from one. */
    val recipeRef: RecipeId? get() = sourceRef?.takeIf { kind == FoodLogKind.RECIPE }?.let(::RecipeId)

    /** The stored unit of [amount]; null for a quick add, which has no quantity to store. */
    val quantityUnit: QuantityUnit? get() = amount.unit

    /** The weight or volume of this line, or null when it is counted in servings or in nothing. */
    val measuredQuantity: Quantity?
        get() = (amount as? LoggedAmount.Measured)?.quantity

    /** The recipe servings of this line, or null when it is not a recipe line. */
    val consumedServings: Servings?
        get() = (amount as? LoggedAmount.Portioned)?.servings

    /** PRD_FOOD 13.2: an unknown energy is shown `—` and feeds neither a bar nor an average. */
    val countsTowardsEnergyAverage: Boolean get() = nutrients.energy != null

    companion object {
        /** PRD_FOOD 15: the same 1-to-80 rule as a food name, applied to a quick add's title. */
        const val MIN_TITLE_LENGTH: Int = Food.MIN_NAME_LENGTH

        const val MAX_TITLE_LENGTH: Int = Food.MAX_NAME_LENGTH

        /**
         * PRD_FOOD 15: "Date de consommation : aujourd'hui ou dans le passé, jamais dans le
         * futur", which PRD_FOOD 21.5 repeats as a rule of the MCP write tools.
         */
        fun isLoggableOn(date: LocalDate, today: LocalDate): Boolean = !date.isAfter(today)
    }
}
