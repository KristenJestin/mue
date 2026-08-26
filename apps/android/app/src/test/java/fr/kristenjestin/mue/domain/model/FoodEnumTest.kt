package fr.kristenjestin.mue.domain.model

import org.junit.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ids nothing may ever renumber, since they are what the columns of PRD_FOOD 20 hold. */
class FoodEnumIdTest {

    @Test
    fun `every persisted id is unique inside its own enum`() {
        assertEquals(FoodSource.entries.size, FoodSource.entries.map { it.id }.toSet().size)
        assertEquals(ReferenceUnit.entries.size, ReferenceUnit.entries.map { it.id }.toSet().size)
        assertEquals(QuantityUnit.entries.size, QuantityUnit.entries.map { it.id }.toSet().size)
        assertEquals(MealSlot.entries.size, MealSlot.entries.map { it.id }.toSet().size)
        assertEquals(RecipeType.entries.size, RecipeType.entries.map { it.id }.toSet().size)
        assertEquals(FoodLogKind.entries.size, FoodLogKind.entries.map { it.id }.toSet().size)
        assertEquals(Estimation.entries.size, Estimation.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `every enum reads back the id it wrote`() {
        FoodSource.entries.forEach { assertEquals(it, FoodSource.fromId(it.id)) }
        ReferenceUnit.entries.forEach { assertEquals(it, ReferenceUnit.fromId(it.id)) }
        QuantityUnit.entries.forEach { assertEquals(it, QuantityUnit.fromId(it.id)) }
        MealSlot.entries.forEach { assertEquals(it, MealSlot.fromId(it.id)) }
        RecipeType.entries.forEach { assertEquals(it, RecipeType.fromId(it.id)) }
        FoodLogKind.entries.forEach { assertEquals(it, FoodLogKind.fromId(it.id)) }
        Estimation.entries.forEach { assertEquals(it, Estimation.fromId(it.id)) }
    }

    @Test
    fun `no fromId ever throws, whatever a future schema writes`() {
        val nonsense = listOf("", " ", "unknown", "FOOD", "café", "null")
        nonsense.forEach {
            FoodSource.fromId(it)
            ReferenceUnit.fromId(it)
            QuantityUnit.fromId(it)
            MealSlot.fromId(it)
            RecipeType.fromId(it)
            FoodLogKind.fromId(it)
            Estimation.fromId(it)
        }
    }

    @Test
    fun `the ids are the exact strings PRD_FOOD 8 names, and are stable`() {
        assertEquals("ciqual", FoodSource.CIQUAL.id)
        assertEquals("open_food_facts", FoodSource.OPEN_FOOD_FACTS.id)
        assertEquals("custom", FoodSource.CUSTOM.id)
        assertEquals("gram", ReferenceUnit.GRAM.id)
        assertEquals("millilitre", ReferenceUnit.MILLILITRE.id)
        assertEquals("serving", QuantityUnit.SERVING.id)
        assertEquals("breakfast", MealSlot.BREAKFAST.id)
        assertEquals("lunch", MealSlot.LUNCH.id)
        assertEquals("snack", MealSlot.SNACK.id)
        assertEquals("dinner", MealSlot.DINNER.id)
        assertEquals("main", RecipeType.MAIN.id)
        assertEquals("quick", FoodLogKind.QUICK.id)
        assertEquals("approximate", Estimation.APPROXIMATE.id)
    }
}

class FoodSourceTest {

    @Test
    fun `an unreadable provenance falls back to the one that stays deletable`() {
        assertEquals(FoodSource.CUSTOM, FoodSource.fromId("whatever"))
        assertFalse(FoodSource.fromId("whatever").isReadOnly)
    }

    @Test
    fun `PRD_FOOD 9-1 makes the embedded reference table the only read-only source`() {
        assertTrue(FoodSource.CIQUAL.isReadOnly)
        assertFalse(FoodSource.OPEN_FOOD_FACTS.isReadOnly)
        assertFalse(FoodSource.CUSTOM.isReadOnly)
    }

    @Test
    fun `PRD_FOOD 21-1 synchronises everything except the reference table`() {
        assertFalse(FoodSource.CIQUAL.isSynchronised)
        assertTrue(FoodSource.OPEN_FOOD_FACTS.isSynchronised)
        assertTrue(FoodSource.CUSTOM.isSynchronised)
    }
}

class ReferenceUnitTest {

    @Test
    fun `a reference unit reads as the journal's own unit without inventing a density`() {
        assertEquals(QuantityUnit.GRAM, ReferenceUnit.GRAM.asQuantityUnit)
        assertEquals(QuantityUnit.MILLILITRE, ReferenceUnit.MILLILITRE.asQuantityUnit)
    }

    @Test
    fun `an unreadable unit is a gram, and the symbols are the ones shown on screen`() {
        assertEquals(ReferenceUnit.GRAM, ReferenceUnit.fromId("litre"))
        assertEquals("g", ReferenceUnit.GRAM.symbol)
        assertEquals("ml", ReferenceUnit.MILLILITRE.symbol)
        assertEquals(QuantityUnit.GRAM, QuantityUnit.fromId("litre"))
    }
}

class MealSlotTest {

    @Test
    fun `PRD_FOOD 10-1 shows the four moments in one order, always`() {
        assertEquals(
            listOf(MealSlot.BREAKFAST, MealSlot.LUNCH, MealSlot.SNACK, MealSlot.DINNER),
            MealSlot.ORDERED,
        )
        assertEquals(MealSlot.entries.size, MealSlot.ORDERED.size)
    }

    @Test
    fun `PRD_FOOD 10-3 defaults a retroactive entry to the middle of its moment`() {
        assertEquals(LocalTime.of(8, 0), MealSlot.BREAKFAST.defaultTime)
        assertEquals(LocalTime.of(13, 0), MealSlot.LUNCH.defaultTime)
        assertEquals(LocalTime.of(16, 30), MealSlot.SNACK.defaultTime)
        assertEquals(LocalTime.of(20, 0), MealSlot.DINNER.defaultTime)
    }

    @Test
    fun `every default time selects the moment it belongs to`() {
        MealSlot.entries.forEach { assertEquals(it, MealSlot.forTime(it.defaultTime)) }
    }

    @Test
    fun `PRD_FOOD 10-3 opens breakfast at five and closes it before ten`() {
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(4, 59)))
        assertEquals(MealSlot.BREAKFAST, MealSlot.forTime(LocalTime.of(5, 0)))
        assertEquals(MealSlot.BREAKFAST, MealSlot.forTime(LocalTime.of(9, 59)))
    }

    @Test
    fun `PRD_FOOD 22 puts an apple at ten o'clock in the snack, not in breakfast`() {
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(10, 0)))
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(11, 29)))
    }

    @Test
    fun `PRD_FOOD 22 puts a dessert at two in the afternoon in lunch`() {
        assertEquals(MealSlot.LUNCH, MealSlot.forTime(LocalTime.of(14, 0)))
        assertEquals(MealSlot.LUNCH, MealSlot.forTime(LocalTime.of(11, 30)))
        assertEquals(MealSlot.LUNCH, MealSlot.forTime(LocalTime.of(14, 29)))
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(14, 30)))
    }

    @Test
    fun `PRD_FOOD 10-3 opens dinner at six and closes it before ten in the evening`() {
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(17, 59)))
        assertEquals(MealSlot.DINNER, MealSlot.forTime(LocalTime.of(18, 0)))
        assertEquals(MealSlot.DINNER, MealSlot.forTime(LocalTime.of(21, 59)))
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(22, 0)))
    }

    @Test
    fun `everything outside the three named windows is a snack, including midnight`() {
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.MIDNIGHT))
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(23, 59)))
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(3, 0)))
        assertEquals(MealSlot.SNACK, MealSlot.fromId("brunch"))
    }

    @Test
    fun `the three windows never overlap, minute by minute across a whole day`() {
        val counts = (0 until 24 * 60)
            .map { MealSlot.forTime(LocalTime.of(it / 60, it % 60)) }
            .groupingBy { it }
            .eachCount()
        assertEquals(5 * 60, counts[MealSlot.BREAKFAST])
        assertEquals(3 * 60, counts[MealSlot.LUNCH])
        assertEquals(4 * 60, counts[MealSlot.DINNER])
        assertEquals(24 * 60 - 12 * 60, counts[MealSlot.SNACK])
    }
}

class RecipeTypeTest {

    @Test
    fun `PRD_FOOD 8-3 gives a recipe three types, not the journal's four moments`() {
        assertEquals(3, RecipeType.entries.size)
        assertEquals(RecipeType.MAIN, RecipeType.fromId("dinner"))
        assertEquals("Main", RecipeType.MAIN.label)
    }
}

class FoodLogKindTest {

    @Test
    fun `an unreadable kind falls back to the only self-contained form`() {
        assertEquals(FoodLogKind.QUICK, FoodLogKind.fromId("meal"))
        assertEquals(3, FoodLogKind.entries.size)
    }
}

class EstimationTest {

    @Test
    fun `an unreadable estimation admits it is approximate rather than claiming precision`() {
        assertEquals(Estimation.APPROXIMATE, Estimation.fromId("exact"))
        assertEquals(2, Estimation.entries.size)
    }
}

class FoodAggregatesTest {

    @Test
    fun `PRD_FOOD 21-2 declares exactly four aggregates, in its own order`() {
        assertEquals(
            listOf("food", "recipe", "foodLogEntry", "mealPlanEntry"),
            FoodAggregates.ALL,
        )
        assertEquals(4, FoodAggregates.ALL.size)
        assertEquals(FoodAggregates.ALL.size, FoodAggregates.ALL.toSet().size)
    }

    @Test
    fun `the four names follow the camel case the shipped aggregates already use`() {
        assertEquals("food", FoodAggregates.TYPE_FOOD)
        assertEquals("recipe", FoodAggregates.TYPE_RECIPE)
        assertEquals("foodLogEntry", FoodAggregates.TYPE_FOOD_LOG_ENTRY)
        assertEquals("mealPlanEntry", FoodAggregates.TYPE_MEAL_PLAN_ENTRY)
    }

    @Test
    fun `nothing else is a food aggregate, including the shipped ones`() {
        FoodAggregates.ALL.forEach { assertTrue(FoodAggregates.isFoodAggregate(it)) }
        assertFalse(FoodAggregates.isFoodAggregate("measurement"))
        assertFalse(FoodAggregates.isFoodAggregate("activitySession"))
        assertFalse(FoodAggregates.isFoodAggregate("recipeIngredient"))
        assertFalse(FoodAggregates.isFoodAggregate(""))
    }
}
