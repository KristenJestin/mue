package fr.kristenjestin.mue.ui.food.day

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
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

    // region the four moments (PRD_FOOD 10.1)

    @Test
    fun `the four moments are always there, in order, filled or not`() {
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

    /** PRD_FOOD 17: the empty state of a moment is its invitation, and it changes once used. */
    @Test
    fun `the add button says what the moment is for and then says what else it takes`() {
        val empty = FoodDayUiState.of(date = TODAY, today = TODAY)
        assertEquals(FoodDayMessages.ADD_FIRST, empty.slot(MealSlot.BREAKFAST).addLabel)

        val filled = FoodDayUiState.of(
            date = TODAY,
            today = TODAY,
            entries = listOf(FoodDayPreviewData.breakfast()),
        )
        assertEquals(FoodDayMessages.ADD_MORE, filled.slot(MealSlot.BREAKFAST).addLabel)
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

    @Test
    fun `today is the ceiling and yesterday is not`() {
        assertFalse(FoodDayUiState.of(TODAY, TODAY).canGoForward)
        assertTrue(FoodDayUiState.of(YESTERDAY, TODAY).canGoForward)
        assertTrue(FoodDayUiState.of(TODAY, TODAY).canGoBack)
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

    private fun FoodDayUiState.slot(slot: MealSlot): FoodDaySlotUiState =
        slots.first { it.slot == slot }
}
