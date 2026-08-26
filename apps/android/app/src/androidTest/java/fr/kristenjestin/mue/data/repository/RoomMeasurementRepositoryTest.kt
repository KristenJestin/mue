package fr.kristenjestin.mue.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.Sex
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
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomMeasurementRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: MeasurementRepository
    private lateinit var scales: RoomScaleRepository

    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    @Before
    fun createRepository() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        repository = RoomMeasurementRepository(database.measurementDao())
        scales = RoomScaleRepository(database.scaleDao())
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

    // --- PRD_SCALE 21.1 : la mesure est un agrégat -------------------------------------------

    /**
     * « Créer ou remplacer un poids écrit le `Measurement` complet dans une seule transaction :
     * poids, provenance, impédance facultative et composition facultative » (PRD_SCALE 21.1).
     * L'agrégat se relit entier, par une seule lecture.
     */
    @Test
    fun writesTheWholeAggregateAndReadsItBackWhole() = runTest {
        val measurement = scaleMeasurement("2026-08-23", withComposition = true)

        repository.save(measurement)

        assertEquals(measurement, repository.findByDate(LocalDate.of(2026, 8, 23)))
        assertEquals(measurement, repository.observeLatest().first())
        assertEquals(listOf(measurement), repository.getAll())
        assertEquals(1, database.measurementDao().compositionCount())
    }

    /**
     * BR-SCALE-008 : une impédance exploitable est conservée sur sa mesure de poids **y compris
     * lorsque aucune composition n'a pu être calculée** — le cas normal tant que le profil est
     * incomplet, et exactement la matière dont FR-BODY-006 aura besoin.
     */
    @Test
    fun anImpedanceIsKeptEvenWhenNoCompositionCouldBeComputed() = runTest {
        repository.save(scaleMeasurement("2026-08-23", withComposition = false))

        val stored = requireNotNull(repository.findByDate(LocalDate.of(2026, 8, 23)))
        assertEquals(512, stored.impedanceOhm)
        assertNull(stored.bodyComposition)
        assertEquals(0, database.measurementDao().compositionCount())
    }

    /**
     * BR-SCALE-007 et BR-SCALE-013 : remplacer une pesée reçue par une saisie manuelle retire la
     * composition dans la même transaction. Une masse grasse dérivée d'un poids que plus personne
     * ne peut lire serait une donnée fausse, pas une donnée périmée.
     */
    @Test
    fun aManualReplacementRemovesTheCompositionThatWasThere() = runTest {
        repository.save(scaleMeasurement("2026-08-23", withComposition = true))

        repository.save(measurement("2026-08-23", 7_500))

        val stored = requireNotNull(repository.findByDate(LocalDate.of(2026, 8, 23)))
        assertEquals(7_500, stored.weight.hundredthsKg)
        assertNull("BR-SCALE-007", stored.bodyComposition)
        assertNull("BR-SCALE-013", stored.impedanceOhm)
        assertEquals(MeasurementSource.MANUAL, stored.source)
        assertEquals(0, database.measurementDao().compositionCount())
    }

    /**
     * BR-SCALE-009 : choisir une autre date pour un poids reçu conserve le poids mais en fait une
     * saisie manuelle sans composition. `replace` reste deux agrégats — un delete, un upsert — et
     * la composition de l'ancienne date part par cascade.
     */
    @Test
    fun movingAMeasurementLeavesNoCompositionBehindOnTheOldDate() = runTest {
        repository.save(scaleMeasurement("2026-08-20", withComposition = true))

        repository.replace(LocalDate.of(2026, 8, 20), measurement("2026-08-21", 7_845))

        assertNull(repository.findByDate(LocalDate.of(2026, 8, 20)))
        val moved = requireNotNull(repository.findByDate(LocalDate.of(2026, 8, 21)))
        assertEquals(MeasurementSource.MANUAL, moved.source)
        assertNull(moved.bodyComposition)
        assertNull(moved.impedanceOhm)
        assertEquals(0, database.measurementDao().compositionCount())
    }

    /** Déplacer une mesure en gardant sa composition la fait suivre, sans doublon. */
    @Test
    fun aCompositionFollowsItsMeasurementToTheNewDate() = runTest {
        repository.save(scaleMeasurement("2026-08-20", withComposition = true))

        repository.replace(
            LocalDate.of(2026, 8, 20),
            scaleMeasurement("2026-08-21", withComposition = true),
        )

        assertEquals(1, database.measurementDao().compositionCount())
        assertEquals(
            LocalDate.of(2026, 8, 21),
            repository.findByDate(LocalDate.of(2026, 8, 21))?.bodyComposition?.date,
        )
    }

    /** BR-SCALE-007 : supprimer un poids supprime sa composition, par la cascade du schéma. */
    @Test
    fun deletingAMeasurementCascadesToItsComposition() = runTest {
        repository.save(scaleMeasurement("2026-08-22", withComposition = true))
        repository.save(scaleMeasurement("2026-08-23", withComposition = true))

        repository.delete(LocalDate.of(2026, 8, 22))

        assertEquals(1, database.measurementDao().compositionCount())
        assertNull(database.measurementDao().findComposition("2026-08-22"))
    }

    /**
     * BR-SCALE-010 : oublier une balance ne supprime aucune mesure. Le poids, la composition et la
     * provenance `scale` restent ; seul l'identifiant local, qui ne désigne plus rien, est annulé.
     */
    @Test
    fun forgettingAScaleKeepsTheMeasurementAndOnlyClearsTheLink() = runTest {
        scales.save(scale())
        repository.save(
            scaleMeasurement("2026-08-23", withComposition = true, sourceScaleId = "scale-1"),
        )
        assertEquals(
            "scale-1",
            repository.findByDate(LocalDate.of(2026, 8, 23))?.sourceScaleId,
        )

        scales.forget("scale-1")

        val stored = requireNotNull(repository.findByDate(LocalDate.of(2026, 8, 23)))
        assertEquals(7_845, stored.weight.hundredthsKg)
        assertEquals(MeasurementSource.SCALE, stored.source)
        assertNull("BR-SCALE-010", stored.sourceScaleId)
        assertEquals(512, stored.impedanceOhm)
        assertEquals(
            "PRD_SCALE 18.1 : oublier la dernière balance ne masque aucune composition",
            183,
            stored.bodyComposition?.bodyFatDeciPercent,
        )
    }

    /**
     * BR-SCALE-015 rendue structurelle : « `inputWeightCg` est toujours égal au poids de sa mesure
     * parente ».
     *
     * Aucune contrainte SQL ne peut la tenir — le poids vit dans l'autre table — et aucune
     * vérification après coup ne serait lancée par qui que ce soit. La seule forme qui la rende
     * vraie est une conversion qui n'offre pas l'occasion de la violer : le domaine qu'on passe ici
     * est délibérément incohérent, et la ligne écrite ne l'est pas.
     */
    @Test
    fun aCompositionWhoseInputWeightDivergesCannotReachTheDatabase() = runTest {
        val coherent = scaleMeasurement("2026-08-23", withComposition = true)

        repository.save(
            coherent.copy(
                bodyComposition = coherent.bodyComposition?.copy(inputWeightCg = 6_000),
            ),
        )

        assertEquals(
            "l'instantané d'entrée est le poids de la ligne parente, pas celui qu'on a fourni",
            7_845,
            database.measurementDao().findComposition("2026-08-23")?.inputWeightCg,
        )
        assertEquals(
            7_845,
            repository.findByDate(LocalDate.of(2026, 8, 23))?.bodyComposition?.inputWeightCg,
        )
    }

    private fun measurement(isoDate: String, weightCg: Int) =
        Measurement(LocalDate.parse(isoDate), Weight.ofHundredthsClamped(weightCg))

    private fun scaleMeasurement(
        isoDate: String,
        withComposition: Boolean,
        sourceScaleId: String? = null,
    ) = Measurement(
        date = LocalDate.parse(isoDate),
        weight = Weight.ofHundredthsClamped(7_845),
        source = MeasurementSource.SCALE,
        sourceScaleId = sourceScaleId,
        impedanceOhm = 512,
        bodyComposition = if (!withComposition) null else BodyComposition(
            date = LocalDate.parse(isoDate),
            formulaId = "mue-foot-to-foot-v1",
            formulaVersion = 1,
            inputWeightCg = 7_845,
            inputHeightCm = 178,
            inputAgeYears = 36,
            inputSex = Sex.MALE,
            bodyFatDeciPercent = 183,
            fatFreeMassCg = 6_409,
            bodyWaterDeciPercent = 552,
            restingEnergyKcal = 1_742,
        ),
    )

    private fun scale() = ScaleDevice(
        id = "scale-1",
        driverId = "homebuds-hb9027",
        address = "FF:10:00:1F:52:C3",
        advertisedName = "HB9027",
        displayName = "Homebuds HB9027",
        lastSeenAt = null,
        createdAt = Instant.ofEpochMilli(1_770_000_000_000L),
    )
}
