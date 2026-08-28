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
 * What the `Day` screen draws (PRD_FOOD 10.1): a date, and six moments under it.
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
    /**
     * The six moments, in order, filled or not.
     *
     * All six are still built, because the six are what the domain groups by and what
     * [MealSlotRules] answers with. What the screen draws is [visibleSlots]; keeping the whole
     * six here is what lets a test ask about a moment that is currently drawing nothing.
     */
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
     * The moments the screen actually draws: the ones that hold something.
     *
     * **This supersedes PRD_FOOD 10.1's "toujours présent".** That section keeps every moment on
     * screen with an add row inside it, so that adding to breakfast is always the same gesture in
     * the same place. Six moments each drawing a heading over an invitation is what the owner was
     * looking at when he wrote *"j'ai « lunch », et les snacks sont grisés ? […] est-ce qu'on
     * pourrait pas imaginer juste avoir les headers […] uniquement quand il y a un élément
     * dedans"*. His instruction wins, and [FoodDayUiState.addLabel]'s one action is what replaces
     * the six.
     *
     * What is lost is named rather than glossed: adding **deliberately** to a moment other than
     * the one the clock is in used to be a single tap on that moment's `+`. It is now the add
     * sheet's moment override — one extra step — and it is the same step FR-FOOD-007 already
     * required of every other way into the sheet.
     */
    val visibleSlots: List<FoodDaySlotUiState> get() = slots.filter(FoodDaySlotUiState::hasContent)

    /** True while the day holds neither a line nor a proposal, and the screen has nothing to list. */
    val isBlank: Boolean get() = visibleSlots.isEmpty()

    /**
     * The one add action's words (PRD_FOOD 17), which are the moment's old pair moved up a level.
     *
     * `Add something` and `Add something else` never claimed a tense and never named what they
     * added to — the heading above them did. With one action for the whole day there is no
     * heading above it, and that is the point: the moment is no longer chosen here at all. The
     * hour decides it on the sheet (FR-FOOD-007), which is the rule the per-moment `+` was
     * quietly overriding.
     */
    val addLabel: String
        get() = when {
            /*
             * PRD_FOOD 12: on a day the journal cannot take, the same action plans. It says so,
             * because a button that wrote a proposal while promising an entry would be the exact
             * inverse of the mistake `ADD_FIRST` was written to end.
             *
             * The pair turns on whether anything is *drawn* rather than on [isRecorded], which
             * counts journal lines and is always zero here. A day already carrying a proposal has
             * something on it, and `Plan something else` is what a reader looking at one expects.
             */
            isPlanning -> if (isBlank) FoodDayMessages.PLAN_FIRST else FoodDayMessages.PLAN_MORE
            isRecorded -> FoodDayMessages.ADD_MORE
            else -> FoodDayMessages.ADD_FIRST
        }

    /**
     * Whether the one action at the foot of the screen poses a **proposal** (PRD_FOOD 12).
     *
     * A day the journal refuses and a proposal may sit on leaves the action one honest meaning,
     * so it takes it rather than going grey. The two predicates are the domain's own and are read
     * in the only order that can be true at once: a day is never both.
     */
    val isPlanning: Boolean get() = !canLog && canPlan

    /**
     * Whether the day's one action does anything at all.
     *
     * It used to be [canLog] alone, which is what made a future day a screen with a dead button
     * under a note explaining that its moments "can carry a suggestion" — a sentence with no
     * gesture behind it anywhere in the module. It is now either rule: write a line, or pose a
     * proposal. Beyond the sixtieth day ahead neither holds, and no route reaches such a day.
     */
    val canAdd: Boolean get() = canLog || canPlan

    /**
     * PRD_FOOD 22: "un jour passé peut être complété ; un jour futur ne peut pas l'être".
     *
     * The **journal's** rule, and only the journal's. Asked of the domain rather than of
     * `isAfter`, so the screen and the storage refuse the same days.
     */
    val canLog: Boolean get() = FoodLogEntry.isLoggableOn(date, today)

    /**
     * PRD_FOOD 12 and 15: "date proposée : aujourd'hui ou dans le futur, dans les 60 jours".
     *
     * The other rule, which is not the journal's and never was. It is what a day ahead is *for* —
     * PRD_FOOD 12 calls planning "l'amorce qui rend le journal possible" — and it is the half the
     * day screen used to have no way of reaching.
     */
    val canPlan: Boolean get() = MealPlanEntry.isPlannableOn(date, today)

    /**
     * A day the module has something to say about, in either direction.
     *
     * This is the whole of the second finding. The forward step, the calendar and the day the
     * ViewModel accepts were **all** gated on [FoodLogEntry.isLoggableOn] — the journal's ceiling
     * — so no day after today could be reached by any route. `MealPlanEntry.isPlannableOn` allowed
     * sixty days ahead, `MealSlotRules.pendingPlans` was already being rendered and the proposal
     * card with its three actions was already drawn, and none of it could ever appear: nothing
     * could be planned because nowhere could be planned *on*.
     *
     * Reading the two domain predicates together is the separation. Nothing new is decided here
     * and no bound is restated: a day is reachable when the journal will take it or when a
     * proposal may sit on it, and both halves stay exactly where they were written.
     */
    val isReachable: Boolean get() = canLog || canPlan

    /** PRD_FOOD 10.1: the date navigation, which now reaches as far as a proposal may. */
    val canGoForward: Boolean get() = isReachable(date.plusDays(1), today)

    /** Backwards is always open: the journal has no floor, only a ceiling. */
    val canGoBack: Boolean get() = true

    companion object {

        /**
         * [isReachable] for a day the screen is not currently showing.
         *
         * The date navigation, the calendar's selectable days and the ViewModel's own guard all
         * ask this one function, so a day offered in the grid is a day the arrows reach is a day
         * the ViewModel will accept. Three readings of one rule was how the module ended up with
         * a planning feature nobody could get to.
         */
        fun isReachable(date: LocalDate, today: LocalDate): Boolean =
            FoodLogEntry.isLoggableOn(date, today) || MealPlanEntry.isPlannableOn(date, today)

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
                        canLog = FoodLogEntry.isLoggableOn(date, today),
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
 * One of PRD_FOOD 10.1's six moments: its proposal, its lines and its own total.
 *
 * It no longer carries an add button. The day carries one for all six
 * ([FoodDayUiState.addLabel]), and a moment appears at all only once it holds something
 * ([hasContent]) — so this object is a description of what was eaten rather than an offer to eat.
 *
 * [totalLabel] is null exactly while the moment holds no line. PRD_FOOD 10.1 shows a moment's
 * total "lorsqu'il contient au moins une ligne" and PRD_FOOD 10.4 forbids inventing one, so a
 * moment holding only a proposal is a heading with no figure beside it — not `0 kcal`, and not
 * `—` either. Those are three different facts and they read three different ways on screen.
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
) {

    val isEmpty: Boolean get() = entries.isEmpty()

    val hasTotal: Boolean get() = totalLabel != null

    /**
     * Whether this moment is drawn at all.
     *
     * A heading appears when its moment has something under it, and not before — which is the
     * owner's instruction, and what removed the six greyed-out invitations he was reading. A
     * proposal counts as something: PRD_FOOD 12 puts it *at the head of* the moment, so a dinner
     * that has been suggested but not eaten is a dinner worth naming.
     *
     * This is what the **fold used to pay for**. An empty snack was drawn as one quiet row rather
     * than a whole block, so that six moments did not make an untouched day three screens tall.
     * A moment that is not drawn at all costs nothing, so the fold has nothing left to buy and
     * has gone with the add rows it existed to compress.
     */
    val hasContent: Boolean get() = entries.isNotEmpty() || plan != null

    companion object {

        fun of(
            slot: MealSlot,
            entries: List<FoodLogEntry>,
            total: Nutrients?,
            plan: MealPlanEntry?,
            recipeNames: Map<RecipeId, String>,
            canLog: Boolean = true,
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
                plan = plan?.let { FoodDayPlanUiState.of(it, recipeNames, canConfirm = canLog) },
                description = FoodDayFormat.sentence(
                    slot.label,
                    FoodDayMessages.entryCount(rows.size),
                    energy?.let(FoodDayFormat::spoken),
                    protein?.let(FoodDayFormat::spoken),
                ),
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
    /**
     * FR-PLAN-003 against PRD_FOOD 22: whether `I ate this` is offered at all.
     *
     * False on a proposal sitting on a day still to come. Confirming writes a **journal line**
     * dated on the proposal's own day, and PRD_FOOD 22 refuses one there — so the action is not
     * shown rather than shown and refused. `Swap` and `Dismiss` stay: replacing tomorrow's dinner
     * and freeing tomorrow's slot are both things you can do today.
     *
     * The claim it would make is the plainer objection: a card cannot ask "did you eat this?"
     * about Thursday.
     */
    val canConfirm: Boolean,
) {
    companion object {

        fun of(
            plan: MealPlanEntry,
            recipeNames: Map<RecipeId, String>,
            canConfirm: Boolean = true,
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
                canConfirm = canConfirm,
            )
        }
    }
}
