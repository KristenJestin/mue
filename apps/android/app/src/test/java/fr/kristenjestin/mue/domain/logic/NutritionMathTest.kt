package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.CookedRatio
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD_FOOD 13.1, line one: the raw/cooked conversion.
 *
 * PRD_FOOD 22 makes one of these an acceptance criterion of the V1 — "150 g de blanc de poulet
 * peses cuits donnent une valeur superieure aux memes 150 g peses crus" — so both halves of it
 * are asserted: the inequality it states, and the exact reference weight it rests on.
 */
class NutritionMathCookingConversionTest {

    @Test
    fun `150 g of chicken weighed cooked at 0_72 is 208_333 g of reference weight`() {
        val reference = assertNotNull(
            NutritionMath.referenceWeightOrNull(
                weighed = quantityOf(150.0),
                cookedRatio = ratioOf(0.72),
                weighedCooked = true,
            ),
        )
        assertEquals(208_333, reference.thousandths)
        assertEquals(208.333, reference.amount)
    }

    @Test
    fun `PRD_FOOD 22 - the same 150 g weighed cooked is worth more than weighed raw`() {
        val chicken = chickenBreast()
        val cooked = NutritionMath.foodContribution(chicken, quantityOf(150.0), weighedCooked = true)
        val raw = NutritionMath.foodContribution(chicken, quantityOf(150.0), weighedCooked = false)

        val cookedEnergy = assertNotNull(cooked.energy)
        val rawEnergy = assertNotNull(raw.energy)
        assertTrue(cookedEnergy > rawEnergy, "$cookedEnergy should exceed $rawEnergy")
    }

    @Test
    fun `the two chicken contributions are exactly the figures the conversion implies`() {
        val chicken = chickenBreast()
        val cooked = NutritionMath.foodContribution(chicken, quantityOf(150.0), weighedCooked = true)
        val raw = NutritionMath.foodContribution(chicken, quantityOf(150.0), weighedCooked = false)

        assertEquals(343_749, cooked.energy?.milliKcal)
        assertEquals(64_583, cooked.protein?.milligrams)
        assertEquals(247_500, raw.energy?.milliKcal)
        assertEquals(46_500, raw.protein?.milligrams)
    }

    @Test
    fun `250 g of dry pasta weighed cooked at 2_3 is 108_696 g of reference weight`() {
        val reference = assertNotNull(
            NutritionMath.referenceWeightOrNull(
                weighed = quantityOf(250.0),
                cookedRatio = ratioOf(2.3),
                weighedCooked = true,
            ),
        )
        assertEquals(108_696, reference.thousandths)
        assertEquals(108.696, reference.amount)
    }

    @Test
    fun `a food that absorbs water is worth less cooked than the same weight of it dry`() {
        val pasta = dryPasta()
        val cooked = NutritionMath.foodContribution(pasta, quantityOf(250.0), weighedCooked = true)
        val dry = NutritionMath.foodContribution(pasta, quantityOf(250.0), weighedCooked = false)

        val cookedEnergy = assertNotNull(cooked.energy)
        val dryEnergy = assertNotNull(dry.energy)
        assertTrue(cookedEnergy < dryEnergy, "$cookedEnergy should be under $dryEnergy")
    }

    @Test
    fun `a ratio of exactly one is the identity`() {
        val weighed = quantityOf(150.0)
        val reference = NutritionMath.referenceWeightOrNull(weighed, ratioOf(1.0), weighedCooked = true)
        assertEquals(weighed, reference)
    }

    @Test
    fun `a ratio of exactly one is the identity at every quantity it is tried on`() {
        val one = ratioOf(1.0)
        listOf(0.001, 1.0, 12.5, 150.0, 999.999, 5_000.0).forEach { amount ->
            val weighed = quantityOf(amount)
            assertEquals(
                weighed,
                NutritionMath.referenceWeightOrNull(weighed, one, weighedCooked = true),
                "identity should hold at $amount",
            )
        }
    }

    @Test
    fun `weighed cooked with no ratio at all is the identity`() {
        val weighed = quantityOf(150.0)
        assertEquals(weighed, NutritionMath.referenceWeightOrNull(weighed, null, weighedCooked = true))
    }

    @Test
    fun `a food with no ratio ignores a leftover cooked flag rather than refusing the line`() {
        val plain = foodOf(cookedRatio = null, per100 = per100(energy = 100.0))
        val flagged = NutritionMath.foodContribution(plain, quantityOf(200.0), weighedCooked = true)
        val plainly = NutritionMath.foodContribution(plain, quantityOf(200.0), weighedCooked = false)
        assertEquals(plainly, flagged)
        assertEquals(200_000, flagged.energy?.milliKcal)
    }

    @Test
    fun `a ratio is ignored when the quantity was weighed in the reference state`() {
        val weighed = quantityOf(150.0)
        assertEquals(
            weighed,
            NutritionMath.referenceWeightOrNull(weighed, ratioOf(0.72), weighedCooked = false),
        )
    }

    @Test
    fun `the conversion is applied once, never twice`() {
        val chicken = chickenBreast()
        val reference = assertNotNull(
            NutritionMath.referenceWeightOrNull(quantityOf(150.0), chicken.cookedRatio, true),
        )
        // The contribution of the converted weight, taken as an already-reference weight, is
        // exactly what the one-step call produces.
        assertEquals(
            NutritionMath.contribution(chicken.per100, reference),
            NutritionMath.foodContribution(chicken, quantityOf(150.0), weighedCooked = true),
        )
    }

    @Test
    fun `the conversion touches the quantity and never the nutrients`() {
        val chicken = chickenBreast()
        val reference = assertNotNull(
            NutritionMath.referenceWeightOrNull(quantityOf(150.0), chicken.cookedRatio, true),
        )
        // A raw line of the converted weight and a cooked line of the weighed weight agree,
        // which can only be true if the ratio never reached the per-100 values.
        assertEquals(
            NutritionMath.foodContribution(chicken, reference, weighedCooked = false),
            NutritionMath.foodContribution(chicken, quantityOf(150.0), weighedCooked = true),
        )
    }

    @Test
    fun `the lower bound of PRD_FOOD 15 converts the heaviest ingredient without overflowing`() {
        val reference = assertNotNull(
            NutritionMath.referenceWeightOrNull(quantityOf(5_000.0), ratioOf(0.3), weighedCooked = true),
        )
        assertEquals(16_666_667, reference.thousandths)
        assertTrue(reference.thousandths > 0)
    }

    @Test
    fun `the upper bound of PRD_FOOD 15 converts the heaviest ingredient without overflowing`() {
        val reference = assertNotNull(
            NutritionMath.referenceWeightOrNull(quantityOf(5_000.0), ratioOf(5.0), weighedCooked = true),
        )
        assertEquals(1_000_000, reference.thousandths)
        assertEquals(1_000.0, reference.amount)
    }

    @Test
    fun `every ratio of the allowed range yields a strictly positive reference weight`() {
        var thousandths = CookedRatio.MIN_THOUSANDTHS
        while (thousandths <= CookedRatio.MAX_THOUSANDTHS) {
            val ratio = assertNotNull(
                CookedRatio.ofThousandthsOrNull(thousandths.toLong()),
            )
            val reference = assertNotNull(
                NutritionMath.referenceWeightOrNull(quantityOf(5_000.0), ratio, weighedCooked = true),
            )
            assertTrue(reference.thousandths > 0, "ratio $thousandths gave ${reference.thousandths}")
            thousandths += 47
        }
    }

    @Test
    fun `a round trip through the two directions stays within one thousandth`() {
        val ratios = listOf(0.3, 0.72, 1.0, 2.3, 2.8, 5.0)
        val weights = listOf(1.0, 25.0, 150.0, 250.0, 1_000.0)
        ratios.forEach { rawRatio ->
            val ratio = ratioOf(rawRatio)
            weights.forEach { amount ->
                val weighed = quantityOf(amount)
                val reference = assertNotNull(
                    NutritionMath.referenceWeightOrNull(weighed, ratio, weighedCooked = true),
                )
                val back = assertNotNull(NutritionMath.cookedWeightOrNull(reference, ratio))
                assertTrue(
                    abs(back.thousandths - weighed.thousandths) <= 1,
                    "$amount g at $rawRatio came back as ${back.amount}",
                )
            }
        }
    }

    @Test
    fun `the cooked weight of a chicken reference weight is the 150 g it was read at`() {
        val reference = quantityOf(208.333)
        val back = assertNotNull(NutritionMath.cookedWeightOrNull(reference, ratioOf(0.72)))
        assertEquals(150_000, back.thousandths)
    }

    @Test
    fun `the inverse of a missing ratio is the identity too`() {
        val reference = quantityOf(150.0)
        assertEquals(reference, NutritionMath.cookedWeightOrNull(reference, null))
    }

    @Test
    fun `a quantity nothing could weigh comes back null rather than wrapped`() {
        val absurd = assertNotNull(Quantity.ofThousandthsOrNull(Int.MAX_VALUE.toLong()))
        assertNull(NutritionMath.referenceWeightOrNull(absurd, ratioOf(0.3), weighedCooked = true))
    }

    @Test
    fun `an unrepresentable reference weight makes the values unknown, never zero`() {
        val absurd = assertNotNull(Quantity.ofThousandthsOrNull(Int.MAX_VALUE.toLong()))
        val food = foodOf(cookedRatio = ratioOf(0.3), per100 = per100(energy = 100.0, protein = 5.0))
        val contribution = NutritionMath.foodContribution(food, absurd, weighedCooked = true)
        assertEquals(Nutrients.UNKNOWN, contribution)
        assertNull(contribution.energy)
    }

    @Test
    fun `nothing in the conversion throws, whatever it is handed`() {
        val ratios = listOf(null, ratioOf(0.3), ratioOf(1.0), ratioOf(5.0))
        val amounts = listOf(0.001, 1.0, 150.0, 5_000.0, 2_000_000.0)
        ratios.forEach { ratio ->
            amounts.forEach { amount ->
                listOf(true, false).forEach { cooked ->
                    NutritionMath.referenceWeightOrNull(quantityOf(amount), ratio, cooked)
                }
            }
        }
    }
}

/** PRD_FOOD 13.1, line two: `contribution = poids de reference x valeurPour100 / 100`. */
class NutritionMathContributionTest {

    @Test
    fun `a hundred grams of a food is exactly its per-100 values`() {
        val food = foodOf(per100 = per100(energy = 89.0, protein = 0.3, carbs = 19.0, fat = 0.2, fibre = 2.4))
        val contribution = NutritionMath.contribution(food.per100, quantityOf(100.0))
        assertEquals(food.per100, contribution)
    }

    @Test
    fun `a hundred and fifty grams of an apple is one and a half times its card`() {
        val apple = foodOf(name = "Apple", per100 = per100(energy = 89.0))
        val contribution = NutritionMath.contribution(apple.per100, quantityOf(150.0))
        assertEquals(133_500, contribution.energy?.milliKcal)
    }

    @Test
    fun `a known zero scales to a known zero and never to an unknown`() {
        val water = foodOf(name = "Water", per100 = Nutrients.ZERO)
        val contribution = NutritionMath.contribution(water.per100, quantityOf(500.0))
        assertEquals(Nutrients.ZERO, contribution)
        assertEquals(Energy.ZERO, contribution.energy)
        assertEquals(Macro.ZERO, contribution.protein)
    }

    @Test
    fun `an unknown metric scales to an unknown metric and never to a zero`() {
        val card = foodOf(per100 = per100(energy = 100.0))
        val contribution = NutritionMath.contribution(card.per100, quantityOf(250.0))
        assertEquals(250_000, contribution.energy?.milliKcal)
        assertNull(contribution.protein)
        assertNull(contribution.carbs)
        assertNull(contribution.fat)
        assertNull(contribution.fibre)
    }

    @Test
    fun `a card that knows nothing contributes nothing known`() {
        val card = foodOf(per100 = Nutrients.UNKNOWN)
        assertEquals(Nutrients.UNKNOWN, NutritionMath.contribution(card.per100, quantityOf(150.0)))
    }

    @Test
    fun `a millilitre food scales the same way a gram food does`() {
        val milk = foodOf(
            name = "Milk",
            per100 = per100(energy = 46.0),
            referenceUnit = ReferenceUnit.MILLILITRE,
        )
        assertEquals(115_000, NutritionMath.contribution(milk.per100, quantityOf(250.0)).energy?.milliKcal)
    }

    @Test
    fun `the heaviest ingredient of the richest food is exact rather than wrapped`() {
        val fat = foodOf(name = "Oil", per100 = per100(energy = 900.0))
        val contribution = NutritionMath.contribution(fat.per100, quantityOf(5_000.0))
        assertEquals(45_000_000, contribution.energy?.milliKcal)
    }

    @Test
    fun `a usual portion resolves to the weight PRD_FOOD 13_2 prints beside it`() {
        val apple = foodOf(name = "Apple", servingLabel = "apple", servingSize = quantityOf(150.0))
        val weight = assertNotNull(NutritionMath.usualServingWeightOrNull(apple, servingsOf(1.5)))
        assertEquals(225_000, weight.thousandths)
    }

    @Test
    fun `a usual portion contributes what that weight contributes`() {
        val apple = foodOf(
            name = "Apple",
            per100 = per100(energy = 52.0),
            servingLabel = "apple",
            servingSize = quantityOf(150.0),
        )
        assertEquals(117_000, NutritionMath.usualServingContribution(apple, servingsOf(1.5)).energy?.milliKcal)
    }

    @Test
    fun `one usual portion is exactly the declared portion size`() {
        val pot = foodOf(name = "Skyr", servingLabel = "pot", servingSize = quantityOf(150.0))
        val weight = assertNotNull(NutritionMath.usualServingWeightOrNull(pot, servingsOf(1.0)))
        assertEquals(150_000, weight.thousandths)
    }

    @Test
    fun `a food declaring no portion size offers no portion weight`() {
        val plain = foodOf(servingLabel = "handful", servingSize = null)
        assertNull(NutritionMath.usualServingWeightOrNull(plain, servingsOf(2.0)))
    }

    @Test
    fun `a portion of a food declaring no size is unknown, never zero`() {
        val plain = foodOf(per100 = per100(energy = 100.0), servingSize = null)
        assertEquals(Nutrients.UNKNOWN, NutritionMath.usualServingContribution(plain, servingsOf(2.0)))
    }

    @Test
    fun `a usual portion never applies a cooking ratio`() {
        val food = foodOf(
            per100 = per100(energy = 100.0),
            cookedRatio = ratioOf(0.5),
            servingLabel = "piece",
            servingSize = quantityOf(100.0),
        )
        assertEquals(100_000, NutritionMath.usualServingContribution(food, servingsOf(1.0)).energy?.milliKcal)
    }
}

/** PRD_FOOD 13.1: a recipe total, its per-serving value and the journal line that uses it. */
class NutritionMathRecipeTest {

    private val rice = foodOf(
        name = "White rice, dry",
        per100 = per100(energy = 350.0, protein = 7.0),
        id = "food-rice",
    )

    private val oil = foodOf(
        name = "Olive oil",
        per100 = per100(energy = 900.0, protein = 0.0),
        id = "food-oil",
    )

    private val detail = recipeDetailOf(
        ingredients = listOf(
            ingredientOf("food-rice", 200.0, position = 0),
            ingredientOf("food-oil", 20.0, position = 1),
        ),
        baseServings = 4,
    )

    private val catalogue = catalogueOf(rice, oil)

    @Test
    fun `a recipe total is the strict sum of its ingredient contributions`() {
        val total = NutritionMath.recipeTotal(detail, catalogue)
        assertEquals(880_000, total.energy?.milliKcal)
        assertEquals(14_000, total.protein?.milligrams)
    }

    @Test
    fun `a per-serving value is the total divided by the servings the recipe is written for`() {
        val perServing = NutritionMath.perServing(detail, catalogue)
        assertEquals(220_000, perServing.energy?.milliKcal)
        assertEquals(3_500, perServing.protein?.milligrams)
    }

    @Test
    fun `a RECIPE line is the per-serving value times the portions consumed`() {
        val line = NutritionMath.recipeLine(detail, catalogue, servingsOf(1.5))
        assertEquals(330_000, line.energy?.milliKcal)
        assertEquals(5_250, line.protein?.milligrams)
    }

    @Test
    fun `one serving of a recipe is its per-serving value exactly`() {
        assertEquals(
            NutritionMath.perServing(detail, catalogue),
            NutritionMath.recipeLine(detail, catalogue, servingsOf(1.0)),
        )
    }

    @Test
    fun `four quarters of a serving are one serving`() {
        val quarter = NutritionMath.recipeLine(detail, catalogue, servingsOf(0.25))
        assertEquals(55_000, quarter.energy?.milliKcal)
        assertEquals(220_000, quarter.energy?.milliKcal?.times(4))
    }

    @Test
    fun `every serving of the whole recipe adds back up to the recipe`() {
        val whole = NutritionMath.recipeLine(detail, catalogue, servingsOf(4.0))
        assertEquals(NutritionMath.recipeTotal(detail, catalogue).energy, whole.energy)
    }

    @Test
    fun `one ingredient with an unknown metric makes only that metric unknown`() {
        val incomplete = foodOf(name = "Spice mix", per100 = per100(energy = 300.0), id = "food-spice")
        val withSpice = recipeDetailOf(
            ingredients = detail.ingredients + ingredientOf("food-spice", 10.0, position = 2),
            baseServings = 4,
        )
        val total = NutritionMath.recipeTotal(withSpice, catalogueOf(rice, oil, incomplete))
        assertEquals(910_000, total.energy?.milliKcal)
        assertNull(total.protein)
    }

    @Test
    fun `an ingredient whose food has not arrived yet makes the total unknown, not wrong`() {
        val orphan = recipeDetailOf(
            ingredients = detail.ingredients + ingredientOf("food-missing", 50.0, position = 2),
            baseServings = 4,
        )
        assertEquals(Nutrients.UNKNOWN, NutritionMath.recipeTotal(orphan, catalogue))
    }

    @Test
    fun `an ingredient row with no food resolves to unknown rather than to zero`() {
        val row = ingredientOf("food-missing", 50.0)
        assertEquals(Nutrients.UNKNOWN, NutritionMath.ingredientContribution(row, null))
    }

    @Test
    fun `an empty recipe sums to a known zero, which PRD_FOOD 15 refuses to save`() {
        val empty = recipeDetailOf(ingredients = emptyList())
        assertEquals(Nutrients.ZERO, NutritionMath.recipeTotal(empty, catalogue))
        assertTrue(FoodValidation.validateIngredientCount(0) is Validated.Invalid)
    }

    @Test
    fun `a recipe written for no servings yields unknown values rather than a division by zero`() {
        assertEquals(Nutrients.UNKNOWN, NutritionMath.perServing(per100(energy = 100.0), 0))
        assertEquals(Nutrients.UNKNOWN, NutritionMath.perServing(per100(energy = 100.0), -3))
    }

    @Test
    fun `ingredient quantities are for the whole recipe and carry no cooking state`() {
        val pasta = dryPasta()
        val withPasta = recipeDetailOf(
            ingredients = listOf(ingredientOf("food-pasta", 250.0)),
            baseServings = 2,
        )
        val total = NutritionMath.recipeTotal(withPasta, catalogueOf(pasta))
        assertEquals(
            NutritionMath.contribution(pasta.per100, quantityOf(250.0)).energy,
            total.energy,
        )
    }

    @Test
    fun `varying the servings on a recipe card rescales its ingredient quantities`() {
        val doubled = assertNotNull(
            NutritionMath.scaledIngredientQuantityOrNull(quantityOf(200.0), baseServings = 4, servings = servingsOf(8.0)),
        )
        assertEquals(400_000, doubled.thousandths)
    }

    @Test
    fun `rescaling to the recipe's own servings changes nothing`() {
        val same = assertNotNull(
            NutritionMath.scaledIngredientQuantityOrNull(quantityOf(200.0), baseServings = 4, servings = servingsOf(4.0)),
        )
        assertEquals(200_000, same.thousandths)
    }

    @Test
    fun `rescaling to a quarter serving of a four-serving recipe is a sixteenth`() {
        val small = assertNotNull(
            NutritionMath.scaledIngredientQuantityOrNull(quantityOf(160.0), baseServings = 4, servings = servingsOf(0.25)),
        )
        assertEquals(10_000, small.thousandths)
    }

    @Test
    fun `rescaling a recipe that serves nobody is refused rather than divided by zero`() {
        assertNull(
            NutritionMath.scaledIngredientQuantityOrNull(quantityOf(200.0), baseServings = 0, servings = servingsOf(1.0)),
        )
    }

    @Test
    fun `a recipe total is the same however its ingredients are ordered`() {
        val reversed = recipeDetailOf(ingredients = detail.ingredients.reversed(), baseServings = 4)
        assertEquals(
            NutritionMath.recipeTotal(detail, catalogue),
            NutritionMath.recipeTotal(reversed, catalogue),
        )
    }

    @Test
    fun `the same food listed twice is counted twice`() {
        val twice = recipeDetailOf(
            ingredients = listOf(
                ingredientOf("food-oil", 10.0, position = 0),
                ingredientOf("food-oil", 10.0, position = 1),
            ),
            baseServings = 1,
        )
        assertEquals(180_000, NutritionMath.recipeTotal(twice, catalogueOf(oil)).energy?.milliKcal)
    }
}

/** PRD_FOOD 13.1: `total d'un moment = somme stricte de ses lignes`. */
class NutritionMathStrictSumTest {

    @Test
    fun `a known energy survives an unknown protein beside it`() {
        val lines = listOf(
            logEntryOf(nutrients = Nutrients(energy = kcalOf(100.0), protein = macroOf(5.0)), id = "a"),
            logEntryOf(nutrients = Nutrients(energy = kcalOf(50.0)), id = "b"),
        )
        val total = NutritionMath.total(lines)
        assertEquals(150_000, total.energy?.milliKcal)
        assertNull(total.protein)
    }

    @Test
    fun `no line at all is a known zero`() {
        assertEquals(Nutrients.ZERO, NutritionMath.total(emptyList()))
        assertEquals(Nutrients.ZERO, Nutrients.strictSum(emptyList()))
    }

    @Test
    fun `one line that knows nothing makes the whole total unknown`() {
        val lines = listOf(logEntryOf(nutrients = Nutrients.UNKNOWN, id = "a"))
        assertEquals(Nutrients.UNKNOWN, NutritionMath.total(lines))
        assertEquals(Nutrients.UNKNOWN, Nutrients.strictSum(listOf(Nutrients.UNKNOWN)))
    }

    @Test
    fun `an unknown line does not erase the metrics its neighbours do know`() {
        val lines = listOf(
            logEntryOf(nutrients = per100(energy = 200.0, protein = 10.0, carbs = 20.0), id = "a"),
            logEntryOf(nutrients = per100(protein = 5.0, carbs = 3.0), id = "b"),
        )
        val total = NutritionMath.total(lines)
        assertNull(total.energy)
        assertEquals(15_000, total.protein?.milligrams)
        assertEquals(23_000, total.carbs?.milligrams)
    }

    @Test
    fun `a moment of known zeroes totals a known zero, not an unknown`() {
        val lines = listOf(
            logEntryOf(nutrients = Nutrients.ZERO, id = "a"),
            logEntryOf(nutrients = Nutrients.ZERO, id = "b"),
        )
        assertEquals(Nutrients.ZERO, NutritionMath.total(lines))
        assertEquals(Energy.ZERO, NutritionMath.total(lines).energy)
    }

    @Test
    fun `a total uses the frozen values of each line and reopens no card`() {
        val line = logEntryOf(nutrients = per100(energy = 123.0))
        assertEquals(123_000, NutritionMath.total(listOf(line)).energy?.milliKcal)
    }

    @Test
    fun `the total of a day is the same addition as the total of a moment`() {
        val lines = listOf(
            logEntryOf(at = "08:00", nutrients = per100(energy = 300.0), id = "a"),
            logEntryOf(at = "13:00", nutrients = per100(energy = 700.0), id = "b"),
        )
        assertEquals(1_000_000, NutritionMath.total(lines).energy?.milliKcal)
    }

    @Test
    fun `the strict sum is commutative`() {
        val a = per100(energy = 100.0, protein = 5.0)
        val b = per100(energy = 50.0)
        assertEquals(Nutrients.strictSum(listOf(a, b)), Nutrients.strictSum(listOf(b, a)))
    }

    @Test
    fun `a recipe total accepts an iterable of contributions directly`() {
        val contributions = listOf(per100(energy = 100.0), per100(energy = 20.0))
        assertEquals(120_000, NutritionMath.recipeTotal(contributions).energy?.milliKcal)
    }
}

/** PRD_FOOD 8.4: what makes a line approximate. */
class NutritionMathEstimationTest {

    @Test
    fun `a recipe whose ingredients are all measured stays measured`() {
        assertEquals(
            Estimation.MEASURED,
            NutritionMath.estimationOf(listOf(Estimation.MEASURED, Estimation.MEASURED)),
        )
    }

    @Test
    fun `one approximate ingredient makes the whole recipe approximate`() {
        assertEquals(
            Estimation.APPROXIMATE,
            NutritionMath.estimationOf(listOf(Estimation.MEASURED, Estimation.APPROXIMATE)),
        )
    }

    @Test
    fun `nothing approximate took part in an empty list`() {
        assertEquals(Estimation.MEASURED, NutritionMath.estimationOf(emptyList()))
    }
}

/** The lines of PRD_FOOD 10.2 as the journal actually assembles them. */
class NutritionMathJournalLineTest {

    @Test
    fun `a FOOD line is the contribution of its food`() {
        val apple = foodOf(name = "Apple", per100 = per100(energy = 89.0), id = "food-apple")
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        val entry = logEntryOf(
            amount = amount,
            nutrients = NutritionMath.foodContribution(apple, quantityOf(150.0)),
        )
        assertEquals(133_500, entry.nutrients.energy?.milliKcal)
        assertEquals(133_500, NutritionMath.total(listOf(entry)).energy?.milliKcal)
    }

    @Test
    fun `a QUICK line is exactly the values that were typed`() {
        val typed = Nutrients(energy = kcalOf(650.0))
        val entry = logEntryOf(
            kind = FoodLogKind.QUICK,
            amount = LoggedAmount.Unmeasured,
            nutrients = typed,
            estimation = Estimation.APPROXIMATE,
        )
        assertEquals(typed, entry.nutrients)
        assertNull(entry.nutrients.protein)
        assertNull(entry.quantityUnit)
    }

    @Test
    fun `a roast is the food plus a separate line of fat, never a hybrid food`() {
        val chicken = chickenBreast()
        val oil = foodOf(name = "Olive oil", per100 = per100(energy = 900.0), id = "food-oil")
        val lines = listOf(
            logEntryOf(
                nutrients = NutritionMath.foodContribution(chicken, quantityOf(150.0), weighedCooked = true),
                id = "roast",
            ),
            logEntryOf(nutrients = NutritionMath.foodContribution(oil, quantityOf(10.0)), id = "fat"),
        )
        assertEquals(343_749 + 90_000, NutritionMath.total(lines).energy?.milliKcal)
    }

    @Test
    fun `a moment mixes the three forms without one replacing another`() {
        val lines = listOf(
            logEntryOf(kind = FoodLogKind.FOOD, nutrients = per100(energy = 100.0), id = "a"),
            logEntryOf(
                kind = FoodLogKind.RECIPE,
                amount = LoggedAmount.Portioned(servingsOf(1.5)),
                nutrients = per100(energy = 330.0),
                id = "b",
            ),
            logEntryOf(
                kind = FoodLogKind.QUICK,
                amount = LoggedAmount.Unmeasured,
                nutrients = per100(energy = 650.0),
                id = "c",
            ),
        )
        assertEquals(3, lines.size)
        assertEquals(1_080_000, NutritionMath.total(lines).energy?.milliKcal)
    }

    @Test
    fun `a food catalogue lookup by id resolves the ingredient it belongs to`() {
        val rice = foodOf(name = "Rice", per100 = per100(energy = 350.0), id = "food-rice")
        val catalogue = catalogueOf(rice)
        assertEquals(rice, catalogue[FoodId("food-rice")])
        assertNull(catalogue[FoodId("food-absent")])
    }
}
