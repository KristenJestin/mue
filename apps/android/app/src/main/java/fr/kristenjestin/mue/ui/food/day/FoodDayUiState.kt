package fr.kristenjestin.mue.ui.food.day

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.DailyNutritionSummary
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.MealSlotRules
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.ui.food.FoodIcons
import java.time.LocalDate
import java.util.Locale

/**
 * What the `Day` screen draws (PRD_FOOD 10.1): a date, and four moments under it.
 *
 * Every figure in here is already computed and already rendered. The totals come from
 * [DailyNutritionSummary], the grouping from [MealSlotRules], the strings from [FoodLabels] —
 * so the screen chooses a colour and a position and never a value. That is not tidiness: the
 * whole module's discipline is that `null` means unknown and `0` means a known zero, and a
 * screen that added `?: 0` anywhere would undo it at the last step, silently, in the one layer
 * nobody unit-tests. Rendering the strings *here* is what makes them provable on the JVM.
 *
 * There is no daily summary on this object's screen. PRD_FOOD 7 is explicit — "rien n'y résume
 * la journée" — and PRD_FOOD 22 makes it an acceptance criterion. [dayTotal] is still computed,
 * because PRD_FOOD 13.1's strict propagation has to hold for a day as much as for a moment and
 * because `Trends` reads exactly this number, but nothing on the screen prints it.
 */
@Immutable
data class FoodDayUiState(
    val date: LocalDate,
    val today: LocalDate,
    /** True while the day being shown has not been read back yet. */
    val isLoading: Boolean,
    /** `Today`, `Yesterday`, a weekday, or the date itself. */
    val dateLabel: String,
    /** The same day spelled in full, for a screen reader (PRD_FOOD 18). */
    val dateDescription: String,
    /** PRD_FOOD 10.1: the four moments, in order, filled or not. */
    val slots: List<FoodDaySlotUiState>,
    /**
     * The day's strict sum (PRD_FOOD 13.1), unknown as soon as one line's metric is.
     *
     * Computed and deliberately not drawn — see the note on the class. It is here so that the
     * propagation is testable at the screen's own boundary rather than only in the domain.
     *
     * A day with no line at all sums to a **known zero**, because an empty strict sum is zero.
     * That is arithmetic, not a reading: what may be shown is [dayEnergyLabel], which asks
     * [DailyNutritionSummary.recordedEnergy] and therefore answers `—` for a day nobody wrote
     * anything on. Reading the energy off this field instead would print `≈ 0 kcal` over an
     * untouched day, which is precisely the invented total PRD_FOOD 10.4 forbids.
     */
    val dayTotal: Nutrients,
    /**
     * The day's energy as it *would* read: `≈ 1204 kcal`, or `—`.
     *
     * `—` covers two different days — one with no line, and one whose lines leave the energy
     * unknown — and [isRecorded] is what tells them apart. Neither of them is a zero.
     */
    val dayEnergyLabel: String,
    val entryCount: Int,
    val isDatePickerVisible: Boolean,
) {

    val isToday: Boolean get() = date == today

    /** PRD_FOOD 10.4 and [DailyNutritionSummary.isRecorded]: an empty day is not a day worth 0. */
    val isRecorded: Boolean get() = entryCount > 0

    /**
     * PRD_FOOD 22: "un jour passé peut être complété ; un jour futur ne peut pas l'être".
     *
     * Asked of the domain rather than of `isBefore`, so the screen and the storage refuse the
     * same days. Stepping forward from today would land on a date no line may be written to.
     */
    val canGoForward: Boolean get() = FoodLogEntry.isLoggableOn(date.plusDays(1), today)

    /** Backwards is always open: the journal has no floor, only a ceiling. */
    val canGoBack: Boolean get() = true

    companion object {

        /**
         * The whole screen from one day's rows.
         *
         * [entries] and [plans] may cover any span; only what belongs to [date] takes part, and
         * that filtering is [MealSlotRules]' and [DailyNutritionSummary]'s, not this function's.
         *
         * [recipeNames] resolves what a proposal points at. A name that is missing is not a
         * name invented: the card says so (PRD_FOOD 17, "recette supprimée mais proposée").
         */
        fun of(
            date: LocalDate,
            today: LocalDate,
            entries: List<FoodLogEntry> = emptyList(),
            plans: List<MealPlanEntry> = emptyList(),
            recipeNames: Map<RecipeId, String> = emptyMap(),
            isLoading: Boolean = false,
            isDatePickerVisible: Boolean = false,
            locale: Locale = Locale.getDefault(),
        ): FoodDayUiState {
            val summary = DailyNutritionSummary.of(date, entries)
            val onDay = MealSlotRules.entriesOn(entries, date)
            val bySlot = MealSlotRules.groupBySlot(onDay)
            val pending = MealSlotRules.plansBySlot(
                MealSlotRules.pendingPlans(plans).filter { it.plannedOn == date },
            )

            return FoodDayUiState(
                date = date,
                today = today,
                isLoading = isLoading,
                dateLabel = FoodDayFormat.dayLabel(date, today, locale),
                dateDescription = FoodDayFormat.dayDescription(date, today, locale),
                slots = MealSlot.ORDERED.map { slot ->
                    FoodDaySlotUiState.of(
                        slot = slot,
                        entries = bySlot[slot].orEmpty(),
                        total = summary.totalIn(slot),
                        plan = pending[slot],
                        recipeNames = recipeNames,
                        locale = locale,
                    )
                },
                dayTotal = summary.total,
                dayEnergyLabel = FoodLabels.energy(summary.recordedEnergy),
                entryCount = summary.entryCount,
                isDatePickerVisible = isDatePickerVisible,
            )
        }
    }
}

/**
 * One of PRD_FOOD 10.1's four moments: its proposal, its lines, its own total, its add button.
 *
 * [totalLabel] is null exactly while the moment holds no line. PRD_FOOD 10.1 shows a moment's
 * total "lorsqu'il contient au moins une ligne" and PRD_FOOD 10.4 forbids inventing one, so an
 * empty breakfast is a heading and an invitation — not `0 kcal`, and not `—` either. Those are
 * three different facts and they read three different ways on screen.
 */
@Immutable
data class FoodDaySlotUiState(
    val slot: MealSlot,
    val label: String,
    val iconName: String,
    /** PRD_FOOD 10.1: sorted by time, which [MealSlotRules.groupBySlot] already did. */
    val entries: List<FoodDayEntryUiState>,
    /** `≈ 369 kcal` or `—`; null when there is nothing to total. */
    val totalLabel: String?,
    /** `≈ 29.1 g protein` or `— protein`; null alongside [totalLabel]. */
    val proteinLabel: String?,
    /** PRD_FOOD 12: the unconfirmed proposal that heads the moment, if there is one. */
    val plan: FoodDayPlanUiState?,
    /** PRD_FOOD 18: the moment, its count and its total announced as one unit. */
    val description: String,
    /** The add button's own words, which change once the moment holds something. */
    val addLabel: String,
) {

    val isEmpty: Boolean get() = entries.isEmpty()

    val hasTotal: Boolean get() = totalLabel != null

    companion object {

        fun of(
            slot: MealSlot,
            entries: List<FoodLogEntry>,
            total: Nutrients?,
            plan: MealPlanEntry?,
            recipeNames: Map<RecipeId, String>,
            locale: Locale = Locale.getDefault(),
        ): FoodDaySlotUiState {
            val rows = entries.map { FoodDayEntryUiState.of(it, locale) }
            val energy = total?.let(FoodDayFormat::energy)
            val protein = total?.let(FoodDayFormat::protein)
            return FoodDaySlotUiState(
                slot = slot,
                label = slot.label,
                iconName = FoodIcons.forSlot(slot),
                entries = rows,
                totalLabel = energy,
                proteinLabel = protein,
                plan = plan?.let { FoodDayPlanUiState.of(it, recipeNames) },
                description = FoodDayFormat.sentence(
                    slot.label,
                    FoodDayMessages.entryCount(rows.size),
                    energy?.let(FoodDayFormat::spoken),
                    protein?.let(FoodDayFormat::spoken),
                ),
                addLabel = if (rows.isEmpty()) {
                    FoodDayMessages.ADD_FIRST
                } else {
                    FoodDayMessages.ADD_MORE
                },
            )
        }
    }
}

/**
 * One journal line, in any of PRD_FOOD 10.2's three forms (`FOOD`, `RECIPE`, `QUICK`).
 *
 * The values are the ones frozen on the line (PRD_FOOD 8.4). Nothing here reopens the food or
 * the recipe it came from, and nothing recomputes a contribution: correcting a food later never
 * completes a line already written.
 */
@Immutable
data class FoodDayEntryUiState(
    val id: FoodLogEntryId,
    val title: String,
    val iconName: String,
    /** PRD_FOOD 10.3: what orders the moment, and PRD_FOOD 18 announces with the line. */
    val timeLabel: String,
    /** PRD_FOOD 13.2's two readings, kept as they were saved; null for a quick add. */
    val amountLabel: String?,
    /** `≈ 369 kcal`, or `—`. */
    val energyLabel: String,
    /** `≈ 29.1 g protein`, or `— protein`. */
    val proteinLabel: String,
    /** PRD_FOOD 18: what a screen reader says instead of the four fragments above. */
    val description: String,
) {
    companion object {

        fun of(entry: FoodLogEntry, locale: Locale = Locale.getDefault()): FoodDayEntryUiState {
            /*
             * The saved label wins because PRD_FOOD 13.2's two readings — `1.5 × apple (225 g)`
             * — need the food's own serving word, which a journal line does not carry. When the
             * line was written without one, `FoodLabels` draws what the stored amount can say on
             * its own rather than the screen assembling a number and a unit by hand.
             */
            val amount = entry.amountLabel?.takeIf { it.isNotBlank() }
                ?: FoodLabels.amountLabel(entry.amount, portions = entry.portions)
            val energy = FoodDayFormat.energy(entry.nutrients)
            val protein = FoodDayFormat.protein(entry.nutrients)
            val time = FoodDayFormat.time(entry.consumedAt, locale)
            return FoodDayEntryUiState(
                id = entry.id,
                title = entry.title,
                iconName = FoodIcons.forKind(entry.kind),
                timeLabel = time,
                amountLabel = amount,
                energyLabel = energy,
                proteinLabel = protein,
                description = FoodDayFormat.sentence(
                    entry.title,
                    time,
                    amount,
                    FoodDayFormat.spoken(energy),
                    FoodDayFormat.spoken(protein),
                ),
            )
        }
    }
}

/**
 * The unconfirmed proposal at the head of a moment (PRD_FOOD 12).
 *
 * It carries no nutritional value at all, and that is the rule rather than an omission: "une
 * proposition n'entre dans aucun total tant qu'elle n'est pas confirmée". Showing its energy
 * beside a real line's would invite the eye to add the two.
 *
 * [key] is the whole identity — PRD_FOOD 8.5 makes `(date, moment)` the proposal — so the three
 * actions address it without a generated id ever existing.
 */
@Immutable
data class FoodDayPlanUiState(
    val key: MealPlanKey,
    val recipeName: String,
    /** `1.5`, with the serving noun already agreed. */
    val servingsLabel: String,
    /** PRD_FOOD 18: `Suggested` is spoken, so the card is never told apart by colour alone. */
    val description: String,
) {
    companion object {

        fun of(
            plan: MealPlanEntry,
            recipeNames: Map<RecipeId, String>,
        ): FoodDayPlanUiState {
            val name = recipeNames[plan.recipeId] ?: FoodDayMessages.MISSING_RECIPE
            val servings = FoodDayMessages.servings(plan.plannedServings)
            return FoodDayPlanUiState(
                key = plan.key,
                recipeName = name,
                servingsLabel = servings,
                description = FoodDayFormat.sentence(
                    FoodDayMessages.SUGGESTED,
                    name,
                    servings,
                ),
            )
        }
    }
}
