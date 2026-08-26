package fr.kristenjestin.mue.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuantityTest {

    @Test
    fun `a quantity of nothing is not a quantity`() {
        assertNull(Quantity.ofThousandthsOrNull(0L))
        assertNull(Quantity.ofThousandthsOrNull(-1L))
        assertEquals(1, Quantity.ofThousandthsOrNull(1L)?.thousandths)
    }

    @Test
    fun `the canonical ceiling is the last value an Int holds, and the next one is null`() {
        assertEquals(Int.MAX_VALUE, Quantity.ofThousandthsOrNull(2_147_483_647L)?.thousandths)
        assertNull(Quantity.ofThousandthsOrNull(2_147_483_648L))
        assertNull(Quantity.ofThousandthsOrNull(Long.MAX_VALUE))
    }

    @Test
    fun `PRD_FOOD 15 bounds an ingredient strictly above zero and at five thousand grams`() {
        assertEquals(1, Quantity.INGREDIENT_MIN_THOUSANDTHS)
        assertEquals(5_000_000, Quantity.INGREDIENT_MAX_THOUSANDTHS)
        assertNull(Quantity.ofIngredientOrNull(0.0))
        assertEquals(1, Quantity.ofIngredientOrNull(0.001)?.thousandths)
        assertEquals(5_000_000, Quantity.ofIngredientOrNull(5_000.0)?.thousandths)
        assertNull(Quantity.ofIngredientOrNull(5_000.001))
    }

    @Test
    fun `PRD_FOOD 15 bounds a usual serving between one and two thousand grams`() {
        assertEquals(1_000, Quantity.USUAL_SERVING_MIN_THOUSANDTHS)
        assertEquals(2_000_000, Quantity.USUAL_SERVING_MAX_THOUSANDTHS)
        assertNull(Quantity.ofUsualServingOrNull(0.999))
        assertEquals(1_000, Quantity.ofUsualServingOrNull(1.0)?.thousandths)
        assertEquals(2_000_000, Quantity.ofUsualServingOrNull(2_000.0)?.thousandths)
        assertNull(Quantity.ofUsualServingOrNull(2_000.001))
    }

    @Test
    fun `the two sets of bounds judge the same value independently`() {
        val gram = assertNotNull(Quantity.ofAmountOrNull(1.0))
        assertTrue(gram.isIngredientAmount)
        assertTrue(gram.isUsualServingSize)
        val crumb = assertNotNull(Quantity.ofAmountOrNull(0.5))
        assertTrue(crumb.isIngredientAmount)
        assertFalse(crumb.isUsualServingSize)
        val heap = assertNotNull(Quantity.ofAmountOrNull(3_000.0))
        assertTrue(heap.isIngredientAmount)
        assertFalse(heap.isUsualServingSize)
    }

    @Test
    fun `a quantity survives the trip through its display accessor`() {
        assertEquals(150.5, assertNotNull(Quantity.ofAmountOrNull(150.5)).amount)
        assertEquals(0.001, assertNotNull(Quantity.ofAmountOrNull(0.001)).amount)
        assertEquals(5_000.0, assertNotNull(Quantity.ofAmountOrNull(5_000.0)).amount)
        assertEquals(1_000, Quantity.THOUSANDTHS_PER_UNIT)
    }

    @Test
    fun `a text field offering infinity or a nonsense number is refused, never wrapped`() {
        assertNull(Quantity.ofAmountOrNull(Double.NaN))
        assertNull(Quantity.ofAmountOrNull(Double.POSITIVE_INFINITY))
        assertNull(Quantity.ofAmountOrNull(Double.NEGATIVE_INFINITY))
        assertNull(Quantity.ofAmountOrNull(1e30))
        assertNull(Quantity.ofIngredientOrNull(1e30))
    }

    @Test
    fun `PRD_FOOD 13-1 divides a cooked weight by the ratio to reach the reference weight`() {
        val pasta = assertNotNull(CookedRatio.ofRatioOrNull(2.3))
        val cooked = assertNotNull(Quantity.ofAmountOrNull(230.0))
        assertEquals(100_000, cooked.toReferenceWeightOrNull(pasta)?.thousandths)

        val chicken = assertNotNull(CookedRatio.ofRatioOrNull(0.72))
        val weighed = assertNotNull(Quantity.ofAmountOrNull(150.0))
        assertEquals(208_333, weighed.toReferenceWeightOrNull(chicken)?.thousandths)
    }

    @Test
    fun `losing water makes the reference weight larger, which is why the same grams count more`() {
        val chicken = assertNotNull(CookedRatio.ofRatioOrNull(0.72))
        val weighed = assertNotNull(Quantity.ofAmountOrNull(150.0))
        val reference = assertNotNull(weighed.toReferenceWeightOrNull(chicken))
        assertTrue(reference > weighed)
    }

    @Test
    fun `a reference weight that no longer fits an Int comes back null rather than wrapped`() {
        val floor = assertNotNull(CookedRatio.ofThousandthsOrNull(CookedRatio.MIN_THOUSANDTHS.toLong()))
        val huge = assertNotNull(Quantity.ofThousandthsOrNull(2_147_483_647L))
        assertNull(huge.toReferenceWeightOrNull(floor))
    }

    @Test
    fun `a reference weight may legitimately exceed the ingredient ceiling`() {
        val floor = assertNotNull(CookedRatio.ofRatioOrNull(0.3))
        val weighed = assertNotNull(Quantity.ofAmountOrNull(5_000.0))
        val reference = assertNotNull(weighed.toReferenceWeightOrNull(floor))
        assertEquals(16_666_667, reference.thousandths)
        assertFalse(reference.isIngredientAmount)
    }

    @Test
    fun `quantities order by their canonical value`() {
        val small = assertNotNull(Quantity.ofAmountOrNull(1.0))
        val large = assertNotNull(Quantity.ofAmountOrNull(2.0))
        assertTrue(small < large)
        assertEquals(small, assertNotNull(Quantity.ofAmountOrNull(1.0)))
    }
}

class EnergyTest {

    @Test
    fun `zero is a known energy and a negative one is not an energy at all`() {
        assertEquals(0, Energy.ZERO.milliKcal)
        assertEquals(0, Energy.ofMilliKcalOrNull(0L)?.milliKcal)
        assertNull(Energy.ofMilliKcalOrNull(-1L))
        assertNull(Energy.ofKilocaloriesOrNull(-0.001))
    }

    @Test
    fun `PRD_FOOD 15 bounds a per-100 energy at zero and at nine hundred kilocalories`() {
        assertEquals(0, Energy.PER_100_MIN_MILLI_KCAL)
        assertEquals(900_000, Energy.PER_100_MAX_MILLI_KCAL)
        assertEquals(0, Energy.ofPer100OrNull(0.0)?.milliKcal)
        assertEquals(900_000, Energy.ofPer100OrNull(900.0)?.milliKcal)
        assertNull(Energy.ofPer100OrNull(900.001))
        assertNull(Energy.ofPer100OrNull(-0.001))
    }

    @Test
    fun `PRD_FOOD 15 lets a quick add quote a whole plate up to five thousand kilocalories`() {
        assertEquals(5_000_000, Energy.QUICK_ADD_MAX_MILLI_KCAL)
        assertEquals(0, Energy.ofQuickAddOrNull(0.0)?.milliKcal)
        assertEquals(5_000_000, Energy.ofQuickAddOrNull(5_000.0)?.milliKcal)
        assertNull(Energy.ofQuickAddOrNull(5_000.001))
    }

    @Test
    fun `the quick-add ceiling is above the per-100 one, and both judge the same value`() {
        val plate = assertNotNull(Energy.ofKilocaloriesOrNull(1_200.0))
        assertFalse(plate.isPer100Value)
        assertTrue(plate.isQuickAddValue)
        val butter = assertNotNull(Energy.ofKilocaloriesOrNull(750.0))
        assertTrue(butter.isPer100Value)
        assertTrue(butter.isQuickAddValue)
    }

    @Test
    fun `an energy survives the trip through its display accessor`() {
        assertEquals(133.5, assertNotNull(Energy.ofKilocaloriesOrNull(133.5)).kilocalories)
        assertEquals(0.0, Energy.ZERO.kilocalories)
        assertEquals(89.0, assertNotNull(Energy.ofPer100OrNull(89.0)).kilocalories)
        assertEquals(1_000, Energy.MILLI_PER_KCAL)
    }

    @Test
    fun `an energy beyond what an Int holds is null rather than a wrapped total`() {
        assertEquals(Int.MAX_VALUE, Energy.ofMilliKcalOrNull(2_147_483_647L)?.milliKcal)
        assertNull(Energy.ofMilliKcalOrNull(2_147_483_648L))
        assertNull(Energy.ofMilliKcalOrNull(Long.MAX_VALUE))
        assertNull(Energy.ofKilocaloriesOrNull(1e30))
        assertNull(Energy.ofKilocaloriesOrNull(Double.NaN))
        assertNull(Energy.ofKilocaloriesOrNull(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `energies order by their canonical value`() {
        val light = assertNotNull(Energy.ofKilocaloriesOrNull(50.0))
        val heavy = assertNotNull(Energy.ofKilocaloriesOrNull(500.0))
        assertTrue(light < heavy)
        assertTrue(Energy.ZERO < light)
    }
}

class MacroTest {

    @Test
    fun `zero grams of a macronutrient is a known value`() {
        assertEquals(0, Macro.ZERO.milligrams)
        assertEquals(0, Macro.ofMilligramsOrNull(0L)?.milligrams)
        assertNull(Macro.ofMilligramsOrNull(-1L))
        assertNull(Macro.ofGramsOrNull(-0.001))
    }

    @Test
    fun `PRD_FOOD 15 bounds a per-100 macronutrient at zero and at a hundred grams`() {
        assertEquals(0, Macro.PER_100_MIN_MILLIGRAMS)
        assertEquals(100_000, Macro.PER_100_MAX_MILLIGRAMS)
        assertEquals(0, Macro.ofPer100OrNull(0.0)?.milligrams)
        assertEquals(100_000, Macro.ofPer100OrNull(100.0)?.milligrams)
        assertNull(Macro.ofPer100OrNull(100.001))
        assertNull(Macro.ofPer100OrNull(-0.001))
    }

    @Test
    fun `a macronutrient survives the trip through its display accessor`() {
        assertEquals(1.3, assertNotNull(Macro.ofGramsOrNull(1.3)).grams)
        assertEquals(0.0, Macro.ZERO.grams)
        assertEquals(20.8, assertNotNull(Macro.ofPer100OrNull(20.8)).grams)
        assertEquals(1_000, Macro.MILLIGRAMS_PER_GRAM)
    }

    @Test
    fun `a macronutrient beyond what an Int holds is null rather than wrapped`() {
        assertEquals(Int.MAX_VALUE, Macro.ofMilligramsOrNull(2_147_483_647L)?.milligrams)
        assertNull(Macro.ofMilligramsOrNull(2_147_483_648L))
        assertNull(Macro.ofGramsOrNull(1e30))
        assertNull(Macro.ofGramsOrNull(Double.NaN))
        assertNull(Macro.ofGramsOrNull(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `a total above the per-100 ceiling is still a macronutrient, it is only not a per-100 one`() {
        val total = assertNotNull(Macro.ofGramsOrNull(250.0))
        assertFalse(total.isPer100Value)
        assertEquals(250_000, total.milligrams)
    }

    @Test
    fun `macronutrients order by their canonical value`() {
        val little = assertNotNull(Macro.ofGramsOrNull(0.5))
        val lots = assertNotNull(Macro.ofGramsOrNull(30.0))
        assertTrue(little < lots)
        assertTrue(Macro.ZERO < little)
    }
}

class ServingsTest {

    @Test
    fun `PRD_FOOD 15 bounds a consumed count at a quarter serving and at ten`() {
        assertEquals(250, Servings.CONSUMED_MIN_THOUSANDTHS)
        assertEquals(10_000, Servings.CONSUMED_MAX_THOUSANDTHS)
        assertEquals(250, Servings.ofConsumedOrNull(0.25)?.thousandths)
        assertEquals(10_000, Servings.ofConsumedOrNull(10.0)?.thousandths)
        assertNull(Servings.ofConsumedOrNull(0.1))
        assertNull(Servings.ofConsumedOrNull(10.2))
    }

    @Test
    fun `a consumed count lands on the quarter step PRD_FOOD 15 defines`() {
        assertEquals(250, Servings.CONSUMED_STEP_THOUSANDTHS)
        assertEquals(1_250, Servings.ofConsumedOrNull(1.3)?.thousandths)
        assertEquals(1_500, Servings.ofConsumedOrNull(1.4)?.thousandths)
        assertEquals(1.25, assertNotNull(Servings.ofConsumedOrNull(1.3)).count)
    }

    @Test
    fun `PRD_FOOD 15 bounds a usual portion count at a half serving and at twenty`() {
        assertEquals(500, Servings.USUAL_MIN_THOUSANDTHS)
        assertEquals(20_000, Servings.USUAL_MAX_THOUSANDTHS)
        assertEquals(500, Servings.ofUsualOrNull(0.5)?.thousandths)
        assertEquals(20_000, Servings.ofUsualOrNull(20.0)?.thousandths)
        assertNull(Servings.ofUsualOrNull(0.1))
        assertNull(Servings.ofUsualOrNull(20.3))
    }

    @Test
    fun `a usual portion count lands on the half step PRD_FOOD 15 defines`() {
        assertEquals(500, Servings.USUAL_STEP_THOUSANDTHS)
        assertEquals(1_500, Servings.ofUsualOrNull(1.5)?.thousandths)
        assertEquals(1_500, Servings.ofUsualOrNull(1.6)?.thousandths)
        assertEquals(2_000, Servings.ofUsualOrNull(1.8)?.thousandths)
    }

    @Test
    fun `rounding happens before the range check, so a value just inside stays inside`() {
        assertEquals(250, Servings.ofConsumedOrNull(0.24)?.thousandths)
        assertEquals(10_000, Servings.ofConsumedOrNull(10.1)?.thousandths)
        assertEquals(500, Servings.ofUsualOrNull(0.4)?.thousandths)
        assertEquals(20_000, Servings.ofUsualOrNull(20.2)?.thousandths)
    }

    @Test
    fun `the two steps judge the same stored count differently`() {
        val threeQuarters = assertNotNull(Servings.ofThousandthsOrNull(750L))
        assertTrue(threeQuarters.isConsumedCount)
        assertFalse(threeQuarters.isUsualCount)
        val half = assertNotNull(Servings.ofThousandthsOrNull(500L))
        assertTrue(half.isConsumedCount)
        assertTrue(half.isUsualCount)
    }

    @Test
    fun `a count off its step is refused even when it sits inside the range`() {
        val stray = assertNotNull(Servings.ofThousandthsOrNull(300L))
        assertFalse(stray.isConsumedCount)
        assertFalse(stray.isUsualCount)
        val fifteen = assertNotNull(Servings.ofThousandthsOrNull(15_000L))
        assertFalse(fifteen.isConsumedCount)
        assertTrue(fifteen.isUsualCount)
    }

    @Test
    fun `no serving at all is not a line of the journal`() {
        assertNull(Servings.ofThousandthsOrNull(0L))
        assertNull(Servings.ofThousandthsOrNull(-1L))
        assertNull(Servings.ofCountOrNull(0.0))
    }

    @Test
    fun `a serving count survives the trip through its display accessor`() {
        assertEquals(1.0, Servings.ONE.count)
        assertEquals(1_000, Servings.THOUSANDTHS_PER_SERVING)
        assertEquals(2.5, assertNotNull(Servings.ofConsumedOrNull(2.5)).count)
        assertEquals(7.5, assertNotNull(Servings.ofUsualOrNull(7.5)).count)
    }

    @Test
    fun `a step count big enough to overflow is refused rather than wrapped`() {
        assertNull(Servings.ofConsumedOrNull(1e30))
        assertNull(Servings.ofUsualOrNull(1e30))
        assertNull(Servings.ofCountOrNull(1e30))
        assertNull(Servings.ofCountOrNull(Double.NaN))
        assertNull(Servings.ofConsumedOrNull(Double.POSITIVE_INFINITY))
        assertNull(Servings.ofUsualOrNull(Double.NaN))
        assertNull(Servings.ofThousandthsOrNull(2_147_483_648L))
    }

    @Test
    fun `serving counts order by their canonical value`() {
        val quarter = assertNotNull(Servings.ofConsumedOrNull(0.25))
        assertTrue(quarter < Servings.ONE)
        assertEquals(Servings.ONE, assertNotNull(Servings.ofCountOrNull(1.0)))
    }
}

class CookedRatioTest {

    @Test
    fun `PRD_FOOD 15 bounds a ratio at three tenths and at five`() {
        assertEquals(300, CookedRatio.MIN_THOUSANDTHS)
        assertEquals(5_000, CookedRatio.MAX_THOUSANDTHS)
        assertEquals(300, CookedRatio.ofRatioOrNull(0.3)?.thousandths)
        assertEquals(5_000, CookedRatio.ofRatioOrNull(5.0)?.thousandths)
        assertNull(CookedRatio.ofRatioOrNull(0.299))
        assertNull(CookedRatio.ofRatioOrNull(5.001))
    }

    @Test
    fun `a ratio of zero or below is not a ratio`() {
        assertNull(CookedRatio.ofRatioOrNull(0.0))
        assertNull(CookedRatio.ofRatioOrNull(-1.0))
        assertNull(CookedRatio.ofThousandthsOrNull(0L))
        assertNull(CookedRatio.ofThousandthsOrNull(-300L))
    }

    @Test
    fun `the four ratios of PRD_FOOD 8-6 survive the trip through the display accessor`() {
        assertEquals(2.3, assertNotNull(CookedRatio.ofRatioOrNull(2.3)).ratio)
        assertEquals(2.8, assertNotNull(CookedRatio.ofRatioOrNull(2.8)).ratio)
        assertEquals(2.4, assertNotNull(CookedRatio.ofRatioOrNull(2.4)).ratio)
        assertEquals(0.72, assertNotNull(CookedRatio.ofRatioOrNull(0.72)).ratio)
        assertEquals(1_000, CookedRatio.THOUSANDTHS_PER_UNIT)
    }

    @Test
    fun `absorbing water and losing it are the same ratio read in two directions`() {
        assertTrue(assertNotNull(CookedRatio.ofRatioOrNull(2.3)).absorbsWater)
        assertFalse(assertNotNull(CookedRatio.ofRatioOrNull(0.72)).absorbsWater)
        assertFalse(assertNotNull(CookedRatio.ofRatioOrNull(1.0)).absorbsWater)
    }

    @Test
    fun `a nonsense ratio from a text field is refused, never wrapped`() {
        assertNull(CookedRatio.ofRatioOrNull(Double.NaN))
        assertNull(CookedRatio.ofRatioOrNull(Double.POSITIVE_INFINITY))
        assertNull(CookedRatio.ofRatioOrNull(1e30))
        assertNull(CookedRatio.ofThousandthsOrNull(Long.MAX_VALUE))
    }

    @Test
    fun `ratios order by their canonical value`() {
        val dries = assertNotNull(CookedRatio.ofRatioOrNull(0.72))
        val soaks = assertNotNull(CookedRatio.ofRatioOrNull(2.3))
        assertTrue(dries < soaks)
        assertEquals(soaks, assertNotNull(CookedRatio.ofThousandthsOrNull(2_300L)))
    }
}
