package fr.kristenjestin.mue.data.local.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeasurementDaoTest {

    private lateinit var database: MueDatabase
    private lateinit var dao: MeasurementDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        dao = database.measurementDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertsAndReadsBackASingleMeasurement() = runTest {
        dao.upsert(entity("2026-08-23", 745))

        assertEquals(entity("2026-08-23", 745), dao.findByDate("2026-08-23"))
        assertEquals(1, dao.count())
    }

    @Test
    fun writingAnExistingDateReplacesItSilently() = runTest {
        dao.upsert(entity("2026-08-23", 745))
        dao.upsert(entity("2026-08-23", 802))

        assertEquals(1, dao.count())
        assertEquals(802, dao.findByDate("2026-08-23")?.weightDg)
    }

    @Test
    fun theDatabaseItselfRejectsADuplicateDate() = runTest {
        dao.upsert(entity("2026-08-23", 745))

        // Bypasses the DAO on purpose: the uniqueness must live in SQLite, not in
        // the conflict strategy Room happens to use (PRD 16.3).
        val error = runCatching {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO measurements (date, weight_dg) VALUES ('2026-08-23', 900)"
            )
        }.exceptionOrNull()

        assertTrue(
            "expected a UNIQUE constraint violation, got $error",
            error is SQLiteConstraintException ||
                error?.message.orEmpty().contains("UNIQUE", ignoreCase = true),
        )
        assertEquals(745, dao.findByDate("2026-08-23")?.weightDg)
    }

    @Test
    fun theDateColumnIsTheDeclaredPrimaryKey() = runTest {
        val cursor = database.openHelper.readableDatabase
            .query("PRAGMA table_info('measurements')")
        val primaryKeyColumns = mutableListOf<String>()
        cursor.use {
            val nameIndex = it.getColumnIndexOrThrow("name")
            val pkIndex = it.getColumnIndexOrThrow("pk")
            val typeIndex = it.getColumnIndexOrThrow("type")
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                if (it.getInt(pkIndex) > 0) primaryKeyColumns += name
                val expectedType = if (name == "date") "TEXT" else "INTEGER"
                assertEquals(expectedType, it.getString(typeIndex))
            }
        }
        assertEquals(listOf("date"), primaryKeyColumns)
    }

    @Test
    fun deletesByDate() = runTest {
        dao.upsert(entity("2026-08-22", 745))
        dao.upsert(entity("2026-08-23", 748))

        dao.deleteByDate("2026-08-22")

        assertNull(dao.findByDate("2026-08-22"))
        assertEquals(1, dao.count())
        assertEquals(748, dao.findByDate("2026-08-23")?.weightDg)
    }

    @Test
    fun deletingAnAbsentDateChangesNothing() = runTest {
        dao.upsert(entity("2026-08-23", 745))

        dao.deleteByDate("2020-01-01")

        assertEquals(1, dao.count())
    }

    @Test
    fun readsBackInChronologicalOrderWhateverTheInsertionOrder() = runTest {
        dao.upsert(entity("2026-08-23", 745))
        dao.upsert(entity("2025-12-31", 800))
        dao.upsert(entity("2026-01-01", 799))
        dao.upsert(entity("2026-08-09", 750))

        assertEquals(
            listOf("2025-12-31", "2026-01-01", "2026-08-09", "2026-08-23"),
            dao.getAll().map { it.date },
        )
        assertEquals(
            listOf("2025-12-31", "2026-01-01", "2026-08-09", "2026-08-23"),
            dao.observeAll().first().map { it.date },
        )
    }

    @Test
    fun queriesAClosedDateRangeInclusively() = runTest {
        seedAugust()

        val inWindow = dao.observeInWindow("2026-08-17", "2026-08-23").first()

        assertEquals(listOf("2026-08-17", "2026-08-20", "2026-08-23"), inWindow.map { it.date })
    }

    @Test
    fun aRangeExcludesTheDayBeforeAndTheDayAfter() = runTest {
        seedAugust()

        val inWindow = dao.observeInWindow("2026-08-18", "2026-08-22").first()

        assertEquals(listOf("2026-08-20"), inWindow.map { it.date })
    }

    @Test
    fun aNullLowerBoundReachesBackToTheFirstMeasurement() = runTest {
        seedAugust()

        val inWindow = dao.observeInWindow(null, "2026-08-17").first()

        assertEquals(listOf("2026-08-01", "2026-08-10", "2026-08-17"), inWindow.map { it.date })
    }

    @Test
    fun aNullUpperBoundReachesForward() = runTest {
        seedAugust()

        val inWindow = dao.observeInWindow("2026-08-17", null).first()

        assertEquals(
            listOf("2026-08-17", "2026-08-20", "2026-08-23", "2026-08-24"),
            inWindow.map { it.date },
        )
    }

    @Test
    fun twoNullBoundsReturnEverything() = runTest {
        seedAugust()

        assertEquals(6, dao.observeInWindow(null, null).first().size)
    }

    @Test
    fun anEmptyRangeReturnsNothing() = runTest {
        seedAugust()

        assertTrue(dao.observeInWindow("2020-01-01", "2020-12-31").first().isEmpty())
    }

    @Test
    fun theLatestMeasurementIsTheOneWithTheHighestDate() = runTest {
        assertNull(dao.observeLatest().first())

        dao.upsert(entity("2026-08-23", 745))
        dao.upsert(entity("2026-08-10", 900))

        assertEquals("2026-08-23", dao.observeLatest().first()?.date)
    }

    @Test
    fun replacingWithAMovedDateLeavesExactlyOneRow() = runTest {
        dao.upsert(entity("2026-08-20", 745))

        dao.replace("2026-08-20", entity("2026-08-21", 750))

        assertEquals(1, dao.count())
        assertNull(dao.findByDate("2026-08-20"))
        assertEquals(750, dao.findByDate("2026-08-21")?.weightDg)
    }

    @Test
    fun movingOntoAnOccupiedDateOverwritesIt() = runTest {
        dao.upsert(entity("2026-08-20", 745))
        dao.upsert(entity("2026-08-21", 999))

        dao.replace("2026-08-20", entity("2026-08-21", 750))

        assertEquals(1, dao.count())
        assertEquals(750, dao.findByDate("2026-08-21")?.weightDg)
    }

    @Test
    fun replacingWithoutMovingTheDateKeepsTheRow() = runTest {
        dao.upsert(entity("2026-08-20", 745))

        dao.replace("2026-08-20", entity("2026-08-20", 751))

        assertEquals(1, dao.count())
        assertEquals(751, dao.findByDate("2026-08-20")?.weightDg)
    }

    @Test
    fun anEmptyTableReadsAsAnEmptyList() = runTest {
        assertEquals(0, dao.count())
        assertTrue(dao.getAll().isEmpty())
        assertTrue(dao.observeAll().first().isEmpty())
        assertNull(dao.findByDate("2026-08-23"))
    }

    private suspend fun seedAugust() {
        listOf(
            "2026-08-01" to 800,
            "2026-08-10" to 790,
            "2026-08-17" to 780,
            "2026-08-20" to 775,
            "2026-08-23" to 770,
            "2026-08-24" to 765,
        ).forEach { (date, weightDg) -> dao.upsert(entity(date, weightDg)) }
    }

    private fun entity(date: String, weightDg: Int) = MeasurementEntity(date, weightDg)
}
