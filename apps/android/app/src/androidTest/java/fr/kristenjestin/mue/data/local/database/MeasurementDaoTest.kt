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
        dao.upsert(entity("2026-08-23", 7_450))

        assertEquals(entity("2026-08-23", 7_450), dao.findByDate("2026-08-23")?.measurement)
        assertNull(
            "une saisie manuelle n'a pas de composition (BR-SCALE-006)",
            dao.findByDate("2026-08-23")?.composition,
        )
        assertEquals(1, dao.count())
    }

    @Test
    fun writingAnExistingDateReplacesItSilently() = runTest {
        dao.upsert(entity("2026-08-23", 7_450))
        dao.upsert(entity("2026-08-23", 8_020))

        assertEquals(1, dao.count())
        assertEquals(8_020, dao.findByDate("2026-08-23")?.measurement?.weightCg)
    }

    @Test
    fun theDatabaseItselfRejectsADuplicateDate() = runTest {
        dao.upsert(entity("2026-08-23", 7_450))

        // Bypasses the DAO on purpose: the uniqueness must live in SQLite, not in
        // the conflict strategy Room happens to use (PRD 16.3).
        val error = runCatching {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO measurements (date, weight_cg) VALUES ('2026-08-23', 9000)"
            )
        }.exceptionOrNull()

        assertTrue(
            "expected a UNIQUE constraint violation, got $error",
            error is SQLiteConstraintException ||
                error?.message.orEmpty().contains("UNIQUE", ignoreCase = true),
        )
        assertEquals(7_450, dao.findByDate("2026-08-23")?.measurement?.weightCg)
    }

    /**
     * L'assertion sur les types est nommée colonne par colonne depuis la version 7.
     *
     * Elle disait « tout sauf `date` est un INTEGER », ce qui n'avait de sens que tant que la
     * table ne portait qu'un poids. `source_type` est du texte parce qu'un enum se stocke par son
     * identifiant stable et non par un rang que réordonner casserait (voir
     * `ActivityEnumConverters`), et `source_scale_id` est du texte parce que c'est un UUID. Ce qui
     * doit rester vrai est plus précis que l'ancienne règle et le reste : **aucune colonne n'est
     * un `REAL`** — PRD BR-003 fait du centième de kilogramme l'unité de vérité, et un flottant y
     * réintroduirait la dérive que l'entier existe pour empêcher.
     */
    @Test
    fun theDateColumnIsTheDeclaredPrimaryKeyAndEveryColumnHasItsDeclaredType() = runTest {
        val expected = mapOf(
            "date" to "TEXT",
            "weight_cg" to "INTEGER",
            "source_type" to "TEXT",
            "source_scale_id" to "TEXT",
            "impedance_ohm" to "INTEGER",
        )
        val cursor = database.openHelper.readableDatabase
            .query("PRAGMA table_info('measurements')")
        val primaryKeyColumns = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        cursor.use {
            val nameIndex = it.getColumnIndexOrThrow("name")
            val pkIndex = it.getColumnIndexOrThrow("pk")
            val typeIndex = it.getColumnIndexOrThrow("type")
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                seen += name
                if (it.getInt(pkIndex) > 0) primaryKeyColumns += name
                assertEquals(name, expected[name], it.getString(typeIndex))
            }
        }
        assertEquals(expected.keys, seen)
        assertEquals(listOf("date"), primaryKeyColumns)
    }

    /**
     * PRD_SCALE 21.1 : `manual` par défaut pour tout l'historique existant. La valeur par défaut
     * vit dans le schéma et non dans le code Kotlin, sans quoi une écriture en SQL brut — celle
     * d'une migration, celle d'un test — produirait une ligne sans provenance.
     */
    @Test
    fun theProvenanceDefaultsToManualEvenForARowWrittenInRawSql() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO measurements (date, weight_cg) VALUES ('2026-08-23', 7450)"
        )

        assertEquals("manual", dao.findByDate("2026-08-23")?.measurement?.sourceType)
        assertNull(dao.findByDate("2026-08-23")?.measurement?.sourceScaleId)
        assertNull(dao.findByDate("2026-08-23")?.measurement?.impedanceOhm)
    }

    /** BR-SCALE-010 : la clé étrangère existe, indexée, et n'efface que le lien. */
    @Test
    fun theScaleReferenceIsADeclaredForeignKeyWithItsOwnIndex() = runTest {
        val database = database.openHelper.readableDatabase

        val foreignKeys = database.query("PRAGMA foreign_key_list('measurements')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("table")),
                            cursor.getString(cursor.getColumnIndexOrThrow("from")),
                            cursor.getString(cursor.getColumnIndexOrThrow("to")),
                            cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                        )
                    )
                }
            }
        }
        assertEquals(listOf(listOf("scale", "source_scale_id", "id", "SET NULL")), foreignKeys)

        val indexNames = database
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'measurements'")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertTrue(
            "an unindexed foreign key makes forgetting a scale a table scan, got $indexNames",
            indexNames.contains("index_measurements_source_scale_id"),
        )
    }

    @Test
    fun deletesByDate() = runTest {
        dao.upsert(entity("2026-08-22", 7_450))
        dao.upsert(entity("2026-08-23", 7_480))

        dao.deleteByDate("2026-08-22")

        assertNull(dao.findByDate("2026-08-22"))
        assertEquals(1, dao.count())
        assertEquals(7_480, dao.findByDate("2026-08-23")?.measurement?.weightCg)
    }

    @Test
    fun deletingAnAbsentDateChangesNothing() = runTest {
        dao.upsert(entity("2026-08-23", 7_450))

        dao.deleteByDate("2020-01-01")

        assertEquals(1, dao.count())
    }

    @Test
    fun readsBackInChronologicalOrderWhateverTheInsertionOrder() = runTest {
        dao.upsert(entity("2026-08-23", 7_450))
        dao.upsert(entity("2025-12-31", 8_000))
        dao.upsert(entity("2026-01-01", 7_990))
        dao.upsert(entity("2026-08-09", 7_500))

        assertEquals(
            listOf("2025-12-31", "2026-01-01", "2026-08-09", "2026-08-23"),
            dao.getAll().map { it.measurement.date },
        )
        assertEquals(
            listOf("2025-12-31", "2026-01-01", "2026-08-09", "2026-08-23"),
            dao.observeAll().first().map { it.measurement.date },
        )
    }

    @Test
    fun queriesAClosedDateRangeInclusively() = runTest {
        seedAugust()

        val inWindow = dao.observeInWindow("2026-08-17", "2026-08-23").first()

        assertEquals(listOf("2026-08-17", "2026-08-20", "2026-08-23"), inWindow.map { it.measurement.date })
    }

    @Test
    fun aRangeExcludesTheDayBeforeAndTheDayAfter() = runTest {
        seedAugust()

        val inWindow = dao.observeInWindow("2026-08-18", "2026-08-22").first()

        assertEquals(listOf("2026-08-20"), inWindow.map { it.measurement.date })
    }

    @Test
    fun aNullLowerBoundReachesBackToTheFirstMeasurement() = runTest {
        seedAugust()

        val inWindow = dao.observeInWindow(null, "2026-08-17").first()

        assertEquals(listOf("2026-08-01", "2026-08-10", "2026-08-17"), inWindow.map { it.measurement.date })
    }

    @Test
    fun aNullUpperBoundReachesForward() = runTest {
        seedAugust()

        val inWindow = dao.observeInWindow("2026-08-17", null).first()

        assertEquals(
            listOf("2026-08-17", "2026-08-20", "2026-08-23", "2026-08-24"),
            inWindow.map { it.measurement.date },
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

        dao.upsert(entity("2026-08-23", 7_450))
        dao.upsert(entity("2026-08-10", 9_000))

        assertEquals("2026-08-23", dao.observeLatest().first()?.measurement?.date)
    }

    @Test
    fun replacingWithAMovedDateLeavesExactlyOneRow() = runTest {
        dao.upsert(entity("2026-08-20", 7_450))

        dao.replace("2026-08-20", entity("2026-08-21", 7_500), null)

        assertEquals(1, dao.count())
        assertNull(dao.findByDate("2026-08-20"))
        assertEquals(7_500, dao.findByDate("2026-08-21")?.measurement?.weightCg)
    }

    @Test
    fun movingOntoAnOccupiedDateOverwritesIt() = runTest {
        dao.upsert(entity("2026-08-20", 7_450))
        dao.upsert(entity("2026-08-21", 9_990))

        dao.replace("2026-08-20", entity("2026-08-21", 7_500), null)

        assertEquals(1, dao.count())
        assertEquals(7_500, dao.findByDate("2026-08-21")?.measurement?.weightCg)
    }

    @Test
    fun replacingWithoutMovingTheDateKeepsTheRow() = runTest {
        dao.upsert(entity("2026-08-20", 7_450))

        dao.replace("2026-08-20", entity("2026-08-20", 7_510), null)

        assertEquals(1, dao.count())
        assertEquals(7_510, dao.findByDate("2026-08-20")?.measurement?.weightCg)
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
            "2026-08-01" to 8_000,
            "2026-08-10" to 7_900,
            "2026-08-17" to 7_800,
            "2026-08-20" to 7_750,
            "2026-08-23" to 7_700,
            "2026-08-24" to 7_650,
        ).forEach { (date, weightCg) -> dao.upsert(entity(date, weightCg)) }
    }

    private fun entity(date: String, weightCg: Int) = MeasurementEntity(date, weightCg)
}
