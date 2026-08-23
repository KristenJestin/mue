package fr.kristenjestin.mue.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomMeasurementRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: MeasurementRepository

    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    @Before
    fun createRepository() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        repository = RoomMeasurementRepository(database.measurementDao())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun roundTripsTheDomainModelWithoutLosingAHundredth() = runTest {
        val measurement = measurement("2026-08-23", 7_405)

        repository.save(measurement)

        assertEquals(measurement, repository.findByDate(LocalDate.of(2026, 8, 23)))
        assertEquals(74.05, repository.findByDate(LocalDate.of(2026, 8, 23))!!.weight.kilograms, 0.0)
    }

    @Test
    fun savingTheSameDateTwiceKeepsOneMeasurement() = runTest {
        repository.save(measurement("2026-08-23", 7_450))
        repository.save(measurement("2026-08-23", 8_020))

        val all = repository.getAll()
        assertEquals(1, all.size)
        assertEquals(8_020, all.single().weight.hundredthsKg)
    }

    @Test
    fun readsBackOldestFirst() = runTest {
        repository.save(measurement("2026-08-23", 7_450))
        repository.save(measurement("2026-08-01", 8_000))

        assertEquals(
            listOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 23)),
            repository.observeAll().first().map { it.date },
        )
    }

    @Test
    fun filtersOnAPeriodWindow() = runTest {
        repository.save(measurement("2026-06-01", 9_000))
        repository.save(measurement("2026-08-18", 7_500))
        repository.save(measurement("2026-08-23", 7_450))

        val window = Period.SEVEN_DAYS.windowEndingOn(today)
        val inWindow = repository.observeIn(window).first()

        assertEquals(
            listOf(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 23)),
            inWindow.map { it.date },
        )
    }

    @Test
    fun theAllPeriodIsUnbounded() = runTest {
        repository.save(measurement("2010-01-01", 9_000))
        repository.save(measurement("2026-08-23", 7_450))

        assertEquals(2, repository.observeIn(DateWindow.UNBOUNDED).first().size)
        assertEquals(2, repository.observeIn(Period.ALL.windowEndingOn(today)).first().size)
    }

    @Test
    fun reportsTheLatestMeasurement() = runTest {
        assertNull(repository.observeLatest().first())

        repository.save(measurement("2026-08-10", 9_000))
        repository.save(measurement("2026-08-23", 7_450))

        assertEquals(LocalDate.of(2026, 8, 23), repository.observeLatest().first()?.date)
    }

    @Test
    fun movingAMeasurementToAnotherDateLeavesNoOrphan() = runTest {
        repository.save(measurement("2026-08-20", 7_450))

        repository.replace(LocalDate.of(2026, 8, 20), measurement("2026-08-21", 7_500))

        val all = repository.getAll()
        assertEquals(1, all.size)
        assertEquals(Measurement(LocalDate.of(2026, 8, 21), Weight.ofHundredthsClamped(7_500)), all.single())
    }

    @Test
    fun movingOntoAnOccupiedDateOverwritesWithoutConfirmation() = runTest {
        repository.save(measurement("2026-08-20", 7_450))
        repository.save(measurement("2026-08-21", 9_990))

        repository.replace(LocalDate.of(2026, 8, 20), measurement("2026-08-21", 7_500))

        assertEquals(listOf(7_500), repository.getAll().map { it.weight.hundredthsKg })
    }

    @Test
    fun deletesAMeasurement() = runTest {
        repository.save(measurement("2026-08-20", 7_450))
        repository.save(measurement("2026-08-23", 7_480))

        repository.delete(LocalDate.of(2026, 8, 20))

        assertEquals(listOf(LocalDate.of(2026, 8, 23)), repository.getAll().map { it.date })
    }

    @Test
    fun anEmptyHistoryReadsAsEmpty() = runTest {
        assertTrue(repository.getAll().isEmpty())
        assertTrue(repository.observeAll().first().isEmpty())
        assertNull(repository.findByDate(today))
    }

    private fun measurement(isoDate: String, weightCg: Int) =
        Measurement(LocalDate.parse(isoDate), Weight.ofHundredthsClamped(weightCg))
}
