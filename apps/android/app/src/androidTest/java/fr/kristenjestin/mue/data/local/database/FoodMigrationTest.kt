package fr.kristenjestin.mue.data.local.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.data.repository.RoomFoodLogRepository
import fr.kristenjestin.mue.data.repository.RoomMealPlanRepository
import fr.kristenjestin.mue.data.repository.RoomRecipeRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.Servings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

private const val FOOD_TEST_DATABASE = "mue-food-migration-test.db"

private val FOOD_TABLES = listOf(
    "food",
    "recipe",
    "recipe_ingredient",
    "food_log_entry",
    "meal_plan_entry",
)

/** The thirteen tables a version 5 file already holds; none of them may move at version 6. */
private val TABLES_ALREADY_THERE_AT_VERSION_FIVE = listOf(
    "measurements",
    "activity_sessions",
    "activity_metrics",
    "session_equipment",
    "exercise_definitions",
    "strength_exercises",
    "strength_sets",
    "timed_activity_drafts",
    "timed_draft_equipment",
    "sync_mutations",
    "sync_aggregate_state",
    "sync_state",
    "health_profile",
)

/**
 * PRD_FOOD 20: the five food tables are added to the database already on the phone, and nothing
 * that was there is touched.
 *
 * Every test seeds a genuine **version 1** file, in tenths of a kilogram, and chains all five
 * migrations. `SyncMigrationTest` gives the reason and it has only grown stronger: a phone that
 * installed Mue at version 1 has never seen version 5 as a starting state, so proving 5 → 6
 * alone would leave the oldest install — the one with the most history to lose — untested, and it
 * is precisely the arithmetic of 1 → 2 that a later migration could quietly undo.
 *
 * `fallbackToDestructiveMigration` would make every assertion here pass on an empty file. It is
 * forbidden project-wide, which is why the whole chain is re-proved at each version rather than
 * the last step alone.
 */
@RunWith(AndroidJUnit4::class)
class FoodMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MueDatabase::class.java,
    )

    /** Weights as version 1 stored them: tenths of a kilogram. */
    private val seeded = listOf(
        "2018-04-09" to 903,
        "2019-11-03" to 812,
        "2020-01-01" to 300,
        "2024-06-15" to 2500,
        "2026-08-23" to 745,
    )

    @Test
    fun everyWeightSurvivesAllFiveMigrations() {
        val rows = migrateFromVersionOne().readMeasurements()

        assertEquals("no measurement may be lost", seeded.size, rows.size)
        assertEquals(seeded.map { (date, tenths) -> date to tenths * 10 }, rows)
    }

    /** 74.5 kg has to still read 74.5 kg five migrations later, not 7.45 kg and not 745 kg. */
    @Test
    fun theWeightsArriveAsHundredthsAtVersionSix() {
        val hundredths = migrateFromVersionOne().readMeasurements().toMap()

        assertEquals(9_030, hundredths.getValue("2018-04-09"))
        assertEquals(8_120, hundredths.getValue("2019-11-03"))
        assertEquals(3_000, hundredths.getValue("2020-01-01"))
        assertEquals(25_000, hundredths.getValue("2024-06-15"))
        assertEquals(7_450, hundredths.getValue("2026-08-23"))
    }

    @Test
    fun allThirteenTablesTheEarlierVersionsBuiltAreStillThere() {
        val tables = migrateFromVersionOne().tableNames()

        assertEquals(13, TABLES_ALREADY_THERE_AT_VERSION_FIVE.size)
        TABLES_ALREADY_THERE_AT_VERSION_FIVE.forEach { table ->
            assertTrue("$table is missing from $tables", tables.contains(table))
        }
    }

    @Test
    fun theChainedMigrationCreatesTheFiveFoodTables() {
        val tables = migrateFromVersionOne().tableNames()

        FOOD_TABLES.forEach { table ->
            assertTrue("$table is missing from $tables", tables.contains(table))
        }
    }

    @Test
    fun theFiveNewTablesArriveEmpty() {
        val database = migrateFromVersionOne()

        FOOD_TABLES.forEach { table ->
            assertEquals("$table should arrive empty", 0, database.countOf(table))
        }
    }

    /**
     * The rule the whole module rests on. Energy is whole milli-kilocalories, a macro whole
     * milligrams, a mass whole thousandths of a gram: PRD_FOOD 13.1 makes a day the strict sum of
     * its lines, and a `REAL` anywhere would let two devices disagree on the last digit of the
     * same day. Asserted over **every** table, not only the new ones, so that no later migration
     * can slip one in either.
     */
    @Test
    fun noRealColumnExistsAnywhereInTheDatabase() {
        val database = migrateFromVersionOne()

        database.tableNames()
            .filterNot { it.startsWith("sqlite_") || it == "android_metadata" || it == "room_master_table" }
            .forEach { table ->
                val types = database.columnTypes(table)
                assertFalse(
                    "$table: $types must contain no REAL",
                    types.values.any { it.equals("REAL", ignoreCase = true) },
                )
            }
    }

    @Test
    fun everyNutrientColumnIsANullableInteger() {
        val database = migrateFromVersionOne()
        val metrics = listOf(
            "energy_milli_kcal",
            "protein_milligrams",
            "carbs_milligrams",
            "fat_milligrams",
            "fibre_milligrams",
        )

        listOf("food", "food_log_entry").forEach { table ->
            metrics.forEach { column ->
                assertEquals("$table.$column", "INTEGER", database.columnTypes(table).getValue(column))
                assertFalse(
                    "$table.$column must accept NULL, or unknown would become zero",
                    database.notNullColumns(table).contains(column),
                )
            }
        }
    }

    @Test
    fun everyQuantityColumnIsAnInteger() {
        val database = migrateFromVersionOne()

        assertEquals("INTEGER", database.columnTypes("food").getValue("serving_thousandths"))
        assertEquals("INTEGER", database.columnTypes("food").getValue("cooked_ratio_thousandths"))
        assertEquals(
            "INTEGER",
            database.columnTypes("recipe_ingredient").getValue("quantity_thousandths"),
        )
        assertEquals(
            "INTEGER",
            database.columnTypes("food_log_entry").getValue("quantity_thousandths"),
        )
        assertEquals(
            "INTEGER",
            database.columnTypes("meal_plan_entry").getValue("planned_servings_thousandths"),
        )
    }

    /**
     * PRD_FOOD 20.1 asks these tables to carry the sync metadata of the server PRD's 12.1.
     * `sync_aggregate_state` already holds it for every aggregate, keyed by type, so the food
     * tables hold none — and this asserts the decision rather than leaving it to be re-litigated.
     */
    @Test
    fun theFoodTablesCarryNoSynchronisationColumn() {
        val database = migrateFromVersionOne()
        val syncColumns = listOf("revision", "deleted_at", "origin_type", "origin_id", "last_mutation_id", "server_updated_at")

        FOOD_TABLES.forEach { table ->
            val columns = database.columnTypes(table).keys
            syncColumns.forEach { column ->
                assertFalse(
                    "$table must not duplicate sync_aggregate_state.$column",
                    columns.contains(column),
                )
            }
        }
        assertTrue(database.columnTypes("sync_aggregate_state").keys.contains("revision"))
    }

    /** PRD_FOOD 21.3: the business key is `(date, moment)`, so the table is keyed by it. */
    @Test
    fun theMealPlanIsKeyedByTheDateAndTheMomentRatherThanByAnId() {
        val database = migrateFromVersionOne()

        assertEquals(listOf("planned_on", "slot"), database.primaryKeyColumns("meal_plan_entry"))
        assertFalse(database.columnTypes("meal_plan_entry").keys.contains("id"))
    }

    /** An unindexed foreign key makes every recipe delete a table scan (PRD_FOOD 20.2). */
    @Test
    fun everyForeignKeyIsIndexed() {
        val database = migrateFromVersionOne()

        listOf("recipe_ingredient", "meal_plan_entry").forEach { table ->
            val indexed = database.indexedColumns(table)
            database.foreignKeyColumns(table).forEach { column ->
                assertTrue("$table.$column is a foreign key with no index", indexed.contains(column))
            }
        }
    }

    @Test
    fun theTwoChildTablesReferenceTheirParentWithACascade() {
        val database = migrateFromVersionOne()

        assertEquals(listOf("recipe" to "recipe_id"), database.foreignKeys("recipe_ingredient"))
        assertEquals(listOf("recipe" to "recipe_id"), database.foreignKeys("meal_plan_entry"))
    }

    /**
     * PRD_FOOD 21.2 lets a recipe reference a food the client has not received yet, and PRD_FOOD
     * 8.4 keeps a journal line valid after its food is deleted. A foreign key on either would
     * reject exactly what those two sentences allow.
     */
    @Test
    fun neitherAnIngredientsFoodNorALinesSourceIsAForeignKey() {
        val database = migrateFromVersionOne()

        assertFalse(database.foreignKeyColumns("recipe_ingredient").contains("food_id"))
        assertEquals(emptyList<Pair<String, String>>(), database.foreignKeys("food_log_entry"))
        assertTrue(database.indexedColumns("recipe_ingredient").contains("food_id"))
        assertTrue(database.indexedColumns("food_log_entry").contains("source_ref"))
    }

    /** PRD_FOOD 20.2 names this index; without it a day of the journal is a table scan. */
    @Test
    fun theJournalIsIndexedOnTheDayTheMomentAndTheTime() {
        val database = migrateFromVersionOne()

        assertTrue(
            database.indexNames("food_log_entry")
                .contains("index_food_log_entry_consumed_on_slot_consumed_at"),
        )
        assertTrue(database.indexNames("food").contains("index_food_name_folded"))
        assertTrue(
            database.indexNames("recipe_ingredient")
                .contains("index_recipe_ingredient_recipe_id"),
        )
    }

    @Test
    fun theChainedMigrationDoesNotReseedTheExerciseCatalogue() {
        assertEquals(
            ExerciseCatalogSeed.DEFINITIONS.size,
            migrateFromVersionOne().countOf("exercise_definitions"),
        )
    }

    /** Version 6 seeds no food: the Ciqual subset is an asset a migration cannot open. */
    @Test
    fun theMigrationSeedsNoFood() {
        assertEquals(0, migrateFromVersionOne().countOf("food"))
    }

    @Test
    fun anEmptyVersionOneDatabaseArrivesEmptyAtVersionSix() {
        helper.createDatabase(FOOD_TEST_DATABASE, 1).close()
        val database = migrate()

        assertTrue(database.readMeasurements().isEmpty())
        FOOD_TABLES.forEach { assertEquals(0, database.countOf(it)) }
        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, database.countOf("exercise_definitions"))
    }

    /** The history the five migrations carried has to be readable by the app that opens it. */
    @Test
    fun theMigratedFileIsUsableThroughTheRealDaos() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val measurements = database.measurementDao().getAll()
            assertEquals(seeded.size, measurements.size)
            assertEquals(7_450, measurements.first { it.date == "2026-08-23" }.weightCg)

            assertEquals(0, database.foodDao().countBySource("ciqual"))
            assertEquals(0, database.foodLogDao().count())
            assertEquals(0, database.mealPlanDao().count())
        }
    }

    /**
     * The point of the version, on a real upgraded file: a food journal written here reaches
     * `sync_mutations` and `sync_aggregate_state` without a single column having been added to
     * `food_log_entry`.
     */
    @Test
    fun aFoodWriteOnTheMigratedFileJournalsItsMutation() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val repository = RoomFoodLogRepository(database.foodLogDao(), SyncOutbox())

            repository.save(
                FoodLogEntry(
                    id = FoodLogEntryId("log-on-migrated"),
                    consumedOn = LocalDate.of(2026, 8, 24),
                    consumedAt = LocalTime.of(12, 30),
                    slot = MealSlot.LUNCH,
                    kind = FoodLogKind.QUICK,
                    title = "Déjeuner",
                    amount = LoggedAmount.Unmeasured,
                    nutrients = Nutrients(energy = Energy.ofMilliKcalOrNull(450_000)),
                    estimation = Estimation.APPROXIMATE,
                ),
            )

            val pending = database.syncDao().pendingMutations(10)
            assertEquals(1, pending.size)
            assertEquals(FoodAggregates.TYPE_FOOD_LOG_ENTRY, pending.single().aggregateType)
            assertEquals("log-on-migrated", pending.single().aggregateId)
            assertNotNull(pending.single().payload)
            assertEquals(seeded.size, database.measurementDao().getAll().size)
        }
    }

    /**
     * The composite key, proved on the migrated file: two propositions for the same moment
     * converge on one row rather than coexisting as two competing plans (PRD_FOOD 8.5, 21.3).
     */
    @Test
    fun twoPropositionsForOneMomentBecomeOneRowOnTheMigratedFile() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val outbox = SyncOutbox()
            val recipes = RoomRecipeRepository(database.recipeDao(), outbox)
            val plans = RoomMealPlanRepository(database.mealPlanDao(), outbox)
            recipes.save(recipeDetail("recipe-a", "Dahl"))
            recipes.save(recipeDetail("recipe-b", "Curry"))

            plans.save(plan("recipe-a"))
            plans.save(plan("recipe-b"))

            assertEquals(1, database.mealPlanDao().count())
            assertEquals(
                RecipeId("recipe-b"),
                requireNotNull(plans.find(plan("recipe-b").key)).recipeId,
            )
        }
    }

    /** FR-SYNC-005 on the migrated file: the row goes, the tombstone stays behind it. */
    @Test
    fun aFoodDeleteOnTheMigratedFileLeavesATombstone() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val repository = RoomRecipeRepository(database.recipeDao(), SyncOutbox())
            repository.save(recipeDetail("recipe-a", "Dahl"))

            repository.delete(RecipeId("recipe-a"))

            val tombstones = database.syncDao().tombstones(FoodAggregates.TYPE_RECIPE)
            assertEquals(listOf("recipe-a"), tombstones.map { it.aggregateId })
            assertNotNull(tombstones.single().deletedAt)
        }
    }

    private fun recipeDetail(id: String, name: String): RecipeDetail = RecipeDetail(
        recipe = Recipe(
            id = RecipeId(id),
            name = name,
            type = RecipeType.MAIN,
            baseServings = 4,
        ),
    )

    private fun plan(recipeId: String): MealPlanEntry = MealPlanEntry(
        plannedOn = LocalDate.of(2026, 9, 1),
        slot = MealSlot.DINNER,
        recipeId = RecipeId(recipeId),
        plannedServings = requireNotNull(Servings.ofThousandthsOrNull(2_000)),
    )

    private inline fun withMigratedDatabase(block: (MueDatabase) -> Unit) {
        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
            FOOD_TEST_DATABASE,
        ).addMigrations(*MueMigrations.ALL).addCallback(ExerciseCatalogSeed.CALLBACK).build()

        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun migrateFromVersionOne(): SupportSQLiteDatabase {
        helper.createDatabase(FOOD_TEST_DATABASE, 1).use { db ->
            seeded.forEach { (date, tenths) ->
                db.execSQL("INSERT INTO measurements (date, weight_dg) VALUES ('$date', $tenths)")
            }
        }
        return migrate()
    }

    /** `validateDroppedTables` is on: a leftover table would fail the run, not pass unnoticed. */
    private fun migrate(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(FOOD_TEST_DATABASE, 6, true, *MueMigrations.ALL)

    /** Raw SQL on purpose: the entity mapping must not be able to make any of this pass. */
    private fun SupportSQLiteDatabase.readMeasurements(): List<Pair<String, Int>> =
        query("SELECT date, weight_cg FROM measurements ORDER BY date ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1)) }
        }

    private fun SupportSQLiteDatabase.tableNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.indexNames(table: String): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)
                            ?.takeUnless { it.startsWith("sqlite_autoindex") }
                            ?.let { add(it) }
                    }
                }
            }

    private fun SupportSQLiteDatabase.columnTypes(table: String): Map<String, String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2)) }
        }

    private fun SupportSQLiteDatabase.notNullColumns(table: String): List<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getInt(3) == 1) add(cursor.getString(1))
                }
            }
        }

    private fun SupportSQLiteDatabase.primaryKeyColumns(table: String): List<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getInt(5) > 0) add(cursor.getInt(5) to cursor.getString(1))
                }
            }
        }.sortedBy { it.first }.map { it.second }

    private fun SupportSQLiteDatabase.foreignKeys(table: String): List<Pair<String, String>> =
        query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(2) to cursor.getString(3))
                }
            }
        }

    private fun SupportSQLiteDatabase.foreignKeyColumns(table: String): List<String> =
        foreignKeys(table).map { it.second }

    /** The first column of every declared index, which is the one a lookup can start from. */
    private fun SupportSQLiteDatabase.indexedColumns(table: String): List<String> =
        indexNames(table).mapNotNull { index ->
            query("PRAGMA index_info(`$index`)").use { cursor ->
                if (cursor.moveToNext()) cursor.getString(2) else null
            }
        }

}
