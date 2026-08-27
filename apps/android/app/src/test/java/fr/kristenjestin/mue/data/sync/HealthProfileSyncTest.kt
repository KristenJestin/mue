package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.InMemoryHealthProfileDao
import fr.kristenjestin.mue.data.local.database.InMemoryJournal
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.local.datastore.FakePreferencesDataStore
import fr.kristenjestin.mue.data.remote.sync.HealthProfileUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.HealthProfileUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.PushRequestDto
import fr.kristenjestin.mue.data.remote.sync.SyncJson
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.data.remote.sync.WIRE_HEALTH_PROFILE_AGGREGATE_ID
import fr.kristenjestin.mue.data.repository.DataStoreUserProfileRepository
import fr.kristenjestin.mue.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The health profile, end to end on the JVM: saved, journalled, sent, acknowledged, gone from
 * the outbox, and applied again when it comes back.
 *
 * The state this file was written against is worth stating exactly, because it is what the
 * owner saw. One row in `sync_mutations` — `aggregate_type "healthProfile"`, `payload
 * {"heightCm":171,"birthDate":"1998-11-18"}`, `state "pending"`, **`attempt_count 0`** — beside
 * a `sync_state` that was paired, had a cursor, had a `last_success_at` and had no error. The
 * zero is the tell: the row was never *attempted*. `SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES`
 * filtered it out before the send window was taken, because `AGGREGATE_TYPES` in
 * `packages/contracts` was `["measurement"]`. So `Data & sync` showed `1 change waiting` for
 * ever — and a counter that never falls is indistinguishable from a fault.
 *
 * Every test below would have failed then and passes now, and none of them needs an emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthProfileSyncTest {

    private val now = 1_770_000_100_000L

    private fun engine(store: SyncStore, api: ScriptedSyncApi, scope: TestScope) =
        SyncEngine(store = store, api = api, now = { now }, scope = scope)

    /**
     * The owner's actual row, drained.
     *
     * It asserts on the *envelope* rather than only on a count, because the two things that made
     * the row undeliverable are both visible there: the branch it takes and the identifier it
     * carries.
     */
    @Test
    fun theProfileRowTheOwnerCouldNotSendIsPushedAndLeavesTheOutbox() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(SyncFixtures.healthProfileUpsert("hp-1", createdAt = 1_000)),
        )
        val api = ScriptedSyncApi()
            .onPush(SyncFixtures.pushResponse(SyncFixtures.applied("hp-1", revision = "1")))
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        val outcome = engine(store, api, this).sync()

        val completed = assertIs<SyncOutcome.Completed>(outcome)
        assertEquals(1, completed.applied)
        assertEquals(0, completed.deferred, "the profile is no longer held back")
        assertEquals(0, completed.rejected)
        assertTrue(!completed.hasIssues)

        val sent = assertIs<HealthProfileUpsertMutationDto>(
            api.pushRequests.single().mutations.single(),
        )
        assertEquals("hp-1", sent.mutationId)
        assertEquals(WIRE_HEALTH_PROFILE_AGGREGATE_ID, sent.aggregateId)
        assertEquals(171, sent.payload.heightCm)
        assertEquals("1998-11-18", sent.payload.birthDate)

        // The counter the owner was looking at, at zero.
        assertEquals(
            emptyList(),
            store.rowsInState(SyncMutationEntity.STATE_PENDING),
            "`1 change waiting` has to be able to reach zero",
        )
        assertEquals(null, store.row("hp-1"))
        assertEquals(
            1L,
            store.revisions[SyncAggregateStateEntity.TYPE_HEALTH_PROFILE to "me"],
            "the accepted revision is recorded, so the next edit quotes a base",
        )
    }

    /** The body on the wire, exactly. Both discriminators, and both values, as text. */
    @Test
    fun theEnvelopeOnTheWireCarriesBothDiscriminatorsAndBothValues() {
        val envelope = checkNotNull(
            SyncWire.toEnvelope(
                SyncFixtures.healthProfileUpsert("0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6071"),
                SyncWire.androidOrigin("device-7f3c1a04"),
            ),
        )

        val text = SyncJson.instance.encodeToString(
            serializer<PushRequestDto>(),
            PushRequestDto(listOf(envelope)),
        )

        for (
            fragment in listOf(
                "\"op\":\"upsert\"",
                "\"aggregateType\":\"healthProfile\"",
                "\"aggregateId\":\"me\"",
                "\"heightCm\":171",
                "\"birthDate\":\"1998-11-18\"",
                "\"payloadSchemaVersion\":1",
            )
        ) {
            assertTrue(text.contains(fragment), "the body must carry $fragment: $text")
        }
    }

    /**
     * A profile arriving from the server lands on the one local row, whatever was there before.
     *
     * `SyncWire.healthProfileEntity` supplies [HealthProfileEntity.ROW_ID] rather than the
     * change's `aggregateId`, which is the client half of PRD 13.4's "un agrégat unique": there
     * is no path by which a second device's change inserts a rival row.
     */
    @Test
    fun aProfileReceivedFromTheServerReplacesTheOneLocalRow() = runTest {
        val journal = InMemoryJournal()
        val profiles = InMemoryHealthProfileDao(journal)
        profiles.upsert(HealthProfileEntity(heightCm = 180, birthDate = null))

        val change = assertIs<HealthProfileUpsertChangeDto>(
            SyncFixtures.healthProfileChange("7", heightCm = 171, birthDate = "1998-11-18"),
        )
        profiles.upsert(SyncWire.healthProfileEntity(change.payload))

        val stored = checkNotNull(profiles.get())
        assertEquals(HealthProfileEntity.ROW_ID, stored.id)
        assertEquals(171, stored.heightCm)
        assertEquals("1998-11-18", stored.birthDate)
    }

    /**
     * The two halves that have to agree: what the outbox writes and what the wire reads.
     *
     * `HealthProfilePayload` and `HealthProfilePayloadV1Dto` are two hand-written shapes in two
     * files, and the only thing binding them is that a payload written by one is decoded by the
     * other. Feeding a real height and a real birth date through both is the check a shape
     * comparison cannot make — the lesson `MutationIds` left behind, where a UUIDv4 and a UUIDv7
     * round-tripped identically and every push was refused.
     */
    @Test
    fun whatTheOutboxWritesIsWhatTheWireReads() {
        val outbox = SyncOutbox(newMutationId = { "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6071" })
        val row = outbox.healthProfileUpsert(heightCm = 171, birthDate = LocalDate.of(1998, 11, 18))

        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(row, SyncWire.androidOrigin("device-7f3c1a04")),
        )

        assertEquals(171, envelope.payload.heightCm)
        assertEquals("1998-11-18", envelope.payload.birthDate)
        // Parsed, not matched: `z.iso.date()` validates the calendar on the server and this is
        // the same rule on this side, so a payload of "1998-11-31" fails here rather than there.
        assertEquals(LocalDate.of(1998, 11, 18), LocalDate.parse(envelope.payload.birthDate))
    }

    /** A cleared profile survives the same crossing, with its nulls intact rather than dropped. */
    @Test
    fun aClearedProfileCrossesAsNullsRatherThanAsAnEmptyPayload() {
        val outbox = SyncOutbox(newMutationId = { "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6072" })
        val row = outbox.healthProfileUpsert(heightCm = null, birthDate = null)

        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(row, SyncWire.androidOrigin("device-7f3c1a04")),
        )

        assertEquals(null, envelope.payload.heightCm)
        assertEquals(null, envelope.payload.birthDate)
    }

    /**
     * Saving a profile through the repository journals a row a send can actually select.
     *
     * This is the path the owner's phone took, minus the socket: `ProfileScreen` calls `save`,
     * the DAO writes the business row and the outbox row in one transaction (FR-SYNC-001), and
     * the queue a push reads from now contains it — which is the single line that changed.
     */
    @Test
    fun savingAProfileJournalsARowThatASendWouldSelect() = runTest {
        val journal = InMemoryJournal()
        val profiles = InMemoryHealthProfileDao(journal)
        val repository = DataStoreUserProfileRepository(
            FakePreferencesDataStore(),
            profiles,
            SyncOutbox(newMutationId = { "hp-1" }, now = { 1_000L }),
            Dispatchers.Unconfined,
        )

        repository.save(
            UserProfile(
                displayName = "Kris",
                heightCm = 171,
                birthDate = LocalDate.of(1998, 11, 18),
            ),
        )

        val pending = journal.pendingOfTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES, limit = 200)
        assertEquals(listOf("hp-1"), pending.map { it.mutationId })
        assertEquals(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, pending.single().aggregateType)
        assertEquals(HealthProfileEntity.ROW_ID, pending.single().aggregateId)

        val profile = repository.profile.first()
        assertEquals(171, profile.heightCm)
        assertEquals(LocalDate.of(1998, 11, 18), profile.birthDate)
    }
}
