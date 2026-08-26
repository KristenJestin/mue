package fr.kristenjestin.mue.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.domain.model.FoodAggregates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomRecipeRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: RoomRecipeRepository

    @Before
    fun createRepository() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MueDatabase::class.java,
        ).build()
        repository = RoomRecipeRepository(database.recipeDao(), SyncOutbox())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun ingredient(
        id: String,
        foodId: String,
        position: Int,
        thousandths: Long = 250_000,
    ): RecipeIngredient = RecipeIngredient(
        id = RecipeIngredientId(id),
        foodId = FoodId(foodId),
        quantity = requireNotNull(Quantity.ofThousandthsOrNull(thousandths)),
        unit = ReferenceUnit.GRAM,
        position = position,
    )

    private fun detail(
        id: String = "recipe-1",
        name: String = "Dahl de lentilles",
        type: RecipeType = RecipeType.MAIN,
        favourite: Boolean = false,
        ingredients: List<RecipeIngredient> = emptyList(),
    ): RecipeDetail = RecipeDetail(
        recipe = Recipe(
            id = RecipeId(id),
            name = name,
            type = type,
            baseServings = 4,
            isFavourite = favourite,
        ),
        ingredients = ingredients,
    )

    @Test
    fun roundTripsARecipeAndItsIngredientsThroughTheRealDao() = runTest {
        val original = RecipeDetail(
            recipe = Recipe(
                id = RecipeId("recipe-full"),
                name = "Curry de pois chiches",
                type = RecipeType.BREAKFAST,
                baseServings = 6,
                description = "Une description",
                prepTimeMinutes = 35,
                steps = listOf("Émincer l'oignon", "Ajouter les épices"),
                imageRef = "images/curry.webp",
                isFavourite = true,
            ),
            ingredients = listOf(
                ingredient("ing-1", "food-1", 0).copy(foodName = "Pois chiches"),
                ingredient("ing-2", "food-2", 1, 30_000),
            ),
        )

        repository.save(original)

        assertEquals(original, repository.findDetail(RecipeId("recipe-full")))
        assertEquals(original, repository.observeDetail(RecipeId("recipe-full")).first())
    }

    @Test
    fun aRecipeWithNoIngredientsIsStillARecipe() = runTest {
        val original = detail()

        repository.save(original)

        assertEquals(original, repository.findDetail(RecipeId("recipe-1")))
        assertEquals(1, repository.observeCount().first())
    }

    /** PRD_FOOD 8.3: nothing nutritional is stored, so nothing nutritional can go stale. */
    @Test
    fun theRecipeTableHoldsNoNutritionalColumn() = runTest {
        repository.save(detail())

        database.openHelper.readableDatabase.query("PRAGMA table_info(`recipe`)").use { cursor ->
            val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            assertTrue(
                "$columns must hold no nutrient",
                columns.none { it.contains("energy") || it.contains("protein") },
            )
        }
    }

    @Test
    fun ingredientsAreRenumberedFromTheOrderTheyArriveIn() = runTest {
        repository.save(
            detail(
                ingredients = listOf(
                    ingredient("ing-a", "food-1", 7),
                    ingredient("ing-b", "food-2", 3),
                ),
            ),
        )

        val read = requireNotNull(repository.findDetail(RecipeId("recipe-1")))
        assertEquals(listOf(0, 1), read.ingredients.map { it.position })
        assertEquals(listOf("ing-a", "ing-b"), read.ingredients.map { it.id.value })
    }

    /** PRD_FOOD 21.3: the whole aggregate is replaced, never merged ingredient by ingredient. */
    @Test
    fun savingAgainReplacesTheWholeIngredientList() = runTest {
        repository.save(detail(ingredients = listOf(ingredient("ing-a", "food-1", 0))))

        repository.save(detail(ingredients = listOf(ingredient("ing-b", "food-2", 0))))

        val read = requireNotNull(repository.findDetail(RecipeId("recipe-1")))
        assertEquals(listOf("ing-b"), read.ingredients.map { it.id.value })
    }

    @Test
    fun savingARecipeJournalsOneMutationForTheWholeAggregate() = runTest {
        repository.save(
            detail(
                ingredients = listOf(
                    ingredient("ing-a", "food-1", 0),
                    ingredient("ing-b", "food-2", 1),
                ),
            ),
        )

        val pending = database.syncDao().pendingMutations(10)
        assertEquals(1, pending.size)
        assertEquals(FoodAggregates.TYPE_RECIPE, pending.single().aggregateType)
        assertEquals("recipe-1", pending.single().aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, pending.single().op)
        assertTrue(requireNotNull(pending.single().payload).contains("ingredients"))
        assertTrue(requireNotNull(pending.single().payload).contains("food-2"))
    }

    @Test
    fun deletingARecipeLeavesATombstoneBehindIt() = runTest {
        repository.save(detail())

        repository.delete(RecipeId("recipe-1"))

        assertNull(repository.findDetail(RecipeId("recipe-1")))
        val tombstones = database.syncDao().tombstones(FoodAggregates.TYPE_RECIPE)
        assertEquals(listOf("recipe-1"), tombstones.map { it.aggregateId })
        assertNotNull(tombstones.single().deletedAt)
    }

    /** The ingredients follow through SQLite's own cascade, which is why the key is indexed. */
    @Test
    fun deletingARecipeTakesItsIngredientsWithIt() = runTest {
        repository.save(detail(ingredients = listOf(ingredient("ing-a", "food-1", 0))))

        repository.delete(RecipeId("recipe-1"))

        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM recipe_ingredient")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
    }

    /**
     * A proposition is an aggregate of its own (PRD_FOOD 21.2), so the cascade is not enough:
     * each moment emptied has to leave its own tombstone, or a copy still queued on another
     * device would come back on the next pull.
     */
    @Test
    fun deletingARecipeEmptiesTheMomentsThatProposedItAndTombstonesEachOne() = runTest {
        repository.save(detail())
        val plans = RoomMealPlanRepository(database.mealPlanDao(), SyncOutbox())
        plans.save(plan(LocalDate.of(2026, 9, 1), MealSlot.DINNER))
        plans.save(plan(LocalDate.of(2026, 9, 2), MealSlot.LUNCH))

        val emptied = repository.delete(RecipeId("recipe-1"))

        assertEquals(
            listOf(
                MealPlanKey(LocalDate.of(2026, 9, 1), MealSlot.DINNER),
                MealPlanKey(LocalDate.of(2026, 9, 2), MealSlot.LUNCH),
            ),
            emptied,
        )
        assertEquals(0, database.mealPlanDao().count())
        assertEquals(
            listOf("2026-09-01/dinner", "2026-09-02/lunch"),
            database.syncDao().tombstones(FoodAggregates.TYPE_MEAL_PLAN_ENTRY)
                .map { it.aggregateId }
                .sorted(),
        )
    }

    @Test
    fun aFavouriteFlipIsJournalledAsTheWholeRecipe() = runTest {
        repository.save(detail())
        val before = database.syncDao().pendingMutations(50).size

        repository.setFavourite(RecipeId("recipe-1"), true)

        assertTrue(requireNotNull(repository.findDetail(RecipeId("recipe-1"))).recipe.isFavourite)
        val pending = database.syncDao().pendingMutations(50)
        assertEquals(before + 1, pending.size)
        assertTrue(requireNotNull(pending.last().payload).contains("\"isFavourite\":true"))
    }

    @Test
    fun flippingTheFavouriteOfAnAbsentRecipeChangesNothing() = runTest {
        repository.setFavourite(RecipeId("absent"), true)

        assertEquals(0, database.syncDao().pendingMutations(10).size)
    }

    @Test
    fun theListCanBeFilteredByTypeAndByFavourite() = runTest {
        repository.save(detail(id = "a", name = "Alpha", type = RecipeType.MAIN))
        repository.save(detail(id = "b", name = "Bravo", type = RecipeType.SNACK, favourite = true))
        repository.save(detail(id = "c", name = "Charlie", type = RecipeType.MAIN, favourite = true))

        assertEquals(3, repository.observeAll().first().size)
        assertEquals(
            listOf("c", "a"),
            repository.observeAll(RecipeType.MAIN).first().map { it.id.value },
        )
        assertEquals(
            listOf("b", "c"),
            repository.observeAll(favouritesOnly = true).first().map { it.id.value }.sorted(),
        )
    }

    @Test
    fun searchIsInsensitiveToCaseAndAccents() = runTest {
        repository.save(detail(id = "a", name = "Crème de marrons"))
        repository.save(detail(id = "b", name = "Soupe"))

        assertEquals(listOf("a"), repository.search("CREME").first().map { it.id.value })
        assertEquals(emptyList<String>(), repository.search("creme", RecipeType.SNACK).first()
            .map { it.id.value })
    }

    @Test
    fun theRecipesUsingAFoodAreFound() = runTest {
        repository.save(detail(id = "a", name = "Alpha", ingredients = listOf(ingredient("i1", "food-1", 0))))
        repository.save(detail(id = "b", name = "Bravo", ingredients = listOf(ingredient("i2", "food-2", 0))))

        assertEquals(listOf("a"), repository.findUsing(FoodId("food-1")).map { it.id.value })
        assertEquals(emptyList<String>(), repository.findUsing(FoodId("food-9")).map { it.id.value })
    }

    /** PRD_FOOD 21.2: a recipe may name a food the client has not received yet. */
    @Test
    fun anIngredientMayNameAFoodThatIsNotInTheCatalogue() = runTest {
        val original = detail(
            ingredients = listOf(ingredient("i1", "never-received", 0).copy(foodName = "Quinoa")),
        )

        repository.save(original)

        val read = requireNotNull(repository.findDetail(RecipeId("recipe-1")))
        assertEquals(FoodId("never-received"), read.ingredients.single().foodId)
        assertEquals("Quinoa", read.ingredients.single().foodName)
    }

    @Test
    fun anAbsentRecipeHasNoDetail() = runTest {
        assertNull(repository.findDetail(RecipeId("absent")))
        assertNull(repository.observeDetail(RecipeId("absent")).first())
    }

    private fun plan(date: LocalDate, slot: MealSlot): MealPlanEntry = MealPlanEntry(
        plannedOn = date,
        slot = slot,
        recipeId = RecipeId("recipe-1"),
        plannedServings = requireNotNull(Servings.ofThousandthsOrNull(2_000)),
    )
}
