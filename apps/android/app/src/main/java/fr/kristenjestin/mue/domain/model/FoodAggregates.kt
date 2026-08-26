package fr.kristenjestin.mue.domain.model

/**
 * The four synchronised aggregates of the Food module (PRD_FOOD 21.2).
 *
 * They live here, in Food's own domain, and not in `SyncAggregateStateEntity`. That entity is
 * the server module's: it defines the envelope — revision, tombstone, origin, last mutation —
 * and it must not have to be reopened every time a business module gains an aggregate. A module
 * declares which aggregates it owns; the transport carries whatever it is given. PRD_FOOD 20.1
 * puts the two on separate schedules on purpose, and a constant declared here is a constant no
 * future module has to negotiate for.
 *
 * The four are exactly PRD_FOOD 21.2's list, and each name matches the camel-cased convention
 * the shipped aggregates already use (`measurement`, `healthProfile`, `activitySession`,
 * `customExerciseDefinition`):
 *
 * - [TYPE_FOOD] — the aliment alone. Only a personal or copied one is synchronised at all: the
 *   embedded Ciqual subset is versioned reference data, not personal data (PRD_FOOD 21.1), and
 *   PRD_FOOD 21.4 forbids even an authorised MCP client from touching it.
 * - [TYPE_RECIPE] — the recipe **with** its ingredients, atomically. It never travels without
 *   them, and PRD_FOOD 21.3 resolves it whole rather than row by row.
 * - [TYPE_FOOD_LOG_ENTRY] — the line alone, self-supporting because it carries its own frozen
 *   snapshot of values.
 * - [TYPE_MEAL_PLAN_ENTRY] — the proposal alone, addressed by the `(date, slot)` business key of
 *   [MealPlanKey] rather than by a generated id, so two devices converge on one slot instead of
 *   producing two proposals for it.
 */
object FoodAggregates {

    const val TYPE_FOOD: String = "food"

    const val TYPE_RECIPE: String = "recipe"

    const val TYPE_FOOD_LOG_ENTRY: String = "foodLogEntry"

    const val TYPE_MEAL_PLAN_ENTRY: String = "mealPlanEntry"

    /** In the order PRD_FOOD 21.2 lists them, which is also the order they depend on each other. */
    val ALL: List<String> = listOf(
        TYPE_FOOD,
        TYPE_RECIPE,
        TYPE_FOOD_LOG_ENTRY,
        TYPE_MEAL_PLAN_ENTRY,
    )

    /** True for the four types above and nothing else; the transport keeps its own registry. */
    fun isFoodAggregate(aggregateType: String): Boolean = aggregateType in ALL
}
