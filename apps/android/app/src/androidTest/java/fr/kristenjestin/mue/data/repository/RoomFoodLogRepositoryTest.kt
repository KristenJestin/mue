package fr.kristenjestin.mue.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class RoomFoodLogRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: RoomFoodLogRepository

    private val day: LocalDate = LocalDate.of(2026, 8, 25)

    @Before
    fun createRepository() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MueDatabase::class.java,
        ).build()
        repository = RoomFoodLogRepository(database.foodLogDao(), SyncOutbox())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun entry(
        id: String = "log-1",
        on: LocalDate = day,
        at: LocalTime = LocalTime.of(13, 5),
        slot: MealSlot = MealSlot.LUNCH,
        kind: FoodLogKind = FoodLogKind.QUICK,
        amount: LoggedAmount = LoggedAmount.Unmeasured,
        nutrients: Nutrients = Nutrients.UNKNOWN,
        sourceRef: String? = null,
    ): FoodLogEntry = FoodLogEntry(
        id = FoodLogEntryId(id),
        consumedOn = on,
        consumedAt = at,
        slot = slot,
        kind = kind,
        title = "Déjeuner",
        amount = amount,
        nutrients = nutrients,
        estimation = Estimation.APPROXIMATE,
        sourceRef = sourceRef,
    )

    @Test
    fun roundTripsAFullyDescribedLineThroughTheRealDao() = runTest {
        val original = FoodLogEntry(
            id = FoodLogEntryId("log-full"),
            consumedOn = day,
            consumedAt = LocalTime.of(20, 15, 30),
            slot = MealSlot.DINNER,
            kind = FoodLogKind.FOOD,
            title = "Blanc de poulet",
            amount = LoggedAmount.Measured(
                requireNotNull(Quantity.ofThousandthsOrNull(180_000)),
                ReferenceUnit.GRAM,
            ),
            nutrients = Nutrients(
                energy = Energy.ofMilliKcalOrNull(217_800),
                protein = Macro.ofMilligramsOrNull(40_680),
            ),
            estimation = Estimation.MEASURED,
            sourceRef = "food-1",
            amountLabel = "180 g",
            portions = Servings.ofThousandthsOrNull(1_500),
            weighedCooked = true,
            fromPlan = MealPlanKey(day, MealSlot.DINNER),
        )

        repository.save(original)

        assertEquals(original, repository.findById(FoodLogEntryId("log-full")))
        assertEquals(listOf(original), repository.observeDay(day).first())
        assertEquals(original, repository.findByPlan(MealPlanKey(day, MealSlot.DINNER)))
    }

    @Test
    fun roundTripsAQuickAddWithNoQuantityAtAll() = runTest {
        val original = entry()

        repository.save(original)

        assertEquals(original, repository.findById(FoodLogEntryId("log-1")))
        assertEquals(LoggedAmount.Unmeasured, repository.findById(FoodLogEntryId("log-1"))?.amount)
    }

    @Test
    fun roundTripsAPortionedRecipeLine() = runTest {
        val original = entry(
            kind = FoodLogKind.RECIPE,
            amount = LoggedAmount.Portioned(requireNotNull(Servings.ofThousandthsOrNull(1_750))),
            sourceRef = "recipe-1",
        )

        repository.save(original)

        assertEquals(original, repository.findById(FoodLogEntryId("log-1")))
    }

    /** PRD_FOOD 13.1 again, through SQLite: a hole stays a hole and a zero stays a zero. */
    @Test
    fun anUnloggedProteinComesBackNullAndNotZero() = runTest {
        repository.save(
            entry(
                nutrients = Nutrients(
                    energy = Energy.ofMilliKcalOrNull(320_000),
                    carbs = Macro.ofMilligramsOrNull(0),
                ),
            ),
        )

        val read = requireNotNull(repository.findById(FoodLogEntryId("log-1")))
        assertNull(read.nutrients.protein)
        assertEquals(Macro.ZERO, read.nutrients.carbs)
        assertEquals(320_000, read.nutrients.energy?.milliKcal)
    }

    @Test
    fun anUnloggedProteinIsNullInTheColumnWhileAZeroIsNot() = runTest {
        repository.save(entry(nutrients = Nutrients(carbs = Macro.ofMilligramsOrNull(0))))

        database.openHelper.readableDatabase
            .query(
                "SELECT protein_milligrams, carbs_milligrams FROM food_log_entry WHERE id = 'log-1'"
            )
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("protein must be NULL", cursor.isNull(0))
                assertFalse("a measured zero must not become NULL", cursor.isNull(1))
                assertEquals(0, cursor.getInt(1))
            }
    }

    @Test
    fun savingALineJournalsAnUpsertMutation() = runTest {
        repository.save(entry())

        val pending = database.syncDao().pendingMutations(10)
        assertEquals(1, pending.size)
        assertEquals(FoodAggregates.TYPE_FOOD_LOG_ENTRY, pending.single().aggregateType)
        assertEquals("log-1", pending.single().aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, pending.single().op)
        assertNotNull(pending.single().payload)
    }

    @Test
    fun deletingALineLeavesATombstoneBehindIt() = runTest {
        repository.save(entry())

        repository.delete(FoodLogEntryId("log-1"))

        assertNull(repository.findById(FoodLogEntryId("log-1")))
        val tombstones = database.syncDao().tombstones(FoodAggregates.TYPE_FOOD_LOG_ENTRY)
        assertEquals(listOf("log-1"), tombstones.map { it.aggregateId })
        assertNotNull(tombstones.single().deletedAt)
    }

    /** FR-SYNC-005: a line written again after a deletion is alive once more. */
    @Test
    fun writingALineAgainClearsItsTombstone() = runTest {
        repository.save(entry())
        repository.delete(FoodLogEntryId("log-1"))

        repository.save(entry())

        assertEquals(0, database.syncDao().tombstones(FoodAggregates.TYPE_FOOD_LOG_ENTRY).size)
        assertNotNull(repository.findById(FoodLogEntryId("log-1")))
    }

    /** PRD_FOOD 21.3: the lines are independent and never merge, however alike they are. */
    @Test
    fun twoIdenticalLinesAtTheSameMomentCoexist() = runTest {
        repository.save(entry(id = "log-1"))
        repository.save(entry(id = "log-2"))

        assertEquals(2, repository.observeDay(day).first().size)
    }

    @Test
    fun aDayIsOrderedByMomentThenTime() = runTest {
        repository.save(entry(id = "dinner", slot = MealSlot.DINNER, at = LocalTime.of(20, 0)))
        repository.save(entry(id = "snack", slot = MealSlot.SNACK, at = LocalTime.of(16, 30)))
        repository.save(entry(id = "breakfast-2", slot = MealSlot.BREAKFAST, at = LocalTime.of(9, 0)))
        repository.save(entry(id = "breakfast-1", slot = MealSlot.BREAKFAST, at = LocalTime.of(8, 0)))
        repository.save(entry(id = "lunch", slot = MealSlot.LUNCH, at = LocalTime.of(13, 0)))

        assertEquals(
            listOf("breakfast-1", "breakfast-2", "lunch", "snack", "dinner"),
            repository.observeDay(day).first().map { it.id.value },
        )
    }

    @Test
    fun aWindowSpansTheDaysItNamesAndNoOthers() = runTest {
        repository.save(entry(id = "before", on = day.minusDays(3)))
        repository.save(entry(id = "inside", on = day))
        repository.save(entry(id = "after", on = day.plusDays(3)))

        val window = DateWindow.of(day.minusDays(1), day.plusDays(1))

        assertEquals(listOf("inside"), repository.observeIn(window).first().map { it.id.value })
        assertEquals(3, repository.observeIn(DateWindow.UNBOUNDED).first().size)
    }

    @Test
    fun theLoggedDaysAreDistinctAndOrdered() = runTest {
        repository.save(entry(id = "a", on = day))
        repository.save(entry(id = "b", on = day))
        repository.save(entry(id = "c", on = day.minusDays(1)))

        assertEquals(
            listOf(day.minusDays(1), day),
            repository.observeLoggedDatesIn(DateWindow.UNBOUNDED).first(),
        )
    }

    /** PRD_FOOD 9.4: recency comes from the journal, most recent first, one entry per food. */
    @Test
    fun theRecentlyUsedFoodsAreTheDistinctFoodsOfTheLatestLines() = runTest {
        repository.save(entry(id = "1", on = day.minusDays(2), kind = FoodLogKind.FOOD, sourceRef = "apple"))
        repository.save(entry(id = "2", on = day.minusDays(1), kind = FoodLogKind.FOOD, sourceRef = "rice"))
        repository.save(entry(id = "3", on = day, kind = FoodLogKind.FOOD, sourceRef = "apple"))
        repository.save(entry(id = "4", on = day, kind = FoodLogKind.RECIPE, sourceRef = "dahl"))
        repository.save(entry(id = "5", on = day))

        assertEquals(
            listOf(FoodId("apple"), FoodId("rice")),
            repository.recentlyUsedFoods(10),
        )
    }

    @Test
    fun theRecentlyUsedFoodsHonourTheirLimit() = runTest {
        repository.save(entry(id = "1", on = day.minusDays(1), kind = FoodLogKind.FOOD, sourceRef = "a"))
        repository.save(entry(id = "2", on = day, kind = FoodLogKind.FOOD, sourceRef = "b"))

        assertEquals(listOf(FoodId("b")), repository.recentlyUsedFoods(1))
    }

    /**
     * PRD_FOOD 8.4: the snapshot is the line's own. Editing the line must not resurrect a value
     * from anywhere else, and the first write's `created_at` has to survive the edit.
     */
    @Test
    fun editingALineKeepsItsCreationInstantAndReplacesItsValues() = runTest {
        var clock = 1_000L
        val repository = RoomFoodLogRepository(
            database.foodLogDao(),
            SyncOutbox(),
            now = { clock },
        )
        repository.save(entry(nutrients = Nutrients(energy = Energy.ofMilliKcalOrNull(320_000))))

        clock = 9_000L
        repository.save(entry(nutrients = Nutrients(energy = Energy.ofMilliKcalOrNull(410_000))))

        database.openHelper.readableDatabase
            .query("SELECT created_at, updated_at, energy_milli_kcal FROM food_log_entry")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1_000L, cursor.getLong(0))
                assertEquals(9_000L, cursor.getLong(1))
                assertEquals(410_000, cursor.getInt(2))
            }
    }

    @Test
    fun aLineWithNoPlanIsNotFoundByAPlanKey() = runTest {
        repository.save(entry())

        assertNull(repository.findByPlan(MealPlanKey(day, MealSlot.LUNCH)))
    }
}
