package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes
import fr.kristenjestin.mue.data.remote.sync.SyncTransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The row on the owner's phone, and the state no run of this application could get it out of.
 *
 * ## What he was looking at
 *
 * `Server settings`, paired to `192.168.1.100:3000` as `kris@mue.home.arpa`:
 *
 * ```
 * Sync issue · Never synchronised
 * 1 change waiting to be sent
 * Every mutation needs a readable UUIDv7 `mutationId`. (sync.invalid_payload)
 * ```
 *
 * and behind it one row, journalled by a build that predates `MutationIds`:
 *
 * ```
 * mutation_id     4317e938-539e-4c48-abd5-27311fb39b74      ← the version nibble is 4
 * aggregate_type  healthProfile   aggregate_id  "me"   op  upsert
 * payload         {"heightCm":171,"birthDate":"1998-11-18"}
 * state           pending         attempt_count 0
 * ```
 *
 * ## Why no retry could ever help
 *
 * `submitMutations` in `packages/domain/src/sync/push.ts` reads every `mutationId` in a push
 * **before it opens a transaction**, and throws for the whole batch if one of them is not a
 * `mutationIdSchema` value. So this is not a rejected mutation, which FR-SYNC-007 would mark
 * `failed` and step over: it is a 400 on the request, which `KtorSyncApi` raises as a
 * [SyncTransportException] and `SyncEngine.push` unwinds through its `finally`, putting every
 * row back to `pending` with its `attempt_count` untouched. The counter never moves, the queue
 * never drains, and one legacy row takes every correctly-minted row queued behind it down with
 * it on every single run.
 *
 * Fixing the *mint* — which `MutationIds` did — cannot reach a row that was already written. The
 * three assertions below are about the row that was, which is why they are here and not in
 * `MutationIdsTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyOutboxRowTest {

    private val now = 1_770_000_100_000L

    /** `z.uuidv7()`, restated — the same rule `MutationIdsTest` applies to what is minted. */
    private val uuidV7 = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )

    /** Two more of the same vintage, so an ordering assertion has something to order. */
    private val olderLegacyId = "4317e938-539e-4c48-abd5-27311fb39b01"
    private val newerLegacyId = "4317e938-539e-4c48-abd5-27311fb39b02"

    /**
     * The whole defect in one assertion: what actually goes on the wire.
     *
     * The push is scripted to fail with the exact error the live server answers, so that the
     * test says nothing about the response and everything about the request. That is the right
     * place to look: a row that leaves the phone carrying `4317e938-…-4c48-…` is refused by
     * every conforming server that will ever exist, and no amount of retrying, re-pairing or
     * reinstalling changes what the phone sends.
     */
    @Test
    fun theRowTheOwnerCouldNotSendLeavesThePhoneWithAnIdentifierAServerCanRead() = runTest {
        val store = FakeSyncStore(mutations = listOf(hisRow()))
        val api = ScriptedSyncApi().onPushFail(theServersAnswer())

        engine(store, api).sync()

        val sent = api.pushRequests.single().mutations.single().mutationId
        assertNotEquals(
            SyncFixtures.LEGACY_V4_MUTATION_ID,
            sent,
            "the phone sent the identifier the server refuses before it looks at the payload",
        )
        assertTrue(uuidV7.matches(sent), "still not a UUIDv7: $sent")
    }

    /**
     * A repair, and not a rewrite.
     *
     * The identifier is the only thing that may change. The payload is a change the user made
     * and FR-SYNC-007 forbids touching it to fix an error; `created_at` is the outbox's local
     * sequence and moving it would reorder the queue; `attempt_count` is history.
     */
    @Test
    fun nothingAboutTheRowChangesExceptItsName() = runTest {
        val store = FakeSyncStore(mutations = listOf(hisRow()))

        // Constructing the engine is what repairs, exactly as it is what recovers `inflight`
        // rows: a phone that is out of range must come back with a queue that can drain.
        engine(store, ScriptedSyncApi())
        advanceUntilIdle()

        val repaired = store.rowsInState(SyncMutationEntity.STATE_PENDING).single()
        assertTrue(uuidV7.matches(repaired.mutationId), "not a UUIDv7: ${repaired.mutationId}")
        assertEquals("healthProfile", repaired.aggregateType)
        assertEquals("me", repaired.aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, repaired.op)
        assertEquals("""{"heightCm":171,"birthDate":"1998-11-18"}""", repaired.payload)
        assertEquals(1_770_000_000_000L, repaired.createdAt)
        assertEquals(0, repaired.attemptCount)
    }

    /**
     * Two legacy rows keep the order they were queued in.
     *
     * It matters for the one case the outbox is ordered for at all: the two mutations of a
     * measurement whose date moved are a delete of the old date and an upsert of the new one,
     * and sending the upsert first would have the server apply a deletion to the row it had just
     * created. A repair that re-minted identifiers and let `created_at` follow them — or that
     * re-inserted rows rather than updating them — would do exactly that.
     */
    @Test
    fun aQueueOfLegacyRowsKeepsItsOrder() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementDelete(olderLegacyId, createdAt = 1_000),
                SyncFixtures.measurementUpsert(newerLegacyId, createdAt = 2_000),
            ),
        )

        engine(store, ScriptedSyncApi())
        advanceUntilIdle()

        assertEquals(
            listOf(SyncMutationEntity.OP_DELETE, SyncMutationEntity.OP_UPSERT),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.op },
        )
        assertEquals(
            listOf(1_000L, 2_000L),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.createdAt },
        )
    }

    /** His row, to the character. */
    private fun hisRow(): SyncMutationEntity =
        SyncFixtures.healthProfileUpsert(SyncFixtures.LEGACY_V4_MUTATION_ID)

    /**
     * What `https://192.168.1.100:3000` really answers, down to the sentence: a 400 for the
     * request, not a rejection for the mutation. `KtorSyncApi` maps it to this.
     */
    private fun theServersAnswer() = SyncTransportException(
        code = SyncErrorCodes.SYNC_INVALID_PAYLOAD,
        message = "Every mutation needs a readable UUIDv7 `mutationId`.",
        retryable = false,
    )

    private fun TestScope.engine(store: SyncStore, api: ScriptedSyncApi) =
        SyncEngine(store = store, api = api, now = { now }, scope = this)
}
