package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.testing.LocaleRule
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * PRD_FOOD 13.2 and 22: "une valeur inconnue s'affiche `—` et jamais `0`".
 *
 * The pair of assertions that matters most in this file is the one that sets a known zero beside
 * an unknown: they are different facts, and a person reading the screen has to be able to tell
 * them apart without opening the row.
 */
class FoodLabelsUnknownTest {

    @Test
    fun `an unknown energy is a dash and never a zero`() {
        assertEquals("—", FoodLabels.energy(null))
        assertEquals(FoodLabels.UNKNOWN, FoodLabels.energy(null))
    }

    @Test
    fun `a known zero energy reads as a zero, which is not a dash`() {
        assertEquals("≈ 0 kcal", FoodLabels.energy(Energy.ZERO))
        assertNotEquals(FoodLabels.energy(null), FoodLabels.energy(Energy.ZERO))
    }

    @Test
    fun `an unknown macronutrient is a dash and a known zero is a zero`() {
        assertEquals("—", FoodLabels.macro(null))
        assertEquals("≈ 0.0 g", FoodLabels.macro(Macro.ZERO))
        assertNotEquals(FoodLabels.macro(null), FoodLabels.macro(Macro.ZERO))
    }

    @Test
    fun `an unknown quantity is a dash`() {
        assertEquals("—", FoodLabels.quantity(null, ReferenceUnit.GRAM))
    }

    @Test
    fun `an unknown serving count is a dash`() {
        assertEquals("—", FoodLabels.servings(null))
    }

    @Test
    fun `a bundle that knows nothing reads as five dashes`() {
        assertEquals("—", FoodLabels.energy(Nutrients.UNKNOWN.energy))
        assertEquals(listOf("—", "—", "—", "—"), FoodLabels.macros(Nutrients.UNKNOWN))
    }

    @Test
    fun `a bundle of known zeroes reads as zeroes`() {
        assertEquals(
            listOf("≈ 0.0 g", "≈ 0.0 g", "≈ 0.0 g", "≈ 0.0 g"),
            FoodLabels.macros(Nutrients.ZERO),
        )
    }

    @Test
    fun `a day with a known energy and unknown protein shows one of each`() {
        val partial = Nutrients(energy = kcalOf(1_800.0))
        assertEquals("≈ 1800 kcal", FoodLabels.energy(partial.energy))
        assertEquals("—", FoodLabels.macro(partial.protein))
    }
}

/** PRD_FOOD 13.2: the kilocalorie for an energy, the tenth of a gram for a macronutrient. */
class FoodLabelsRoundingTest {

    @Test
    fun `an energy is rounded to the unit`() {
        assertEquals("≈ 134 kcal", FoodLabels.energy(kcalOf(133.5)))
        assertEquals("≈ 133 kcal", FoodLabels.energy(kcalOf(133.4)))
        assertEquals("≈ 89 kcal", FoodLabels.energy(kcalOf(89.0)))
    }

    @Test
    fun `the chicken contribution of PRD_FOOD 22 reads as a whole number of kilocalories`() {
        val cooked = NutritionMath.foodContribution(chickenBreast(), quantityOf(150.0), weighedCooked = true)
        assertEquals("≈ 344 kcal", FoodLabels.energy(cooked.energy))
    }

    @Test
    fun `a macronutrient is rounded to the tenth of a gram`() {
        assertEquals("≈ 5.0 g", FoodLabels.macro(macroOf(5.0)))
        assertEquals("≈ 5.1 g", FoodLabels.macro(macroOf(5.05)))
        assertEquals("≈ 5.0 g", FoodLabels.macro(macroOf(5.04)))
        assertEquals("≈ 31.4 g", FoodLabels.macro(macroOf(31.4)))
    }

    @Test
    fun `a macronutrient keeps its tenth even when the tenth is zero`() {
        assertEquals("≈ 12.0 g", FoodLabels.macro(macroOf(12.0)))
    }

    @Test
    fun `a computed value carries the approximation mark PRD_FOOD 22 requires`() {
        assertEquals("≈ 250 kcal", FoodLabels.energy(kcalOf(250.0)))
        assertEquals("≈ 1.5 g", FoodLabels.macro(macroOf(1.5)))
    }

    @Test
    fun `a value that was typed unchanged may be shown without the mark`() {
        assertEquals("250 kcal", FoodLabels.energy(kcalOf(250.0), approximate = false))
        assertEquals("1.5 g", FoodLabels.macro(macroOf(1.5), approximate = false))
    }

    @Test
    fun `a quantity drops the trailing zeros it does not need`() {
        assertEquals("150 g", FoodLabels.quantity(quantityOf(150.0), ReferenceUnit.GRAM))
        assertEquals("225 ml", FoodLabels.quantity(quantityOf(225.0), ReferenceUnit.MILLILITRE))
        assertEquals("12.5 g", FoodLabels.quantity(quantityOf(12.5), ReferenceUnit.GRAM))
    }

    @Test
    fun `a converted weight keeps the thousandth the conversion produced`() {
        assertEquals("208.333 g", FoodLabels.quantity(quantityOf(208.333), ReferenceUnit.GRAM))
        assertEquals("108.696 g", FoodLabels.quantity(quantityOf(108.696), ReferenceUnit.GRAM))
    }

    @Test
    fun `a quantity carries no approximation mark, because it was measured`() {
        assertEquals("150 g", FoodLabels.quantity(quantityOf(150.0), ReferenceUnit.GRAM))
    }

    @Test
    fun `a serving count drops the decimals it does not need`() {
        assertEquals("1", FoodLabels.servings(servingsOf(1.0)))
        assertEquals("1.5", FoodLabels.servings(servingsOf(1.5)))
        assertEquals("0.25", FoodLabels.servings(servingsOf(0.25)))
        assertEquals("10", FoodLabels.servings(servingsOf(10.0)))
    }
}

/** PRD_FOOD 13.2: "le libelle d'une quantite conserve les deux lectures quand elles existent". */
class FoodLabelsAmountTest {

    private val apple = foodOf(
        name = "Apple",
        per100 = per100(energy = 52.0),
        servingLabel = "apple",
        servingSize = quantityOf(150.0),
        id = "food-apple",
    )

    @Test
    fun `both readings are kept when both exist`() {
        val amount = LoggedAmount.Measured(quantityOf(225.0), ReferenceUnit.GRAM)
        assertEquals(
            "1.5 × apple (225 g)",
            FoodLabels.amountLabel(amount, apple, portions = servingsOf(1.5)),
        )
    }

    @Test
    fun `PRD_FOOD 22 - an exact weight keeps only one reading`() {
        val amount = LoggedAmount.Measured(quantityOf(225.0), ReferenceUnit.GRAM)
        assertEquals("225 g", FoodLabels.amountLabel(amount, apple, portions = null))
    }

    @Test
    fun `a cooked reading is spelled out beside the weight`() {
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        assertEquals(
            "150 g cooked",
            FoodLabels.amountLabel(amount, chickenBreast(), weighedCooked = true),
        )
    }

    @Test
    fun `a food naming its cooked state differently is named that way`() {
        val roast = foodOf(cookedRatio = ratioOf(0.72), cookedLabel = "Roasted")
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        assertEquals("150 g roasted", FoodLabels.amountLabel(amount, roast, weighedCooked = true))
    }

    @Test
    fun `a weight read in the reference state says nothing about cooking`() {
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        assertEquals("150 g", FoodLabels.amountLabel(amount, chickenBreast(), weighedCooked = false))
    }

    @Test
    fun `a recipe line reads as a count of servings`() {
        val amount = LoggedAmount.Portioned(servingsOf(1.5))
        assertEquals("1.5 × serving", FoodLabels.amountLabel(amount))
    }

    @Test
    fun `a quick add has no quantity to show at all`() {
        assertNull(FoodLabels.amountLabel(LoggedAmount.Unmeasured))
    }

    @Test
    fun `a weight with no food behind it still reads`() {
        val amount = LoggedAmount.Measured(quantityOf(80.0), ReferenceUnit.GRAM)
        assertEquals("80 g", FoodLabels.amountLabel(amount))
    }

    @Test
    fun `a millilitre food reads in millilitres`() {
        val amount = LoggedAmount.Measured(quantityOf(250.0), ReferenceUnit.MILLILITRE)
        assertEquals("250 ml", FoodLabels.amountLabel(amount))
    }

    @Test
    fun `the cooked word is the lower-cased label of the food`() {
        assertEquals("cooked", FoodLabels.cookedSuffix(chickenBreast()))
        assertEquals("roasted", FoodLabels.cookedSuffix(foodOf(cookedLabel = " Roasted ")))
    }

    @Test
    fun `a portion label with no portions given falls back to the weight alone`() {
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        val noLabel = foodOf(servingLabel = null, servingSize = quantityOf(150.0))
        assertEquals("150 g", FoodLabels.amountLabel(amount, noLabel, portions = servingsOf(1.0)))
    }
}

/**
 * PRD_FOOD 13.2 asks for `tabular-nums`, not for a locale. These run the real labels with the
 * JVM default locale set to `fr-FR`, which would break any implementation built on a formatter.
 */
class FoodLabelsLocaleTest {

    @get:Rule
    val localeRule = LocaleRule(Locale.FRANCE)

    @Test
    fun `the rule really switches the default locale`() {
        assertEquals(Locale.FRANCE, Locale.getDefault())
        assertNotEquals("133.5", String.format("%.1f", 133.5))
    }

    @Test
    fun `a French phone shows the same decimal separator as any other`() {
        assertEquals("≈ 5.1 g", FoodLabels.macro(macroOf(5.05)))
        assertEquals("208.333 g", FoodLabels.quantity(quantityOf(208.333), ReferenceUnit.GRAM))
        assertEquals("1.5", FoodLabels.servings(servingsOf(1.5)))
    }

    @Test
    fun `a French phone shows the same energy`() {
        assertEquals("≈ 1800 kcal", FoodLabels.energy(kcalOf(1_800.0)))
    }

    @Test
    fun `a French phone shows the same dash for an unknown value`() {
        assertEquals("—", FoodLabels.energy(null))
        assertEquals("—", FoodLabels.macro(null))
    }

    @Test
    fun `a Turkish phone lower-cases the cooked label the same way`() {
        assertEquals("cooked", FoodLabels.cookedSuffix(foodOf(cookedLabel = "COOKED")))
    }

    @Test
    fun `a French phone builds the same quantity label`() {
        val amount = LoggedAmount.Measured(quantityOf(225.0), ReferenceUnit.GRAM)
        val apple = foodOf(servingLabel = "apple", servingSize = quantityOf(150.0))
        assertEquals("1.5 × apple (225 g)", FoodLabels.amountLabel(amount, apple, servingsOf(1.5)))
    }
}
