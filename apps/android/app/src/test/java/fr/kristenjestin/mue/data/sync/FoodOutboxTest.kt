package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val outbox = SyncOutbox(newMutationId = { "fixed-mutation" }, now = { 1_772_000_000_000L })

private val food = Food(
    id = FoodId("food-1"),
    name = "Huile d'olive",
    source = FoodSource.CUSTOM,
    per100 = Nutrients(
        energy = assertNotNull(Energy.ofMilliKcalOrNull(899_000)),
        protein = assertNotNull(Macro.ofMilligramsOrNull(0)),
    ),
)

private val recipe = RecipeDetail(
    recipe = Recipe(
        id = RecipeId("recipe-1"),
        name = "Dahl",
        type = RecipeType.MAIN,
        baseServings = 4,
        steps = listOf("Un", "Deux"),
    ),
    ingredients = listOf(
        RecipeIngredient(
            id = RecipeIngredientId("ing-1"),
            foodId = FoodId("food-1"),
            quantity = assertNotNull(Quantity.ofThousandthsOrNull(250_000)),
            unit = ReferenceUnit.GRAM,
            position = 0,
            foodName = "Lentilles corail",
        ),
    ),
)

private val logEntry = FoodLogEntry(
    id = FoodLogEntryId("log-1"),
    consumedOn = LocalDate.of(2026, 8, 25),
    consumedAt = LocalTime.of(13, 5),
    slot = MealSlot.LUNCH,
    kind = FoodLogKind.FOOD,
    title = "Dahl",
    amount = LoggedAmount.Measured(
        assertNotNull(Quantity.ofThousandthsOrNull(180_000)),
        ReferenceUnit.GRAM,
    ),
    nutrients = Nutrients(energy = assertNotNull(Energy.ofMilliKcalOrNull(320_000))),
    estimation = Estimation.MEASURED,
    sourceRef = "food-1",
)

private val plan = MealPlanEntry(
    plannedOn = LocalDate.of(2026, 9, 1),
    slot = MealSlot.DINNER,
    recipeId = RecipeId("recipe-1"),
    plannedServings = assertNotNull(Servings.ofThousandthsOrNull(2_000)),
)

/**
 * The four food aggregates journal through the same outbox as a measurement, and are keyed by
 * the four type names `FoodAggregates` already declares — which is what lets the Food tables
 * carry no synchronisation column of their own (PRD_FOOD 20.1, 21.2).
 */
class FoodOutboxAggregateTypeTest {

    @Test
    fun `each aggregate is journalled under the type the domain declares`() {
        assertEquals(FoodAggregates.TYPE_FOOD, outbox.foodUpsert(food).aggregateType)
        assertEquals(FoodAggregates.TYPE_RECIPE, outbox.recipeUpsert(recipe).aggregateType)
        assertEquals(FoodAggregates.TYPE_FOOD_LOG_ENTRY, outbox.foodLogUpsert(logEntry).aggregateType)
        assertEquals(FoodAggregates.TYPE_MEAL_PLAN_ENTRY, outbox.mealPlanUpsert(plan).aggregateType)
    }

    @Test
    fun `all four types are food aggregates and none is the measurement one`() {
        listOf(
            outbox.foodUpsert(food),
            outbox.recipeUpsert(recipe),
            outbox.foodLogUpsert(logEntry),
            outbox.mealPlanUpsert(plan),
        ).forEach { mutation ->
            assertTrue(FoodAggregates.isFoodAggregate(mutation.aggregateType))
        }
    }

    @Test
    fun `an aggregate is identified by its own key`() {
        assertEquals("food-1", outbox.foodUpsert(food).aggregateId)
        assertEquals("recipe-1", outbox.recipeUpsert(recipe).aggregateId)
        assertEquals("log-1", outbox.foodLogUpsert(logEntry).aggregateId)
    }

    /** PRD_FOOD 21.3: `(date, moment)` is the business key, so it is the aggregate id. */
    @Test
    fun `a proposition is identified by its date and moment, not by an invented id`() {
        assertEquals("2026-09-01/dinner", outbox.mealPlanUpsert(plan).aggregateId)
        assertEquals(
            "2026-09-01/dinner",
            outbox.mealPlanDelete(MealPlanKey(LocalDate.of(2026, 9, 1), MealSlot.DINNER)).aggregateId,
        )
    }

    @Test
    fun `every food mutation starts pending, unattempted and with no base revision`() {
        listOf(
            outbox.foodUpsert(food),
            outbox.recipeDelete(RecipeId("recipe-1")),
            outbox.foodLogUpsert(logEntry),
            outbox.mealPlanUpsert(plan),
        ).forEach { mutation ->
            assertEquals(SyncMutationEntity.STATE_PENDING, mutation.state)
            assertEquals(0, mutation.attemptCount)
            assertNull(mutation.baseRevision)
            assertEquals(PAYLOAD_SCHEMA_VERSION, mutation.payloadSchemaVersion)
        }
    }

    @Test
    fun `a delete carries no payload and an upsert always carries one`() {
        assertNull(outbox.foodDelete(FoodId("food-1")).payload)
        assertNull(outbox.recipeDelete(RecipeId("recipe-1")).payload)
        assertNull(outbox.foodLogDelete(FoodLogEntryId("log-1")).payload)
        assertNull(outbox.mealPlanDelete(plan.key).payload)

        assertNotNull(outbox.foodUpsert(food).payload)
        assertNotNull(outbox.recipeUpsert(recipe).payload)
        assertNotNull(outbox.foodLogUpsert(logEntry).payload)
        assertNotNull(outbox.mealPlanUpsert(plan).payload)
    }

    @Test
    fun `an upsert is an upsert and a delete is a delete`() {
        assertEquals(SyncMutationEntity.OP_UPSERT, outbox.foodUpsert(food).op)
        assertEquals(SyncMutationEntity.OP_DELETE, outbox.foodDelete(FoodId("food-1")).op)
    }
}

/**
 * A payload that turned a missing protein into `0` would make the server store a claim the phone
 * never made, and hand it back as fact on the next pull. So an unknown metric is an **absent
 * field**, and a measured zero is a present `0`.
 */
class FoodPayloadUnknownIsNotZeroTest {

    @Test
    fun `an unknown metric does not appear in the payload at all`() {
        val payload = assertNotNull(outbox.foodUpsert(food).payload)

        assertTrue(payload.contains("energyMilliKcal"), payload)
        assertTrue(!payload.contains("carbsMilligrams"), payload)
        assertTrue(!payload.contains("fibreMilligrams"), payload)
    }

    @Test
    fun `a measured zero does appear in the payload`() {
        val payload = assertNotNull(outbox.foodUpsert(food).payload)

        assertTrue(payload.contains("\"proteinMilligrams\":0"), payload)
    }

    @Test
    fun `a journal line with no macros carries only its energy`() {
        val payload = assertNotNull(outbox.foodLogUpsert(logEntry).payload)

        assertTrue(payload.contains("\"energyMilliKcal\":320000"), payload)
        assertTrue(!payload.contains("proteinMilligrams"), payload)
    }

    @Test
    fun `every number on the wire is an integer of its canonical unit`() {
        listOf(
            assertNotNull(outbox.foodUpsert(food).payload),
            assertNotNull(outbox.recipeUpsert(recipe).payload),
            assertNotNull(outbox.foodLogUpsert(logEntry).payload),
            assertNotNull(outbox.mealPlanUpsert(plan).payload),
        ).forEach { payload ->
            assertTrue(
                Regex("[0-9]+\\.[0-9]").find(payload) == null,
                "no decimal may reach the wire: $payload",
            )
        }
    }
}

class FoodPayloadShapeTest {

    /** PRD_FOOD 21.2: "une recette n'apparaît jamais sans ses ingrédients". */
    @Test
    fun `a recipe payload carries its ingredients`() {
        val payload = assertNotNull(outbox.recipeUpsert(recipe).payload)

        assertTrue(payload.contains("\"ingredients\""), payload)
        assertTrue(payload.contains("\"quantityThousandths\":250000"), payload)
        assertTrue(payload.contains("Lentilles corail"), payload)
    }

    @Test
    fun `a recipe payload carries no nutritional value`() {
        val payload = assertNotNull(outbox.recipeUpsert(recipe).payload)

        assertTrue(!payload.contains("energyMilliKcal"), payload)
    }

    @Test
    fun `a weighed line carries its quantity and its unit`() {
        val payload = assertNotNull(outbox.foodLogUpsert(logEntry).payload)

        assertTrue(payload.contains("\"quantityThousandths\":180000"), payload)
        assertTrue(payload.contains("\"quantityUnit\":\"gram\""), payload)
    }

    @Test
    fun `a portioned line carries its servings under the serving unit`() {
        val portioned = logEntry.copy(
            kind = FoodLogKind.RECIPE,
            amount = LoggedAmount.Portioned(assertNotNull(Servings.ofThousandthsOrNull(1_500))),
        )
        val payload = assertNotNull(outbox.foodLogUpsert(portioned).payload)

        assertTrue(payload.contains("\"quantityThousandths\":1500"), payload)
        assertTrue(payload.contains("\"quantityUnit\":\"serving\""), payload)
    }

    @Test
    fun `an unmeasured line carries neither quantity nor unit`() {
        val quick = logEntry.copy(kind = FoodLogKind.QUICK, amount = LoggedAmount.Unmeasured)
        val payload = assertNotNull(outbox.foodLogUpsert(quick).payload)

        assertTrue(!payload.contains("quantityThousandths"), payload)
        assertTrue(!payload.contains("quantityUnit"), payload)
    }

    @Test
    fun `a line born of a proposition names it by its business key`() {
        val fromPlan = logEntry.copy(fromPlan = plan.key)
        val payload = assertNotNull(outbox.foodLogUpsert(fromPlan).payload)

        assertTrue(payload.contains("\"fromPlan\":\"2026-09-01/dinner\""), payload)
    }

    @Test
    fun `a proposition payload carries its servings as thousandths`() {
        val payload = assertNotNull(outbox.mealPlanUpsert(plan).payload)

        assertTrue(payload.contains("\"plannedServingsThousandths\":2000"), payload)
        assertTrue(!payload.contains("consumedLogEntryId"), payload)
    }

    @Test
    fun `the mutation id and the clock are the injected ones`() {
        val mutation = outbox.foodUpsert(food)

        assertEquals("fixed-mutation", mutation.mutationId)
        assertEquals(1_772_000_000_000L, mutation.createdAt)
    }
}
