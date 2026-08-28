package fr.kristenjestin.mue.ui.food.day

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.testing.LocaleRule
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = FoodDayPreviewData.TODAY
private val YESTERDAY: LocalDate = TODAY.minusDays(1)

/**
 * What the `Day` screen is handed, and the one rule it exists to keep: PRD_FOOD 13.1's `null`
 * means unknown, `0` means a known zero, and no code path turns either into the other.
 *
 * Every assertion here is about a *rendered string*, because that is where the rule is finally
 * either honoured or lost. The domain proves the arithmetic; this file proves that nothing
 * between the arithmetic and the glass adds a fallback.
 */
class FoodDayUiStateTest {

    /** The day the screen shows must not follow the phone's region (PRD_FOOD 13.2). */
    @get:Rule
    val locale = LocaleRule(Locale.UK)

    // region unknown, zero, and nothing at all

    /**
     * The three readings of a moment, side by side.
     *
     * A moment with nothing in it shows **no total**; a moment holding a known zero shows a
     * zero; a moment holding an unknown shows `—`. Collapsing any two of the three would make
     * the module unable to say what it does and does not know.
     */
    @Test
    fun `nothing logged, a known zero and an unknown are three different readings`() {
        val state = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            entries = listOf(
                FoodDayPreviewData.espresso(),
                FoodDayPreviewData.tiramisu(),
            ),
        )

        val breakfast = state.slot(MealSlot.BREAKFAST)
        val lunch = state.slot(MealSlot.LUNCH)
        val snack = state.slot(MealSlot.SNACK)

        // Nothing logged: no total at all, not a zero and not a dash.
        assertNull(breakfast.totalLabel, "an empty moment invented a total")
        assertNull(breakfast.proteinLabel)
        assertFalse(breakfast.hasTotal)

        // A known zero: black coffee really has no energy and no protein.
        assertEquals("≈ 0 kcal", lunch.totalLabel)
        assertEquals("≈ 0.0 g protein", lunch.proteinLabel)

        // Unknown: nobody wrote the tiramisu's protein down.
        assertEquals("≈ 420 kcal", snack.totalLabel)
        assertEquals("${FoodLabels.UNKNOWN} protein", snack.proteinLabel)

        assertNotEquals(lunch.proteinLabel, snack.proteinLabel)
    }

    /**
     * PRD_FOOD 22: "un aliment dont l'énergie est inconnue rend inconnu le total de son moment,
     * sans rendre inconnues les autres métriques de ce moment".
     */
    @Test
    fun `an unknown energy leaves the other metrics of its moment known`() {
        val known = FoodDayPreviewData.breakfast()
        val energyless = known.copy(
            id = FoodLogEntryId("no-energy"),
            nutrients = known.nutrients.copy(energy = null),
        )

        val breakfast = FoodDayUiState
            .of(date = TODAY, today = TODAY, entries = listOf(known, energyless))
            .slot(MealSlot.BREAKFAST)

        assertEquals(FoodLabels.UNKNOWN, breakfast.totalLabel)
        assertEquals("≈ 58.2 g protein", breakfast.proteinLabel)
    }

    /**
     * A day nobody wrote anything on and a day whose energy is unknown both read `—`.
     *
     * They are still not the same day, and the difference is [FoodDayUiState.isRecorded] —
     * PRD_FOOD 10.4's "un jour sans saisie reste vide" against PRD_FOOD 13.1's unknown total.
     * An empty day sums to a *known* zero, so reading the energy off the raw total would print
     * `≈ 0 kcal` over an untouched journal.
     */
    @Test
    fun `an empty day and a day of unknown energy read alike but are not the same day`() {
        val empty = FoodDayUiState.of(date = TODAY, today = TODAY)
        val unknown = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            entries = listOf(
                FoodDayPreviewData.breakfast().copy(nutrients = Nutrients.UNKNOWN),
            ),
        )

        assertEquals(FoodLabels.UNKNOWN, empty.dayEnergyLabel)
        assertEquals(FoodLabels.UNKNOWN, unknown.dayEnergyLabel)

        assertFalse(empty.isRecorded)
        assertTrue(unknown.isRecorded)
        assertEquals(Energy.ZERO, empty.dayTotal.energy, "an empty sum is a known zero")
        assertNull(unknown.dayTotal.energy, "one unknown line makes the day's energy unknown")
    }

    /** PRD_FOOD 13.2: an unknown is `—` at every level, and never rendered as `0`. */
    @Test
    fun `no rendered value ever falls back to zero`() {
        val state = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            entries = listOf(FoodDayPreviewData.tiramisu()),
        )
        val line = state.slot(MealSlot.SNACK).entries.single()

        assertEquals("${FoodLabels.UNKNOWN} protein", line.proteinLabel)
        assertFalse(line.proteinLabel.contains('0'))
        assertTrue(line.description.contains(FoodDayFormat.UNKNOWN_SPOKEN))
    }

    // endregion

    // region the six moments (PRD_FOOD 10.1)

    @Test
    fun `the six moments are always there, in order, filled or not`() {
        val state = FoodDayUiState.of(date = TODAY, today = TODAY)

        assertEquals(MealSlot.ORDERED, state.slots.map { it.slot })
        assertTrue(state.slots.all { it.isEmpty })
        assertTrue(state.slots.all { it.totalLabel == null })
        assertEquals(0, state.entryCount)
    }

    @Test
    fun `a moment orders its lines by time and keeps them out of the others`() {
        val late = FoodDayPreviewData.espresso()
        val early = FoodDayPreviewData.lunch()

        val lunch = FoodDayUiState
            .of(date = TODAY, today = TODAY, entries = listOf(late, early))
            .slot(MealSlot.LUNCH)

        assertEquals(listOf(early.id, late.id), lunch.entries.map { it.id })
        assertEquals(2, lunch.entries.size)
    }

    /** PRD_FOOD 10.1: only the selected day takes part; the journal invents no day. */
    @Test
    fun `a line from another day never reaches this one`() {
        val state = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            entries = listOf(FoodDayPreviewData.breakfast(YESTERDAY)),
        )

        assertEquals(0, state.entryCount)
        assertTrue(state.slots.all { it.isEmpty })
    }

    /**
     * PRD_FOOD 17: the day's one action invites, and changes its words once the day holds a line.
     *
     * The pair used to belong to each moment; there is one action for the whole day now, so the
     * label is asked of the day. `Add something` on an untouched day, `Add something else` once
     * anything has been written — the same two sentences, one level up.
     */
    @Test
    fun `the add action says what the day is for and then says what else it takes`() {
        val empty = FoodDayUiState.of(date = TODAY, today = TODAY)
        assertEquals(FoodDayMessages.ADD_FIRST, empty.addLabel)

        val filled = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            entries = listOf(FoodDayPreviewData.breakfast()),
        )
        assertEquals(FoodDayMessages.ADD_MORE, filled.addLabel)
    }

    /**
     * A day carrying only a proposal has still had nothing *written* on it.
     *
     * PRD_FOOD 12: "une proposition n'entre dans aucun total tant qu'elle n'est pas confirmée",
     * and it is not an entry either — so the action still offers the first line rather than
     * another one. The moment is drawn all the same, which is the other half of the pair.
     */
    @Test
    fun `a day holding only a proposal still offers the first line`() {
        val state = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            plans = FoodDayPreviewData.plans(TODAY),
            recipeNames = FoodDayPreviewData.recipeNames,
        )

        assertEquals(FoodDayMessages.ADD_FIRST, state.addLabel)
        assertFalse(state.isRecorded)
        assertFalse(state.isBlank, "a proposal is something to draw")
    }

    // endregion

    // region proposals (PRD_FOOD 12)

    @Test
    fun `an unconfirmed proposal heads its moment and carries no value`() {
        val plan = FoodDayUiState
            .of(
                date = TODAY,
                today = TODAY,
                plans = FoodDayPreviewData.plans(),
                recipeNames = FoodDayPreviewData.recipeNames,
            )
            .slot(MealSlot.DINNER)

        val card = requireNotNull(plan.plan)
        assertEquals(FoodDayPreviewData.PLANNED_RECIPE, card.recipeName)
        assertEquals("1.5 servings", card.servingsLabel)
        assertTrue(card.description.startsWith(FoodDayMessages.SUGGESTED))

        // PRD_FOOD 12: it enters no total, so the moment that holds only it has none.
        assertNull(plan.totalLabel)
        assertTrue(plan.isEmpty)
    }

    /** PRD_FOOD 10.1: a proposal that has been confirmed stops being shown. */
    @Test
    fun `a confirmed proposal leaves the screen`() {
        val consumed = FoodDayPreviewData.plannedDinner().copy(
            consumedLogEntryId = FoodDayPreviewData.breakfast().id,
        )

        val dinner = FoodDayUiState
            .of(date = TODAY, today = TODAY, plans = listOf(consumed))
            .slot(MealSlot.DINNER)

        assertNull(dinner.plan)
    }

    /** PRD_FOOD 17: a proposal whose recipe is gone says so rather than showing a blank card. */
    @Test
    fun `a proposal with no recipe behind it says so`() {
        val dinner = FoodDayUiState
            .of(date = TODAY, today = TODAY, plans = FoodDayPreviewData.plans())
            .slot(MealSlot.DINNER)

        assertEquals(FoodDayMessages.MISSING_RECIPE, requireNotNull(dinner.plan).recipeName)
    }

    /**
     * FR-PLAN-003 against PRD_FOOD 22, now that a day ahead can be reached at all.
     *
     * `I ate this` writes a journal line dated on the proposal's own day, so on Thursday's dinner
     * it cannot be offered — the card would be asking whether you ate Thursday. `Swap` and
     * `Dismiss` are untouched: both are things you do today about a day to come.
     */
    @Test
    fun `a proposal ahead of today is not offered I ate this`() {
        val tomorrow = TODAY.plusDays(1)

        val ahead = FoodDayUiState
            .of(date = tomorrow, today = TODAY, plans = FoodDayPreviewData.plans(tomorrow))
            .slot(MealSlot.DINNER)

        assertFalse(requireNotNull(ahead.plan).canConfirm)

        val now = FoodDayUiState
            .of(date = TODAY, today = TODAY, plans = FoodDayPreviewData.plans())
            .slot(MealSlot.DINNER)

        assertTrue(requireNotNull(now.plan).canConfirm)
    }

    /** A proposal posed for another day is not this day's business. */
    @Test
    fun `a proposal from another day never reaches this one`() {
        val dinner = FoodDayUiState
            .of(date = TODAY, today = TODAY, plans = FoodDayPreviewData.plans(YESTERDAY))
            .slot(MealSlot.DINNER)

        assertNull(dinner.plan)
    }

    // endregion

    // region the date (PRD_FOOD 10.1 and 22)

    /*
     * The second finding, in the owner's words:
     *
     *   "le fait que je puisse pas aller dans le futur mais uniquement dans le passé sur la page
     *    food ?"
     *
     * Two rules had been read as one. `FoodLogEntry.isLoggableOn` is the **journal's** ceiling and
     * PRD_FOOD 22 keeps it — a day ahead cannot be completed. `MealPlanEntry.isPlannableOn` allows
     * sixty days ahead and is what PRD_FOOD 12 calls the primer that makes the journal possible.
     * The date navigation asked only the first, so no future day could be reached by any route,
     * and the proposal machinery that was already written and already rendered could never appear.
     */

    /** Forward now stops where **both** rules stop, not where the journal's does. */
    @Test
    fun `the day navigation reaches as far ahead as a proposal may be posed`() {
        val lastPlannable = TODAY.plusDays(MealPlanEntry.MAX_DAYS_AHEAD)

        assertTrue(FoodDayUiState.of(TODAY, TODAY).canGoForward)
        assertTrue(FoodDayUiState.of(YESTERDAY, TODAY).canGoForward)
        assertTrue(FoodDayUiState.of(lastPlannable.minusDays(1), TODAY).canGoForward)
        assertFalse(FoodDayUiState.of(lastPlannable, TODAY).canGoForward)
        assertTrue(FoodDayUiState.of(TODAY, TODAY).canGoBack)
    }

    /**
     * PRD_FOOD 22 unchanged: reaching a day ahead is not being allowed to write on it.
     *
     * The two facts held together on one object is the whole of the separation — the day is
     * reachable, and it is not loggable.
     */
    @Test
    fun `a day ahead can be reached and still cannot be logged`() {
        val tomorrow = FoodDayUiState.of(TODAY.plusDays(1), TODAY)

        assertTrue(tomorrow.isReachable)
        assertTrue(tomorrow.canPlan)
        assertFalse(tomorrow.canLog)

        val today = FoodDayUiState.of(TODAY, TODAY)
        assertTrue(today.canLog)
        assertTrue(today.canPlan)

        val yesterday = FoodDayUiState.of(YESTERDAY, TODAY)
        assertTrue(yesterday.canLog)
        assertFalse(yesterday.canPlan)
    }

    /** Beyond the sixtieth day there is neither a line to write nor a proposal to pose. */
    @Test
    fun `a day past the planning window is reachable by nothing`() {
        val beyond = TODAY.plusDays(MealPlanEntry.MAX_DAYS_AHEAD + 1)

        assertFalse(FoodDayUiState.isReachable(beyond, TODAY))
        assertTrue(FoodDayUiState.isReachable(TODAY.plusDays(MealPlanEntry.MAX_DAYS_AHEAD), TODAY))
        assertTrue(FoodDayUiState.isReachable(TODAY.minusYears(3), TODAY))
    }

    /**
     * PRD_FOOD 22 will not let a line be written to a day that has not happened.
     *
     * The action keeps its place and stops being a control, which is the same answer the six add
     * rows gave and for the same reason — a control that vanishes reflows the screen as the week
     * is walked. It is asked of the **day** now: one action, one refusal, said once.
     */
    @Test
    fun `a day ahead of today keeps the action and refuses it`() {
        assertFalse(FoodDayUiState.of(TODAY.plusDays(2), TODAY).canAdd)
        assertTrue(FoodDayUiState.of(TODAY, TODAY).canAdd)
        assertTrue(FoodDayUiState.of(TODAY.minusDays(3), TODAY).canAdd)
    }

    @Test
    fun `the date is named for the eye and spelled out for the ear`() {
        val state = FoodDayUiState.of(TODAY, TODAY)

        assertEquals(FoodDayFormat.TODAY, state.dateLabel)
        assertTrue(state.dateDescription.startsWith(FoodDayFormat.TODAY))
        assertTrue(state.dateDescription.contains("2026"), state.dateDescription)
    }

    // endregion

    // region what a line reads and says

    /** PRD_FOOD 13.2: the label frozen on the line is kept, both readings and all. */
    @Test
    fun `a line keeps the quantity label it was saved with`() {
        val entry = FoodDayPreviewData.breakfast().copy(amountLabel = "1.5 × apple (225 g)")

        val line = FoodDayUiState
            .of(date = TODAY, today = TODAY, entries = listOf(entry))
            .slot(MealSlot.BREAKFAST)
            .entries
            .single()

        assertEquals("1.5 × apple (225 g)", line.amountLabel)
    }

    /** A line saved without one falls back to what `FoodLabels` can draw from the amount. */
    @Test
    fun `a line with no saved label is drawn from its stored amount`() {
        val entry = FoodDayPreviewData.lunch().copy(amountLabel = null)

        val line = FoodDayUiState
            .of(date = TODAY, today = TODAY, entries = listOf(entry))
            .slot(MealSlot.LUNCH)
            .entries
            .single()

        assertEquals("225 g", line.amountLabel)
    }

    /** PRD_FOOD 10.2: a quick add has no quantity at all, so it shows no quantity row. */
    @Test
    fun `a quick add shows no quantity`() {
        val line = FoodDayUiState
            .of(date = TODAY, today = TODAY, entries = listOf(FoodDayPreviewData.tiramisu()))
            .slot(MealSlot.SNACK)
            .entries
            .single()

        assertNull(line.amountLabel)
    }

    /** PRD_FOOD 18: a line states what it is, when it was, how much, and what it is worth. */
    @Test
    fun `a line announces itself whole`() {
        val line = FoodDayUiState
            .of(date = TODAY, today = TODAY, entries = listOf(FoodDayPreviewData.breakfast()))
            .slot(MealSlot.BREAKFAST)
            .entries
            .single()

        val spoken = line.description
        assertTrue(spoken.startsWith(FoodDayPreviewData.BREAKFAST_TITLE), spoken)
        assertTrue(spoken.contains(line.timeLabel), spoken)
        assertTrue(spoken.contains("1 × serving"), spoken)
        assertTrue(spoken.contains("about 370 kcal"), spoken)
        assertTrue(spoken.contains("about 29.1 g protein"), spoken)
        // `≈` and `—` are drawings; neither survives into what is heard.
        assertFalse(spoken.contains(FoodLabels.UNKNOWN), spoken)
        assertFalse(spoken.contains('≈'), spoken)
    }

    /** PRD_FOOD 18: a moment and its total are one announcement, not three fragments. */
    @Test
    fun `a moment announces its name, its count and its total together`() {
        val snack = FoodDayUiState
            .of(date = TODAY, today = TODAY, entries = listOf(FoodDayPreviewData.tiramisu()))
            .slot(MealSlot.SNACK)

        assertEquals(
            "Snack, 1 entry, about 420 kcal, unknown protein",
            snack.description,
        )
    }

    @Test
    fun `an empty moment says it holds nothing rather than saying zero`() {
        val breakfast = FoodDayUiState.of(TODAY, TODAY).slot(MealSlot.BREAKFAST)

        assertEquals("Breakfast, ${FoodDayMessages.NOTHING_LOGGED}", breakfast.description)
    }

    // endregion

    // region a heading appears when its moment holds something (the owner, over PRD_FOOD 10.1)

    /**
     * The whole of the owner's instruction, in one assertion.
     *
     * *"Est-ce qu'on pourrait pas imaginer juste avoir les headers, sans le plus, uniquement
     * quand il y a un élément dedans"* — so an untouched day draws **no moment at all**, where it
     * used to draw six headings over six invitations, three of them folded to save the height the
     * other three were spending.
     *
     * All six are still built. [FoodDayUiState.slots] is what the domain grouped and what a test
     * asks about a moment that is currently drawing nothing; [FoodDayUiState.visibleSlots] is
     * what the screen iterates. Keeping both is what lets this file prove the difference.
     */
    @Test
    fun `an untouched day draws no moment at all`() {
        val state = FoodDayUiState.of(date = TODAY, today = TODAY)

        assertEquals(MealSlot.ORDERED, state.slots.map { it.slot }, "the six are still built")
        assertEquals(emptyList(), state.visibleSlots, "an empty moment is drawn")
        assertTrue(state.isBlank)
    }

    /** A moment appears the instant a line lands in it, and its neighbours stay away. */
    @Test
    fun `a moment appears when it holds a line and only that moment appears`() {
        val state = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            entries = listOf(FoodDayPreviewData.tiramisu()),
        )

        assertEquals(listOf(MealSlot.SNACK), state.visibleSlots.map { it.slot })
        assertTrue(state.slot(MealSlot.SNACK).hasContent)
        assertFalse(state.slot(MealSlot.BREAKFAST).hasContent)
        assertFalse(state.isBlank)
    }

    /**
     * A proposal is something to draw, though it holds no line and enters no total.
     *
     * PRD_FOOD 12 puts the dashed card at the *head of its moment* with three actions on it, so a
     * moment that had been suggested a dinner is a moment worth naming — the alternative is a
     * card with no heading over it, floating in the day.
     */
    @Test
    fun `a moment carrying only a proposal is still drawn`() {
        val plan = MealPlanEntry(
            plannedOn = TODAY,
            slot = MealSlot.EVENING_SNACK,
            recipeId = FoodDayPreviewData.PLANNED_RECIPE_ID,
            plannedServings = FoodDayPreviewData.plannedDinner().plannedServings,
        )
        val state = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            plans = listOf(plan),
            recipeNames = FoodDayPreviewData.recipeNames,
        )

        assertEquals(listOf(MealSlot.EVENING_SNACK), state.visibleSlots.map { it.slot })
        assertNull(
            state.slot(MealSlot.EVENING_SNACK).totalLabel,
            "a proposal is not a line and enters no total",
        )
    }

    /** The moments that are drawn keep PRD_FOOD 10.1's order, whichever of the six they are. */
    @Test
    fun `the drawn moments keep the order of the day`() {
        val state = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            entries = listOf(
                FoodDayPreviewData.tiramisu(),
                FoodDayPreviewData.breakfast(),
                FoodDayPreviewData.lunch(),
            ),
        )

        assertEquals(
            listOf(MealSlot.BREAKFAST, MealSlot.LUNCH, MealSlot.SNACK),
            state.visibleSlots.map { it.slot },
        )
    }

    /**
     * A day still to come draws its proposals and nothing else.
     *
     * PRD_FOOD 22 refuses a journal line there, so there is nothing to list but what has been
     * suggested — and the day's own action is what says the refusal, once, rather than four rows
     * repeating it.
     */
    @Test
    fun `a day ahead draws its proposals and no empty moment`() {
        val ahead = TODAY.plusDays(2)
        val state = FoodDayUiState.of(
            date = ahead,
            today = TODAY,
            plans = FoodDayPreviewData.plans(ahead),
            recipeNames = FoodDayPreviewData.recipeNames,
        )

        assertTrue(state.visibleSlots.isNotEmpty())
        assertTrue(state.visibleSlots.all { it.plan != null })
        assertFalse(state.canAdd)
    }

    // endregion

    private fun FoodDayUiState.slot(slot: MealSlot): FoodDaySlotUiState =
        slots.first { it.slot == slot }
}
