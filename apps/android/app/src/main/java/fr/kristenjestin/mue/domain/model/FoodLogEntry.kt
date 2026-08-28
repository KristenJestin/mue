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
 * has no identity, no name of its own and no values, which is precisely what lets several
 * heterogeneous lines sit under the same heading and what makes a slot total a local addition
 * rather than a stored one.
 *
 * ## The six moments, and why the day is partitioned rather than dotted with windows
 *
 * PRD_FOOD 10.3 named three windows and let *"tout le reste"* fall to `Snack`, which left a
 * quarter of the clock — the whole of the night, the late morning, the end of the afternoon —
 * inside one label that meant nothing in particular. The owner's requirement is the opposite:
 * *"faut que chaque horaire ait son truc, qu'on ne se retrouve pas avec des heures sans rien"*,
 * and the six windows he approved are the ones below. Each meal is followed by its own snack, so
 * a bite at eleven at night and a dinner at seven are no longer the same entry.
 *
 * | Moment | Window |
 * |---|---|
 * | `BREAKFAST` | `05:00 – 10:00` |
 * | `MORNING_SNACK` | `10:00 – 12:00` |
 * | `LUNCH` | `12:00 – 14:30` |
 * | `SNACK` | `14:30 – 18:30` |
 * | `DINNER` | `18:30 – 22:00` |
 * | `EVENING_SNACK` | `22:00 – 05:00` |
 *
 * [from] is a **constructor parameter and the only bound written down**. A window ends exactly
 * where the next one begins, so [untilExclusive] is read off the successor and the partition is
 * structural: there is no pair of numbers that can be edited into a gap or an overlap, and the
 * one window that crosses midnight is simply the last one, whose successor is the first.
 *
 * That is what makes `MealSlotCoverageTest`'s walk of all 1 440 minutes a property of the shape
 * rather than a coincidence of six hand-written intervals.
 */
enum class MealSlot(
    val id: String,
    val label: String,
    /** Inclusive start of the moment's window; its end is the next moment's start. */
    val from: LocalTime,
    /**
     * PRD_FOOD 10.3: the default time of a retroactive entry — an hour that sits inside this
     * moment and reads as typical of it, never the current time.
     */
    val defaultTime: LocalTime,
) {
    BREAKFAST("breakfast", "Breakfast", LocalTime.of(5, 0), LocalTime.of(8, 0)),
    MORNING_SNACK("morning_snack", "Morning snack", LocalTime.of(10, 0), LocalTime.of(11, 0)),
    LUNCH("lunch", "Lunch", LocalTime.of(12, 0), LocalTime.of(13, 0)),
    SNACK("snack", "Snack", LocalTime.of(14, 30), LocalTime.of(16, 30)),
    DINNER("dinner", "Dinner", LocalTime.of(18, 30), LocalTime.of(20, 0)),
    EVENING_SNACK("evening_snack", "Evening snack", LocalTime.of(22, 0), LocalTime.of(23, 0)),
    ;

    /**
     * Exclusive end of the moment's window: the next moment's [from], wrapping at the last.
     *
     * For [EVENING_SNACK] that is `05:00` — an end **earlier than its own start**, which is the
     * single fact that makes this moment's containment test different from the other five. A one
     * o'clock meal is a late evening snack, not a lost minute.
     */
    val untilExclusive: LocalTime get() = entries[(ordinal + 1) % entries.size].from

    /**
     * Whether this moment is one of the day's three **meals**, as opposed to a snack.
     *
     * Not a hierarchy and not a rule: a snack takes exactly the same lines, the same totals and
     * the same add button as a meal, and nothing in the domain treats the two differently. It is
     * a fact about the day the `Day` screen needs and would otherwise invent — with a snack after
     * each meal, an ordinary day shows three moments that were eaten in and three that were not,
     * and drawing six full blocks makes the screen a third longer for nothing.
     *
     * Exhaustive rather than a set, so a seventh moment cannot be added without somebody deciding
     * which of the two it is.
     */
    val isMeal: Boolean get() = when (this) {
        BREAKFAST, LUNCH, DINNER -> true
        MORNING_SNACK, SNACK, EVENING_SNACK -> false
    }

    /** True of [EVENING_SNACK] alone: the only window whose end is not after its start. */
    val wrapsMidnight: Boolean get() = untilExclusive <= from

    companion object {
        private val byId: Map<String, MealSlot> = entries.associateBy { it.id }

        /**
         * Total and non-throwing. [SNACK] absorbs an unreadable id because it is the moment that
         * claims the least: an afternoon bite is the weakest thing a row can be said to be, and
         * the fallback has to be a value that misrepresents an unknown row as little as possible.
         *
         * It is also what an **older build** does with a moment it has never heard of. A phone
         * still on four moments reading `morning_snack` off the wire shows the line under
         * `Snack` — the line, its hour and every one of its values intact, in the wrong heading.
         * That is a demotion and not a loss, and it is the reason this stays a fallback rather
         * than becoming a refusal.
         */
        fun fromId(id: String): MealSlot = byId[id] ?: SNACK

        /**
         * PRD_FOOD 10.1: the moments always appear, in this order, filled or not.
         *
         * Declaration order **is** chronological order, which is what [untilExclusive] reads the
         * successor's bound from, so the display order and the partition cannot disagree.
         */
        val ORDERED: List<MealSlot> = entries.toList()

        /**
         * The moment PRD_FOOD 10.3 and FR-FOOD-007 preselect from a time of day. A default and
         * nothing more: every moment stays reachable in one gesture, and no entry is ever refused
         * for being logged outside its window.
         *
         * Each window is **closed at its start and open at its end**. The table of PRD_FOOD 10.3
         * writes them as `05:00 – 10:00`, but the sentence under it and the acceptance criterion
         * of PRD_FOOD 22 both require that "une pomme à dix heures tombe en collation" — so ten
         * o'clock sharp belongs to the moment that follows breakfast, not to breakfast. Read the
         * other way the two statements contradict each other; read half-open they agree, and
         * `14:00` still falls in lunch as the same sentence demands.
         *
         * **This is the rule any writer of a line leans on, human or otherwise.** A moment is
         * derived from the hour and is not a second thing to enter: the `Add food` sheet shows no
         * moment picker at all, and offers a discreet override for the midday meal genuinely
         * eaten at 11:30 or at 15:00.
         *
         * The server says the same thing to an agent, and must keep saying it: `mue.create_food_log`
         * takes its moment **optionally**, derives it from `consumedAt` through
         * `mealSlotForLocalTime` in `@mue/contracts`, and its description tells the agent to supply
         * one only when the meal was explicitly of a given type. Any food writing tool added later
         * owes the same three things — an agent handed a required enum and no guidance fills it in
         * plausibly and wrongly, and a yoghurt at ten becomes somebody's breakfast.
         */
        fun forTime(time: LocalTime): MealSlot {
            /*
             * Walked forwards from the first moment, so the answer is the last window whose start
             * the time has reached. A time before the first start — the small hours — has reached
             * none of them and belongs to the window that wrapped past midnight to meet it, which
             * is the last one. No branch names a moment, so adding or moving one changes nothing
             * here.
             */
            return entries.lastOrNull { time >= it.from } ?: entries.last()
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
