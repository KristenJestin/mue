package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.RecipeType
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * One of the six windows a moment occupies, **closed at its start and open at its end**.
 *
 * The half-open reading is not a choice made here: `MealSlot.forTime` already settled it in the
 * domain contract, because PRD_FOOD 10.3's table and PRD_FOOD 22's acceptance criterion
 * contradict each other read closed — "une pomme a dix heures tombe en collation" cannot be true
 * if `10:00` still belongs to breakfast. This class only gives that decision a name, so a screen
 * that wants to draw a window does not re-derive the bounds from a pile of loose constants.
 *
 * One window **crosses midnight** — `MealSlot.EVENING_SNACK`, `22:00 – 05:00` — and it is the
 * only one whose [contains] is not a plain `from <= t < untilExclusive`. Writing that case here,
 * once, is why no caller has to know which of the six it is holding.
 */
data class MealSlotWindow(val from: LocalTime, val untilExclusive: LocalTime) {

    /** True when the window ends earlier in the day than it starts, i.e. it runs past midnight. */
    val wrapsMidnight: Boolean get() = untilExclusive <= from

    /**
     * Half-open: [from] is inside, [untilExclusive] is not.
     *
     * A wrapping window is the union of its two halves rather than their intersection, which is
     * the whole of the difference: `01:00` is in `22:00 – 05:00` and in nothing else.
     */
    operator fun contains(time: LocalTime): Boolean = if (wrapsMidnight) {
        time >= from || time < untilExclusive
    } else {
        time >= from && time < untilExclusive
    }
}

/**
 * How a journal line finds its moment, and how the moments arrange the day (PRD_FOOD 10.1 to
 * 10.3, and 12 for the proposal that heads a slot).
 *
 * A slot is a label and a grouping, never an object: it has no identity, no values and no total
 * of its own at rest. Everything here is therefore a pure function of the lines it is handed,
 * recomputed on every read, which is exactly what lets a correction show up immediately
 * (PRD_FOOD 22: "le total d'un moment se recalcule a chaque ajout, correction et suppression").
 *
 * Nothing in this file ever *refuses* a line for falling outside its window. PRD_FOOD 10.3 is
 * explicit: the windows "ne creent aucune contrainte : elles ne font que choisir la valeur par
 * defaut". A midday meal genuinely eaten at 11:30 or at 15:00 is still a lunch, and that is the
 * case the `Add food` sheet's override exists for.
 */
object MealSlotRules {

    /**
     * PRD_FOOD 10.3 and FR-FOOD-007: the moment preselected from a time of day.
     *
     * Delegates to the contract rather than restating the bounds, so one reading of the half-open
     * windows exists in the module and not two.
     */
    fun slotFor(time: LocalTime): MealSlot = MealSlot.forTime(time)

    /**
     * The window a moment occupies. **Never null**, because there is no longer a moment that is
     * merely "everything else".
     *
     * It used to answer null for [MealSlot.SNACK], which PRD_FOOD 10.3 defined as "tout le
     * reste" — the complement of three named windows, and not an interval at all. The six
     * moments partition the clock, so the complement has a name of its own everywhere it used to
     * be nameless, and a screen can print every moment's hours instead of one of them reading
     * "any other time".
     */
    fun windowOf(slot: MealSlot): MealSlotWindow =
        MealSlotWindow(slot.from, slot.untilExclusive)

    /**
     * Whether a time falls in a moment's own window — informative only, never a rule for saving.
     *
     * Exactly one moment answers true for any given time, which keeps [slotFor] and this
     * predicate two readings of one partition rather than two rules that can drift.
     */
    fun isWithinWindow(slot: MealSlot, time: LocalTime): Boolean = time in windowOf(slot)

    /**
     * PRD_FOOD 8.4 stores a local time; the picker and the storage both work to the minute, so
     * a stray second never decides which of two lines is shown first.
     */
    fun normalize(time: LocalTime): LocalTime = time.truncatedTo(ChronoUnit.MINUTES)

    /**
     * PRD_FOOD 10.3: the default time of a new line.
     *
     * For today it is the current time. For a retroactive entry it is an hour **inside the chosen
     * moment** — `08:00`, `11:00`, `13:00`, `16:30`, `20:00`, `23:00` — and not the current time,
     * which would place yesterday's breakfast at ten in the evening and give a misleading
     * chronology.
     */
    fun defaultTime(
        slot: MealSlot,
        date: LocalDate,
        today: LocalDate,
        now: LocalTime,
    ): LocalTime = if (date == today) normalize(now) else slot.defaultTime

    /**
     * PRD_FOOD 10.1: the lines of a moment, ordered by time.
     *
     * `sortedBy` is stable, so two lines saved at the same minute keep the order they were given
     * instead of swapping between two reads of the same day.
     */
    fun sortedByTime(entries: List<FoodLogEntry>): List<FoodLogEntry> =
        entries.sortedBy { it.consumedAt }

    /** The lines of one moment, in order. Filtering by date is the caller's own business. */
    fun entriesIn(entries: List<FoodLogEntry>, slot: MealSlot): List<FoodLogEntry> =
        sortedByTime(entries.filter { it.slot == slot })

    /**
     * PRD_FOOD 10.1: every moment always appears, in [MealSlot.ORDERED] order, filled or not.
     *
     * Every slot is a key, so a screen iterating this map draws the empty ones too — an empty day
     * is six empty moments and their add buttons, never a blank screen.
     */
    fun groupBySlot(entries: List<FoodLogEntry>): Map<MealSlot, List<FoodLogEntry>> =
        MealSlot.ORDERED.associateWith { slot -> entriesIn(entries, slot) }

    /** PRD_FOOD 10.1: only lines of the selected day take part; the journal invents no day. */
    fun entriesOn(entries: List<FoodLogEntry>, date: LocalDate): List<FoodLogEntry> =
        entries.filter { it.consumedOn == date }

    /**
     * PRD_FOOD 8.5: a moment carries **at most one** proposal.
     *
     * The last one wins if a malformed set ever offers two, which is the direction PRD_FOOD 21.3
     * resolves a conflict in — the last accepted mutation — rather than a merge to invent.
     */
    fun planIn(plans: List<MealPlanEntry>, slot: MealSlot): MealPlanEntry? =
        plans.lastOrNull { it.slot == slot }

    /** PRD_FOOD 10.1: a confirmed proposal stops being shown; only the line it created remains. */
    fun pendingPlans(plans: List<MealPlanEntry>): List<MealPlanEntry> =
        plans.filterNot { it.isConsumed }

    /**
     * One proposal per moment, in [MealSlot.ORDERED] order — what a day screen actually draws.
     *
     * PRD_FOOD 12 keeps a proposal out of every total until it is confirmed, which is why this
     * returns proposals and never nutrients.
     */
    fun plansBySlot(plans: List<MealPlanEntry>): Map<MealSlot, MealPlanEntry?> =
        MealSlot.ORDERED.associateWith { slot -> planIn(plans, slot) }

    /**
     * The moments of a day that already hold a proposal — free or not, in one set.
     *
     * A **confirmed** proposal counts. PRD_FOOD 8.5 makes `(date, moment)` the primary key, so
     * the row is there whether or not `I ate this` has been pressed, and posing another proposal
     * on that moment still replaces it. Filtering to the pending ones here would have told the
     * planning sheet a moment was free and then silently overwritten a confirmed row.
     */
    fun plannedSlots(plans: List<MealPlanEntry>): Set<MealSlot> =
        plans.mapTo(mutableSetOf(), MealPlanEntry::slot)

    /**
     * Which moment a proposal opens on, when there is **no "now" to deduce one from**.
     *
     * [slotFor] is the journal's answer and it cannot be reused here: a line carries the hour it
     * was written at, and a proposal for next Thursday carries no hour at all — PRD_FOOD 8.5 gives
     * `MealPlanEntry` a moment and no time. Taking the current clock would put every meal planned
     * during an evening into `Dinner`, whatever the dish and whatever day it is for, which is a
     * default derived from a fact about the *planner* rather than about the plan.
     *
     * So the two things that do exist decide it:
     *
     * 1. **what the dish is.** PRD_FOOD 11 puts a `type de moment` on every recipe, and PRD_FOOD
     *    8.5 makes a proposal always reference one — a plain food is logged directly and is never
     *    planned. A breakfast recipe therefore opens on `Breakfast`;
     * 2. **what the day already holds.** A moment carries at most one proposal, so a candidate
     *    already taken is not offered first: planning two mains for one day lands the second on
     *    `Dinner` rather than asking to replace the lunch just posed.
     *
     * [RecipeType] has three values and the day six moments, so the mapping is a **preference
     * order** rather than a bijection — `Main` is lunch or dinner, which is the same dish at two
     * hours (PRD_FOOD 8.3), and only the day's own state can say which of the two is meant here.
     * When every candidate is taken the first is returned anyway: PRD_FOOD FR-PLAN-001 answers an
     * occupied moment with a confirmation and a replacement, never with a refusal, and the
     * override is one tap away in either case.
     *
     * This chooses a value and constrains nothing, exactly as PRD_FOOD 10.3's windows do for the
     * journal: any of the six stays reachable in one gesture.
     */
    fun plannedSlotFor(type: RecipeType, taken: Set<MealSlot> = emptySet()): MealSlot {
        val candidates = plannableSlotsFor(type)
        return candidates.firstOrNull { it !in taken } ?: candidates.first()
    }

    /**
     * The moments a recipe of this type is proposed into, best first.
     *
     * Exhaustive over [RecipeType] rather than a map, so a fourth type could not be added without
     * somebody saying where its dishes belong. Each list stays inside the family the type names —
     * a snack recipe never falls through to `Dinner` — because a wrong default that is *near*
     * the truth is corrected in one tap, and one that is not is simply confusing.
     */
    fun plannableSlotsFor(type: RecipeType): List<MealSlot> = when (type) {
        RecipeType.BREAKFAST -> listOf(MealSlot.BREAKFAST, MealSlot.MORNING_SNACK)
        RecipeType.MAIN -> listOf(MealSlot.LUNCH, MealSlot.DINNER)
        RecipeType.SNACK -> listOf(
            MealSlot.SNACK,
            MealSlot.MORNING_SNACK,
            MealSlot.EVENING_SNACK,
        )
    }
}
