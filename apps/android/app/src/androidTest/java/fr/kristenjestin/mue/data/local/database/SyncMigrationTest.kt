package fr.kristenjestin.mue.data.local.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.data.repository.RoomMeasurementRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
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

private const val SYNC_TEST_DATABASE = "mue-sync-migration-test.db"

private val SYNC_TABLES = listOf("sync_mutations", "sync_aggregate_state", "sync_state")

private val TABLES_ALREADY_THERE_AT_VERSION_FOUR = listOf(
    "measurements",
    "activity_sessions",
    "activity_metrics",
    "session_equipment",
    "exercise_definitions",
    "strength_exercises",
    "strength_sets",
    "timed_activity_drafts",
    "timed_draft_equipment",
)

/**
 * Sync PRD 19: the synchronisation tables are added to the database already on the phone, and
 * nothing that was there is touched.
 *
 * Every test seeds a genuine **version 1** file, in tenths of a kilogram, and chains all four
 * migrations. A phone that installed Mue at version 1 has never seen version 4 as a starting
 * state, so proving 4 → 5 alone would leave the oldest install — the one with the most history
 * to lose — untested, and it is precisely the arithmetic of 1 → 2 that a later migration could
 * quietly undo.
 *
 * `fallbackToDestructiveMigration` would make every assertion here pass on an empty file. It is
 * forbidden by PRD 19 and by [MueDatabase]'s own comment, which is why the whole chain is
 * re-proved at each version rather than the last step alone.
 */
@RunWith(AndroidJUnit4::class)
class SyncMigrationTest {

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
    fun everyWeightSurvivesAllFourMigrations() {
        val rows = migrateFromVersionOne().readMeasurements()

        assertEquals("no measurement may be lost", seeded.size, rows.size)
        assertEquals(seeded.map { (date, tenths) -> date to tenths * 10 }, rows)
    }

    /** 74.5 kg has to still read 74.5 kg four migrations later, not 7.45 kg and not 745 kg. */
    @Test
    fun theWeightsArriveAsHundredthsAtVersionFive() {
        val hundredths = migrateFromVersionOne().readMeasurements().toMap()

        assertEquals(9_030, hundredths.getValue("2018-04-09"))
        assertEquals(8_120, hundredths.getValue("2019-11-03"))
        assertEquals(3_000, hundredths.getValue("2020-01-01"))
        assertEquals(25_000, hundredths.getValue("2024-06-15"))
        assertEquals(7_450, hundredths.getValue("2026-08-23"))
    }

    @Test
    fun everythingTheEarlierVersionsBuiltIsStillThere() {
        val tables = migrateFromVersionOne().tableNames()

        TABLES_ALREADY_THERE_AT_VERSION_FOUR.forEach { table ->
            assertTrue("$table is missing from $tables", tables.contains(table))
        }
    }

    @Test
    fun theChainedMigrationCreatesTheThreeSyncTablesAndTheHealthProfile() {
        val tables = migrateFromVersionOne().tableNames()

        (SYNC_TABLES + "health_profile").forEach { table ->
            assertTrue("$table is missing from $tables", tables.contains(table))
        }
    }

    /** Without it, every send would scan the whole outbox to find the oldest pending row. */
    @Test
    fun theOutboxIsIndexedOnStateAndAge() {
        val indices = migrateFromVersionOne().indexNames("sync_mutations")

        assertEquals(listOf("index_sync_mutations_state_created_at"), indices)
    }

    /**
     * Room validates every index a file carries, so an index nobody declared fails the very
     * `runMigrationsAndValidate` this test rests on. Asserting the absence keeps that failure
     * legible if someone adds one to the migration and not to the entity.
     */
    @Test
    fun theOtherNewTablesCarryNoUndeclaredIndex() {
        val database = migrateFromVersionOne()

        assertEquals(emptyList<String>(), database.indexNames("sync_aggregate_state"))
        assertEquals(emptyList<String>(), database.indexNames("sync_state"))
        assertEquals(emptyList<String>(), database.indexNames("health_profile"))
    }

    /**
     * The correct starting state, and each emptiness means something: nothing waiting to be
     * sent, no server paired, and nothing the server has ever acknowledged — all true of a
     * phone that has never synchronised.
     */
    @Test
    fun theNewTablesArriveEmpty() {
        val database = migrateFromVersionOne()

        (SYNC_TABLES + "health_profile").forEach { table ->
            assertEquals("$table should arrive empty", 0, database.countOf(table))
        }
    }

    /**
     * The height and the birth date are in a Preferences file the migration cannot open, so
     * seeding them here is impossible; `HealthProfileSeeding` does it at startup instead. This
     * asserts the migration does not pretend otherwise by writing an empty row.
     */
    @Test
    fun theMigrationSeedsNoHealthProfileRow() {
        assertEquals(0, migrateFromVersionOne().countOf("health_profile"))
    }

    /** Version 5 adds no catalogue, so it must not run the seed a second time. */
    @Test
    fun theChainedMigrationDoesNotReseedTheCatalogue() {
        assertEquals(
            ExerciseCatalogSeed.DEFINITIONS.size,
            migrateFromVersionOne().countOf("exercise_definitions"),
        )
    }

    /** Every instant is epoch milliseconds and every counter an integer, as everywhere here. */
    @Test
    fun theNewTablesStoreNoFloatingPointColumn() {
        val database = migrateFromVersionOne()

        (SYNC_TABLES + "health_profile").forEach { table ->
            val types = database.columnTypes(table)
            assertFalse(
                "$table: $types must contain no REAL",
                types.values.any { it.equals("REAL", ignoreCase = true) },
            )
        }
        assertEquals("INTEGER", database.columnTypes("sync_mutations").getValue("created_at"))
        assertEquals("INTEGER", database.columnTypes("sync_aggregate_state").getValue("deleted_at"))
        assertEquals("INTEGER", database.columnTypes("health_profile").getValue("height_cm"))
    }

    /** PRD 9.2 puts the session bearer in Keystore; a column here would put it in a file. */
    @Test
    fun theSyncStateTableHoldsNoToken() {
        val columns = migrateFromVersionOne().columnTypes("sync_state").keys

        assertTrue(
            "$columns must not name a token",
            columns.none { it.contains("token", ignoreCase = true) },
        )
        assertTrue(columns.contains("cursor"))
        assertTrue(columns.contains("profile_seeded"))
    }

    @Test
    fun anEmptyVersionOneDatabaseArrivesEmptyAtVersionFive() {
        helper.createDatabase(SYNC_TEST_DATABASE, 1).close()
        val database = migrate()

        assertTrue(database.readMeasurements().isEmpty())
        assertEquals(0, database.countOf("sync_mutations"))
        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, database.countOf("exercise_definitions"))
    }

    /**
     * The whole point of the version: a weight written on the migrated file leaves an outbox
     * row behind it. Read back through the app's own repository and DAO rather than through raw
     * SQL, because that is the path the phone actually takes.
     */
    @Test
    fun aWriteOnTheMigratedFileJournalsItsMutation() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val repository = RoomMeasurementRepository(database.measurementDao(), SyncOutbox())

            repository.save(Measurement(LocalDate.parse("2026-08-24"), Weight.ofHundredthsClamped(7_405)))

            assertEquals(seeded.size + 1, database.measurementDao().getAll().size)

            val pending = database.syncDao().pendingMutations(10)
            assertEquals(1, pending.size)
            assertEquals(SyncMutationEntity.OP_UPSERT, pending.single().op)
            assertEquals("2026-08-24", pending.single().aggregateId)
            assertEquals(
                SyncAggregateStateEntity.TYPE_MEASUREMENT,
                pending.single().aggregateType,
            )
            assertNotNull(pending.single().payload)
        }
    }

    /** FR-SYNC-005 on the migrated file: the row goes, the tombstone stays behind it. */
    @Test
    fun aDeleteOnTheMigratedFileLeavesATombstone() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val repository = RoomMeasurementRepository(database.measurementDao(), SyncOutbox())

            repository.delete(LocalDate.parse("2026-08-23"))

            assertNull(database.measurementDao().findByDate("2026-08-23"))
            val tombstones =
                database.syncDao().tombstones(SyncAggregateStateEntity.TYPE_MEASUREMENT)
            assertEquals(listOf("2026-08-23"), tombstones.map { it.aggregateId })
            assertNotNull(tombstones.single().deletedAt)
        }
    }

    /** The history the four migrations carried has to be readable by the app that opens it. */
    @Test
    fun theMigratedFileIsUsableThroughTheRealDaos() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val measurements = database.measurementDao().getAll()
            assertEquals(seeded.size, measurements.size)
            assertEquals(7_450, measurements.first { it.measurement.date == "2026-08-23" }.measurement.weightCg)

            assertNull("no server is paired yet", database.syncDao().syncState())
            assertNull("nothing has been seeded yet", database.healthProfileDao().get())
        }
    }

    private inline fun withMigratedDatabase(block: (MueDatabase) -> Unit) {
        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
            SYNC_TEST_DATABASE,
        ).addMigrations(*MueMigrations.ALL).addCallback(ExerciseCatalogSeed.CALLBACK).build()

        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun migrateFromVersionOne(): SupportSQLiteDatabase {
        helper.createDatabase(SYNC_TEST_DATABASE, 1).use { db ->
            seeded.forEach { (date, tenths) ->
                db.execSQL("INSERT INTO measurements (date, weight_dg) VALUES ('$date', $tenths)")
            }
        }
        return migrate()
    }

    /** `validateDroppedTables` is on: a leftover table would fail the run, not pass unnoticed. */
    private fun migrate(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(SYNC_TEST_DATABASE, 5, true, *MueMigrations.ALL)

    /** Raw SQL on purpose: the entity mapping must not be able to make any of this pass. */
    private fun SupportSQLiteDatabase.readMeasurements(): List<Pair<String, Int>> =
        query("SELECT date, weight_cg FROM measurements ORDER BY date ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1)) }
        }

    private fun SupportSQLiteDatabase.tableNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
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
}
