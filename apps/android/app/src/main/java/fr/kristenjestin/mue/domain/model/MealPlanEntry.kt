package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * What identifies a proposal: the date and the moment it occupies (PRD_FOOD 8.5 and 21.3).
 *
 * A slot holds **at most one** proposal, so the pair is the identity and no UUID has to be
 * invented for it — the same reasoning `Measurement` uses for its date, and the same reasoning
 * the server applies to `measurements`, keyed by `(user_id, date)`. Two devices proposing
 * something for Tuesday lunch address the same row and converge structurally; PRD_FOOD 21.3 then
 * only has to say which of the two mutations wins, with no merge heuristic to invent and no risk
 * of a duplicate slot.
 */
data class MealPlanKey(
    val plannedOn: LocalDate,
    val slot: MealSlot,
) {
    /**
     * The identity as the synchronisation layer addresses it, under
     * [FoodAggregates.TYPE_MEAL_PLAN_ENTRY].
     *
     * ISO date, a slash, and the slot's stable id — sortable, stable across devices, and
     * readable in an audit trail. [parseOrNull] is its exact inverse.
     */
    val aggregateId: String get() = "$plannedOn$SEPARATOR${slot.id}"

    companion object {
        private const val SEPARATOR: Char = '/'

        /**
         * Reads back what [aggregateId] wrote, and null for anything else.
         *
         * Total and non-throwing: an unparseable id arrives from the network, and a malformed
         * one is a proposal to ignore, never a crash on the day screen. Note that the slot side
         * cannot fail — `MealSlot.fromId` is total — so only the date can reject a string.
         */
        fun parseOrNull(aggregateId: String): MealPlanKey? {
            val separator = aggregateId.lastIndexOf(SEPARATOR)
            if (separator <= 0 || separator == aggregateId.lastIndex) return null
            val date = try {
                LocalDate.parse(aggregateId.substring(0, separator))
            } catch (_: DateTimeParseException) {
                return null
            }
            val slotId = aggregateId.substring(separator + 1)
            if (MealSlot.entries.none { it.id == slotId }) return null
            return MealPlanKey(date, MealSlot.fromId(slotId))
        }
    }
}

/**
 * A proposal for one moment (PRD_FOOD 8.5 and 12).
 *
 * Planning is not forecasting: PRD_FOOD 12 calls it the primer that makes the journal possible —
 * an empty day does not fill itself, a proposed day gets confirmed. A proposal therefore always
 * references a recipe; a plain food is logged directly and is never planned.
 *
 * It has **no identifier field**: its identity is [key], the `(date, slot)` pair. Proposing on an
 * occupied slot asks for confirmation in the interface and then replaces what was there, which
 * on the wire is the same aggregate written twice rather than two aggregates to reconcile.
 *
 * A proposal enters no total until it is confirmed (PRD_FOOD 12), which is why nothing here
 * carries a nutritional value.
 */
data class MealPlanEntry(
    val plannedOn: LocalDate,
    val slot: MealSlot,
    val recipeId: RecipeId,
    /** PRD_FOOD 15: the quarter-serving counter of a consumed portion, used ahead of time. */
    val plannedServings: Servings,
    /**
     * The line `I ate this` created (PRD_FOOD 12 and FR-PLAN-003). Deleting that line clears
     * this field and puts the proposal back in waiting; it never deletes the proposal.
     */
    val consumedLogEntryId: FoodLogEntryId? = null,
) {
    val key: MealPlanKey get() = MealPlanKey(plannedOn, slot)

    val aggregateId: String get() = key.aggregateId

    /** True once `I ate this` has been used; PRD_FOOD 10.1 then stops showing the card. */
    val isConsumed: Boolean get() = consumedLogEntryId != null

    companion object {
        /** PRD_FOOD 15: "Date proposée : aujourd'hui ou dans le futur, dans les 60 jours". */
        const val MAX_DAYS_AHEAD: Long = 60

        /** The mirror image of `FoodLogEntry.isLoggableOn`: never behind, never past 60 days. */
        fun isPlannableOn(date: LocalDate, today: LocalDate): Boolean =
            !date.isBefore(today) && !date.isAfter(today.plusDays(MAX_DAYS_AHEAD))
    }
}
