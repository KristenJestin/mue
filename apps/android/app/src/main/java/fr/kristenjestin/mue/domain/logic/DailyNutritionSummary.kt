package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import java.time.LocalDate

/**
 * One moment of a day that actually holds something (PRD_FOOD 10.1).
 *
 * It exists only for slots with at least one line, because PRD_FOOD 10.1 shows a moment's own
 * total "lorsqu'il contient au moins une ligne" and PRD_FOOD 10.4 forbids inventing one:
 * an empty breakfast is a heading and an add button, not a `0 kcal`.
 */
data class MealSlotTotal(
    val slot: MealSlot,
    /** A local addition of this moment's lines, never a share of a day (PRD_FOOD 10.1). */
    val total: Nutrients,
    val entryCount: Int,
)

/**
 * What one day of the journal is worth (PRD_FOOD 10.1 and 13.1).
 *
 * Four states matter here, and only the first two are usually noticed:
 *
 * 1. **not recorded** — no line at all. [isRecorded] is false, and PRD_FOOD 10.4 says the day
 *    "reste vide" rather than showing a zero.
 * 2. **recorded, energy known** — the ordinary case, and the only one that feeds a bar or an
 *    average.
 * 3. **recorded, energy unknown** — one line carried an unknown energy, so PRD_FOOD 13.1 makes
 *    the whole day's energy `null`. The day *is* recorded: it counts in `daysRecorded`, its
 *    lines are there, its other metrics may well be known — but PRD_FOOD 22 keeps it out of
 *    "une barre de `Trends`" and out of "une moyenne". Confusing this with state 1 is the
 *    mistake this class is shaped to prevent.
 * 4. **recorded, some metrics known and others not** — the metric-by-metric case of PRD_FOOD
 *    13.1: a known energy coexists with unknown protein, and the day's protein alone is `—`.
 *
 * [total] is the strict sum of [Nutrients.strictSum], so a day with no line is a *known* zero
 * and a day with one unknown line is unknown. Which of the two a screen may show is [isRecorded]'s
 * answer, not the total's.
 */
data class DailyNutritionSummary(
    val date: LocalDate,
    /** Only the moments that carry a line, in [MealSlot.ORDERED] order. */
    val slots: List<MealSlotTotal>,
    val total: Nutrients,
    val entryCount: Int,
) {
    /** PRD_FOOD 10.4: a day with no line is not a day worth zero. */
    val isRecorded: Boolean get() = entryCount > 0

    /**
     * The energy that may enter a bar or an average: known **and** actually recorded.
     *
     * The two conditions are separate facts and both are needed. An empty day sums to a known
     * zero, which would otherwise drag every average down towards nothing.
     */
    val recordedEnergy: Energy? get() = if (isRecorded) total.energy else null

    /** PRD_FOOD 13.1: an unknown energy "n'alimente ni la hauteur d'une barre ni une moyenne". */
    val countsTowardsEnergyAverage: Boolean get() = recordedEnergy != null

    /** Null when the moment holds nothing, which is how PRD_FOOD 10.1 avoids an invented total. */
    fun slotTotalIn(slot: MealSlot): MealSlotTotal? = slots.firstOrNull { it.slot == slot }

    fun totalIn(slot: MealSlot): Nutrients? = slotTotalIn(slot)?.total

    companion object {
        /**
         * [entries] may cover any span; only the lines stored on [date] are counted, on the
         * stored local date alone so no time zone takes part (PRD_FOOD 10.1).
         */
        fun of(date: LocalDate, entries: List<FoodLogEntry>): DailyNutritionSummary {
            val onDay = MealSlotRules.entriesOn(entries, date)
            val slots = MealSlot.ORDERED.mapNotNull { slot ->
                val lines = onDay.filter { it.slot == slot }
                if (lines.isEmpty()) {
                    null
                } else {
                    MealSlotTotal(slot, NutritionMath.total(lines), lines.size)
                }
            }
            return DailyNutritionSummary(
                date = date,
                slots = slots,
                total = NutritionMath.total(onDay),
                entryCount = onDay.size,
            )
        }

        /** A day nobody wrote anything on: no moment, no line, and a total nothing may show. */
        fun empty(date: LocalDate): DailyNutritionSummary = of(date, emptyList())
    }
}

/**
 * The seven days of `Trends` (PRD_FOOD 10.5): "une barre par jour, la moyenne des jours
 * renseignes, le nombre de jours renseignes, le nombre de lignes, et l'historique cliquable".
 *
 * No other series appears here. PRD_FOOD 10.4 and 22 forbid putting energy spent, a goal or a
 * comparison of two days beside it; the confrontation with activity belongs to `Progress`.
 *
 * Every day of the window is present in [days], recorded or not, so a bar chart draws seven
 * columns rather than as many as happen to have data.
 */
data class NutritionTrend(
    val window: DateWindow,
    /** Oldest first, one per day of [window]. */
    val days: List<DailyNutritionSummary>,
) {
    /** PRD_FOOD 10.5: "le nombre de jours renseignes" — recorded, whatever their energy is worth. */
    val daysRecorded: Int get() = days.count { it.isRecorded }

    /** PRD_FOOD 10.5: "le nombre de lignes". */
    val entryCount: Int get() = days.sumOf { it.entryCount }

    val hasAnyRecord: Boolean get() = daysRecorded > 0

    /**
     * PRD_FOOD 10.5 and 13.1: the mean over the recorded days **whose energy is known**.
     *
     * A recorded day with an unknown energy is excluded from the divisor as well as from the sum:
     * counting it below would report an average that no day of the week resembles. Null when no
     * day qualifies, which the screen shows as `—`.
     */
    val averageEnergy: Energy?
        get() {
            val known = recordedEnergies
            if (known.isEmpty()) return null
            val sum = known.sumOf { it.milliKcal.toLong() }
            return Energy.ofMilliKcalOrNull((sum + known.size / 2) / known.size)
        }

    /** The tallest bar, which the others are drawn against. Null when nothing may be drawn. */
    val highestEnergy: Energy? get() = recordedEnergies.maxOrNull()

    /** The day of [days] at [index], or null outside the window. */
    fun dayAt(index: Int): DailyNutritionSummary? = days.getOrNull(index)

    fun dayOn(date: LocalDate): DailyNutritionSummary? = days.firstOrNull { it.date == date }

    /** How tall the bar at [index] is drawn, from 0 to 1. */
    fun barFraction(index: Int): Double = fractionOf(days.getOrNull(index))

    /**
     * [NO_BAR] for a day that was never recorded **and** for a recorded day whose energy is
     * unknown — PRD_FOOD 22: "une journee dont l'energie est inconnue n'entre ni dans une barre
     * de `Trends` ni dans une moyenne".
     *
     * The height is zero; the value is not. Nothing here writes a `0` into an energy, and
     * [DailyNutritionSummary.recordedEnergy] stays null for exactly those days.
     */
    fun fractionOf(day: DailyNutritionSummary?): Double {
        val energy = day?.recordedEnergy ?: return NO_BAR
        val tallest = highestEnergy ?: return NO_BAR
        if (tallest.milliKcal <= 0) return NO_BAR
        return energy.milliKcal.toDouble() / tallest.milliKcal
    }

    private val recordedEnergies: List<Energy> get() = days.mapNotNull { it.recordedEnergy }

    companion object {
        /** PRD_FOOD 10.5: "sept jours de ce qui a ete enregistre", today included. */
        const val DAYS: Int = 7

        /**
         * A bar of no height. It is a *drawing* instruction and never a nutritional value: the
         * day it describes keeps a null energy, which is what PRD_FOOD 13.2 shows as `—`.
         */
        const val NO_BAR: Double = 0.0

        /** The seven days ending on [today], which is what `Trends` opens on. */
        fun of(entries: List<FoodLogEntry>, today: LocalDate): NutritionTrend =
            of(entries, today.minusDays((DAYS - 1).toLong()), today)

        /**
         * Any bounded window, oldest day first. An [endInclusive] before [start] yields no day at
         * all rather than a reversed range.
         */
        fun of(
            entries: List<FoodLogEntry>,
            start: LocalDate,
            endInclusive: LocalDate,
        ): NutritionTrend {
            val byDate = entries.groupBy { it.consumedOn }
            val days = generateSequence(start) { it.plusDays(1) }
                .takeWhile { !it.isAfter(endInclusive) }
                .map { date -> DailyNutritionSummary.of(date, byDate[date].orEmpty()) }
                .toList()
            return NutritionTrend(DateWindow.of(start, endInclusive), days)
        }

        /** Seven empty days: the state PRD_FOOD 17 shows when nothing has been logged at all. */
        fun empty(today: LocalDate): NutritionTrend = of(emptyList(), today)
    }
}
