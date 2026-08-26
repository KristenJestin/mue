package fr.kristenjestin.mue.data.local.database

import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val STAMP = 1_772_000_000_000L

private const val RECIPE_ID = "66666666-6666-4666-8666-666666666666"

private fun recipe(steps: List<String> = emptyList()): Recipe = Recipe(
    id = RecipeId(RECIPE_ID),
    name = "Dahl de lentilles corail",
    type = RecipeType.MAIN,
    baseServings = 4,
    steps = steps,
)

private fun ingredient(position: Int = 0): RecipeIngredient = RecipeIngredient(
    id = RecipeIngredientId("77777777-7777-4777-8777-777777777777"),
    foodId = FoodId("88888888-8888-4888-8888-888888888888"),
    quantity = assertNotNull(Quantity.ofThousandthsOrNull(250_000)),
    unit = ReferenceUnit.GRAM,
    position = position,
)

class RecipeEntityRoundTripTest {

    @Test
    fun `a fully described recipe survives the round trip unchanged`() {
        val original = Recipe(
            id = RecipeId(RECIPE_ID),
            name = "Curry de pois chiches",
            type = RecipeType.BREAKFAST,
            baseServings = 6,
            description = "Une description",
            prepTimeMinutes = 35,
            steps = listOf("Émincer l'oignon", "Ajouter les épices"),
            imageRef = "images/curry.webp",
            isFavourite = true,
        )

        assertEquals(original, original.toEntity(STAMP, STAMP).toDomain())
    }

    @Test
    fun `a minimal recipe survives the round trip unchanged`() {
        val original = recipe()

        assertEquals(original, original.toEntity(STAMP, STAMP).toDomain())
    }

    @Test
    fun `every recipe type round trips through its stable id`() {
        RecipeType.entries.forEach { type ->
            val stored = recipe().copy(type = type).toEntity(STAMP, STAMP)

            assertEquals(type.id, stored.type)
            assertEquals(type, stored.toDomain().type)
        }
    }

    @Test
    fun `the folded name is written for the search index`() {
        val stored = recipe().copy(name = "Œufs à la Coque").toEntity(STAMP, STAMP)

        assertEquals("œufs a la coque", stored.nameFolded)
    }

    @Test
    fun `the steps travel as one JSON column and come back in order`() {
        val steps = listOf("Un", "Deux \"guillemets\"", "Trois\nlignes")
        val stored = recipe(steps).toEntity(STAMP, STAMP)

        assertEquals(steps, stored.toDomain().steps)
        assertEquals(steps, decodeSteps(stored.steps))
    }

    @Test
    fun `no steps is an empty JSON array, not a null column`() {
        assertEquals("[]", recipe().toEntity(STAMP, STAMP).steps)
    }

    /** Losing the prose must not make the ingredients — the part that produces numbers — unreadable. */
    @Test
    fun `an unparsable steps column yields no steps rather than a crash`() {
        val stored = recipe(listOf("Un")).toEntity(STAMP, STAMP).copy(steps = "not json")

        assertEquals(emptyList(), stored.toDomain().steps)
        assertEquals("Dahl de lentilles corail", stored.toDomain().name)
    }

    @Test
    fun `the recipe stores no nutritional column at all`() {
        val columns = RecipeEntity::class.java.declaredFields.map { it.name }

        assertNull(columns.firstOrNull { it.contains("energy", ignoreCase = true) })
        assertNull(columns.firstOrNull { it.contains("protein", ignoreCase = true) })
        assertNull(columns.firstOrNull { it.contains("nutrient", ignoreCase = true) })
    }
}

class RecipeIngredientEntityTest {

    @Test
    fun `an ingredient survives the round trip unchanged`() {
        val original = ingredient(position = 3).copy(foodName = "Lentilles corail")

        assertEquals(original, original.toEntity(RECIPE_ID).toDomainOrNull())
    }

    @Test
    fun `the recipe it belongs to is written on the row`() {
        assertEquals(RECIPE_ID, ingredient().toEntity(RECIPE_ID).recipeId)
    }

    @Test
    fun `both reference units round trip through their stable id`() {
        ReferenceUnit.entries.forEach { unit ->
            val stored = ingredient().copy(unit = unit).toEntity(RECIPE_ID)

            assertEquals(unit.id, stored.unit)
            assertEquals(unit, assertNotNull(stored.toDomainOrNull()).unit)
        }
    }

    /** PRD_FOOD 21.2: the snapshot shown until the food the row names arrives. */
    @Test
    fun `the food name snapshot is optional and survives when present`() {
        assertNull(ingredient().toEntity(RECIPE_ID).foodName)
        assertEquals(
            "Lentilles corail",
            ingredient().copy(foodName = "Lentilles corail").toEntity(RECIPE_ID).foodName,
        )
    }

    @Test
    fun `a quantity of zero is unreadable rather than an ingredient of nothing`() {
        val stored = ingredient().toEntity(RECIPE_ID).copy(quantityThousandths = 0)

        assertNull(stored.toDomainOrNull())
    }

    @Test
    fun `a negative quantity is unreadable too`() {
        val stored = ingredient().toEntity(RECIPE_ID).copy(quantityThousandths = -1)

        assertNull(stored.toDomainOrNull())
    }
}

class MealPlanEntryEntityTest {

    private val entry = MealPlanEntry(
        plannedOn = LocalDate.of(2026, 9, 1),
        slot = MealSlot.DINNER,
        recipeId = RecipeId(RECIPE_ID),
        plannedServings = assertNotNull(Servings.ofThousandthsOrNull(2_000)),
    )

    @Test
    fun `a proposition survives the round trip unchanged`() {
        assertEquals(entry, entry.toEntity(STAMP, STAMP).toDomainOrNull())
    }

    @Test
    fun `a confirmed proposition keeps the line it produced`() {
        val original = entry.copy(
            consumedLogEntryId = fr.kristenjestin.mue.domain.model.FoodLogEntryId("abc"),
        )

        assertEquals(original, original.toEntity(STAMP, STAMP).toDomainOrNull())
        assertEquals("abc", original.toEntity(STAMP, STAMP).consumedLogEntryId)
    }

    /** The primary key is the business key of PRD_FOOD 21.3, and the entity carries no id. */
    @Test
    fun `the row is keyed by the date and the moment, and carries no id column`() {
        val stored = entry.toEntity(STAMP, STAMP)

        assertEquals("2026-09-01", stored.plannedOn)
        assertEquals(MealSlot.DINNER.id, stored.slot)
        assertNull(MealPlanEntryEntity::class.java.declaredFields.firstOrNull { it.name == "id" })
    }

    @Test
    fun `the aggregate id the outbox journals is the same pair`() {
        assertEquals("2026-09-01/dinner", entry.aggregateId)
        assertEquals(entry.key, assertNotNull(fr.kristenjestin.mue.domain.model.MealPlanKey
            .parseOrNull(entry.aggregateId)))
    }

    @Test
    fun `every moment round trips through its stable id`() {
        MealSlot.entries.forEach { slot ->
            val stored = entry.copy(slot = slot).toEntity(STAMP, STAMP)

            assertEquals(slot, assertNotNull(stored.toDomainOrNull()).slot)
        }
    }

    @Test
    fun `a proposition of no servings is unreadable rather than a plan for nothing`() {
        val stored = entry.toEntity(STAMP, STAMP).copy(plannedServingsThousandths = 0)

        assertNull(stored.toDomainOrNull())
    }
}
