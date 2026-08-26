package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.logic.NutritionMath
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = FoodAddPreviewData.TODAY
private val NOW: LocalTime = FoodAddPreviewData.NOW

/**
 * What the add flow writes, and what it refuses (PRD_FOOD 13.1, 15 and 8.4).
 *
 * Every rule below is a pure function of a draft, a food and a stored line, so the whole of
 * PRD_FOOD 15's table is settled here — on the JVM, in milliseconds, with no ViewModel, no
 * database and no emulator between the assertion and the rule.
 *
 * The **numbers** are asserted against [NutritionMath] rather than against constants copied out
 * of a calculator: the point of these tests is that the screen calls the domain, and a literal
 * would only prove that two copies of the same arithmetic agree. The **strings** are asserted
 * literally, because those are what a person reads.
 */
class FoodAddDraftTest {

    // region a weighed food (FR-FOOD-002 and 006)

    @Test
    fun `a weighed food is stored in grams, with the values the domain computes`() {
        val food = FoodAddPreviewData.longNamed()
        val entry = ready(draft(food).copy(quantity = "225"), food)

        assertEquals(FoodLogKind.FOOD, entry.kind)
        assertEquals(food.name, entry.title)
        assertEquals(food.id.value, entry.sourceRef)
        assertEquals(
            LoggedAmount.Measured(quantity(225.0), ReferenceUnit.GRAM),
            entry.amount,
        )
        assertEquals(
            NutritionMath.foodContribution(food, quantity(225.0)),
            entry.nutrients,
        )
        assertEquals("225 g", entry.amountLabel)
        // PRD_FOOD 8.4 names two approximate lines, and a weighed food is neither of them.
        assertEquals(Estimation.MEASURED, entry.estimation)
        assertFalse(entry.weighedCooked)
        assertNull(entry.portions)
    }

    @Test
    fun `a volume keeps its own unit, with no density invented`() {
        val food = FoodAddPreviewData.espresso()
        val entry = ready(draft(food).copy(quantity = "30"), food)

        assertEquals(
            LoggedAmount.Measured(quantity(30.0), ReferenceUnit.MILLILITRE),
            entry.amount,
        )
        assertEquals("30 ml", entry.amountLabel)
    }

    // endregion

    // region raw and cooked (PRD_FOOD 8.6, 13.1 and 22)

    /**
     * PRD_FOOD 22: "150 g de blanc de poulet pesés cuits donnent une valeur supérieure aux mêmes
     * 150 g pesés crus", read the other way round — rice absorbs water, so a cooked weight is
     * worth *less* than the same number read raw.
     *
     * This is the whole reason the selector exists: 600 g of cooked rice typed against a raw
     * reference would count 2 094 kcal instead of 926.
     */
    @Test
    fun `a cooked weight is converted once, and counts less than the same number read raw`() {
        val rice = FoodAddPreviewData.rice()
        val cooked = ready(draft(rice).copy(quantity = "600", weighedCooked = true), rice)
        val raw = ready(draft(rice).copy(quantity = "600"), rice)

        assertEquals(
            NutritionMath.foodContribution(rice, quantity(600.0), weighedCooked = true),
            cooked.nutrients,
        )
        assertTrue(
            cooked.nutrients.energy!! < raw.nutrients.energy!!,
            "a cooked weight of rice counted at least as much as the same weight read raw",
        )
        assertTrue(cooked.weighedCooked)
        // PRD_FOOD 13.2: "le libellé conserve les deux lectures quand elles existent".
        assertEquals("600 g cooked", cooked.amountLabel)
        assertEquals("600 g", raw.amountLabel)
    }

    /** PRD_FOOD 13.1: the conversion is applied to the quantity, once, and never twice. */
    @Test
    fun `the reference weight behind a cooked reading is the domain's own`() {
        val rice = FoodAddPreviewData.rice()
        val amount = FoodAddPreviewData.draft()
            .copy(foodId = rice.id.value, quantity = "600", weighedCooked = true)
            .resolveAmount(rice)

        val value = assertNotNull(amount.valid())
        assertEquals(
            NutritionMath.referenceWeightOrNull(quantity(600.0), rice.cookedRatio, true),
            value.referenceWeight,
        )
        assertEquals("265.487 g", FoodLabels.quantity(value.referenceWeight, ReferenceUnit.GRAM))
    }

    /** FR-FOOD-006: the selector appears nowhere else, and a stray flag is refused. */
    @Test
    fun `claiming a cooked reading of a food with no cooked state is refused`() {
        val apple = FoodAddPreviewData.apple()
        val errors = refused(draft(apple).copy(quantity = "150", weighedCooked = true), apple)

        assertEquals(FoodValidation.COOKED_STATE_UNAVAILABLE_ERROR, errors.quantity)
    }

    // endregion

    // region usual portions (PRD_FOOD 8.6 and 22)

    @Test
    fun `a count of usual portions is stored in grams, with both readings kept`() {
        val apple = FoodAddPreviewData.apple()
        val portions = servings(1.5)
        val entry = ready(
            draft(apple).copy(portionThousandths = portions.thousandths, quantity = "225"),
            apple,
        )

        assertEquals(
            LoggedAmount.Measured(quantity(225.0), ReferenceUnit.GRAM),
            entry.amount,
        )
        assertEquals(portions, entry.portions)
        assertEquals(
            NutritionMath.usualServingContribution(apple, portions),
            entry.nutrients,
        )
        // PRD_FOOD 13.2's own example, with the catalogue's own portion word.
        assertEquals("1.5 × 1 apple (225 g)", entry.amountLabel)
    }

    /** PRD_FOOD 22: "saisir un poids exact désactive la lecture en portions". */
    @Test
    fun `an exact weight leaves the label one reading`() {
        val apple = FoodAddPreviewData.apple()
        val entry = ready(draft(apple).copy(quantity = "180"), apple)

        assertNull(entry.portions)
        assertEquals("180 g", entry.amountLabel)
    }

    /** PRD_FOOD 15: "0,5 à 20, par pas de 0,5", and a count off the step is not a count. */
    @Test
    fun `a portion count outside the step is refused`() {
        val apple = FoodAddPreviewData.apple()
        val offStep = Servings.USUAL_MIN_THOUSANDTHS - 1
        val errors = refused(draft(apple).copy(portionThousandths = offStep), apple)

        assertEquals(FoodValidation.USUAL_PORTIONS_ERROR, errors.quantity)
    }

    // endregion

    // region what PRD_FOOD 15 refuses

    @Test
    fun `no quantity at all is refused, and says so in words a person can act on`() {
        val food = FoodAddPreviewData.rice()
        val errors = refused(draft(food), food)

        assertEquals(FoodAddMessages.NO_QUANTITY, errors.quantity)
        assertEquals(FoodAddMessages.NO_QUANTITY, errors.summary)
    }

    @Test
    fun `a quantity of zero and a quantity above five kilos are both refused`() {
        val food = FoodAddPreviewData.rice()

        assertEquals(
            FoodValidation.INGREDIENT_QUANTITY_ERROR,
            refused(draft(food).copy(quantity = "0"), food).quantity,
        )
        assertEquals(
            FoodValidation.INGREDIENT_QUANTITY_ERROR,
            refused(draft(food).copy(quantity = "5001"), food).quantity,
        )
    }

    /** PRD_FOOD 15: both separators are read, whatever the phone's language is. */
    @Test
    fun `a comma is a decimal point`() {
        val food = FoodAddPreviewData.rice()
        val comma = ready(draft(food).copy(quantity = "62,5"), food)
        val point = ready(draft(food).copy(quantity = "62.5"), food)

        assertEquals(point.nutrients, comma.nutrients)
        assertEquals(quantity(62.5), comma.measuredQuantity)
    }

    /** PRD_FOOD 15: "aujourd'hui ou dans le passé, jamais dans le futur". */
    @Test
    fun `a line cannot be written in the future`() {
        val food = FoodAddPreviewData.rice()
        val tomorrow = draft(food).copy(quantity = "80").withDate(TODAY.plusDays(1))

        assertEquals(FoodValidation.CONSUMED_DATE_ERROR, refused(tomorrow, food).date)
    }

    // endregion

    // region the quick add (FR-FOOD-005, PRD_FOOD 13.1)

    /**
     * PRD_FOOD 13.1: "l'ajout rapide stocke les protéines à `null` lorsqu'elles ne sont pas
     * renseignées". Not `Macro.ZERO`, which would claim the plate held none.
     */
    @Test
    fun `a quick add states an energy and leaves everything else unknown`() {
        val entry = ready(quickDraft(), food = null)

        assertEquals(FoodLogKind.QUICK, entry.kind)
        assertEquals(FoodAddPreviewData.QUICK_NAME, entry.title)
        assertEquals(LoggedAmount.Unmeasured, entry.amount)
        assertNull(entry.quantityUnit)
        assertNull(entry.amountLabel)
        assertEquals(300_000, entry.nutrients.energy?.milliKcal)
        assertNull(entry.nutrients.protein)
        assertNull(entry.nutrients.carbs)
        assertNull(entry.nutrients.fat)
        assertNull(entry.nutrients.fibre)
        // PRD_FOOD 22: "un ajout rapide est enregistré et signalé comme approximatif".
        assertEquals(Estimation.APPROXIMATE, entry.estimation)
        assertNull(entry.sourceRef)
    }

    @Test
    fun `a typed zero protein is a known zero and not an unknown`() {
        val entry = ready(quickDraft().copy(quickProtein = "0"), food = null)

        assertEquals(0, entry.nutrients.protein?.milligrams)
    }

    @Test
    fun `a quick add refuses each of its fields on its own`() {
        val errors = refused(
            quickDraft().copy(quickTitle = " ", quickEnergy = "9000", quickProtein = "800"),
            food = null,
        )

        assertEquals(FoodValidation.NAME_ERROR, errors.title)
        assertEquals(FoodValidation.QUICK_ADD_ENERGY_ERROR, errors.energy)
        assertEquals(FoodValidation.QUICK_ADD_PROTEIN_ERROR, errors.protein)
    }

    // endregion

    // region correcting a stored line (FR-FOOD-008)

    /**
     * PRD_FOOD 8.4 and 11: a recipe edited since is not a line rewritten, so the correction
     * rescales the snapshot the line already carries.
     */
    @Test
    fun `correcting a recipe line rescales what it was saved with`() {
        val stored = FoodAddPreviewData.recipeEntry()
        val entry = ready(
            FoodAddDraft.forEntry(stored).copy(servings = "1.5"),
            food = null,
            original = stored,
        )

        assertEquals(stored.id, entry.id)
        assertEquals(LoggedAmount.Portioned(servings(1.5)), entry.amount)
        assertEquals(648_000, entry.nutrients.energy?.milliKcal)
        // PRD_FOOD 13.1: an unknown metric stays unknown however it is rescaled.
        assertNull(entry.nutrients.fibre)
        assertEquals("1.5 × serving", entry.amountLabel)
    }

    /** PRD_FOOD 17: "aliment supprimé mais journalisé — la ligne reste intacte". */
    @Test
    fun `a line whose food is gone keeps its values and moves only in time`() {
        val stored = FoodAddPreviewData.orphanedEntry()
        val entry = ready(
            FoodAddDraft.forEntry(stored)
                .copy(slotId = MealSlot.DINNER.id)
                .withTime(LocalTime.of(20, 15)),
            food = null,
            original = stored,
        )

        assertEquals(stored.nutrients, entry.nutrients)
        assertEquals(stored.amount, entry.amount)
        assertEquals(stored.amountLabel, entry.amountLabel)
        assertEquals(stored.title, entry.title)
        assertEquals(MealSlot.DINNER, entry.slot)
        assertEquals(LocalTime.of(20, 15), entry.consumedAt)
    }

    /** PRD_FOOD 12: correcting the line a proposal was confirmed into leaves the two linked. */
    @Test
    fun `a correction keeps the identity and the proposal of the line it started from`() {
        val stored = FoodAddPreviewData.recipeEntry()
            .copy(fromPlan = MealPlanKey(TODAY, MealSlot.DINNER))
        val entry = ready(
            FoodAddDraft.forEntry(stored).copy(servings = "0.5"),
            food = null,
            original = stored,
        )

        assertEquals(stored.id, entry.id)
        assertEquals(stored.fromPlan, entry.fromPlan)
    }

    /** A stored weight reopens as the very text that would produce it again. */
    @Test
    fun `reopening a weighed line puts the number back in the field`() {
        val food = FoodAddPreviewData.rice()
        val stored = ready(draft(food).copy(quantity = "62.5"), food)
        val reopened = FoodAddDraft.forEntry(stored)

        assertEquals("62.5", reopened.quantity)
        assertTrue(reopened.slotPinned)
        assertTrue(reopened.timePinned)
        assertEquals(ready(reopened, food).nutrients, stored.nutrients)
    }

    // endregion

    // region what survives a process death (PRD 16.4)

    @Test
    fun `a half-typed draft crosses a bundle unchanged`() {
        val food = FoodAddPreviewData.apple()
        val typed = draft(food).copy(
            quantity = "7,",
            portionThousandths = null,
            quickTitle = "half a thought",
        )

        val restored = FoodAddDraft.fromJson(FoodAddDraft.toJson(typed))

        assertEquals(typed, restored)
    }

    @Test
    fun `an unreadable draft is a draft that was never there`() {
        assertNull(FoodAddDraft.fromJson("{"))
        assertNull(FoodAddDraft.fromJson(null))
        assertNull(FoodAddDraft.fromJson("   "))
    }

    // endregion

    // region the moment and the hour (PRD_FOOD 10.3, FR-FOOD-007)

    @Test
    fun `a new line for today opens on the hour it is being written at`() {
        val fresh = FoodAddDraft.forTarget(date = null, slot = null, today = TODAY, now = NOW)

        assertEquals(TODAY, fresh.date(TODAY))
        assertEquals(NOW, fresh.time(LocalTime.MIDNIGHT))
        // 19:40 is dinner (PRD_FOOD 10.3), and nothing pinned it.
        assertEquals(MealSlot.DINNER, fresh.slot)
        assertFalse(fresh.slotPinned)
    }

    /** PRD_FOOD 10.3: a retroactive line opens in the middle of its moment, not at the clock. */
    @Test
    fun `a line written for a past day opens in the middle of its moment`() {
        val yesterday = TODAY.minusDays(1)
        val fresh = FoodAddDraft.forTarget(yesterday, MealSlot.BREAKFAST, TODAY, NOW)

        assertEquals(MealSlot.BREAKFAST.defaultTime, fresh.time(LocalTime.MIDNIGHT))
        assertTrue(fresh.slotPinned)
    }

    // endregion

    // region harness

    private fun draft(food: Food): FoodAddDraft =
        FoodAddPreviewData.draft().copy(foodId = food.id.value)

    private fun quickDraft(): FoodAddDraft = FoodAddPreviewData.draft().copy(
        kindId = FoodLogKind.QUICK.id,
        quickTitle = FoodAddPreviewData.QUICK_NAME,
        quickEnergy = "300",
    )

    private fun ready(
        draft: FoodAddDraft,
        food: Food?,
        original: FoodLogEntry? = null,
    ): FoodLogEntry {
        val resolution = draft.resolve(food, original, TODAY)
        assertTrue(resolution is FoodAddResolution.Ready, "expected a line, got $resolution")
        return (resolution as FoodAddResolution.Ready).entry
    }

    private fun refused(
        draft: FoodAddDraft,
        food: Food?,
        original: FoodLogEntry? = null,
    ): FoodAddErrors {
        val resolution = draft.resolve(food, original, TODAY)
        assertTrue(resolution is FoodAddResolution.Refused, "expected a refusal, got $resolution")
        return (resolution as FoodAddResolution.Refused).errors
    }

    private fun quantity(amount: Double): Quantity =
        requireNotNull(Quantity.ofIngredientOrNull(amount))

    private fun servings(count: Double): Servings =
        requireNotNull(Servings.ofConsumedOrNull(count))

    private fun Validated<FoodAddAmount>.valid(): FoodAddAmount? =
        (this as? Validated.Valid)?.value

    // endregion
}
