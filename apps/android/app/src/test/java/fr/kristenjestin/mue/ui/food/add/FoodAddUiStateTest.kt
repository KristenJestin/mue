package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Servings
import org.junit.Test
import java.time.LocalDate
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

        val creating = previewCookedState()
        assertFalse(creating.isEditing)
        assertEquals(FoodAddMessages.ADD_TITLE, creating.screenTitle)
        assertEquals(FoodAddMessages.SAVE_ENTRY, creating.saveLabel)
        assertFalse(creating.canDelete)
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

    @Test
    fun `the four moments are always offered, with one of them chosen`() {
        val state = previewCookedState()

        assertEquals(MealSlot.ORDERED, state.slots.map { it.slot })
        assertEquals(listOf(MealSlot.DINNER), state.slots.filter { it.selected }.map { it.slot })
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
