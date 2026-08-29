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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TIMER_TEST_DATABASE = "mue-timer-migration-test.db"

private val TIMER_TABLES = listOf("timed_activity_drafts", "timed_draft_equipment")

private val ACTIVITY_TABLES_AT_VERSION_FOUR = listOf(
    "activity_sessions",
    "activity_metrics",
    "session_equipment",
    "exercise_definitions",
    "strength_exercises",
    "strength_sets",
)

/**
 * Timer PRD 9: the timer adds its two tables to the database already on the phone, and touches
 * nothing that was there.
 *
 * Every test seeds a genuine **version 1** file, in tenths of a kilogram, and chains all three
 * migrations. A phone that installed Mue at version 1 has never seen version 3 as a starting
 * state, so proving 3 → 4 alone would leave the oldest install — the one with the most history
 * to lose — untested, and it is precisely the arithmetic of 1 → 2 that a later migration could
 * quietly undo.
 */
@RunWith(AndroidJUnit4::class)
class TimerMigrationTest {

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
    fun everyWeightSurvivesAllThreeMigrations() {
        val rows = migrateFromVersionOne().readMeasurements()

        assertEquals("no measurement may be lost", seeded.size, rows.size)
        assertEquals(seeded.map { (date, tenths) -> date to tenths * 10 }, rows)
    }

    /** 74.5 kg has to still read 74.5 kg three migrations later, not 7.45 kg and not 745 kg. */
    @Test
    fun theWeightsArriveAsHundredthsAtVersionFour() {
        val hundredths = migrateFromVersionOne().readMeasurements().toMap()

        assertEquals(8_120, hundredths.getValue("2019-11-03"))
        assertEquals(3_000, hundredths.getValue("2020-01-01"))
        assertEquals(25_000, hundredths.getValue("2024-06-15"))
        assertEquals(7_450, hundredths.getValue("2026-08-23"))
    }

    @Test
    fun theActivityTablesAreStillThereAtVersionFour() {
        val tables = migrateFromVersionOne().tableNames()

        ACTIVITY_TABLES_AT_VERSION_FOUR.forEach { table ->
            assertTrue("$table is missing from $tables", tables.contains(table))
        }
        assertTrue("the weight history is gone", tables.contains("measurements"))
    }

    @Test
    fun theChainedMigrationCreatesBothTimerTables() {
        val tables = migrateFromVersionOne().tableNames()

        TIMER_TABLES.forEach { table ->
            assertTrue("$table is missing from $tables", tables.contains(table))
        }
    }

    @Test
    fun theChainedMigrationCreatesTheDeclaredTimerIndices() {
        val indices = migrateFromVersionOne().indexNames()

        listOf(
            "index_timed_activity_drafts_status",
            "index_timed_draft_equipment_draft_id",
            "index_timed_draft_equipment_draft_id_equipment_type_custom_name_folded",
        ).forEach { index ->
            assertTrue("$index is missing from $indices", indices.contains(index))
        }
    }

    /**
     * The single-timer rule is a `@Transaction` and never an index. Room's `TableInfo` validation
     * reads every index the file carries, so a partial unique index nobody declared would fail
     * the very `runMigrationsAndValidate` this test rests on — which is why its absence is
     * asserted rather than assumed.
     */
    @Test
    fun theTimerRuleLeavesNoUndeclaredIndexBehind() {
        val drafts = migrateFromVersionOne().indexNames("timed_activity_drafts")

        assertEquals(listOf("index_timed_activity_drafts_status"), drafts)
    }

    /** Version 4 adds no catalogue, so it must not run the seed a second time. */
    @Test
    fun theChainedMigrationDoesNotReseedTheCatalogue() {
        val database = migrateFromVersionOne()

        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, database.countOf("exercise_definitions"))
        assertEquals(
            ExerciseCatalogSeed.DEFINITIONS.map { it.name }.sorted(),
            database.exerciseNames().sorted(),
        )
    }

    @Test
    fun theTimerTablesArriveEmpty() {
        val database = migrateFromVersionOne()

        assertEquals(0, database.countOf("timed_activity_drafts"))
        assertEquals(0, database.countOf("timed_draft_equipment"))
    }

    @Test
    fun anEmptyVersionOneDatabaseArrivesEmptyAtVersionFour() {
        helper.createDatabase(TIMER_TEST_DATABASE, 1).close()
        val database = migrate()

        assertTrue(database.readMeasurements().isEmpty())
        assertEquals(0, database.countOf("timed_activity_drafts"))
        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, database.countOf("exercise_definitions"))
    }

    /**
     * `MigrationTestHelper` opens its connections without `PRAGMA foreign_keys`, so a cascade can
     * only be proved on a database Room itself opened — the migrated file, reopened exactly as
     * the app would open it.
     */
    @Test
    fun theMigratedFileCascadesTheDraftEquipment() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val raw = database.openHelper.writableDatabase
            database.timerDao().writeOneDraft()

            assertEquals(1, raw.countOf("timed_activity_drafts"))
            assertEquals(2, raw.countOf("timed_draft_equipment"))

            database.timerDao().deleteDraft(DRAFT_ID)

            assertEquals(0, raw.countOf("timed_activity_drafts"))
            assertEquals(0, raw.countOf("timed_draft_equipment"))
        }
    }

    /** PRD FR-ACTIVITY-008, carried over to the draft: one draft, one `treadmill`. */
    @Test
    fun theMigratedFileRefusesTheSameFoldedNameTwice() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            database.timerDao().writeOneDraft()

            val error = runCatching {
                database.timerDao().insertEquipment(
                    listOf(
                        TimedDraftEquipmentEntity(
                            id = "99999999-9999-4999-8999-999999999999",
                            draftId = DRAFT_ID,
                            equipmentType = "other",
                            customName = "Ski Erg",
                            customNameFolded = "ski erg",
                            position = 2,
                        )
                    )
                )
            }.exceptionOrNull()

            assertTrue("expected a constraint failure, got $error", error is SQLiteConstraintException)
        }
    }

    /** Deleting a draft is a timer affair: no session, no metric and no weight goes with it. */
    @Test
    fun deletingADraftLeavesTheRestOfTheDatabaseWhole() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            database.timerDao().writeOneDraft()
            database.timerDao().deleteDraft(DRAFT_ID)

            assertEquals(seeded.size, database.measurementDao().getAll().size)
            assertEquals(
                ExerciseCatalogSeed.DEFINITIONS.size,
                database.exerciseCatalogDao().count(),
            )
        }
    }

    /** The whole chain, read back through the app's own DAOs rather than through raw SQL. */
    @Test
    fun theMigratedFileIsUsableThroughTheTimerDao() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            database.timerDao().writeOneDraft()

            val live = database.timerDao().findLiveRow()
            assertNotNull(live)
            assertEquals(DRAFT_ID, live?.draft?.id)
            assertEquals(2, live?.equipment?.size)

            val measurements = database.measurementDao().getAll()
            assertEquals(7_450, measurements.first { it.measurement.date == "2026-08-23" }.measurement.weightCg)
        }
    }

    /** A draft carries no float: every duration and every instant is an integer column. */
    @Test
    fun theDraftTableStoresNoFloatingPointColumn() {
        val types = migrateFromVersionOne().columnTypes("timed_activity_drafts")

        assertFalse(
            "$types must contain no REAL",
            types.values.any { it.equals("REAL", ignoreCase = true) },
        )
        assertEquals("INTEGER", types.getValue("accumulated_active_seconds"))
        assertEquals("INTEGER", types.getValue("started_at_millis"))
        assertEquals("INTEGER", types.getValue("boot_reference_millis"))
    }

    /** One running draft with gear, so a cascade and a unique index both have something to bite. */
    private suspend fun TimerDao.writeOneDraft() {
        upsertDraft(
            TimedActivityDraftEntity(
                id = DRAFT_ID,
                status = "running",
                movement = "running",
                customMovementName = null,
                environment = "outdoor",
                startedAtMillis = 1_700_000_000_000L,
                startedOn = "2026-08-20",
                startedAtLocalTime = "18:32:47",
                accumulatedActiveSeconds = 0,
                currentSegmentStartedAtMillis = 1_700_000_000_000L,
                currentSegmentStartedElapsedRealtimeMillis = 5_000L,
                bootReferenceMillis = 1_699_999_995_000L,
                finishedAtMillis = null,
                reviewFormState = null,
                reviewFormSchemaVersion = 0,
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        insertEquipment(
            listOf(
                TimedDraftEquipmentEntity(EQUIPMENT_ID, DRAFT_ID, "treadmill", null, "", 0),
                TimedDraftEquipmentEntity(
                    id = OTHER_EQUIPMENT_ID,
                    draftId = DRAFT_ID,
                    equipmentType = "other",
                    customName = "Ski erg",
                    customNameFolded = "ski erg",
                    position = 1,
                ),
            )
        )
    }

    private inline fun withMigratedDatabase(block: (MueDatabase) -> Unit) {
        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
            TIMER_TEST_DATABASE,
        ).addMigrations(*MueMigrations.ALL).addCallback(ExerciseCatalogSeed.CALLBACK).build()

        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun migrateFromVersionOne(): SupportSQLiteDatabase {
        helper.createDatabase(TIMER_TEST_DATABASE, 1).use { db ->
            seeded.forEach { (date, tenths) ->
                db.execSQL("INSERT INTO measurements (date, weight_dg) VALUES ('$date', $tenths)")
            }
        }
        return migrate()
    }

    /** `validateDroppedTables` is on: a leftover table would fail the run, not pass unnoticed. */
    private fun migrate(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(TIMER_TEST_DATABASE, 4, true, *MueMigrations.ALL)

    /** Raw SQL on purpose: the entity mapping must not be able to make any of this pass. */
    private fun SupportSQLiteDatabase.readMeasurements(): List<Pair<String, Int>> =
        query("SELECT date, weight_cg FROM measurements ORDER BY date ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1)) }
        }

    private fun SupportSQLiteDatabase.exerciseNames(): List<String> =
        query("SELECT name FROM exercise_definitions").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.tableNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.indexNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    /** Only the named indices: SQLite's own `sqlite_autoindex_…` rows are not declarations. */
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

    private companion object {
        const val DRAFT_ID = "55555555-5555-4555-8555-555555555555"
        const val EQUIPMENT_ID = "66666666-6666-4666-8666-666666666666"
        const val OTHER_EQUIPMENT_ID = "77777777-7777-4777-8777-777777777777"
    }
}
