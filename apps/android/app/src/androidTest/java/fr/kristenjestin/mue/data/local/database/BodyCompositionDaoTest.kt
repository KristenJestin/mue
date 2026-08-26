package fr.kristenjestin.mue.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/**
 * `body_composition` : un enfant qui ne peut pas exister seul (BR-SCALE-006) et qui suit son
 * parent atomiquement (BR-SCALE-007).
 *
 * Les contraintes sont prouvées **en SQL brut** puis exercées à travers le DAO. C'est délibéré :
 * `PRAGMA foreign_key_list` dit ce que la table déclare, l'écriture dit ce que SQLite fait de
 * cette déclaration, et aucune des deux ne peut être satisfaite par un mapping d'entité.
 */
@RunWith(AndroidJUnit4::class)
class BodyCompositionDaoTest {

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
    fun aCompositionIsReadBackWithItsMeasurement() = runTest {
        dao.upsertAggregate(measurement("2026-08-23"), composition("2026-08-23"))

        val row = requireNotNull(dao.findByDate("2026-08-23"))
        assertEquals(7_845, row.measurement.weightCg)
        assertEquals(512, row.measurement.impedanceOhm)
        assertEquals(composition("2026-08-23"), row.composition)
        assertEquals(composition("2026-08-23"), dao.observeLatest().first()?.composition)
        assertEquals(1, dao.compositionCount())
    }

    /** BR-SCALE-006 : une composition orpheline est impossible à écrire, pas seulement interdite. */
    @Test
    fun aCompositionCannotBeWrittenWithoutItsMeasurement() = runTest {
        val error = runCatching { dao.upsertComposition(composition("2026-08-23")) }
            .exceptionOrNull()

        assertTrue("expected a foreign key failure, got $error", error != null)
        assertEquals(0, dao.compositionCount())
    }

    /** Au plus une composition par jour : la date est la clé primaire de l'enfant. */
    @Test
    fun aDateHoldsAtMostOneComposition() = runTest {
        dao.upsertAggregate(measurement("2026-08-23"), composition("2026-08-23"))

        dao.upsertAggregate(
            measurement("2026-08-23"),
            composition("2026-08-23").copy(bodyFatDeciPercent = 201),
        )

        assertEquals(1, dao.compositionCount())
        assertEquals(201, dao.findComposition("2026-08-23")?.bodyFatDeciPercent)
    }

    /**
     * BR-SCALE-007, première moitié : « un payload complet sans composition retire l'ancienne
     * composition ». C'est ce qui empêche une masse grasse calculée à partir d'un poids reçu de
     * survivre à la saisie manuelle qui remplace ce poids.
     */
    @Test
    fun aPayloadWithoutACompositionRemovesTheOneThatWasThere() = runTest {
        dao.upsertAggregate(measurement("2026-08-23"), composition("2026-08-23"))

        dao.upsertAggregate(
            MeasurementEntity(date = "2026-08-23", weightCg = 7_500),
            composition = null,
        )

        assertNull(dao.findComposition("2026-08-23"))
        assertNull(dao.findByDate("2026-08-23")?.composition)
        assertEquals(7_500, dao.findByDate("2026-08-23")?.measurement?.weightCg)
        assertEquals(0, dao.compositionCount())
    }

    /** BR-SCALE-007, seconde moitié : supprimer la mesure supprime la composition. */
    @Test
    fun deletingAMeasurementCascadesToItsComposition() = runTest {
        dao.upsertAggregate(measurement("2026-08-22"), composition("2026-08-22"))
        dao.upsertAggregate(measurement("2026-08-23"), composition("2026-08-23"))

        dao.deleteByDate("2026-08-22")

        assertNull(dao.findComposition("2026-08-22"))
        assertNotNull("la composition voisine n'est pas touchée", dao.findComposition("2026-08-23"))
        assertEquals(1, dao.compositionCount())
    }

    /** Déplacer une mesure emmène sa composition : l'ancienne date n'en garde aucune. */
    @Test
    fun movingAMeasurementLeavesNoOrphanedComposition() = runTest {
        dao.upsertAggregate(measurement("2026-08-20"), composition("2026-08-20"))

        dao.replace(
            originalDate = "2026-08-20",
            entity = measurement("2026-08-21"),
            composition = composition("2026-08-21"),
        )

        assertNull(dao.findComposition("2026-08-20"))
        assertEquals(1, dao.compositionCount())
        assertEquals("2026-08-21", dao.findComposition("2026-08-21")?.date)
    }

    /** BR-SCALE-009 : déplacer la date d'un poids reçu en fait une saisie manuelle sans composition. */
    @Test
    fun movingAMeasurementWithoutACompositionLeavesNoneBehind() = runTest {
        dao.upsertAggregate(measurement("2026-08-20"), composition("2026-08-20"))

        dao.replace(
            originalDate = "2026-08-20",
            entity = MeasurementEntity(date = "2026-08-21", weightCg = 7_500),
            composition = null,
        )

        assertEquals(0, dao.compositionCount())
        assertEquals("manual", dao.findByDate("2026-08-21")?.measurement?.sourceType)
    }

    @Test
    fun theDateIsBothThePrimaryKeyAndTheForeignKey() = runTest {
        val readable = database.openHelper.readableDatabase

        val primaryKey = readable.query("PRAGMA table_info('body_composition')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getInt(5) > 0) add(cursor.getInt(5) to cursor.getString(1))
                }
            }
        }.sortedBy { it.first }.map { it.second }
        assertEquals(listOf("date"), primaryKey)

        val foreignKeys = readable.query("PRAGMA foreign_key_list('body_composition')")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            listOf(
                                cursor.getString(cursor.getColumnIndexOrThrow("table")),
                                cursor.getString(cursor.getColumnIndexOrThrow("from")),
                                cursor.getString(cursor.getColumnIndexOrThrow("to")),
                                cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                                cursor.getString(cursor.getColumnIndexOrThrow("on_update")),
                            )
                        )
                    }
                }
            }
        assertEquals(
            listOf(listOf("measurements", "date", "date", "CASCADE", "CASCADE")),
            foreignKeys,
        )
    }

    /**
     * PRD_SCALE 21.1 : aucun flottant stocké. Deux implémentations — Kotlin et TypeScript — doivent
     * produire les mêmes entiers pour le même payload, ce qu'un `REAL` ne garantirait pas.
     */
    @Test
    fun everyStoredValueIsAnIntegerOrItsIdentityText() = runTest {
        val columns = database.openHelper.readableDatabase
            .query("PRAGMA table_info('body_composition')")
            .use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2)) }
            }

        assertEquals(
            mapOf(
                "date" to "TEXT",
                "formula_id" to "TEXT",
                "formula_version" to "INTEGER",
                "input_weight_cg" to "INTEGER",
                "input_height_cm" to "INTEGER",
                "input_age_years" to "INTEGER",
                "input_sex" to "TEXT",
                "body_fat_deci_percent" to "INTEGER",
                "fat_free_mass_cg" to "INTEGER",
                "body_water_deci_percent" to "INTEGER",
                "resting_energy_kcal" to "INTEGER",
            ),
            columns,
        )
        assertFalse(columns.values.any { it.equals("REAL", ignoreCase = true) })
    }

    /**
     * PRD_SCALE 22 : la composition n'est pas un agrégat synchronisé indépendant, elle voyage dans
     * le payload de sa mesure. Elle ne porte donc aucune métadonnée de synchronisation.
     */
    @Test
    fun theChildTableCarriesNoSynchronisationColumn() = runTest {
        val columns = database.openHelper.readableDatabase
            .query("PRAGMA table_info('body_composition')")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(1)) } }

        listOf("revision", "deleted_at", "origin_type", "origin_id", "last_mutation_id")
            .forEach { column ->
                assertFalse(
                    "body_composition must not duplicate sync_aggregate_state.$column",
                    columns.contains(column),
                )
            }
    }

    /** BR-SCALE-014 : la version de formule et l'instantané des entrées sont conservés tels quels. */
    @Test
    fun theSnapshotOfTheInputsIsStoredVerbatim() = runTest {
        dao.upsertAggregate(measurement("2026-08-23"), composition("2026-08-23"))

        val row = requireNotNull(dao.findComposition("2026-08-23"))
        assertEquals("mue-foot-to-foot-v1", row.formulaId)
        assertEquals(1, row.formulaVersion)
        assertEquals(7_845, row.inputWeightCg)
        assertEquals(178, row.inputHeightCm)
        assertEquals(36, row.inputAgeYears)
        assertEquals("male", row.inputSex)
    }

    private fun measurement(date: String) = MeasurementEntity(
        date = date,
        weightCg = 7_845,
        sourceType = "scale",
        impedanceOhm = 512,
    )

    private fun composition(date: String) = BodyCompositionEntity(
        date = date,
        formulaId = "mue-foot-to-foot-v1",
        formulaVersion = 1,
        inputWeightCg = 7_845,
        inputHeightCm = 178,
        inputAgeYears = 36,
        inputSex = "male",
        bodyFatDeciPercent = 183,
        fatFreeMassCg = 6_409,
        bodyWaterDeciPercent = 552,
        restingEnergyKcal = 1_742,
    )
}
