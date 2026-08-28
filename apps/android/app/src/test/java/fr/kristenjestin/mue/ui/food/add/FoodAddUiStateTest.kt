package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Servings
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = FoodAddPreviewData.TODAY

/**
 * What the sheet draws (PRD_FOOD 13.2, 15, 17 and 18).
 *
 * The state is rendered here, on the JVM, rather than on the glass — which is the whole reason
 * the strings live on the state object at all. PRD_FOOD 13.1's rule is that an unknown is `—` and
 * a known zero is a number, and the last place it can be broken is the layer nobody unit-tests.
 * So it is tested here, and again on the glass in `FoodAddScreenTest`.
 */
class FoodAddUiStateTest {

    // region the whole of PRD_FOOD 13.1, in two screens

    /**
     * **An unknown and a known zero are not the same screen.**
     *
     * A quick add states an energy and nothing else, so its protein, carbohydrate, fat and fibre
     * are unknown — `—`, four times. A black espresso states every one of them as zero, which is a
     * fact about coffee. Neither reading may be produced from the other, and the two are put side
     * by side here so that what is compared is what would be drawn.
     */
    @Test
    fun `an unknown is a dash and a known zero is a zero`() {
        val unknown = previewQuickState().figures!!
        val zero = previewKnownZeroState().figures!!

        assertEquals("≈ 300 kcal", unknown.value(FoodNutrientsUiState.ENERGY))
        assertEquals(FoodLabels.UNKNOWN, unknown.value(FoodNutrientsUiState.PROTEIN))
        assertEquals(FoodLabels.UNKNOWN, unknown.value(FoodNutrientsUiState.CARBS))
        assertEquals(FoodLabels.UNKNOWN, unknown.value(FoodNutrientsUiState.FAT))
        assertEquals(FoodLabels.UNKNOWN, unknown.value(FoodNutrientsUiState.FIBRE))

        assertEquals("≈ 0 kcal", zero.value(FoodNutrientsUiState.ENERGY))
        assertEquals("≈ 0.0 g", zero.value(FoodNutrientsUiState.PROTEIN))
        assertEquals("≈ 0.0 g", zero.value(FoodNutrientsUiState.FIBRE))

        assertTrue(
            unknown.value(FoodNutrientsUiState.PROTEIN) != zero.value(FoodNutrientsUiState.PROTEIN),
            "an unknown protein was drawn as a zero",
        )
    }

    /**
     * PRD_FOOD 18: a dash is a drawing, and TalkBack reads it as punctuation or skips it.
     *
     * The eye gets `—` and the ear gets `unknown`; neither of them is a zero, and the `≈` is
     * announced as the approximation PRD_FOOD 18 asks to be heard.
     */
    @Test
    fun `an unknown is spoken as unknown and an approximation is spoken as about`() {
        val figures = previewQuickState().figures!!

        assertEquals("unknown", figures.spoken(FoodNutrientsUiState.PROTEIN))
        assertEquals("about 300 kcal", figures.spoken(FoodNutrientsUiState.ENERGY))
        assertTrue(figures.description.contains("about 300 kcal"))
        assertTrue(figures.description.contains("${FoodAddMessages.PROTEIN_NOUN} unknown"))
    }

    /** PRD_FOOD 22, metric by metric: an unknown fibre leaves the energy a number. */
    @Test
    fun `one unknown metric leaves the others known`() {
        val figures = previewCookedState().figures!!

        assertEquals(FoodLabels.UNKNOWN, figures.value(FoodNutrientsUiState.FIBRE))
        assertTrue(figures.value(FoodNutrientsUiState.ENERGY).endsWith(FoodLabels.ENERGY_UNIT))
        assertFalse(figures.value(FoodNutrientsUiState.ENERGY) == FoodLabels.UNKNOWN)
    }

    // endregion

    // region before and after a quantity

    /**
     * A quantity nobody has typed yet is **not** an unknown value.
     *
     * With nothing typed the card shows what the food is worth per 100 — which is what those
     * numbers are — rather than five dashes, which would claim the catalogue does not know them.
     * Once a quantity is given the same five rows say what this line contributes.
     */
    @Test
    fun `the figures are the food's own until a quantity is given`() {
        val food = FoodAddPreviewData.apple()
        val empty = FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(foodId = food.id.value),
            food = food,
            today = TODAY,
        )

        assertNull(empty.contribution)
        assertEquals(FoodAddMessages.per100Label(food), empty.figures?.header)
        assertEquals("≈ 51 kcal", empty.figures?.value(FoodNutrientsUiState.ENERGY))

        val weighed = FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(foodId = food.id.value, quantity = "200"),
            food = food,
            today = TODAY,
        )

        assertNotNull(weighed.contribution)
        assertEquals(FoodAddMessages.CONTRIBUTION_SECTION, weighed.figures?.header)
        assertEquals("≈ 103 kcal", weighed.figures?.value(FoodNutrientsUiState.ENERGY))
    }

    // endregion

    // region raw and cooked, on screen (FR-FOOD-006)

    /**
     * The finding this screen was built around: the state the number is read in has to be
     * **beside the field**, not only behind the arithmetic.
     */
    @Test
    fun `the quantity field names the state it is read in`() {
        val rice = FoodAddPreviewData.rice()
        val raw = amount(rice, quantity = "600", cooked = false)
        val cooked = amount(rice, quantity = "600", cooked = true)

        assertEquals("Weight, raw", raw.quantityLabel)
        assertEquals("Weight, cooked", cooked.quantityLabel)
        assertEquals(listOf("raw", "cooked"), cooked.cookedStates)
        assertEquals("cooked", cooked.cookedStateSelected)
    }

    /** PRD_FOOD 13.1, said out loud: what 600 g weighed cooked is actually counted as. */
    @Test
    fun `a cooked weight says what it is counted as, and a raw one has nothing to say`() {
        val rice = FoodAddPreviewData.rice()

        assertEquals(
            FoodAddMessages.countedAs("265.487 g", "raw"),
            amount(rice, quantity = "600", cooked = true).referenceNote,
        )
        assertNull(amount(rice, quantity = "600", cooked = false).referenceNote)
    }

    /** PRD_FOOD 22: "le sélecteur cru/cuit n'apparaît que sur les aliments portant un ratio". */
    @Test
    fun `a food that cannot be cooked into another state offers no selector`() {
        val apple = FoodAddPreviewData.apple()
        val shown = amount(apple, quantity = "150", cooked = false)

        assertNull(shown.cookedStates)
        assertNull(shown.cookedStateSelected)
        assertEquals(FoodAddMessages.WEIGHT_LABEL, shown.quantityLabel)
        assertFalse(shown.weighedCooked)
    }

    /** PRD_FOOD 8.6: a millilitre food is a volume, and no density turns one into the other. */
    @Test
    fun `a liquid is asked for a volume`() {
        val espresso = FoodAddPreviewData.espresso()
        val shown = amount(espresso, quantity = "30", cooked = false)

        assertEquals(FoodAddMessages.VOLUME_LABEL, shown.quantityLabel)
        assertEquals("ml", shown.unitSymbol)
        assertEquals("Per 100 ml", FoodAddMessages.per100Label(espresso))
    }

    // endregion

    // region the portion counter (FR-FOOD-006, PRD_FOOD 15)

    @Test
    fun `the counter is offered only by a food that declares a portion`() {
        assertEquals("1 apple", amount(FoodAddPreviewData.apple(), "150", false).servingLabel)
        assertNull(amount(FoodAddPreviewData.rice(), "150", false).servingLabel)
    }

    /** PRD_FOOD 15: 0.5 to 20 by halves, so the two buttons stop exactly where the range does. */
    @Test
    fun `the counter stops at both ends of the range`() {
        val apple = FoodAddPreviewData.apple()

        val none = amount(apple, quantity = "", cooked = false)
        assertTrue(none.canAddPortion)
        assertFalse(none.canRemovePortion)

        val most = amountWithPortions(apple, Servings.USUAL_MAX_THOUSANDTHS)
        assertFalse(most.canAddPortion)
        assertTrue(most.canRemovePortion)

        val least = amountWithPortions(apple, Servings.USUAL_MIN_THOUSANDTHS)
        assertTrue(least.canAddPortion)
        assertFalse(least.canRemovePortion)
    }

    @Test
    fun `one step is half a portion`() {
        val one = Servings.ofUsualOrNull(1.0)

        assertEquals(1.5, FoodAddUiState.stepped(one, up = true)?.count)
        assertEquals(0.5, FoodAddUiState.stepped(one, up = false)?.count)
        assertNull(FoodAddUiState.stepped(null, up = false))
    }

    // endregion

    // region which stage, and what it can do

    @Test
    fun `the sheet opens on the ways in, and a chosen food moves it on`() {
        assertEquals(FoodAddStage.PATHS, previewPathsState().stage)
        assertEquals(FoodAddStage.AMOUNT, previewCookedState().stage)
        assertEquals(FoodAddStage.QUICK, previewQuickState().stage)
        assertEquals(FoodAddStage.SERVINGS, previewServingsState().stage)
        assertEquals(FoodAddStage.FROZEN, previewOrphanedState().stage)
    }

    /** PRD_FOOD 17: the sheet says the food is gone rather than showing a form over nothing. */
    @Test
    fun `a line whose food is gone says so and still shows what it was saved with`() {
        val state = previewOrphanedState()

        assertTrue(state.isFoodMissing)
        assertNull(state.amount)
        assertEquals(FoodAddMessages.CONTRIBUTION_SECTION, state.figures?.header)
        assertEquals("≈ 211 kcal", state.figures?.value(FoodNutrientsUiState.ENERGY))
        assertEquals(FoodLabels.UNKNOWN, state.figures?.value(FoodNutrientsUiState.CARBS))
    }

    @Test
    fun `an entry being corrected says so on its title and offers to delete itself`() {
        val editing = previewServingsState()

        assertTrue(editing.isEditing)
        assertEquals(FoodAddMessages.EDIT_TITLE, editing.screenTitle)
        assertEquals(FoodAddMessages.SAVE_CHANGES, editing.saveLabel)
        assertTrue(editing.canDelete)

        /*
         * A new line names its **stage** instead, which is the whole of the title fix: `Add food`
         * belongs to the ways in and nowhere else. What still separates the two sheets is that a
         * correction says `Edit entry` on every stage it can reach, so its title never moves.
         */
        val creating = previewCookedState()
        assertFalse(creating.isEditing)
        assertEquals(FoodAddMessages.AMOUNT_SECTION, creating.screenTitle)
        assertEquals(FoodAddMessages.SAVE_ENTRY, creating.saveLabel)
        assertFalse(creating.canDelete)

        assertEquals(FoodAddMessages.ADD_TITLE, previewPathsState().screenTitle)
    }

    /**
     * Each stage of a new line says where it is (PRD_FOOD 7).
     *
     * "quand je rentre dans « scan a barcode », j'ai le « add food »". One title over five
     * screens is not a title, and the two the owner met were the two furthest from `Add food`.
     */
    @Test
    fun `every stage of a new line names itself`() {
        assertEquals(FoodAddMessages.ADD_TITLE, previewPathsState().screenTitle)
        assertEquals(FoodAddMessages.SCAN_PATH, previewScanRefusedState().screenTitle)
        assertEquals(FoodAddMessages.QUICK_PATH, previewQuickState().screenTitle)
        assertEquals(FoodAddMessages.AMOUNT_SECTION, previewCookedState().screenTitle)
        assertEquals(FoodAddMessages.SERVINGS_SECTION, previewRecipeServingsState().screenTitle)
    }

    /**
     * A food must not change its wording across a single tap.
     *
     * The card at the top of the sheet says what the row in the picker said — same name, same
     * provenance, same separator — because the two are one food and the person just chose it.
     */
    @Test
    fun `the chosen food is worded exactly as its row in the picker was`() {
        val food = FoodAddPreviewData.longNamed()
        val row = FoodPickerRowUiState.of(food)
        val state = FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(foodId = food.id.value),
            food = food,
            today = TODAY,
        )

        assertEquals(row.name, state.food?.name)
        assertEquals(row.meta, state.food?.meta)
        assertEquals(row.iconName, state.food?.iconName)
    }

    // endregion

    // region the moment and the day (PRD_FOOD 10.1 and 18)

    /**
     * The moment is **not asked for**, and this is the shape of that.
     *
     * The six of them are still built — the override panel draws exactly this list — but the form
     * itself shows only [FoodAddUiState.slotFieldValue], which is what the hour already decided.
     */
    @Test
    fun `the six moments are offered in the override, with one of them chosen`() {
        val state = previewCookedState()

        assertEquals(MealSlot.ORDERED, state.slots.map { it.slot })
        assertEquals(listOf(MealSlot.DINNER), state.slots.filter { it.selected }.map { it.slot })
        // Closed until somebody asks for it: the moment is automatic and out of the way.
        assertFalse(state.isSlotPickerVisible)
    }

    /*
     * The fourth finding, in the owner's words:
     *
     *   "« which moment » on comprend pas, je peux sélectionner breakfast, mais avoir un time à
     *    18h, je comprends pas ?"
     *
     * and then, once the hours were printed on the tiles:
     *
     *   "je définis mon heure de bouffer, le système a déjà en mémoire les plages… ça affiche bien
     *    lunch dans l'interface mais pas à la création"
     *
     * Two controls for one fact. The pairing is not forbidden and must not become so — PRD_FOOD
     * 10.3 says the windows "ne créent aucune contrainte" and a late breakfast is a real meal — so
     * the moment stops being asked for and becomes a value: derived from the hour, shown already
     * correct, and overridable in a panel one tap away.
     */

    /** FR-FOOD-007: what the hour decided, rendered, with the window that explains it. */
    @Test
    fun `the moment on the form is the one the hour chose, with its hours beside it`() {
        val state = stateAt(MealSlot.LUNCH, LocalTime.of(13, 0))

        assertEquals("Lunch · 12:00 – 14:30", state.slotFieldValue)
        assertTrue(state.slotFieldDescription.contains(MealSlot.LUNCH.label))
    }

    /** PRD_FOOD 10.3's table, on the thing being chosen inside the panel. */
    @Test
    fun `every moment shows the hours it usually covers`() {
        val slots = stateAt(MealSlot.DINNER, LocalTime.of(20, 0)).slots.associateBy { it.slot }

        assertEquals("05:00 – 10:00", slots.getValue(MealSlot.BREAKFAST).hoursLabel)
        assertEquals("10:00 – 12:00", slots.getValue(MealSlot.MORNING_SNACK).hoursLabel)
        assertEquals("12:00 – 14:30", slots.getValue(MealSlot.LUNCH).hoursLabel)
        assertEquals("14:30 – 18:30", slots.getValue(MealSlot.SNACK).hoursLabel)
        assertEquals("18:30 – 22:00", slots.getValue(MealSlot.DINNER).hoursLabel)
        // The one that crosses midnight, printed as it is rather than as two intervals.
        assertEquals("22:00 – 05:00", slots.getValue(MealSlot.EVENING_SNACK).hoursLabel)
    }

    /**
     * Every moment claims a window now, including the snacks.
     *
     * PRD_FOOD 10.3 used to give the snack none — it was "tout le reste", the complement of three
     * intervals and not an interval itself — so its card read `Any other time`. Six moments
     * partition the clock, so there is no complement left to name and no moment without hours.
     */
    @Test
    fun `no moment is left without hours of its own`() {
        val slots = stateAt(MealSlot.DINNER, LocalTime.of(20, 0)).slots

        assertEquals(MealSlot.entries.size, slots.size)
        slots.forEach { option ->
            assertTrue(option.hoursLabel.contains("–"), "${option.slot} has no window: «${option.hoursLabel}»")
        }
    }

    /** Breakfast at eight in the evening: allowed, saved as chosen, and no longer unexplained. */
    @Test
    fun `an hour outside the chosen moment is stated rather than refused`() {
        val state = stateAt(MealSlot.BREAKFAST, LocalTime.of(20, 0))

        val note = assertNotNull(state.slotTimeNote, "the mismatch was left unexplained")
        assertTrue(note.contains("20:00"), "the hour is not named: «$note»")
        // The moment the clock would have chosen, and the one that was actually chosen.
        assertTrue(note.contains(MealSlot.DINNER.label), "the clock's moment is not named: «$note»")
        assertTrue(note.contains(MealSlot.BREAKFAST.label), "the choice is not named: «$note»")
        // The pairing survives the explanation: nothing was moved and nothing was refused.
        assertEquals(MealSlot.BREAKFAST, state.slot)
        assertEquals(LocalTime.of(20, 0), state.time)
    }

    /** A moment and an hour that agree have nothing to explain, so nothing is said. */
    @Test
    fun `an hour inside the chosen moment says nothing at all`() {
        assertNull(stateAt(MealSlot.BREAKFAST, LocalTime.of(8, 0)).slotTimeNote)
        assertNull(stateAt(MealSlot.LUNCH, LocalTime.of(14, 0)).slotTimeNote)
        assertNull(stateAt(MealSlot.DINNER, LocalTime.of(21, 59)).slotTimeNote)
        // The window that crosses midnight agrees with both of its halves.
        assertNull(stateAt(MealSlot.EVENING_SNACK, LocalTime.of(23, 30)).slotTimeNote)
        assertNull(stateAt(MealSlot.EVENING_SNACK, LocalTime.of(1, 0)).slotTimeNote)
    }

    /**
     * The midday meal the override exists for: *"y a un monde où je vais manger à 11h30 ou 15h
     * mon repas de midi"*.
     *
     * Both hours fall outside lunch's window, both are kept exactly as chosen, and both say why
     * the moment on screen is not the one the clock would have picked.
     */
    @Test
    fun `a lunch eaten at half eleven or at three is kept, and explained`() {
        val early = stateAt(MealSlot.LUNCH, LocalTime.of(11, 30))
        assertEquals(MealSlot.LUNCH, early.slot)
        assertTrue(assertNotNull(early.slotTimeNote).contains(MealSlot.MORNING_SNACK.label))

        val late = stateAt(MealSlot.LUNCH, LocalTime.of(15, 0))
        assertEquals(MealSlot.LUNCH, late.slot)
        assertTrue(assertNotNull(late.slotTimeNote).contains(MealSlot.SNACK.label))
    }

    /** PRD_FOOD 18: the save action says where the line is going, not just that it saves. */
    @Test
    fun `the save action announces the moment and the day it writes to`() {
        val state = previewCookedState()

        assertTrue(state.saveDescription.contains(MealSlot.DINNER.label))
        assertTrue(state.saveDescription.contains(state.dateLabel))
    }

    // endregion

    // region harness

    /**
     * The sheet aimed at one moment and one hour, in a locale that writes a clock as PRD_FOOD 10.3
     * writes it.
     *
     * The times are formatted for the reader's own locale, so a fixture that left it to the
     * machine would assert `18:00` on a French laptop and `6:00 pm` on an American one. `Locale.UK`
     * is the one `FoodAddViewModelTest` already pins for the same reason.
     */
    private fun stateAt(slot: MealSlot, time: LocalTime): FoodAddUiState = FoodAddUiState.of(
        draft = FoodAddPreviewData.draft(slot).withTime(time).copy(timePinned = true),
        food = FoodAddPreviewData.rice(),
        today = FoodAddPreviewData.TODAY,
        locale = Locale.UK,
    )

    private fun FoodNutrientsUiState.value(key: String): String =
        rows.first { it.key == key }.value

    private fun FoodNutrientsUiState.spoken(key: String): String =
        rows.first { it.key == key }.spoken

    private fun amount(
        food: fr.kristenjestin.mue.domain.model.Food,
        quantity: String,
        cooked: Boolean,
    ): FoodAmountUiState = assertNotNull(
        FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(
                kindId = FoodLogKind.FOOD.id,
                foodId = food.id.value,
                quantity = quantity,
                weighedCooked = cooked,
            ),
            food = food,
            today = TODAY,
        ).amount,
    )

    private fun amountWithPortions(
        food: fr.kristenjestin.mue.domain.model.Food,
        thousandths: Int,
    ): FoodAmountUiState = assertNotNull(
        FoodAddUiState.of(
            draft = FoodAddPreviewData.draft().copy(
                foodId = food.id.value,
                portionThousandths = thousandths,
            ),
            food = food,
            today = TODAY,
        ).amount,
    )

    // endregion
}
