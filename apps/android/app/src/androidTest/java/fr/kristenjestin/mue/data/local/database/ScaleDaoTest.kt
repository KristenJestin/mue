package fr.kristenjestin.mue.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `scale` : le cycle de vie d'une balance appairée, et les deux propriétés du schéma dont tout le
 * reste du module dépend (PRD_SCALE 9.3, 21.1).
 *
 * Les assertions de schéma sont écrites en **SQL brut** — `PRAGMA table_info`, `PRAGMA index_list`
 * — plutôt que contre les entités : un mapping Room peut faire passer une assertion sur un
 * `data class`, il ne peut rien faire passer sur ce que SQLite dit de sa propre table.
 */
@RunWith(AndroidJUnit4::class)
class ScaleDaoTest {

    private lateinit var database: MueDatabase
    private lateinit var dao: ScaleDao
    private lateinit var measurements: MeasurementDao

    private val pairedAt = 1_770_000_000_000L

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        dao = database.scaleDao()
        measurements = database.measurementDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertsAndReadsBackAPairedScale() = runTest {
        dao.upsert(scale("scale-1"))

        assertEquals(scale("scale-1"), dao.findById("scale-1"))
        assertEquals(1, dao.count())
        assertEquals(listOf("scale-1"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun readsBackInPairingOrder() = runTest {
        dao.upsert(scale("scale-b", createdAt = pairedAt + 10))
        dao.upsert(scale("scale-a", createdAt = pairedAt))

        assertEquals(listOf("scale-a", "scale-b"), dao.getAll().map { it.id })
        assertEquals(listOf("scale-a", "scale-b"), dao.observeAll().first().map { it.id })
    }

    /** FR-SCALE-015 : plusieurs balances peuvent coexister dans la liste. */
    @Test
    fun severalScalesCoexist() = runTest {
        dao.upsert(scale("scale-a", address = "FF:10:00:1F:52:C3"))
        dao.upsert(scale("scale-b", address = "FF:10:00:1F:52:C4", createdAt = pairedAt + 1))

        assertEquals(2, dao.count())
    }

    /**
     * FR-SCALE-013 : renommer ne touche à rien d'autre. C'est un `UPDATE` ciblé et non un upsert
     * de la ligne entière, sans quoi un renommage saisi pendant un scan réécrirait l'adresse avec
     * une valeur lue avant le scan et annulerait le contact le plus récent.
     */
    @Test
    fun renamingTouchesNothingElse() = runTest {
        dao.upsert(scale("scale-1"))
        dao.markSeen("scale-1", "FF:10:00:1F:52:FF", "HB9027", pairedAt + 500)

        dao.rename("scale-1", "Bathroom")

        val row = requireNotNull(dao.findById("scale-1"))
        assertEquals("Bathroom", row.displayName)
        assertEquals("FF:10:00:1F:52:FF", row.address)
        assertEquals(pairedAt + 500, row.lastSeenAt)
        assertEquals(pairedAt, row.createdAt)
    }

    /**
     * FR-SCALE-001 : l'adresse et le nom annoncé sont des **indices** rafraîchis à chaque
     * rencontre. L'identifiant, le nom donné et la date d'appairage ne bougent pas — c'est ce qui
     * permet à une balance dont l'adresse a changé de conserver son historique.
     */
    @Test
    fun seeingAScaleAgainRefreshesItsCluesAndNothingElse() = runTest {
        dao.upsert(scale("scale-1"))
        dao.rename("scale-1", "Bathroom")

        dao.markSeen("scale-1", "FF:10:00:1F:52:D9", "HB9027-B", pairedAt + 900)

        val row = requireNotNull(dao.findById("scale-1"))
        assertEquals("FF:10:00:1F:52:D9", row.address)
        assertEquals("HB9027-B", row.advertisedName)
        assertEquals(pairedAt + 900, row.lastSeenAt)
        assertEquals("Bathroom", row.displayName)
        assertEquals("homebuds-hb9027", row.driverId)
        assertEquals(pairedAt, row.createdAt)
    }

    @Test
    fun aScaleNeverSeenSinceItWasPairedHasNoLastContact() = runTest {
        dao.upsert(scale("scale-1", lastSeenAt = null))

        assertNull(dao.findById("scale-1")?.lastSeenAt)
    }

    @Test
    fun forgettingAScaleRemovesItAndNothingElseInThisTable() = runTest {
        dao.upsert(scale("scale-a"))
        dao.upsert(scale("scale-b", createdAt = pairedAt + 1))

        dao.delete("scale-a")

        assertNull(dao.findById("scale-a"))
        assertEquals(listOf("scale-b"), dao.getAll().map { it.id })
    }

    /**
     * BR-SCALE-010, prouvé au niveau du DAO : oublier une balance conserve la mesure **et** sa
     * provenance `scale`, et n'annule que l'identifiant devenu inutilisable. C'est la contrainte
     * `ON DELETE SET NULL` qui le fait, pas une ligne de Kotlin.
     */
    @Test
    fun forgettingAScaleKeepsItsMeasurementsAndOnlyClearsTheLink() = runTest {
        dao.upsert(scale("scale-1"))
        measurements.upsert(
            MeasurementEntity(
                date = "2026-08-23",
                weightCg = 7_450,
                sourceType = "scale",
                sourceScaleId = "scale-1",
                impedanceOhm = 512,
            )
        )

        dao.delete("scale-1")

        val row = requireNotNull(measurements.findByDate("2026-08-23")).measurement
        assertEquals(7_450, row.weightCg)
        assertEquals("scale", row.sourceType)
        assertNull("BR-SCALE-010: le lien seul est annulé", row.sourceScaleId)
        assertEquals(
            "l'impédance brute survit à l'oubli de la balance (BR-SCALE-008)",
            512,
            row.impedanceOhm,
        )
    }

    /** L'écriture d'une mesure ne peut pas inventer une balance qui n'existe pas. */
    @Test
    fun aMeasurementCannotPointAtAScaleThatWasNeverPaired() = runTest {
        val error = runCatching {
            measurements.upsert(
                MeasurementEntity(
                    date = "2026-08-23",
                    weightCg = 7_450,
                    sourceType = "scale",
                    sourceScaleId = "never-paired",
                )
            )
        }.exceptionOrNull()

        assertTrue("expected a foreign key failure, got $error", error != null)
        assertEquals(0, measurements.count())
    }

    @Test
    fun theTableDeclaresEveryColumnPrdScaleAsksFor() = runTest {
        val columns = database.openHelper.readableDatabase
            .query("PRAGMA table_info('scale')")
            .use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2)) }
            }

        assertEquals(
            mapOf(
                "id" to "TEXT",
                "driver_id" to "TEXT",
                "address" to "TEXT",
                "advertised_name" to "TEXT",
                "display_name" to "TEXT",
                "last_seen_at" to "INTEGER",
                "created_at" to "INTEGER",
            ),
            columns,
        )
    }

    @Test
    fun theIdentifierIsTheDeclaredPrimaryKeyAndTheAddressIsMerelyIndexed() = runTest {
        val readable = database.openHelper.readableDatabase

        val primaryKey = readable.query("PRAGMA table_info('scale')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getInt(5) > 0) add(cursor.getInt(5) to cursor.getString(1))
                }
            }
        }.sortedBy { it.first }.map { it.second }
        assertEquals(listOf("id"), primaryKey)

        // L'index de l'adresse n'est pas unique, et ne doit pas l'être : deux balances peuvent
        // annoncer la même adresse le temps qu'un rattachement soit proposé (FR-SCALE-001).
        val indices = readable.query("PRAGMA index_list('scale')").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(1), cursor.getInt(2) == 1)
                }
            }
        }
        assertEquals(false, indices["index_scale_address"])
    }

    /**
     * PRD_SCALE 22 : les balances enregistrées ne sont pas synchronisées. La table ne porte donc
     * aucune des colonnes de métadonnées que `sync_aggregate_state` détient pour les agrégats qui
     * le sont, et aucune écriture d'ici ne remplit l'outbox.
     */
    @Test
    fun theTableIsPurelyLocal() = runTest {
        val columns = database.openHelper.readableDatabase
            .query("PRAGMA table_info('scale')")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(1)) } }

        listOf("revision", "deleted_at", "origin_type", "origin_id", "last_mutation_id", "server_updated_at")
            .forEach { column ->
                assertFalse(
                    "scale must not duplicate sync_aggregate_state.$column",
                    columns.contains(column),
                )
            }

        dao.upsert(scale("scale-1"))
        dao.rename("scale-1", "Bathroom")
        dao.markSeen("scale-1", "FF:10:00:1F:52:D9", "HB9027", pairedAt + 1)
        dao.delete("scale-1")

        assertEquals(
            "aucune écriture de balance ne produit de mutation",
            0,
            database.syncDao().countInState(SyncMutationEntity.STATE_PENDING),
        )
    }

    @Test
    fun anEmptyTableReadsAsAnEmptyList() = runTest {
        assertEquals(0, dao.count())
        assertTrue(dao.getAll().isEmpty())
        assertTrue(dao.observeAll().first().isEmpty())
        assertNull(dao.findById("scale-1"))
    }

    private fun scale(
        id: String,
        address: String = "FF:10:00:1F:52:C3",
        lastSeenAt: Long? = null,
        createdAt: Long = pairedAt,
    ) = ScaleEntity(
        id = id,
        driverId = "homebuds-hb9027",
        address = address,
        advertisedName = "HB9027",
        displayName = "Homebuds HB9027",
        lastSeenAt = lastSeenAt,
        createdAt = createdAt,
    )
}
