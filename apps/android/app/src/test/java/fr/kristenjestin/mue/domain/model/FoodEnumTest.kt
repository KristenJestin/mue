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
        assertEquals("morning_snack", MealSlot.MORNING_SNACK.id)
        assertEquals("lunch", MealSlot.LUNCH.id)
        assertEquals("snack", MealSlot.SNACK.id)
        assertEquals("dinner", MealSlot.DINNER.id)
        assertEquals("evening_snack", MealSlot.EVENING_SNACK.id)
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
    fun `PRD_FOOD 10-1 shows the six moments in one order, always`() {
        assertEquals(
            listOf(
                MealSlot.BREAKFAST,
                MealSlot.MORNING_SNACK,
                MealSlot.LUNCH,
                MealSlot.SNACK,
                MealSlot.DINNER,
                MealSlot.EVENING_SNACK,
            ),
            MealSlot.ORDERED,
        )
        assertEquals(MealSlot.entries.size, MealSlot.ORDERED.size)
    }

    @Test
    fun `the display order is the order of the day, so no window ends before it starts`() {
        // Every moment but the last starts later than the one before it. The last is the only
        // one allowed to break the rule, and breaking it is what "crosses midnight" means.
        MealSlot.ORDERED.zipWithNext { earlier, later ->
            assertTrue(later.from > earlier.from, "$later starts before $earlier")
        }
    }

    @Test
    fun `PRD_FOOD 10-3 defaults a retroactive entry to an hour inside its own moment`() {
        assertEquals(LocalTime.of(8, 0), MealSlot.BREAKFAST.defaultTime)
        assertEquals(LocalTime.of(11, 0), MealSlot.MORNING_SNACK.defaultTime)
        assertEquals(LocalTime.of(13, 0), MealSlot.LUNCH.defaultTime)
        assertEquals(LocalTime.of(16, 30), MealSlot.SNACK.defaultTime)
        assertEquals(LocalTime.of(20, 0), MealSlot.DINNER.defaultTime)
        assertEquals(LocalTime.of(23, 0), MealSlot.EVENING_SNACK.defaultTime)
    }

    @Test
    fun `every default time selects the moment it belongs to`() {
        MealSlot.entries.forEach { assertEquals(it, MealSlot.forTime(it.defaultTime)) }
    }

    @Test
    fun `a window ends exactly where the next one begins, so the day has no seam`() {
        assertEquals(LocalTime.of(10, 0), MealSlot.BREAKFAST.untilExclusive)
        assertEquals(LocalTime.of(12, 0), MealSlot.MORNING_SNACK.untilExclusive)
        assertEquals(LocalTime.of(14, 30), MealSlot.LUNCH.untilExclusive)
        assertEquals(LocalTime.of(18, 30), MealSlot.SNACK.untilExclusive)
        assertEquals(LocalTime.of(22, 0), MealSlot.DINNER.untilExclusive)
        // The one that wraps: it ends where the first one begins, on the following day.
        assertEquals(LocalTime.of(5, 0), MealSlot.EVENING_SNACK.untilExclusive)
    }

    @Test
    fun `exactly one moment crosses midnight`() {
        assertEquals(
            listOf(MealSlot.EVENING_SNACK),
            MealSlot.entries.filter { it.wrapsMidnight },
        )
    }

    @Test
    fun `PRD_FOOD 10-3 opens breakfast at five and closes it before ten`() {
        assertEquals(MealSlot.EVENING_SNACK, MealSlot.forTime(LocalTime.of(4, 59)))
        assertEquals(MealSlot.BREAKFAST, MealSlot.forTime(LocalTime.of(5, 0)))
        assertEquals(MealSlot.BREAKFAST, MealSlot.forTime(LocalTime.of(9, 59)))
    }

    @Test
    fun `PRD_FOOD 22 puts an apple at ten o'clock in a snack, not in breakfast`() {
        assertEquals(MealSlot.MORNING_SNACK, MealSlot.forTime(LocalTime.of(10, 0)))
        assertEquals(MealSlot.MORNING_SNACK, MealSlot.forTime(LocalTime.of(11, 29)))
        assertEquals(MealSlot.MORNING_SNACK, MealSlot.forTime(LocalTime.of(11, 59)))
    }

    @Test
    fun `PRD_FOOD 22 puts a dessert at two in the afternoon in lunch`() {
        assertEquals(MealSlot.LUNCH, MealSlot.forTime(LocalTime.of(14, 0)))
        assertEquals(MealSlot.LUNCH, MealSlot.forTime(LocalTime.of(12, 0)))
        assertEquals(MealSlot.LUNCH, MealSlot.forTime(LocalTime.of(14, 29)))
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(14, 30)))
    }

    @Test
    fun `dinner opens at half past six and closes at ten in the evening`() {
        assertEquals(MealSlot.SNACK, MealSlot.forTime(LocalTime.of(18, 29)))
        assertEquals(MealSlot.DINNER, MealSlot.forTime(LocalTime.of(18, 30)))
        assertEquals(MealSlot.DINNER, MealSlot.forTime(LocalTime.of(21, 59)))
        assertEquals(MealSlot.EVENING_SNACK, MealSlot.forTime(LocalTime.of(22, 0)))
    }

    @Test
    fun `the small hours belong to the evening snack, which is what a late dinner is`() {
        assertEquals(MealSlot.EVENING_SNACK, MealSlot.forTime(LocalTime.MIDNIGHT))
        assertEquals(MealSlot.EVENING_SNACK, MealSlot.forTime(LocalTime.of(23, 59)))
        assertEquals(MealSlot.EVENING_SNACK, MealSlot.forTime(LocalTime.of(1, 0)))
        assertEquals(MealSlot.EVENING_SNACK, MealSlot.forTime(LocalTime.of(3, 0)))
    }

    @Test
    fun `an unreadable id is a snack, which is what an older build makes of a newer one`() {
        assertEquals(MealSlot.SNACK, MealSlot.fromId("brunch"))
        // The demotion this branch describes, spelled out: a build that has never heard of the
        // two new moments still shows the line, under the heading that claims the least.
        assertEquals(MealSlot.SNACK, MealSlot.fromId("second_breakfast"))
    }

    @Test
    fun `the six windows never overlap, minute by minute across a whole day`() {
        val counts = (0 until 24 * 60)
            .map { MealSlot.forTime(LocalTime.of(it / 60, it % 60)) }
            .groupingBy { it }
            .eachCount()
        assertEquals(5 * 60, counts[MealSlot.BREAKFAST])
        assertEquals(2 * 60, counts[MealSlot.MORNING_SNACK])
        assertEquals(150, counts[MealSlot.LUNCH])
        assertEquals(4 * 60, counts[MealSlot.SNACK])
        assertEquals(210, counts[MealSlot.DINNER])
        assertEquals(7 * 60, counts[MealSlot.EVENING_SNACK])
        assertEquals(24 * 60, counts.values.sum())
        assertEquals(MealSlot.entries.size, counts.size)
    }
}


class RecipeTypeTest {

    @Test
    fun `PRD_FOOD 8-3 gives a recipe three types, not the journal's six moments`() {
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

/**
 * The whole clock, minute by minute: **no hour of the day is without a moment**.
 *
 * The owner's words are the requirement — "faut que chaque horaire ait son truc, qu'on ne se
 * retrouve pas avec des heures sans rien" — and the only way to prove a partition is to walk it.
 * A count per moment is what catches an off-by-one at a boundary that a handful of spot checks
 * reads straight past: two windows that overlap by a minute and two that leave a minute out both
 * still answer correctly at every hour anybody would think to test.
 *
 * The keys are the persisted ids rather than the constants, so this test is what a new member has
 * to satisfy before the enum has it.
 */
class MealSlotCoverageTest {

    @Test
    fun `every one of the 1440 minutes falls in exactly one moment`() {
        val counts = (0 until 24 * 60)
            .map { MealSlot.forTime(LocalTime.of(it / 60, it % 60)) }
            .groupingBy { it.id }
            .eachCount()

        assertEquals(
            mapOf(
                // 05:00 – 10:00
                "breakfast" to 5 * 60,
                // 10:00 – 12:00
                "morning_snack" to 2 * 60,
                // 12:00 – 14:30
                "lunch" to 150,
                // 14:30 – 18:30
                "snack" to 4 * 60,
                // 18:30 – 22:00
                "dinner" to 210,
                // 22:00 – 05:00, the one window that crosses midnight
                "evening_snack" to 7 * 60,
            ),
            counts,
        )
        assertEquals(24 * 60, counts.values.sum())
    }
}
