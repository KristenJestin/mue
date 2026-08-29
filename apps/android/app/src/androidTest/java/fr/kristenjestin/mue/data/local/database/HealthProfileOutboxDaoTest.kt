package fr.kristenjestin.mue.data.local.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.repository.DataStoreUserProfileRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * Gap 2: `health_profile` journals its writes now, in the transaction that makes them.
 *
 * Sync PRD 13.4 has called the profile a synchronised aggregate all along and
 * [SyncAggregateStateEntity.TYPE_HEALTH_PROFILE] already existed, but
 * `DataStoreUserProfileRepository.save` called a bare `healthProfileDao.upsert`. A height the
 * user typed was therefore a local write with no trace that it still had to be sent, and
 * FR-SYNC-001's "une mutation ne peut pas être perdue" held for measurements only.
 *
 * The transaction boundary is proved the same way [MeasurementOutboxDaoTest] proves its own:
 * by breaking the journal write and finding that the profile did not survive it.
 */
@RunWith(AndroidJUnit4::class)
class HealthProfileOutboxDaoTest {

    private lateinit var file: File
    private lateinit var profileStore: DataStore<Preferences>
    private lateinit var database: MueDatabase
    private lateinit var syncDao: SyncDao
    private lateinit var healthProfileDao: HealthProfileDao
    private lateinit var repository: DataStoreUserProfileRepository

    private val fixedNow = 1_770_000_000_000L
    private var nextId = 0

    @Before
    fun createStores() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "test_profile_outbox_${System.nanoTime()}.preferences_pb")
        profileStore = PreferenceDataStoreFactory.create { file }
        database = Room.inMemoryDatabaseBuilder(context, MueDatabase::class.java).build()
        syncDao = database.syncDao()
        healthProfileDao = database.healthProfileDao()
        repository = DataStoreUserProfileRepository(
            profileStore,
            healthProfileDao,
            SyncOutbox(newMutationId = { "mutation-${nextId++}" }, now = { fixedNow }),
        )
    }

    @After
    fun closeStores() {
        database.close()
        file.delete()
    }

    @Test
    fun savingAProfileLeavesExactlyOnePendingMutation() = runTest {
        repository.save(UserProfile("Kristen", 178, LocalDate.of(1990, 4, 12)))

        assertEquals(178, healthProfileDao.get()?.heightCm)

        val pending = syncDao.pendingMutations(10)
        assertEquals(1, pending.size)
        assertEquals(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, pending.single().aggregateType)
        assertEquals(HealthProfileEntity.ROW_ID, pending.single().aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, pending.single().op)
        assertEquals(
            """{"heightCm":178,"birthDate":"1990-04-12","sex":null}""",
            pending.single().payload,
        )
    }

    /**
     * Sync PRD 10.1 keeps the display name on the phone. Journalling it would send a field the
     * matrix says is not synchronised, so a name-only edit must produce a mutation that carries
     * the two synchronised fields and nothing else.
     */
    @Test
    fun theDisplayNameIsNotWhatIsJournalled() = runTest {
        repository.save(UserProfile("Kristen", null, null))

        val payload = syncDao.pendingMutations(10).single().payload
        assertEquals("""{"heightCm":null,"birthDate":null,"sex":null}""", payload)
        assertTrue("the display name must not reach the wire", payload?.contains("Kristen") != true)
    }

    /** The proof of the transaction boundary: a failing journal write takes the profile with it. */
    @Test
    fun aFailingJournalWriteTakesTheProfileWithIt() = runTest {
        syncDao.enqueueMutation(
            SyncOutbox(newMutationId = { "mutation-0" }, now = { fixedNow })
                .healthProfileUpsert(160, null, null)
        )
        nextId = 0

        val error = runCatching {
            repository.save(UserProfile("Kristen", 178, LocalDate.of(1990, 4, 12)))
        }.exceptionOrNull()

        assertTrue("expected a constraint failure, got $error", error is SQLiteConstraintException)
        assertNull("the write must not survive its own journal", healthProfileDao.get())
    }

    /**
     * PRD_SCALE FR-PROFILE-007 et 22 : le sexe vit dans `health_profile`, pas dans DataStore, et
     * rejoint l'agrégat synchronisé. L'arbitrage est celui de PRD_SCALE 21.1 renversé pour la
     * raison qui avait déjà fait déménager la taille : une donnée synchronisée doit pouvoir être
     * appliquée dans la même transaction que son curseur.
     */
    @Test
    fun theSexIsStoredInRoomAndJournalledWithTheProfile() = runTest {
        repository.save(UserProfile("Kristen", 178, LocalDate.of(1990, 4, 12), Sex.FEMALE))

        assertEquals("female", healthProfileDao.get()?.sex)
        assertEquals(
            """{"heightCm":178,"birthDate":"1990-04-12","sex":"female"}""",
            syncDao.pendingMutations(10).single().payload,
        )
        assertEquals(Sex.FEMALE, repository.profile.first().sex)
    }

    /** Un profil sans sexe reste parfaitement valide (FR-BODY-001) : c'est une absence. */
    @Test
    fun clearingTheSexIsAnUpsertStatingNull() = runTest {
        repository.save(UserProfile("Kristen", 178, null, Sex.MALE))
        syncDao.deleteMutation("mutation-0")

        repository.save(UserProfile("Kristen", 178, null, null))

        assertNull(healthProfileDao.get()?.sex)
        assertEquals(
            """{"heightCm":178,"birthDate":null,"sex":null}""",
            syncDao.pendingMutations(10).single().payload,
        )
        assertNull(repository.profile.first().sex)
    }

    /** The profile is an aggregate the server can acknowledge, so it needs its identity row. */
    @Test
    fun theProfileGetsItsAggregateStateRow() = runTest {
        repository.save(UserProfile(null, 178, null))

        val state = syncDao.aggregateState(
            SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
            HealthProfileEntity.ROW_ID,
        )
        assertNotNull(state)
        assertNull("nothing has been acknowledged yet", state?.revision)
        assertNull("the profile has no tombstone path (PRD 13.4)", state?.deletedAt)
        assertEquals("mutation-0", state?.lastMutationId)
    }

    /** PRD 13.3: the next mutation quotes the revision the server acknowledged. */
    @Test
    fun theSecondSaveQuotesTheAcknowledgedRevision() = runTest {
        repository.save(UserProfile(null, 178, null))
        syncDao.deleteMutation("mutation-0")
        syncDao.recordAcceptedRevision(
            aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
            aggregateId = HealthProfileEntity.ROW_ID,
            revision = 4L,
            mutationId = "mutation-0",
            serverUpdatedAt = fixedNow,
        )

        repository.save(UserProfile(null, 181, null))

        assertEquals(4L, syncDao.pendingMutations(10).single().baseRevision)
    }

    /** Clearing a field is an upsert stating null, never a delete: the profile always exists. */
    @Test
    fun clearingAFieldIsAnUpsertAndNotATombstone() = runTest {
        repository.save(UserProfile("Kristen", 178, LocalDate.of(1990, 4, 12)))
        syncDao.deleteMutation("mutation-0")

        repository.save(UserProfile("Kristen", 178, null))

        val pending = syncDao.pendingMutations(10).single()
        assertEquals(SyncMutationEntity.OP_UPSERT, pending.op)
        assertEquals("""{"heightCm":178,"birthDate":null,"sex":null}""", pending.payload)
        assertEquals(
            emptyList<String>(),
            syncDao.tombstones(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE).map { it.aggregateId },
        )
    }

    /** The measurement outbox and the profile outbox share one queue and one local sequence. */
    @Test
    fun theProfileAndTheMeasurementsShareOneOrderedQueue() = runTest {
        repository.save(UserProfile(null, 178, null))
        repository.save(UserProfile(null, 181, null))

        val pending = syncDao.pendingMutations(10)
        assertEquals(listOf("mutation-0", "mutation-1"), pending.map { it.mutationId })
        assertEquals(listOf(fixedNow, fixedNow + 1), pending.map { it.createdAt })
    }
}
