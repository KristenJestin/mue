package fr.kristenjestin.mue.data.local.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DATABASE = "mue-migration-test.db"

/**
 * PRD 16.3 and 20.3: the change of storage unit must convert the history, never erase it.
 *
 * Each test seeds a real version 1 file with known tenths, runs the real migration, then reads
 * the result back through raw SQL — so no assertion here can be satisfied by the entity
 * mapping rather than by the migration itself.
 */
@RunWith(AndroidJUnit4::class)
class MueMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MueDatabase::class.java,
    )

    /** Measurements standing in for the ones already on the owner's phone, in tenths. */
    private val seeded = listOf(
        "2020-01-01" to 300,
        "2020-01-02" to 2500,
        "2026-08-12" to 748,
        "2026-08-18" to 749,
        "2026-08-23" to 745,
    )

    @Test
    fun tenthsBecomeHundredthsWithoutLosingARow() {
        val rows = seedAndMigrate().readMeasurements()

        assertEquals("no measurement may be lost", seeded.size, rows.size)
        assertEquals(seeded.map { (date, tenths) -> date to tenths * 10 }, rows)
    }

    /** 74.5 kg must still read 74.5 kg afterwards, not 7.45 kg. */
    @Test
    fun theStoredKilogramsAreUnchanged() {
        val hundredths = seedAndMigrate().readMeasurements().toMap()

        assertEquals(7480, hundredths.getValue("2026-08-12"))
        assertEquals(7450, hundredths.getValue("2026-08-23"))
        assertEquals(3000, hundredths.getValue("2020-01-01"))
        assertEquals(25000, hundredths.getValue("2020-01-02"))
    }

    /** Every converted value lands on the 0.05 kg step of BR-003, with nothing to round. */
    @Test
    fun everyMigratedValueLandsOnTheStep() {
        seedAndMigrate().readMeasurements().forEach { (date, hundredths) ->
            assertEquals("$date is off the 0.05 kg grid", 0, hundredths % 5)
        }
    }

    /** The old column has to be gone, or a stale reader could still find tenths in it. */
    @Test
    fun theOldColumnIsReplacedRatherThanKept() {
        assertEquals(listOf("date", "weight_cg"), seedAndMigrate().columnNames())
    }

    /** The date must still be the primary key: BR-001 lives in SQLite, not in the UI. */
    @Test
    fun theDateStaysThePrimaryKey() {
        assertEquals(listOf("date"), seedAndMigrate().primaryKeyColumns())
    }

    /** The scratch table the conversion goes through must not survive it. */
    @Test
    fun noIntermediateTableIsLeftBehind() {
        val tables = seedAndMigrate()
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }

        assertTrue("measurements is missing", tables.contains("measurements"))
        assertTrue("a scratch table survived: $tables", tables.none { it.startsWith("measurements_") })
    }

    @Test
    fun anEmptyVersionOneDatabaseMigratesToAnEmptyOne() {
        helper.createDatabase(TEST_DATABASE, 1).close()

        assertTrue(migrate().readMeasurements().isEmpty())
    }

    /**
     * The migrated file has to be usable by the app itself, not merely well shaped: this
     * reopens it through the real Room build and reads the history back.
     */
    @Test
    fun theMigratedHistoryIsReadableThroughTheRealDao() = runTest {
        seedAndMigrate().close()

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
            TEST_DATABASE,
        ).addMigrations(*MueMigrations.ALL).build()

        try {
            val measurements = database.measurementDao().getAll()
            assertEquals(seeded.size, measurements.size)
            assertEquals(7450, measurements.first { it.measurement.date == "2026-08-23" }.measurement.weightCg)
            assertEquals(74.5, measurements.first { it.measurement.date == "2026-08-23" }.toDomain().weight.kilograms, 0.0)
            assertNull(database.measurementDao().findByDate("1999-01-01"))
        } finally {
            database.close()
        }
    }

    private fun seedAndMigrate(): SupportSQLiteDatabase {
        helper.createDatabase(TEST_DATABASE, 1).use { db ->
            seeded.forEach { (date, tenths) ->
                db.execSQL("INSERT INTO measurements (date, weight_dg) VALUES ('$date', $tenths)")
            }
        }
        return migrate()
    }

    /** `validateDroppedTables` is on: a leftover table would fail the run, not pass unnoticed. */
    private fun migrate(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(TEST_DATABASE, 2, true, MueMigrations.MIGRATION_1_2)

    /** Raw SQL on purpose: the entity mapping must not be able to make this pass. */
    private fun SupportSQLiteDatabase.readMeasurements(): List<Pair<String, Int>> =
        query("SELECT date, weight_cg FROM measurements ORDER BY date ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1))
            }
        }

    private fun SupportSQLiteDatabase.columnNames(): List<String> =
        query("PRAGMA table_info('measurements')").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private fun SupportSQLiteDatabase.primaryKeyColumns(): List<String> =
        query("PRAGMA table_info('measurements')").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val primaryKey = cursor.getColumnIndexOrThrow("pk")
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getInt(primaryKey) > 0) add(cursor.getString(name))
                }
            }
        }
}
