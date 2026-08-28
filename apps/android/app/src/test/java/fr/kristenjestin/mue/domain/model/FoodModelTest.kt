package fr.kristenjestin.mue.domain.model

import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun quantity(amount: Double): Quantity = assertNotNull(Quantity.ofAmountOrNull(amount))

private fun food(
    name: String = "Apple",
    source: FoodSource = FoodSource.CIQUAL,
    servingLabel: String? = null,
    servingSize: Quantity? = null,
    cookedRatio: CookedRatio? = null,
): Food = Food(
    id = FoodId("food-1"),
    name = name,
    source = source,
    servingLabel = servingLabel,
    servingSize = servingSize,
    cookedRatio = cookedRatio,
)

class FoodTest {

    @Test
    fun `PRD_FOOD 15 bounds a name at one and eighty characters once trimmed`() {
        assertEquals(1, Food.MIN_NAME_LENGTH)
        assertEquals(80, Food.MAX_NAME_LENGTH)
        assertEquals(Food.MAX_NAME_LENGTH, Recipe.MAX_NAME_LENGTH)
    }

    @Test
    fun `PRD_FOOD 9-4 folds a name past its case, its padding and its accents`() {
        assertEquals("creme brulee", Food.fold("  Crème Brûlée  "))
        assertEquals(Food.fold("Crème Brûlée"), Food.fold("creme brulee"))
        assertEquals(Food.fold("Pâtes"), Food.fold("PATES"))
        assertEquals("yaourt nature", food(name = " Yaourt Nature ").nameFolded)
    }

    @Test
    fun `a folded name is the same on every device, whatever its locale`() {
        assertEquals("iogurt", Food.fold("IOGURT"))
        assertEquals("i", Food.fold("I"))
    }

    @Test
    fun `a brand folds the same way, and stays absent when there is none`() {
        assertEquals("bjorg", food().copy(brand = "Björg").brandFolded)
        assertNull(food().brandFolded)
    }

    @Test
    fun `PRD_FOOD 9-1 makes a Ciqual entry read-only and everything else editable`() {
        assertTrue(food(source = FoodSource.CIQUAL).isReadOnly)
        assertFalse(food(source = FoodSource.OPEN_FOOD_FACTS).isReadOnly)
        assertFalse(food(source = FoodSource.CUSTOM).isReadOnly)
    }

    @Test
    fun `PRD_FOOD FR-FOOD-006 offers the serving counter only when both halves exist`() {
        assertFalse(food().hasUsualServing)
        assertFalse(food(servingLabel = "apple").hasUsualServing)
        assertFalse(food(servingSize = quantity(150.0)).hasUsualServing)
        assertTrue(food(servingLabel = "apple", servingSize = quantity(150.0)).hasUsualServing)
    }

    @Test
    fun `PRD_FOOD FR-FOOD-006 offers the raw-cooked selector only on a food that carries a ratio`() {
        assertFalse(food().hasCookedState)
        assertTrue(food(cookedRatio = assertNotNull(CookedRatio.ofRatioOrNull(0.72))).hasCookedState)
    }

    @Test
    fun `PRD_FOOD 15 accepts a food with no value at all, which is the nominal scanned card`() {
        assertEquals(Nutrients.UNKNOWN, food().per100)
        assertTrue(food().per100.isUnknown)
    }

    @Test
    fun `PRD_FOOD 8-2 labels the reference state Raw and the other one Cooked by default`() {
        assertEquals("Raw", Food.DEFAULT_RAW_LABEL)
        assertEquals("Cooked", Food.DEFAULT_COOKED_LABEL)
        assertEquals("Raw", food().rawLabel)
        assertEquals("Cooked", food().cookedLabel)
    }

    @Test
    fun `a retail barcode is eight to fourteen digits long`() {
        assertEquals(8..14, Food.BARCODE_LENGTH_RANGE)
        assertTrue("3017620422003".length in Food.BARCODE_LENGTH_RANGE)
        assertFalse("123".length in Food.BARCODE_LENGTH_RANGE)
    }

    @Test
    fun `two generated ids never collide, and an id keeps the text it was given`() {
        assertFalse(FoodId.random() == FoodId.random())
        assertEquals("abc", FoodId("abc").value)
        assertEquals(FoodId("abc"), FoodId("abc"))
    }
}

class RecipeTest {

    private val recipe = Recipe(
        id = RecipeId("recipe-1"),
        name = "Red lentil curry",
        type = RecipeType.MAIN,
        baseServings = 4,
    )

    @Test
    fun `PRD_FOOD 15 writes a recipe for a whole number of servings from one to twelve`() {
        assertEquals(1..12, Recipe.BASE_SERVINGS_RANGE)
        assertTrue(1 in Recipe.BASE_SERVINGS_RANGE)
        assertTrue(12 in Recipe.BASE_SERVINGS_RANGE)
        assertFalse(0 in Recipe.BASE_SERVINGS_RANGE)
        assertFalse(13 in Recipe.BASE_SERVINGS_RANGE)
    }

    @Test
    fun `PRD_FOOD 15 bounds the steps at thirty lines of five hundred characters`() {
        assertEquals(30, Recipe.MAX_STEPS)
        assertEquals(500, Recipe.MAX_STEP_LENGTH)
        assertTrue(recipe.steps.isEmpty())
    }

    @Test
    fun `PRD_FOOD 15 bounds the ingredients at one and forty`() {
        assertEquals(1, Recipe.MIN_INGREDIENTS)
        assertEquals(40, Recipe.MAX_INGREDIENTS)
    }

    @Test
    fun `a preparation time and a description carry a ceiling PRD_FOOD 15 leaves open`() {
        assertEquals(1..1_440, Recipe.PREP_TIME_MINUTES_RANGE)
        assertEquals(500, Recipe.MAX_DESCRIPTION_LENGTH)
        assertNull(recipe.prepTimeMinutes)
        assertNull(recipe.description)
    }

    @Test
    fun `a recipe name folds the way a food name does`() {
        assertEquals("red lentil curry", recipe.nameFolded)
        assertEquals(Food.fold("Purée"), recipe.copy(name = "Purée").nameFolded)
    }

    @Test
    fun `a recipe is not a favourite until it is made one`() {
        assertFalse(recipe.isFavourite)
        assertTrue(recipe.copy(isFavourite = true).isFavourite)
    }

    @Test
    fun `PRD_FOOD 8-3 stores no nutritional value on a recipe at all`() {
        val fields = Recipe::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("calor", ignoreCase = true) })
        assertFalse(fields.any { it.contains("protein", ignoreCase = true) })
        assertFalse(fields.any { it.contains("nutrient", ignoreCase = true) })
    }
}

class RecipeDetailTest {

    private fun ingredient(foodId: String, position: Int, amount: Double) = RecipeIngredient(
        id = RecipeIngredientId("ing-$position"),
        foodId = FoodId(foodId),
        quantity = quantity(amount),
        unit = ReferenceUnit.GRAM,
        position = position,
        foodName = "Ingredient $position",
    )

    private val detail = RecipeDetail(
        recipe = Recipe(RecipeId("r"), "Curry", RecipeType.MAIN, baseServings = 4),
        ingredients = listOf(
            ingredient("lentils", 0, 250.0),
            ingredient("oil", 1, 15.0),
            ingredient("lentils", 2, 50.0),
        ),
    )

    @Test
    fun `PRD_FOOD 21-2 makes the recipe and its ingredients one aggregate`() {
        assertEquals(RecipeId("r"), detail.id)
        assertTrue(detail.hasIngredients)
        assertEquals(3, detail.ingredients.size)
    }

    @Test
    fun `PRD_FOOD 15 refuses a recipe with no ingredient`() {
        assertFalse(detail.copy(ingredients = emptyList()).hasIngredients)
    }

    @Test
    fun `the same food may appear twice, so the foods to resolve are the distinct ones`() {
        assertEquals(listOf(FoodId("lentils"), FoodId("oil")), detail.foodIds)
    }

    @Test
    fun `PRD_FOOD 21-2 keeps a name snapshot so an unreceived food still renders`() {
        assertEquals("Ingredient 0", detail.ingredients.first().foodName)
        val orphan = detail.ingredients.first().copy(foodName = null)
        assertNull(orphan.foodName)
        assertEquals(FoodId("lentils"), orphan.foodId)
    }

    @Test
    fun `an ingredient quantity is for the whole recipe and keeps its own unit`() {
        assertEquals(250_000, detail.ingredients[0].quantity.thousandths)
        assertEquals(ReferenceUnit.GRAM, detail.ingredients[0].unit)
        assertFalse(RecipeIngredientId.random() == RecipeIngredientId.random())
        assertFalse(RecipeId.random() == RecipeId.random())
    }
}

class FoodLogEntryTest {

    private fun entry(
        kind: FoodLogKind,
        amount: LoggedAmount,
        sourceRef: String? = null,
    ) = FoodLogEntry(
        id = FoodLogEntryId("line-1"),
        consumedOn = LocalDate.of(2026, 3, 14),
        consumedAt = LocalTime.of(10, 0),
        slot = MealSlot.SNACK,
        kind = kind,
        title = "Apple",
        amount = amount,
        nutrients = Nutrients(energy = assertNotNull(Energy.ofPer100OrNull(89.0))),
        estimation = Estimation.MEASURED,
        sourceRef = sourceRef,
    )

    @Test
    fun `PRD_FOOD 10-2 measures a food line in grams or millilitres`() {
        val line = entry(
            FoodLogKind.FOOD,
            LoggedAmount.Measured(quantity(150.0), ReferenceUnit.GRAM),
            sourceRef = "food-7",
        )
        assertEquals(QuantityUnit.GRAM, line.quantityUnit)
        assertEquals(150_000, line.measuredQuantity?.thousandths)
        assertNull(line.consumedServings)
        assertEquals(FoodId("food-7"), line.foodRef)
        assertNull(line.recipeRef)
    }

    @Test
    fun `a liquid keeps its own unit and no density is invented for it`() {
        val line = entry(FoodLogKind.FOOD, LoggedAmount.Measured(quantity(250.0), ReferenceUnit.MILLILITRE))
        assertEquals(QuantityUnit.MILLILITRE, line.quantityUnit)
        assertEquals(250_000, line.measuredQuantity?.thousandths)
    }

    @Test
    fun `PRD_FOOD 10-2 counts a recipe line in servings`() {
        val servings = assertNotNull(Servings.ofConsumedOrNull(1.5))
        val line = entry(FoodLogKind.RECIPE, LoggedAmount.Portioned(servings), sourceRef = "recipe-3")
        assertEquals(QuantityUnit.SERVING, line.quantityUnit)
        assertEquals(1_500, line.consumedServings?.thousandths)
        assertNull(line.measuredQuantity)
        assertEquals(RecipeId("recipe-3"), line.recipeRef)
        assertNull(line.foodRef)
    }

    @Test
    fun `PRD_FOOD 15 gives a quick add a name and an energy and nothing to weigh`() {
        val line = entry(FoodLogKind.QUICK, LoggedAmount.Unmeasured)
        assertNull(line.quantityUnit)
        assertNull(line.measuredQuantity)
        assertNull(line.consumedServings)
        assertNull(line.foodRef)
        assertNull(line.recipeRef)
    }

    @Test
    fun `a source reference is only read for the kind that owns it`() {
        val quick = entry(FoodLogKind.QUICK, LoggedAmount.Unmeasured, sourceRef = "food-7")
        assertNull(quick.foodRef)
        assertNull(quick.recipeRef)
        val food = entry(FoodLogKind.FOOD, LoggedAmount.Unmeasured, sourceRef = null)
        assertNull(food.foodRef)
    }

    @Test
    fun `PRD_FOOD 13-2 keeps a line with an unknown energy out of the averages`() {
        val line = entry(FoodLogKind.FOOD, LoggedAmount.Measured(quantity(1.0), ReferenceUnit.GRAM))
        assertTrue(line.countsTowardsEnergyAverage)
        assertFalse(line.copy(nutrients = Nutrients.UNKNOWN).countsTowardsEnergyAverage)
        assertTrue(line.copy(nutrients = Nutrients.ZERO).countsTowardsEnergyAverage)
    }

    @Test
    fun `PRD_FOOD 15 refuses a future date and accepts today and every day behind it`() {
        val today = LocalDate.of(2026, 3, 14)
        assertTrue(FoodLogEntry.isLoggableOn(today, today))
        assertTrue(FoodLogEntry.isLoggableOn(today.minusDays(1), today))
        assertTrue(FoodLogEntry.isLoggableOn(today.minusYears(3), today))
        assertFalse(FoodLogEntry.isLoggableOn(today.plusDays(1), today))
    }

    @Test
    fun `a line carries its own optional readings without inventing any of them`() {
        val line = entry(FoodLogKind.FOOD, LoggedAmount.Measured(quantity(150.0), ReferenceUnit.GRAM))
        assertNull(line.amountLabel)
        assertNull(line.portions)
        assertFalse(line.weighedCooked)
        assertNull(line.fromPlan)
        assertEquals(FoodLogEntry.MAX_TITLE_LENGTH, Food.MAX_NAME_LENGTH)
        assertFalse(FoodLogEntryId.random() == FoodLogEntryId.random())
    }

    @Test
    fun `the three amounts are three distinct shapes, never one nullable number`() {
        assertEquals(QuantityUnit.GRAM, LoggedAmount.Measured(quantity(1.0), ReferenceUnit.GRAM).unit)
        assertEquals(QuantityUnit.SERVING, LoggedAmount.Portioned(Servings.ONE).unit)
        assertNull(LoggedAmount.Unmeasured.unit)
    }
}

class MealPlanKeyTest {

    private val key = MealPlanKey(LocalDate.of(2026, 3, 14), MealSlot.DINNER)

    /**
     * `aggregateIdSchema` in `packages/contracts/src/primitives.ts`, transcribed.
     *
     * It is written out rather than referenced, because it is the rule a *stored* identifier is
     * ultimately judged against on a machine this test cannot reach, and a copy that could drift
     * towards what this file happens to emit would assert nothing at all.
     */
    private val AGGREGATE_ID = Regex("^[A-Za-z0-9._:-]+\$")

    /**
     * The separator is a colon, and that is a contract requirement rather than a preference.
     *
     * `aggregateIdSchema` in `packages/contracts` is `^[A-Za-z0-9._:-]+$`. The `/` this used to
     * write is not in it, so every proposal already journalled would have been refused *at the
     * envelope* — before any handler, before any storage — on the day `mealPlanEntry` joined
     * `AGGREGATE_TYPES`. `MealPlanIdRepair` moves the rows that exist; this is what stops the next
     * one being written.
     */
    @Test
    fun `the identity of a proposal is its date and its moment, in a sortable id`() {
        assertEquals("2026-03-14:dinner", key.aggregateId)
        assertEquals("2026-01-05:breakfast", MealPlanKey(LocalDate.of(2026, 1, 5), MealSlot.BREAKFAST).aggregateId)
        assertTrue(AGGREGATE_ID.matches(key.aggregateId))
    }

    /**
     * The old spelling still *reads*, and only reads.
     *
     * A phone upgrading mid-queue holds rows written with a `/` in `sync_mutations`, in
     * `sync_aggregate_state` and inside the `fromPlan` of journalled food-log payloads. Every one
     * of those has to keep resolving to the day it names while the repair works through them, and
     * a navigation key restored from saved instance state across the upgrade has to as well.
     */
    @Test
    fun `the slash a previous build wrote still parses, and is never written again`() {
        val legacy = "2026-03-14/dinner"
        assertEquals(key, MealPlanKey.parseOrNull(legacy))
        assertEquals("2026-03-14:dinner", MealPlanKey.canonicalOrNull(legacy))
        assertFalse(AGGREGATE_ID.matches(legacy))
    }

    /** A row already canonical is not a repair candidate: the pass must be idempotent. */
    @Test
    fun `an identifier a current build wrote needs no repair`() {
        assertNull(MealPlanKey.canonicalOrNull("2026-03-14:dinner"))
        assertNull(MealPlanKey.canonicalOrNull("not an identifier at all"))
    }

    @Test
    fun `every key written reads back identical, for every moment`() {
        MealSlot.entries.forEach { slot ->
            val written = MealPlanKey(LocalDate.of(2026, 12, 31), slot)
            assertEquals(written, MealPlanKey.parseOrNull(written.aggregateId))
        }
    }

    @Test
    fun `a key parses back across a leap day and a year boundary`() {
        listOf(LocalDate.of(2028, 2, 29), LocalDate.of(2026, 1, 1), LocalDate.of(1999, 12, 31))
            .forEach { date ->
                val written = MealPlanKey(date, MealSlot.LUNCH)
                assertEquals(written, MealPlanKey.parseOrNull(written.aggregateId))
            }
    }

    @Test
    fun `a malformed id is ignored rather than thrown`() {
        assertNull(MealPlanKey.parseOrNull(""))
        assertNull(MealPlanKey.parseOrNull(":"))
        assertNull(MealPlanKey.parseOrNull("dinner"))
        assertNull(MealPlanKey.parseOrNull("2026-03-14"))
        assertNull(MealPlanKey.parseOrNull("2026-03-14:"))
        assertNull(MealPlanKey.parseOrNull(":dinner"))
        assertNull(MealPlanKey.parseOrNull("2026-13-14:dinner"))
        assertNull(MealPlanKey.parseOrNull("not-a-date:dinner"))
    }

    @Test
    fun `an unknown moment is refused here, where the total fromId would have hidden it`() {
        assertEquals(MealSlot.SNACK, MealSlot.fromId("brunch"))
        assertNull(MealPlanKey.parseOrNull("2026-03-14:brunch"))
    }
}

class MealPlanEntryTest {

    private val entry = MealPlanEntry(
        plannedOn = LocalDate.of(2026, 3, 14),
        slot = MealSlot.DINNER,
        recipeId = RecipeId("recipe-3"),
        plannedServings = assertNotNull(Servings.ofConsumedOrNull(2.0)),
    )

    @Test
    fun `a proposal is addressed by its business key and carries no identifier of its own`() {
        assertEquals(MealPlanKey(LocalDate.of(2026, 3, 14), MealSlot.DINNER), entry.key)
        assertEquals("2026-03-14:dinner", entry.aggregateId)
        val fields = MealPlanEntry::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("id"))
    }

    @Test
    fun `PRD_FOOD 12 leaves a proposal unconfirmed until I ate this links a line to it`() {
        assertFalse(entry.isConsumed)
        assertNull(entry.consumedLogEntryId)
        val confirmed = entry.copy(consumedLogEntryId = FoodLogEntryId("line-9"))
        assertTrue(confirmed.isConsumed)
    }

    @Test
    fun `PRD_FOOD 15 plans today or ahead, and no further than sixty days`() {
        val today = LocalDate.of(2026, 3, 14)
        assertEquals(60L, MealPlanEntry.MAX_DAYS_AHEAD)
        assertTrue(MealPlanEntry.isPlannableOn(today, today))
        assertTrue(MealPlanEntry.isPlannableOn(today.plusDays(1), today))
        assertTrue(MealPlanEntry.isPlannableOn(today.plusDays(60), today))
        assertFalse(MealPlanEntry.isPlannableOn(today.plusDays(61), today))
        assertFalse(MealPlanEntry.isPlannableOn(today.minusDays(1), today))
    }

    @Test
    fun `a proposal and a journal line accept exactly opposite days, meeting only on today`() {
        val today = LocalDate.of(2026, 3, 14)
        assertTrue(MealPlanEntry.isPlannableOn(today, today) && FoodLogEntry.isLoggableOn(today, today))
        assertFalse(MealPlanEntry.isPlannableOn(today.minusDays(1), today))
        assertTrue(FoodLogEntry.isLoggableOn(today.minusDays(1), today))
        assertTrue(MealPlanEntry.isPlannableOn(today.plusDays(1), today))
        assertFalse(FoodLogEntry.isLoggableOn(today.plusDays(1), today))
    }

    @Test
    fun `a proposal carries a quarter-step count of servings and no nutritional value`() {
        assertEquals(2_000, entry.plannedServings.thousandths)
        assertTrue(entry.plannedServings.isConsumedCount)
        val fields = MealPlanEntry::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("nutrient", ignoreCase = true) })
        assertFalse(fields.any { it.contains("energy", ignoreCase = true) })
    }
}
