package fr.kristenjestin.mue.data.local.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val ACTIVITY_TEST_DATABASE = "mue-activity-migration-test.db"

private val ACTIVITY_TABLES = listOf(
    "activity_sessions",
    "activity_metrics",
    "session_equipment",
    "exercise_definitions",
    "strength_exercises",
    "strength_sets",
)

/**
 * PRD 16.2: the Activities module adds its tables to the database already on the phone, without
 * touching the weight history.
 *
 * The chained tests are the point of this file. A phone that installed Mue at version 1 has never
 * seen version 2 as a starting state, so proving 2 → 3 alone would leave the oldest install — the
 * one with the most history to lose — untested. Every chained test therefore seeds a genuine
 * version 1 file, in tenths of a kilogram, and asserts on what comes out the far end of both
 * migrations.
 */
@RunWith(AndroidJUnit4::class)
class ActivityMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MueDatabase::class.java,
    )

    /** Weights as version 1 stored them: tenths of a kilogram. */
    private val seeded = listOf(
        "2019-11-03" to 812,
        "2020-01-01" to 300,
        "2024-06-15" to 2500,
        "2026-08-23" to 745,
    )

    @Test
    fun anOriginalInstallKeepsEveryWeightThroughBothMigrations() {
        val rows = migrateFromVersionOne().readMeasurements()

        assertEquals("no measurement may be lost", seeded.size, rows.size)
        assertEquals(seeded.map { (date, tenths) -> date to tenths * 10 }, rows)
    }

    /** 74.5 kg has to still read 74.5 kg two migrations later, not 7.45 kg and not 745 kg. */
    @Test
    fun theWeightsArriveAsHundredthsAtVersionThree() {
        val hundredths = migrateFromVersionOne().readMeasurements().toMap()

        assertEquals(8_120, hundredths.getValue("2019-11-03"))
        assertEquals(3_000, hundredths.getValue("2020-01-01"))
        assertEquals(25_000, hundredths.getValue("2024-06-15"))
        assertEquals(7_450, hundredths.getValue("2026-08-23"))
    }

    @Test
    fun theChainedMigrationCreatesEveryActivityTable() {
        val tables = migrateFromVersionOne().tableNames()

        ACTIVITY_TABLES.forEach { table ->
            assertTrue("$table is missing from $tables", tables.contains(table))
        }
        assertTrue("the weight history is gone", tables.contains("measurements"))
    }

    @Test
    fun theChainedMigrationSeedsTheProvidedCatalogue() {
        val database = migrateFromVersionOne()

        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, database.countOf("exercise_definitions"))
        assertEquals(
            ExerciseCatalogSeed.DEFINITIONS.map { it.name }.sorted(),
            database.exerciseNames().sorted(),
        )
    }

    /** PRD 16.3 asks for the five indices the weekly aggregate and the joins read through. */
    @Test
    fun theChainedMigrationCreatesTheDeclaredIndices() {
        val indices = migrateFromVersionOne().indexNames()

        listOf(
            "index_activity_sessions_started_on",
            "index_session_equipment_session_id",
            "index_session_equipment_session_id_equipment_type_custom_name_folded",
            "index_exercise_definitions_name_folded",
            "index_strength_exercises_session_id",
            "index_strength_exercises_exercise_definition_id",
            "index_strength_sets_strength_exercise_id",
        ).forEach { index ->
            assertTrue("$index is missing from $indices", indices.contains(index))
        }
    }

    @Test
    fun theChainedMigrationLeavesTheActivityTablesEmpty() {
        val database = migrateFromVersionOne()

        assertEquals(0, database.countOf("activity_sessions"))
        assertEquals(0, database.countOf("activity_metrics"))
        assertEquals(0, database.countOf("session_equipment"))
        assertEquals(0, database.countOf("strength_exercises"))
        assertEquals(0, database.countOf("strength_sets"))
    }

    @Test
    fun anEmptyVersionOneDatabaseArrivesEmptyButCatalogued() {
        helper.createDatabase(ACTIVITY_TEST_DATABASE, 1).close()
        val database = migrate(MueMigrations.MIGRATION_1_2, MueMigrations.MIGRATION_2_3)

        assertTrue(database.readMeasurements().isEmpty())
        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, database.countOf("exercise_definitions"))
    }

    @Test
    fun migratingFromVersionTwoLosesNoMeasurement() {
        helper.createDatabase(ACTIVITY_TEST_DATABASE, 2).use { db ->
            seeded.forEach { (date, tenths) ->
                db.execSQL("INSERT INTO measurements (date, weight_cg) VALUES ('$date', ${tenths * 10})")
            }
        }

        val rows = migrate(MueMigrations.MIGRATION_2_3).readMeasurements()

        assertEquals(seeded.size, rows.size)
        assertEquals(seeded.map { (date, tenths) -> date to tenths * 10 }, rows)
    }

    @Test
    fun migratingFromVersionTwoSeedsTheCatalogueToo() {
        helper.createDatabase(ACTIVITY_TEST_DATABASE, 2).close()

        assertEquals(
            ExerciseCatalogSeed.DEFINITIONS.size,
            migrate(MueMigrations.MIGRATION_2_3).countOf("exercise_definitions"),
        )
    }

    /**
     * The same row has to carry the same key on every phone, whichever path put it there — which
     * is why the seed writes its ids down instead of generating them.
     */
    @Test
    fun theSeededDefinitionsCarryTheirWrittenIds() {
        val database = migrateFromVersionOne()

        ExerciseCatalogSeed.DEFINITIONS.forEach { definition ->
            assertEquals(
                definition.name,
                database.nameOfDefinition(definition.id.value),
            )
        }
    }

    /** A migration followed by a callback on a rebuilt file must not double the catalogue. */
    @Test
    fun seedingTwiceChangesNothing() {
        val database = migrateFromVersionOne()

        ExerciseCatalogSeed.insertInto(database)

        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, database.countOf("exercise_definitions"))
    }

    /**
     * `MigrationTestHelper` opens its connections without `PRAGMA foreign_keys`, so a cascade can
     * only be proved on a database Room itself opened — the migrated file, reopened exactly as
     * the app would open it.
     */
    @Test
    fun theMigratedFileCascadesTheWayTheAppNeeds() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val raw = database.openHelper.writableDatabase
            database.activityDao().writeOneOfEverything()

            assertEquals(1, raw.countOf("activity_sessions"))
            assertEquals(1, raw.countOf("activity_metrics"))
            assertEquals(1, raw.countOf("session_equipment"))
            assertEquals(1, raw.countOf("strength_exercises"))
            assertEquals(1, raw.countOf("strength_sets"))

            database.activityDao().deleteSession(SESSION_ID)

            assertEquals(0, raw.countOf("activity_sessions"))
            assertEquals(0, raw.countOf("activity_metrics"))
            assertEquals(0, raw.countOf("session_equipment"))
            assertEquals(0, raw.countOf("strength_exercises"))
            assertEquals(0, raw.countOf("strength_sets"))
        }
    }

    /** PRD 9.2: deleting a session never deletes the definition its exercises named. */
    @Test
    fun deletingASessionLeavesTheCatalogueWhole() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            database.activityDao().writeOneOfEverything()
            database.activityDao().deleteSession(SESSION_ID)

            assertEquals(
                ExerciseCatalogSeed.DEFINITIONS.size,
                database.exerciseCatalogDao().count(),
            )
        }
    }

    /** The other half of `ON DELETE RESTRICT`: a definition in use cannot be dropped at all. */
    @Test
    fun aDefinitionInUseCannotBeDeleted() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            database.activityDao().writeOneOfEverything()

            val error = runCatching {
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM exercise_definitions WHERE id = '${seedDefinition.id.value}'"
                )
            }.exceptionOrNull()

            assertTrue("expected a constraint failure, got $error", error is SQLiteConstraintException)
        }
    }

    /** The whole chain, read back through the app's own DAOs rather than through raw SQL. */
    @Test
    fun theMigratedFileIsUsableThroughTheRealDaos() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val measurements = database.measurementDao().getAll()
            assertEquals(seeded.size, measurements.size)
            assertEquals(7_450, measurements.first { it.measurement.date == "2026-08-23" }.measurement.weightCg)

            val catalogue = database.exerciseCatalogDao().findByFoldedName("bench press")
            assertNotNull(catalogue)
            assertEquals("Bench press", catalogue?.name)
            assertNull(database.exerciseCatalogDao().findByFoldedName("bench pres"))
        }
    }

    private val seedDefinition = ExerciseCatalogSeed.DEFINITIONS.first()

    /** One row in each of the five child tables, so a cascade has something to take with it. */
    private suspend fun ActivityDao.writeOneOfEverything() {
        saveDetail(
            session = ActivitySessionEntity(
                id = SESSION_ID,
                movement = "strength_training",
                customMovementName = null,
                environment = "indoor",
                startedOn = "2026-08-20",
                startedAtTime = "18:30",
                durationSeconds = 3_600,
                perceivedEffort = 7,
                notes = null,
                source = "manual",
                createdAt = 1L,
                updatedAt = 1L,
            ),
            metrics = listOf(ActivityMetricEntity(SESSION_ID, "estimated_energy", 320, "manual")),
            equipment = listOf(
                SessionEquipmentEntity(EQUIPMENT_ID, SESSION_ID, "barbell", null, "", 0),
            ),
            exercises = listOf(
                StrengthExerciseEntity(EXERCISE_ID, SESSION_ID, seedDefinition.id.value, 0, null),
            ),
            sets = listOf(StrengthSetEntity(SET_ID, EXERCISE_ID, 0, "working", 8, 60_000, null, null)),
        )
    }

    private inline fun withMigratedDatabase(block: (MueDatabase) -> Unit) {
        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
            ACTIVITY_TEST_DATABASE,
        ).addMigrations(*MueMigrations.ALL).addCallback(ExerciseCatalogSeed.CALLBACK).build()

        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun migrateFromVersionOne(): SupportSQLiteDatabase {
        helper.createDatabase(ACTIVITY_TEST_DATABASE, 1).use { db ->
            seeded.forEach { (date, tenths) ->
                db.execSQL("INSERT INTO measurements (date, weight_dg) VALUES ('$date', $tenths)")
            }
        }
        return migrate(MueMigrations.MIGRATION_1_2, MueMigrations.MIGRATION_2_3)
    }

    /** `validateDroppedTables` is on: a leftover table would fail the run, not pass unnoticed. */
    private fun migrate(vararg migrations: Migration): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(ACTIVITY_TEST_DATABASE, 3, true, *migrations)

    /** Raw SQL on purpose: the entity mapping must not be able to make any of this pass. */
    private fun SupportSQLiteDatabase.readMeasurements(): List<Pair<String, Int>> =
        query("SELECT date, weight_cg FROM measurements ORDER BY date ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1)) }
        }

    private fun SupportSQLiteDatabase.exerciseNames(): List<String> =
        query("SELECT name FROM exercise_definitions").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.nameOfDefinition(id: String): String? =
        query("SELECT name FROM exercise_definitions WHERE id = '$id'").use { cursor ->
            if (cursor.moveToNext()) cursor.getString(0) else null
        }

    private fun SupportSQLiteDatabase.tableNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.indexNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private companion object {
        const val SESSION_ID = "11111111-1111-4111-8111-111111111111"
        const val EQUIPMENT_ID = "22222222-2222-4222-8222-222222222222"
        const val EXERCISE_ID = "33333333-3333-4333-8333-333333333333"
        const val SET_ID = "44444444-4444-4444-8444-444444444444"
    }
}

internal fun SupportSQLiteDatabase.countOf(table: String): Int =
    query("SELECT COUNT(*) FROM `$table`").use { cursor ->
        if (cursor.moveToNext()) cursor.getInt(0) else 0
    }
