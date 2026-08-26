package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.InMemoryHealthProfileDao
import fr.kristenjestin.mue.data.local.database.InMemoryJournal
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.local.datastore.FakePreferencesDataStore
import fr.kristenjestin.mue.data.sync.PAYLOAD_SCHEMA_VERSION
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gap 2, on the JVM: **`health_profile` journals through the outbox.**
 *
 * `DataStoreUserProfileRepository.save` called a bare `healthProfileDao.upsert`. Sync PRD 13.4
 * has called the profile a synchronised aggregate all along and
 * [SyncAggregateStateEntity.TYPE_HEALTH_PROFILE] already existed, but a height the user typed was
 * a local write with **no trace that it still had to be sent** — so FR-SYNC-001's "une mutation
 * ne peut pas être perdue" held for measurements only, and the profile silently did not
 * synchronise at all.
 *
 * The write now goes through `HealthProfileDao.upsertWithMutation`, a `@Transaction` default
 * method. Being a default method is what lets this run without a device: the code exercised below
 * is the shipped code, with the queries around it in memory (see `InMemoryJournal`). What stays
 * instrumented is the transaction itself — "the profile did not survive a journal write that
 * failed" is a statement about SQLite, and `HealthProfileOutboxDaoTest` makes it.
 */
class UserProfileJournalTest {

    private val journal = InMemoryJournal()
    private val profiles = InMemoryHealthProfileDao(journal)
    private val preferences = FakePreferencesDataStore()

    private var nextId = 0
    private val fixedNow = 1_770_000_000_000L

    private val repository = DataStoreUserProfileRepository(
        preferences,
        profiles,
        SyncOutbox(newMutationId = { "mutation-${nextId++}" }, now = { fixedNow }),
        Dispatchers.Unconfined,
    )

    @Test
    fun savingAProfileLeavesExactlyOnePendingMutation() = runTest {
        repository.save(UserProfile("Kristen", 178, LocalDate.of(1990, 4, 12)))

        assertEquals(178, profiles.get()?.heightCm)

        val pending = journal.pending().single()
        assertEquals(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, pending.aggregateType)
        assertEquals(HealthProfileEntity.ROW_ID, pending.aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, pending.op)
        assertEquals(SyncMutationEntity.STATE_PENDING, pending.state)
        assertEquals(PAYLOAD_SCHEMA_VERSION, pending.payloadSchemaVersion)
        assertEquals("""{"heightCm":178,"birthDate":"1990-04-12"}""", pending.payload)
    }

    /**
     * Sync PRD 10.1 keeps the display name on the phone. Journalling it would send a field the
     * contract does not carry and the user did not agree to share.
     */
    @Test
    fun theDisplayNameIsNotWhatIsJournalled() = runTest {
        repository.save(UserProfile("Kristen", 178, null))

        val payload = assertNotNull(journal.pending().single().payload)
        assertTrue(!payload.contains("Kristen"), payload)
        assertTrue(!payload.contains("displayName"), payload)
    }

    /**
     * PRD 13.4 gives the profile no deletion: clearing a height is an upsert whose payload says
     * null, which the server can merge field by field. A tombstone would claim the profile itself
     * had ceased to exist, and FR-SYNC-005 would then use it to block its own resurrection.
     */
    @Test
    fun clearingAFieldIsAnUpsertAndNotATombstone() = runTest {
        repository.save(UserProfile("Kristen", 178, LocalDate.of(1990, 4, 12)))
        repository.save(UserProfile("Kristen", null, null))

        val rows = journal.pending()
        assertEquals(listOf(SyncMutationEntity.OP_UPSERT, SyncMutationEntity.OP_UPSERT), rows.map { it.op })
        assertEquals("""{"heightCm":null,"birthDate":null}""", rows[1].payload)
        assertNull(
            journal.state(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, HealthProfileEntity.ROW_ID)
                ?.deletedAt,
            "a cleared field is not a deleted aggregate",
        )
    }

    /**
     * PRD 12.2's `baseRevision`, read inside the write rather than passed in. A second edit that
     * quoted the revision it had before the first was acknowledged would be refused by PRD 13.3
     * as an update founded on an old revision.
     */
    @Test
    fun theSecondSaveQuotesTheAcknowledgedRevision() = runTest {
        repository.save(UserProfile("Kristen", 178, null))
        assertNull(journal.mutation("mutation-0")?.baseRevision, "a creation quotes nothing")

        journal.recordAcceptedRevision(
            SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
            HealthProfileEntity.ROW_ID,
            revision = 4L,
        )
        repository.save(UserProfile("Kristen", 181, null))

        assertEquals(4L, journal.mutation("mutation-1")?.baseRevision)
    }

    /** The aggregate gets its identity row, which is what a revision is later written into. */
    @Test
    fun theProfileGetsItsAggregateStateRow() = runTest {
        repository.save(UserProfile("Kristen", 178, null))

        val state = assertNotNull(
            journal.state(
                SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                HealthProfileEntity.ROW_ID,
            ),
        )
        assertNull(state.revision, "null until the server has accepted a mutation for it")
        assertEquals("mutation-0", state.lastMutationId)
    }

    /** The profile still reads back as one object assembled from the two stores (PRD 11.2). */
    @Test
    fun theProfileReadsBackFromBothStores() = runTest {
        repository.save(UserProfile("Kristen", 178, LocalDate.of(1990, 4, 12)))

        assertEquals(
            UserProfile("Kristen", 178, LocalDate.of(1990, 4, 12)),
            repository.profile.first(),
        )
    }
}
