package fr.kristenjestin.mue.data.local.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.data.repository.RoomMeasurementRepository
import fr.kristenjestin.mue.data.repository.RoomScaleRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.Sex
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
import java.time.Instant
import java.time.LocalDate

private const val SCALE_TEST_DATABASE = "mue-scale-migration-test.db"

/** Les dix-huit tables qu'un fichier de version 6 tient déjà ; aucune ne peut disparaître. */
private val TABLES_ALREADY_THERE_AT_VERSION_SIX = listOf(
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
    "food",
    "recipe",
    "recipe_ingredient",
    "food_log_entry",
    "meal_plan_entry",
)

/**
 * PRD_SCALE 21.1 : les deux tables du module balance et les quatre colonnes qu'il ajoute arrivent
 * sur la base déjà présente sur le téléphone, **sans qu'un seul poids ne soit réécrit**.
 *
 * Chaque test part d'un fichier de **version 1** authentique, en dixièmes de kilogramme, et
 * rejoue les six migrations. `FoodMigrationTest` donne la raison et elle n'a fait que se
 * renforcer : un téléphone installé en version 1 n'a jamais vu la version 6 comme état de départ,
 * donc prouver 6 → 7 seule laisserait la plus ancienne installation — celle qui a le plus
 * d'historique à perdre — sans couverture. Cette version-ci a une raison de plus : c'est la
 * première depuis 1 → 2 à **recréer `measurements`**, donc la première qui pourrait défaire
 * l'arithmétique de 1 → 2.
 *
 * `fallbackToDestructiveMigration` ferait passer chacune de ces assertions sur un fichier vide.
 * Il est interdit dans tout le projet, et [theDestructiveMigrationIsNeverTaken] le vérifie plutôt
 * que de le supposer.
 */
@RunWith(AndroidJUnit4::class)
class ScaleMigrationTest {

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
    fun everyWeightSurvivesAllSixMigrations() {
        val rows = migrateFromVersionOne().readMeasurements()

        assertEquals("no measurement may be lost", seeded.size, rows.size)
        assertEquals(seeded.map { (date, tenths) -> date to tenths * 10 }, rows)
    }

    /** 74.5 kg doit toujours valoir 74.5 kg six migrations plus tard, ni 7.45 kg ni 745 kg. */
    @Test
    fun theWeightsAreStillHundredthsAtVersionSeven() {
        val hundredths = migrateFromVersionOne().readMeasurements().toMap()

        assertEquals(9_030, hundredths.getValue("2018-04-09"))
        assertEquals(8_120, hundredths.getValue("2019-11-03"))
        assertEquals(3_000, hundredths.getValue("2020-01-01"))
        assertEquals(25_000, hundredths.getValue("2024-06-15"))
        assertEquals(7_450, hundredths.getValue("2026-08-23"))
    }

    /**
     * PRD_SCALE 21.1 : « `manual` par défaut pour tout l'historique existant ». C'est le point de
     * la version — la recréation de `measurements` doit remplir la colonne, pas la laisser vide.
     */
    @Test
    fun everyExistingMeasurementArrivesAsAManualEntry() {
        val database = migrateFromVersionOne()

        val rows = database
            .query("SELECT date, source_type, source_scale_id, impedance_ohm FROM measurements ORDER BY date ASC")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            listOf(
                                cursor.getString(0),
                                cursor.getString(1),
                                if (cursor.isNull(2)) null else cursor.getString(2),
                                if (cursor.isNull(3)) null else cursor.getInt(3).toString(),
                            )
                        )
                    }
                }
            }

        assertEquals(seeded.size, rows.size)
        rows.forEach { row ->
            assertEquals("source_type de ${row[0]}", "manual", row[1])
            assertNull("aucune balance n'a jamais existé sur ce fichier", row[2])
            assertNull("aucune impédance n'a jamais été mesurée", row[3])
        }
    }

    /** La valeur par défaut vit dans le schéma, pas seulement dans le code Kotlin. */
    @Test
    fun theProvenanceColumnCarriesItsDefaultInTheSchema() {
        val database = migrateFromVersionOne()

        database.execSQL("INSERT INTO measurements (date, weight_cg) VALUES ('2027-01-01', 7000)")

        assertEquals(
            "manual",
            database.query("SELECT source_type FROM measurements WHERE date = '2027-01-01'")
                .use { cursor -> cursor.moveToNext(); cursor.getString(0) },
        )
        assertTrue(database.notNullColumns("measurements").contains("source_type"))
    }

    @Test
    fun allEighteenTablesTheEarlierVersionsBuiltAreStillThere() {
        val tables = migrateFromVersionOne().tableNames()

        assertEquals(18, TABLES_ALREADY_THERE_AT_VERSION_SIX.size)
        TABLES_ALREADY_THERE_AT_VERSION_SIX.forEach { table ->
            assertTrue("$table is missing from $tables", tables.contains(table))
        }
    }

    @Test
    fun theChainedMigrationCreatesTheTwoScaleTables() {
        val tables = migrateFromVersionOne().tableNames()

        assertTrue("scale is missing from $tables", tables.contains("scale"))
        assertTrue("body_composition is missing from $tables", tables.contains("body_composition"))
    }

    /**
     * Le nom de la table intermédiaire ne doit rester nulle part : `validateDroppedTables` le
     * dirait, et l'assertion le dit aussi en clair pour que l'échec se lise.
     */
    @Test
    fun theIntermediateTableIsGone() {
        val tables = migrateFromVersionOne().tableNames()

        assertFalse("measurements_v7 survived the migration", tables.contains("measurements_v7"))
    }

    @Test
    fun theTwoNewTablesArriveEmpty() {
        val database = migrateFromVersionOne()

        assertEquals(0, database.countOf("scale"))
        assertEquals(0, database.countOf("body_composition"))
    }

    /**
     * BR-SCALE-010 en SQL : la clé étrangère existe, elle est en `SET NULL`, et elle est indexée.
     * Sans l'index, oublier une balance serait un balayage complet de l'historique.
     */
    @Test
    fun theScaleReferenceIsASetNullForeignKeyWithItsOwnIndex() {
        val database = migrateFromVersionOne()

        assertEquals(
            listOf(Fk("scale", "source_scale_id", "id", "SET NULL", "NO ACTION")),
            database.foreignKeys("measurements"),
        )
        assertTrue(
            database.indexNames("measurements").toString(),
            database.indexNames("measurements").contains("index_measurements_source_scale_id"),
        )
    }

    @Test
    fun theCompositionIsKeyedAndConstrainedByItsMeasurementDate() {
        val database = migrateFromVersionOne()

        assertEquals(listOf("date"), database.primaryKeyColumns("body_composition"))
        assertEquals(
            listOf(Fk("measurements", "date", "date", "CASCADE", "CASCADE")),
            database.foreignKeys("body_composition"),
        )
    }

    @Test
    fun theHealthProfileGainsItsNullableSex() {
        val database = migrateFromVersionOne()

        assertEquals("TEXT", database.columnTypes("health_profile").getValue("sex"))
        assertFalse(
            "un profil sans sexe reste valide (FR-BODY-001)",
            database.notNullColumns("health_profile").contains("sex"),
        )
    }

    /** PRD_SCALE 22 : `scale` est purement locale, donc sans métadonnée de synchronisation. */
    @Test
    fun theTwoNewTablesCarryNoSynchronisationColumn() {
        val database = migrateFromVersionOne()
        val syncColumns = listOf(
            "revision",
            "deleted_at",
            "origin_type",
            "origin_id",
            "last_mutation_id",
            "server_updated_at",
        )

        listOf("scale", "body_composition").forEach { table ->
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

    /** PRD_FOOD 13.1 et PRD BR-003, réaffirmés sur tout le fichier après une table recréée. */
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
    fun theChainedMigrationDoesNotReseedTheExerciseCatalogue() {
        assertEquals(
            ExerciseCatalogSeed.DEFINITIONS.size,
            migrateFromVersionOne().countOf("exercise_definitions"),
        )
    }

    @Test
    fun anEmptyVersionOneDatabaseArrivesEmptyAtVersionSeven() {
        helper.createDatabase(SCALE_TEST_DATABASE, 1).close()
        val database = migrate()

        assertTrue(database.readMeasurements().isEmpty())
        assertEquals(0, database.countOf("scale"))
        assertEquals(0, database.countOf("body_composition"))
        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, database.countOf("exercise_definitions"))
    }

    /**
     * **La migration destructive n'est jamais empruntée.**
     *
     * Room n'ouvrirait un fichier qu'il ne sait pas migrer qu'en le vidant, et `MueDatabase.build`
     * n'active cette voie sous aucune forme (PRD 16.3, 20.3). La preuve est un fichier réel : la
     * base est migrée depuis la version 1, rouverte par le vrai constructeur — celui de
     * l'application, avec `MueMigrations.ALL` — et l'historique est recompté. Un repli destructif
     * rendrait ce compte nul, et toutes les autres assertions de ce fichier vertes.
     */
    @Test
    fun theDestructiveMigrationIsNeverTaken() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            assertEquals(seeded.size, database.measurementDao().getAll().size)
            assertEquals(
                7_450,
                database.measurementDao().findByDate("2026-08-23")?.measurement?.weightCg,
            )
        }
    }

    /** L'historique migré doit être lisible par l'application qui ouvre le fichier. */
    @Test
    fun theMigratedFileIsUsableThroughTheRealDaos() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val measurements = database.measurementDao().getAll()
            assertEquals(seeded.size, measurements.size)
            measurements.forEach { row ->
                assertEquals(MeasurementSource.MANUAL.wireValue, row.measurement.sourceType)
                assertNull(row.composition)
            }
            assertEquals(0, database.scaleDao().count())
            assertEquals(0, database.measurementDao().compositionCount())
        }
    }

    /**
     * Le point de la version, sur un fichier réellement mis à jour : une pesée reçue d'une balance
     * s'écrit avec sa provenance, son impédance et sa composition, et oublier la balance conserve
     * tout sauf le lien (BR-SCALE-010).
     */
    @Test
    fun aScaleMeasurementSurvivesForgettingItsScaleOnTheMigratedFile() = runTest {
        migrateFromVersionOne().close()

        withMigratedDatabase { database ->
            val scales = RoomScaleRepository(database.scaleDao())
            val measurements = RoomMeasurementRepository(database.measurementDao(), SyncOutbox())
            scales.save(
                ScaleDevice(
                    id = "scale-1",
                    driverId = "homebuds-hb9027",
                    address = "FF:10:00:1F:52:C3",
                    advertisedName = "HB9027",
                    displayName = "Homebuds HB9027",
                    lastSeenAt = null,
                    createdAt = Instant.ofEpochMilli(1_770_000_000_000L),
                ),
            )
            measurements.save(scaleMeasurement())

            scales.forget("scale-1")

            val stored = requireNotNull(measurements.findByDate(LocalDate.parse("2026-08-24")))
            assertEquals(MeasurementSource.SCALE, stored.source)
            assertNull("BR-SCALE-010", stored.sourceScaleId)
            assertEquals(512, stored.impedanceOhm)
            assertNotNull("la composition survit à l'oubli de la balance", stored.bodyComposition)
            assertEquals(seeded.size + 1, measurements.getAll().size)
        }
    }

    private fun scaleMeasurement(): Measurement = Measurement(
        date = LocalDate.parse("2026-08-24"),
        weight = Weight.ofHundredthsClamped(7_845),
        source = MeasurementSource.SCALE,
        sourceScaleId = "scale-1",
        impedanceOhm = 512,
        bodyComposition = BodyComposition(
            date = LocalDate.parse("2026-08-24"),
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

    private inline fun withMigratedDatabase(block: (MueDatabase) -> Unit) {
        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
            SCALE_TEST_DATABASE,
        ).addMigrations(*MueMigrations.ALL).addCallback(ExerciseCatalogSeed.CALLBACK).build()

        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun migrateFromVersionOne(): SupportSQLiteDatabase {
        helper.createDatabase(SCALE_TEST_DATABASE, 1).use { db ->
            seeded.forEach { (date, tenths) ->
                db.execSQL("INSERT INTO measurements (date, weight_dg) VALUES ('$date', $tenths)")
            }
        }
        return migrate()
    }

    /** `validateDroppedTables` is on: a leftover table would fail the run, not pass unnoticed. */
    private fun migrate(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(SCALE_TEST_DATABASE, 7, true, *MueMigrations.ALL)

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

    private fun SupportSQLiteDatabase.foreignKeys(table: String): List<Fk> =
        query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Fk(
                            table = cursor.getString(cursor.getColumnIndexOrThrow("table")),
                            from = cursor.getString(cursor.getColumnIndexOrThrow("from")),
                            to = cursor.getString(cursor.getColumnIndexOrThrow("to")),
                            onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                            onUpdate = cursor.getString(cursor.getColumnIndexOrThrow("on_update")),
                        )
                    )
                }
            }
        }

    /** Une clé étrangère telle que `PRAGMA foreign_key_list` la décrit, actions comprises. */
    private data class Fk(
        val table: String,
        val from: String,
        val to: String,
        val onDelete: String,
        val onUpdate: String,
    )
}
