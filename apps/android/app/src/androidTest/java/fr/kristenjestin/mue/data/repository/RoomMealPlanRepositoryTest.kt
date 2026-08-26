package fr.kristenjestin.mue.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.Servings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class RoomMealPlanRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: RoomMealPlanRepository

    private val day: LocalDate = LocalDate.of(2026, 9, 1)

    @Before
    fun createRepository() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MueDatabase::class.java,
        ).build()
        repository = RoomMealPlanRepository(database.mealPlanDao(), SyncOutbox())

        val recipes = RoomRecipeRepository(database.recipeDao(), SyncOutbox())
        recipes.save(recipe("recipe-a", "Dahl"))
        recipes.save(recipe("recipe-b", "Curry"))
        // The recipe writes are the fixture, not the subject; start the outbox from empty.
        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_mutations")
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun recipe(id: String, name: String): RecipeDetail = RecipeDetail(
        recipe = Recipe(
            id = RecipeId(id),
            name = name,
            type = RecipeType.MAIN,
            baseServings = 4,
        ),
    )

    private fun plan(
        date: LocalDate = day,
        slot: MealSlot = MealSlot.DINNER,
        recipeId: String = "recipe-a",
        servings: Long = 2_000,
        consumed: String? = null,
    ): MealPlanEntry = MealPlanEntry(
        plannedOn = date,
        slot = slot,
        recipeId = RecipeId(recipeId),
        plannedServings = requireNotNull(Servings.ofThousandthsOrNull(servings)),
        consumedLogEntryId = consumed?.let(::FoodLogEntryId),
    )

    @Test
    fun roundTripsAPropositionThroughTheRealDao() = runTest {
        val original = plan(consumed = "log-1")

        repository.save(original)

        assertEquals(original, repository.find(original.key))
        assertEquals(listOf(original), repository.observeDay(day).first())
    }

    /**
     * The whole reason the table is keyed by `(planned_on, slot)` and not by a UUID: two
     * propositions for one moment converge on one row, structurally (PRD_FOOD 8.5, 21.3). With an
     * id, both would be valid rows and every client would have to re-enforce the rule by hand.
     */
    @Test
    fun aSecondPropositionForTheSameMomentReplacesTheFirst() = runTest {
        repository.save(plan(recipeId = "recipe-a"))

        repository.save(plan(recipeId = "recipe-b", servings = 1_000))

        assertEquals(1, database.mealPlanDao().count())
        val read = requireNotNull(repository.find(MealPlanKey(day, MealSlot.DINNER)))
        assertEquals(RecipeId("recipe-b"), read.recipeId)
        assertEquals(1_000, read.plannedServings.thousandths)
    }

    @Test
    fun theSameDayHoldsOnePropositionPerMoment() = runTest {
        MealSlot.entries.forEach { slot -> repository.save(plan(slot = slot)) }

        assertEquals(MealSlot.entries.size, repository.observeDay(day).first().size)
        assertEquals(
            MealSlot.ORDERED,
            repository.observeDay(day).first().map { it.slot },
        )
    }

    @Test
    fun savingAPropositionJournalsAnUpsertKeyedByTheDateAndTheMoment() = runTest {
        repository.save(plan())

        val pending = database.syncDao().pendingMutations(10)
        assertEquals(1, pending.size)
        assertEquals(FoodAggregates.TYPE_MEAL_PLAN_ENTRY, pending.single().aggregateType)
        assertEquals("2026-09-01/dinner", pending.single().aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, pending.single().op)
        assertNotNull(pending.single().payload)
    }

    @Test
    fun deletingAPropositionLeavesATombstoneBehindIt() = runTest {
        repository.save(plan())

        repository.delete(MealPlanKey(day, MealSlot.DINNER))

        assertNull(repository.find(MealPlanKey(day, MealSlot.DINNER)))
        val tombstones = database.syncDao().tombstones(FoodAggregates.TYPE_MEAL_PLAN_ENTRY)
        assertEquals(listOf("2026-09-01/dinner"), tombstones.map { it.aggregateId })
        assertNotNull(tombstones.single().deletedAt)
    }

    @Test
    fun proposingAgainOnATombstonedMomentBringsItBackToLife() = runTest {
        repository.save(plan())
        repository.delete(MealPlanKey(day, MealSlot.DINNER))

        repository.save(plan(recipeId = "recipe-b"))

        assertEquals(0, database.syncDao().tombstones(FoodAggregates.TYPE_MEAL_PLAN_ENTRY).size)
        assertNotNull(repository.find(MealPlanKey(day, MealSlot.DINNER)))
    }

    /** PRD_FOOD 8.5: confirming fills the line, undoing the confirmation empties it. */
    @Test
    fun confirmingAndUnconfirmingAPropositionRoundTrips() = runTest {
        repository.save(plan())
        val key = MealPlanKey(day, MealSlot.DINNER)

        repository.setConsumed(key, FoodLogEntryId("log-1"))
        assertEquals(FoodLogEntryId("log-1"), requireNotNull(repository.find(key)).consumedLogEntryId)
        assertTrue(requireNotNull(repository.find(key)).isConsumed)

        repository.setConsumed(key, null)
        assertNull(requireNotNull(repository.find(key)).consumedLogEntryId)
    }

    @Test
    fun confirmingJournalsTheWholeAggregate() = runTest {
        repository.save(plan())

        repository.setConsumed(MealPlanKey(day, MealSlot.DINNER), FoodLogEntryId("log-1"))

        val pending = database.syncDao().pendingMutations(10)
        assertEquals(2, pending.size)
        assertTrue(requireNotNull(pending.last().payload).contains("\"consumedLogEntryId\":\"log-1\""))
        assertTrue(requireNotNull(pending.last().payload).contains("\"plannedServingsThousandths\":2000"))
    }

    @Test
    fun confirmingAMomentWithNoPropositionInventsNothing() = runTest {
        repository.setConsumed(MealPlanKey(day, MealSlot.LUNCH), FoodLogEntryId("log-1"))

        assertEquals(0, database.mealPlanDao().count())
        assertEquals(0, database.syncDao().pendingMutations(10).size)
    }

    @Test
    fun aWindowSpansTheDaysItNamesAndNoOthers() = runTest {
        repository.save(plan(date = day.minusDays(3)))
        repository.save(plan(date = day))
        repository.save(plan(date = day.plusDays(3)))

        val window = DateWindow.of(day.minusDays(1), day.plusDays(1))

        assertEquals(listOf(day), repository.observeIn(window).first().map { it.plannedOn })
        assertEquals(3, repository.observeIn(DateWindow.UNBOUNDED).first().size)
    }

    @Test
    fun thePropositionsOfARecipeAreRemovedAndReported() = runTest {
        repository.save(plan(date = day, slot = MealSlot.DINNER, recipeId = "recipe-a"))
        repository.save(plan(date = day, slot = MealSlot.LUNCH, recipeId = "recipe-b"))
        repository.save(plan(date = day.plusDays(1), slot = MealSlot.DINNER, recipeId = "recipe-a"))

        val removed = repository.deleteReferencing(RecipeId("recipe-a"))

        assertEquals(
            listOf(
                MealPlanKey(day, MealSlot.DINNER),
                MealPlanKey(day.plusDays(1), MealSlot.DINNER),
            ),
            removed,
        )
        assertEquals(1, database.mealPlanDao().count())
        assertEquals(
            listOf("2026-09-01/dinner", "2026-09-02/dinner"),
            database.syncDao().tombstones(FoodAggregates.TYPE_MEAL_PLAN_ENTRY)
                .map { it.aggregateId }
                .sorted(),
        )
    }

    @Test
    fun aRecipeWithNoPropositionRemovesNothing() = runTest {
        assertEquals(emptyList<MealPlanKey>(), repository.deleteReferencing(RecipeId("recipe-a")))
        assertEquals(0, database.syncDao().pendingMutations(10).size)
    }

    @Test
    fun anAbsentMomentHasNoProposition() = runTest {
        assertNull(repository.find(MealPlanKey(day, MealSlot.BREAKFAST)))
        assertEquals(emptyList<MealPlanEntry>(), repository.observeDay(day).first())
    }

    /** Every servings count is a whole thousandth of a serving; no `REAL` reaches the column. */
    @Test
    fun theServingsAreStoredAsWholeThousandths() = runTest {
        repository.save(plan(servings = 2_500))

        database.openHelper.readableDatabase
            .query("SELECT planned_servings_thousandths, typeof(planned_servings_thousandths) FROM meal_plan_entry")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2_500, cursor.getInt(0))
                assertEquals("integer", cursor.getString(1))
            }
    }
}
