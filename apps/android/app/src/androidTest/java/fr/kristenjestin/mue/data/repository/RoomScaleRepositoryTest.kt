package fr.kristenjestin.mue.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.repository.ScaleRepository
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

/**
 * [ScaleRepository] contre du vrai SQLite (PRD_SCALE 9.3, 21.1).
 *
 * Les instants font l'aller-retour par des millisecondes entières, comme partout ailleurs dans
 * cette base : c'est le seul point que le mapping peut perdre, et le seul que ce fichier ajoute à
 * ce que `ScaleDaoTest` prouve déjà au niveau de la table.
 */
@RunWith(AndroidJUnit4::class)
class RoomScaleRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: ScaleRepository

    private val pairedAt: Instant = Instant.ofEpochMilli(1_770_000_000_000L)

    @Before
    fun createRepository() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        repository = RoomScaleRepository(database.scaleDao())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun roundTripsTheDomainModel() = runTest {
        val device = scale("scale-1", lastSeenAt = pairedAt.plusMillis(900))

        repository.save(device)

        assertEquals(device, repository.findById("scale-1"))
        assertEquals(listOf(device), repository.getAll())
        assertEquals(listOf(device), repository.observeAll().first())
    }

    @Test
    fun aScaleNeverSeenSincePairingRoundTripsItsAbsentContact() = runTest {
        repository.save(scale("scale-1", lastSeenAt = null))

        assertNull(repository.findById("scale-1")?.lastSeenAt)
        assertEquals(pairedAt, repository.findById("scale-1")?.createdAt)
    }

    @Test
    fun savingTheSameIdentifierTwiceKeepsOneScale() = runTest {
        repository.save(scale("scale-1"))
        repository.save(scale("scale-1").copy(displayName = "Bathroom"))

        assertEquals(1, repository.getAll().size)
        assertEquals("Bathroom", repository.findById("scale-1")?.displayName)
    }

    @Test
    fun renamingChangesOnlyTheGivenName() = runTest {
        repository.save(scale("scale-1"))

        repository.rename("scale-1", "Bathroom")

        assertEquals(scale("scale-1").copy(displayName = "Bathroom"), repository.findById("scale-1"))
    }

    /** FR-SCALE-001 : l'adresse et le nom annoncé sont des indices, l'identifiant est l'identité. */
    @Test
    fun seeingAScaleAgainRefreshesItsCluesWithoutTouchingItsIdentity() = runTest {
        repository.save(scale("scale-1"))
        repository.rename("scale-1", "Bathroom")

        repository.markSeen(
            id = "scale-1",
            address = "FF:10:00:1F:52:D9",
            advertisedName = "HB9027-B",
            at = pairedAt.plusMillis(900),
        )

        assertEquals(
            scale("scale-1").copy(
                address = "FF:10:00:1F:52:D9",
                advertisedName = "HB9027-B",
                displayName = "Bathroom",
                lastSeenAt = pairedAt.plusMillis(900),
            ),
            repository.findById("scale-1"),
        )
    }

    @Test
    fun forgettingAScaleRemovesIt() = runTest {
        repository.save(scale("scale-a"))
        repository.save(scale("scale-b", createdAt = pairedAt.plusMillis(1)))

        repository.forget("scale-a")

        assertNull(repository.findById("scale-a"))
        assertEquals(listOf("scale-b"), repository.getAll().map { it.id })
    }

    /**
     * PRD_SCALE 22 : les balances enregistrées ne sont pas synchronisées. `RoomScaleRepository`
     * n'accepte pas d'outbox du tout, et cette assertion vérifie la conséquence : le journal reste
     * vide quoi qu'on fasse à cette collection.
     */
    @Test
    fun noScaleWriteEverReachesTheOutbox() = runTest {
        repository.save(scale("scale-1"))
        repository.rename("scale-1", "Bathroom")
        repository.markSeen("scale-1", "FF:10:00:1F:52:D9", "HB9027", pairedAt.plusMillis(1))
        repository.forget("scale-1")

        assertTrue(database.syncDao().pendingMutations(10).isEmpty())
        assertEquals(0, database.syncDao().countInState(SyncMutationEntity.STATE_PENDING))
    }

    @Test
    fun anEmptyListReadsAsEmpty() = runTest {
        assertTrue(repository.getAll().isEmpty())
        assertTrue(repository.observeAll().first().isEmpty())
        assertNull(repository.findById("scale-1"))
    }

    private fun scale(
        id: String,
        lastSeenAt: Instant? = null,
        createdAt: Instant = pairedAt,
    ) = ScaleDevice(
        id = id,
        driverId = "homebuds-hb9027",
        address = "FF:10:00:1F:52:C3",
        advertisedName = "HB9027",
        displayName = "Homebuds HB9027",
        lastSeenAt = lastSeenAt,
        createdAt = createdAt,
    )
}
