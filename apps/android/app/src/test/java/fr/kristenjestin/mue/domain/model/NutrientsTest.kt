package fr.kristenjestin.mue.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The five metrics as a list, so a test can assert on all of them without naming each one. */
private val Nutrients.all: List<Any?> get() = listOf(energy, protein, carbs, fat, fibre)

private fun kcal(kilocalories: Double): Energy = assertNotNull(Energy.ofKilocaloriesOrNull(kilocalories))

private fun grams(value: Double): Macro = assertNotNull(Macro.ofGramsOrNull(value))

class NutrientsConstantsTest {

    @Test
    fun `UNKNOWN is five unknowns and nothing else`() {
        assertTrue(Nutrients.UNKNOWN.all.all { it == null })
        assertTrue(Nutrients.UNKNOWN.isUnknown)
        assertFalse(Nutrients.UNKNOWN.isFullyKnown)
    }

    @Test
    fun `ZERO is five known zeroes, and is not UNKNOWN`() {
        assertEquals(Energy.ZERO, Nutrients.ZERO.energy)
        assertEquals(Macro.ZERO, Nutrients.ZERO.protein)
        assertEquals(Macro.ZERO, Nutrients.ZERO.carbs)
        assertEquals(Macro.ZERO, Nutrients.ZERO.fat)
        assertEquals(Macro.ZERO, Nutrients.ZERO.fibre)
        assertTrue(Nutrients.ZERO.isFullyKnown)
        assertFalse(Nutrients.ZERO.isUnknown)
        assertFalse(Nutrients.ZERO == Nutrients.UNKNOWN)
    }

    @Test
    fun `a default bundle knows nothing, which is the nominal state of an incomplete card`() {
        assertEquals(Nutrients.UNKNOWN, Nutrients())
    }

    @Test
    fun `a per-100 value is quoted for a hundred thousand thousandths`() {
        assertEquals(100_000L, Nutrients.PER_100_THOUSANDTHS)
    }

    @Test
    fun `a bundle with one known metric is neither fully known nor unknown`() {
        val partial = Nutrients(energy = kcal(89.0))
        assertFalse(partial.isUnknown)
        assertFalse(partial.isFullyKnown)
    }
}

class NutrientsStrictSumTest {

    @Test
    fun `an empty sum is a known zero, because a total of nothing is not a mystery`() {
        assertEquals(Nutrients.ZERO, Nutrients.strictSum(emptyList()))
        assertTrue(Nutrients.strictSum(emptyList()).isFullyKnown)
    }

    @Test
    fun `a single unknown contribution makes every metric of the total unknown`() {
        val total = Nutrients.strictSum(listOf(Nutrients.UNKNOWN))
        assertTrue(total.all.all { it == null })
        assertTrue(total.isUnknown)
    }

    @Test
    fun `PRD_FOOD 13-1 propagates metric by metric, not bundle by bundle`() {
        val known = Nutrients(energy = kcal(100.0), protein = grams(10.0))
        val partial = Nutrients(energy = kcal(50.0), protein = null)
        val total = Nutrients.strictSum(listOf(known, partial))
        assertEquals(150_000, total.energy?.milliKcal)
        assertNull(total.protein)
    }

    @Test
    fun `known contributions add up normally`() {
        val lines = listOf(
            Nutrients(kcal(100.0), grams(1.0), grams(2.0), grams(3.0), grams(4.0)),
            Nutrients(kcal(50.5), grams(0.5), grams(0.5), grams(0.5), grams(0.5)),
        )
        val total = Nutrients.strictSum(lines)
        assertEquals(150_500, total.energy?.milliKcal)
        assertEquals(1_500, total.protein?.milligrams)
        assertEquals(2_500, total.carbs?.milligrams)
        assertEquals(3_500, total.fat?.milligrams)
        assertEquals(4_500, total.fibre?.milligrams)
    }

    @Test
    fun `a known zero contributes zero and never turns a total unknown`() {
        val total = Nutrients.strictSum(listOf(Nutrients.ZERO, Nutrients.ZERO, Nutrients.ZERO))
        assertEquals(Nutrients.ZERO, total)
    }

    @Test
    fun `one unknown among many known contributions still sinks its own metric`() {
        val lines = listOf(
            Nutrients(kcal(100.0), grams(1.0)),
            Nutrients(kcal(100.0), grams(1.0)),
            Nutrients(kcal(100.0), protein = null),
            Nutrients(kcal(100.0), grams(1.0)),
        )
        val total = Nutrients.strictSum(lines)
        assertEquals(400_000, total.energy?.milliKcal)
        assertNull(total.protein)
    }

    @Test
    fun `summing the same list twice gives the same total`() {
        val lines = listOf(Nutrients(kcal(33.3), grams(1.1)), Nutrients(kcal(66.6), grams(2.2)))
        assertEquals(Nutrients.strictSum(lines), Nutrients.strictSum(lines))
    }
}

class NutrientsPlusTest {

    /** A deliberate mix: full, empty, all-zero, all-unknown, and two half-known bundles. */
    private val samples: List<Nutrients> = listOf(
        Nutrients.ZERO,
        Nutrients.UNKNOWN,
        Nutrients(kcal(89.0), grams(0.3), grams(11.6), grams(0.2), grams(2.0)),
        Nutrients(energy = Energy.ZERO, protein = null, carbs = grams(5.0), fat = null, fibre = Macro.ZERO),
        Nutrients(energy = kcal(250.5), protein = Macro.ZERO, carbs = null, fat = grams(30.0), fibre = null),
        Nutrients(energy = null, protein = grams(0.0), carbs = grams(0.0), fat = grams(0.0), fibre = null),
    )

    @Test
    fun `plus is commutative over a set mixing nulls and zeroes`() {
        for (left in samples) {
            for (right in samples) {
                assertEquals(left + right, right + left, "$left + $right")
            }
        }
    }

    @Test
    fun `plus is associative over a set mixing nulls and zeroes`() {
        for (a in samples) {
            for (b in samples) {
                for (c in samples) {
                    assertEquals((a + b) + c, a + (b + c), "($a + $b) + $c")
                }
            }
        }
    }

    @Test
    fun `ZERO is the identity of plus, and UNKNOWN its absorbing element`() {
        for (sample in samples) {
            assertEquals(sample, sample + Nutrients.ZERO)
            assertEquals(sample, Nutrients.ZERO + sample)
            assertTrue((sample + Nutrients.UNKNOWN).isUnknown)
            assertTrue((Nutrients.UNKNOWN + sample).isUnknown)
        }
    }

    @Test
    fun `a known value plus an unknown one is unknown, never the known one alone`() {
        val sum = Nutrients(energy = kcal(100.0)) + Nutrients(energy = null)
        assertNull(sum.energy)
    }

    @Test
    fun `a known zero plus a known value is that value, which is why zero is not null`() {
        val sum = Nutrients(energy = Energy.ZERO) + Nutrients(energy = kcal(100.0))
        assertEquals(100_000, sum.energy?.milliKcal)
    }

    @Test
    fun `a sum too large for an Int comes back unknown rather than wrapped negative`() {
        val huge = Nutrients(
            energy = assertNotNull(Energy.ofMilliKcalOrNull(2_000_000_000L)),
            protein = assertNotNull(Macro.ofMilligramsOrNull(2_000_000_000L)),
        )
        val sum = huge + huge
        assertNull(sum.energy)
        assertNull(sum.protein)
    }

    @Test
    fun `associativity survives the overflow guard, because no contribution is negative`() {
        val big = Nutrients(energy = assertNotNull(Energy.ofMilliKcalOrNull(1_500_000_000L)))
        val small = Nutrients(energy = kcal(1.0))
        assertEquals((big + big) + small, big + (big + small))
        assertNull(((big + big) + small).energy)
    }
}

class NutrientsScaledTest {

    private val apple = Nutrients(
        energy = kcal(89.0),
        protein = grams(0.3),
        carbs = grams(11.6),
        fat = grams(0.2),
        fibre = grams(2.0),
    )

    @Test
    fun `PRD_FOOD 13-1 scales a per-100 bundle by the reference weight`() {
        val weight = assertNotNull(Quantity.ofAmountOrNull(150.0))
        val contribution = apple.scaled(weight.thousandths.toLong(), Nutrients.PER_100_THOUSANDTHS)
        assertEquals(133_500, contribution.energy?.milliKcal)
        assertEquals(133.5, assertNotNull(contribution.energy).kilocalories)
        assertEquals(450, contribution.protein?.milligrams)
        assertEquals(17_400, contribution.carbs?.milligrams)
        assertEquals(300, contribution.fat?.milligrams)
        assertEquals(3_000, contribution.fibre?.milligrams)
    }

    @Test
    fun `scaling by one hundred grams returns the per-100 values unchanged`() {
        val weight = assertNotNull(Quantity.ofAmountOrNull(100.0))
        assertEquals(apple, apple.scaled(weight.thousandths.toLong(), Nutrients.PER_100_THOUSANDTHS))
    }

    @Test
    fun `PRD_FOOD 13-1 divides a recipe total by its base servings`() {
        val total = Nutrients(kcal(1_200.0), grams(60.0), grams(120.0), grams(40.0), grams(12.0))
        val perServing = total.scaled(1L, 4L)
        assertEquals(300_000, perServing.energy?.milliKcal)
        assertEquals(15_000, perServing.protein?.milligrams)
        assertEquals(30_000, perServing.carbs?.milligrams)
        assertEquals(10_000, perServing.fat?.milligrams)
        assertEquals(3_000, perServing.fibre?.milligrams)
    }

    @Test
    fun `PRD_FOOD 13-1 multiplies a per-serving value by the servings consumed`() {
        val perServing = Nutrients(energy = kcal(300.0), protein = grams(15.0))
        val servings = assertNotNull(Servings.ofConsumedOrNull(1.5))
        val line = perServing.scaled(
            servings.thousandths.toLong(),
            Servings.THOUSANDTHS_PER_SERVING.toLong(),
        )
        assertEquals(450_000, line.energy?.milliKcal)
        assertEquals(22_500, line.protein?.milligrams)
    }

    @Test
    fun `an unknown metric stays unknown however it is scaled`() {
        val partial = Nutrients(energy = kcal(100.0), protein = null)
        val scaled = partial.scaled(3L, 2L)
        assertEquals(150_000, scaled.energy?.milliKcal)
        assertNull(scaled.protein)
        assertTrue(Nutrients.UNKNOWN.scaled(7L, 3L).isUnknown)
    }

    @Test
    fun `scaling by zero yields a known zero, never an unknown`() {
        val scaled = apple.scaled(0L, Nutrients.PER_100_THOUSANDTHS)
        assertEquals(Nutrients.ZERO, scaled)
        assertTrue(scaled.isFullyKnown)
    }

    @Test
    fun `scaling rounds half up rather than truncating`() {
        val one = Nutrients(energy = assertNotNull(Energy.ofMilliKcalOrNull(1L)))
        assertEquals(1, one.scaled(1L, 2L).energy?.milliKcal)
        val three = Nutrients(energy = assertNotNull(Energy.ofMilliKcalOrNull(3L)))
        assertEquals(2, three.scaled(1L, 2L).energy?.milliKcal)
        assertEquals(1, three.scaled(1L, 3L).energy?.milliKcal)
    }

    @Test
    fun `the worst case of PRD_FOOD 15 is exact in Long and would have wrapped in an Int`() {
        val fat = Nutrients(energy = assertNotNull(Energy.ofPer100OrNull(900.0)))
        val ingredient = assertNotNull(Quantity.ofIngredientOrNull(5_000.0))
        val contribution = fat.scaled(ingredient.thousandths.toLong(), Nutrients.PER_100_THOUSANDTHS)
        assertEquals(45_000_000, contribution.energy?.milliKcal)
        assertEquals(45_000.0, assertNotNull(contribution.energy).kilocalories)
        assertEquals(4_500_000_000_000L, 900_000L * 5_000_000L)
    }

    @Test
    fun `a scaled value that no longer fits an Int comes back null rather than wrapped`() {
        val huge = Nutrients(
            energy = assertNotNull(Energy.ofMilliKcalOrNull(2_000_000_000L)),
            protein = assertNotNull(Macro.ofMilligramsOrNull(2_000_000_000L)),
        )
        val scaled = huge.scaled(3L, 1L)
        assertNull(scaled.energy)
        assertNull(scaled.protein)
    }

    @Test
    fun `a numerator large enough to overflow a Long is caught before the multiplication`() {
        val huge = Nutrients(energy = assertNotNull(Energy.ofMilliKcalOrNull(2_000_000_000L)))
        assertNull(huge.scaled(Long.MAX_VALUE, 1L).energy)
        assertNull(huge.scaled(Long.MAX_VALUE, 1_000L).energy)
    }

    @Test
    fun `a denominator that cannot divide anything yields UNKNOWN rather than a crash`() {
        assertEquals(Nutrients.UNKNOWN, apple.scaled(1L, 0L))
        assertEquals(Nutrients.UNKNOWN, apple.scaled(1L, -4L))
        assertEquals(Nutrients.UNKNOWN, apple.scaled(-1L, 4L))
    }
}

class NutrientsMacroSumTest {

    @Test
    fun `PRD_FOOD 15 caps the known macronutrients of a per-100 card at a hundred grams`() {
        val exact = Nutrients(protein = grams(50.0), carbs = grams(30.0), fat = grams(20.0))
        assertTrue(exact.isMacroSumWithinPer100Limit)
        val over = Nutrients(protein = grams(50.0), carbs = grams(30.0), fat = grams(20.001))
        assertFalse(over.isMacroSumWithinPer100Limit)
    }

    @Test
    fun `unknown macronutrients are ignored by the check, not read as zero`() {
        val partial = Nutrients(protein = grams(90.0), carbs = null, fat = null)
        assertTrue(partial.isMacroSumWithinPer100Limit)
        assertEquals(1, partial.knownEnergyMacros.size)
        val twoKnown = Nutrients(protein = grams(90.0), carbs = null, fat = grams(90.0))
        assertFalse(twoKnown.isMacroSumWithinPer100Limit)
        assertEquals(2, twoKnown.knownEnergyMacros.size)
    }

    @Test
    fun `a card with no macronutrient at all passes the check`() {
        assertTrue(Nutrients.UNKNOWN.isMacroSumWithinPer100Limit)
        assertTrue(Nutrients.ZERO.isMacroSumWithinPer100Limit)
        assertTrue(Nutrients.UNKNOWN.knownEnergyMacros.isEmpty())
    }

    @Test
    fun `fibre is outside the sum, since Ciqual counts most of it inside the carbohydrates`() {
        val bran = Nutrients(protein = grams(15.0), carbs = grams(60.0), fat = grams(5.0), fibre = grams(45.0))
        assertTrue(bran.isMacroSumWithinPer100Limit)
        assertEquals(3, bran.knownEnergyMacros.size)
    }
}
