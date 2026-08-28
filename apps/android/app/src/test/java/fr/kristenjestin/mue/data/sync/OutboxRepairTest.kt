package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which stored rows may be given a new identifier, and which may not.
 *
 * A mutation id is FR-SYNC-006's idempotency key: the same id on every retry of the same send is
 * what has the server replay its stored result rather than apply the change a second time.
 * Changing an id the server has already seen would double-apply a change — a duplicate weight,
 * silently. So every case below is a safety case, and the two halves of [OutboxRepair]'s
 * argument are asserted separately: *this row cannot have been accepted*, and *this row is not
 * inside a request*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxRepairTest {

    private val now = 1_770_000_100_000L

    private val legacy = SyncFixtures.LEGACY_V4_MUTATION_ID
    private val sound = SyncFixtures.mutationId(1)

    // --- the rule ------------------------------------------------------------------------------

    /**
     * The owner's row: `pending`, never attempted, carrying a v4. The one case the pass exists
     * for.
     */
    @Test
    fun aPendingRowWithAnIdentifierTheContractRefusesIsReminted() {
        assertEquals(
            OutboxRepair.Verdict.REMINT,
            OutboxRepair.verdict(SyncMutationEntity.STATE_PENDING, legacy, 0, null),
        )
    }

    /**
     * A sound identifier is left alone whatever else is true of the row, because the proof that
     * the server never saw it is *the identifier*. Take that away and there is no proof left.
     */
    @Test
    fun aRowWhoseIdentifierTheContractAcceptsIsNeverTouched() {
        for (state in listOf("pending", "inflight", "failed")) {
            assertEquals(
                OutboxRepair.Verdict.SOUND,
                OutboxRepair.verdict(state, sound, 0, null),
                "a $state row with a valid identifier must not be re-minted",
            )
        }
        assertEquals(
            OutboxRepair.Verdict.SOUND,
            OutboxRepair.verdict(
                SyncMutationEntity.STATE_FAILED,
                sound,
                3,
                SyncErrorCodes.SYNC_REVISION_CONFLICT,
            ),
            "even one the server refused for a real reason",
        )
    }

    /**
     * `inflight` is the row that may be on the wire *right now*, under the identifier it holds.
     *
     * Re-minting under a live request would have the response come back keyed by the old id, the
     * engine count the mutation unanswered, and the row sent again under the new one — which is
     * the double-apply FR-SYNC-006 exists to prevent, arriving by a different door than the one
     * everything else here guards.
     */
    @Test
    fun anInflightRowIsNeverRemintedEvenWithALegacyIdentifier() {
        assertEquals(
            OutboxRepair.Verdict.HELD,
            OutboxRepair.verdict(SyncMutationEntity.STATE_INFLIGHT, legacy, 0, null),
        )
    }

    /**
     * `failed` is where the interesting decision is. FR-SYNC-007 keeps a refused mutation out of
     * the queue on purpose — it holds a change the user made and deleting it would lose it — but
     * a row refused *for its identifier* was never judged on its merits at all: the server threw
     * the batch out before it looked at the payload. Leaving it out would fix nothing.
     */
    @Test
    fun aFailedRowRefusedForItsIdentifierIsRemintedAndRequeued() {
        assertEquals(
            OutboxRepair.Verdict.REMINT,
            OutboxRepair.verdict(
                SyncMutationEntity.STATE_FAILED,
                legacy,
                1,
                SyncErrorCodes.SYNC_INVALID_PAYLOAD,
            ),
        )
    }

    /**
     * Curable from incurable: the test is on the *defect*, never on the code.
     *
     * Every code below names something wrong with the payload or with the aggregate, which a new
     * identifier does not touch. Requeueing one would send a row certain to be refused again,
     * which is the churn `failed` exists to stop.
     *
     * `sync.invalid_payload` is deliberately not among them, and it is the entry that shows why
     * the code cannot be the test: it is what the server answers for an unreadable identifier
     * *and* what this client stamps on a row whose stored payload it can no longer read. One
     * code, two unrelated causes.
     */
    @Test
    fun aFailedRowRefusedForItsPayloadOrItsAggregateIsHeld() {
        val incurable = listOf(
            SyncErrorCodes.SYNC_UNKNOWN_AGGREGATE_TYPE,
            SyncErrorCodes.SYNC_UPGRADE_REQUIRED,
            SyncErrorCodes.SYNC_MISSING_REQUIRED_FIELD,
            SyncErrorCodes.SYNC_REVISION_CONFLICT,
            SyncErrorCodes.SYNC_AGGREGATE_DELETED,
        )
        assertEquals(incurable.toSet(), OutboxRepair.INCURABLE_ERROR_CODES)
        for (code in incurable) {
            assertEquals(
                OutboxRepair.Verdict.HELD,
                OutboxRepair.verdict(SyncMutationEntity.STATE_FAILED, legacy, 1, code),
                "$code names a defect a new identifier does not remove",
            )
        }
    }

    /**
     * The corroborating guard, and only that.
     *
     * `attempt_count` is incremented by one statement in this schema — `SyncDao.markFailed` —
     * so it counts *rejections*, not sends, and cannot prove a row never reached the server. It
     * is honoured all the same on the `pending` branch: nothing in this build can produce that
     * row, and refusing it costs nothing.
     */
    @Test
    fun aPendingRowThatHasBeenAttemptedIsHeldRatherThanReminted() {
        assertEquals(
            OutboxRepair.Verdict.HELD,
            OutboxRepair.verdict(SyncMutationEntity.STATE_PENDING, legacy, 1, null),
        )
    }

    /**
     * The predicate is the server's, not this client's.
     *
     * `z.uuidv7()` accepts hex in either case; `MutationIds.random` emits lower case because
     * `java.util.UUID` does. A stricter predicate would re-mint an identifier the server *would*
     * have accepted — and might already have — which is the one mistake this pass must not make.
     */
    @Test
    fun anUpperCaseIdentifierTheServerWouldAcceptIsLeftAlone() {
        val shouted = SyncFixtures.mutationId(1).uppercase()
        assertTrue(MutationIds.isMutationId(shouted), "the contract accepts $shouted")
        assertEquals(
            OutboxRepair.Verdict.SOUND,
            OutboxRepair.verdict(SyncMutationEntity.STATE_PENDING, shouted, 0, null),
        )
    }

    /** Anything at all that is not a `mutationIdSchema` value, not only a v4. */
    @Test
    fun everyShapeTheContractRefusesIsARepairCandidate() {
        val refused = listOf(
            "4317e938-539e-4c48-abd5-27311fb39b74", // a v4, the one that shipped
            "00000000-0000-0000-0000-000000000000", // the nil UUID
            "0198f0a1-0000-1000-8000-000000000001", // a v1
            "0198f0a1-0000-7000-c000-000000000001", // the wrong variant nibble
            "0198f0a1-0000-7000-8000-00000000001", // one hex digit short
            "0198f0a10000700080000000000000001", // no hyphens
            "m-1", // and the shape a test fixture used to have
            "",
        )
        for (id in refused) {
            assertEquals(
                OutboxRepair.Verdict.REMINT,
                OutboxRepair.verdict(SyncMutationEntity.STATE_PENDING, id, 0, null),
                "$id is not a mutationId and the row carrying it can never be sent",
            )
        }
    }

    /** A state no constant names — a downgrade, or a database somebody edited by hand. */
    @Test
    fun anUnknownStateIsLeftExactlyAsItIs() {
        assertEquals(OutboxRepair.Verdict.HELD, OutboxRepair.verdict("archived", legacy, 0, null))
    }

    // --- the engine, which is where the rule runs ----------------------------------------------

    /**
     * Recovery is two passes now, and the order is not free.
     *
     * The repair refuses to touch an `inflight` row, so `requeueInflight` has to have run first
     * for a row stranded by a killed process to be repairable in the same engine start rather
     * than one start later. Both still precede everything else: a phone that is out of range
     * must come back with a queue that can actually drain.
     */
    @Test
    fun constructingTheEngineRecoversThenRepairs() = runTest {
        val store = FakeSyncStore(mutations = listOf(SyncFixtures.measurementUpsert(legacy)))

        engine(store, ScriptedSyncApi())
        advanceUntilIdle()

        assertEquals(listOf("requeueInflight", "repairUnsendableMutationIds"), store.calls)
    }

    /**
     * The row a killed process stranded *and* an older build named: unstuck by both passes in
     * one start, which is what running them in this order buys.
     */
    @Test
    fun aStrandedLegacyRowIsRecoveredAndRepairedInTheSameStart() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert(legacy, state = SyncMutationEntity.STATE_INFLIGHT),
            ),
        )

        engine(store, ScriptedSyncApi())
        advanceUntilIdle()

        val recovered = store.rowsInState(SyncMutationEntity.STATE_PENDING).single()
        assertTrue(MutationIds.isMutationId(recovered.mutationId), recovered.mutationId)
    }

    /**
     * The counter falls. His profile leaves the outbox and the run says what it repaired.
     *
     * The minted identifier is pinned so the scripted server can answer it, which is the only
     * thing that has to be arranged: everything else is the ordinary push path, unchanged, which
     * is the point — a repaired row is not a special kind of row.
     */
    @Test
    fun theRepairedRowIsAcceptedAndTheOutboxDrains() = runTest {
        val minted = SyncFixtures.profileMutationId(0xfeed)
        val store = FakeSyncStore(mutations = listOf(SyncFixtures.healthProfileUpsert(legacy)))
        store.newMutationId = { minted }
        val api = ScriptedSyncApi()
            .onPush(SyncFixtures.pushResponse(SyncFixtures.applied(minted, revision = "1")))
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        val completed = assertIs<SyncOutcome.Completed>(engine(store, api).sync())

        assertEquals(1, completed.repaired)
        assertEquals(1, completed.applied)
        assertEquals(0, completed.rejected)
        assertEquals(minted, api.pushRequests.single().mutations.single().mutationId)
        assertEquals(
            emptyList(),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.mutationId },
        )
        assertNull(store.row(legacy))
    }

    /**
     * It cannot loop, and it does not need to remember anything not to.
     *
     * The predicate is "this identifier is not a mutation id" and the action makes it false, so a
     * row is a candidate at most once in the life of the database — however the send that follows
     * turns out. That is what bounds the residue of the curable/incurable question: a row that is
     * `failed` for a payload reason *and* carries a legacy identifier is requeued exactly once,
     * refused once more with its true reason, and never repaired again.
     */
    @Test
    fun aSecondEngineStartRepairsNothing() = runTest {
        val store = FakeSyncStore(mutations = listOf(SyncFixtures.measurementUpsert(legacy)))

        engine(store, ScriptedSyncApi())
        advanceUntilIdle()
        assertEquals(1, store.repairCalls)

        assertEquals(0, store.repairUnsendableMutationIds())
    }

    /**
     * A repaired `failed` row goes back to `pending` with the error that was recorded about its
     * old name cleared — otherwise `Data & sync` would go on quoting "Every mutation needs a
     * readable UUIDv7 `mutationId`" at a row that now has one. Its `attempt_count` stays: the
     * row really was refused, and that is history rather than a fault.
     */
    @Test
    fun aRepairedFailedRowForgetsAnErrorThatWasAboutItsOldName() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert(legacy).copy(
                    state = SyncMutationEntity.STATE_FAILED,
                    attemptCount = 2,
                    lastErrorCode = SyncErrorCodes.SYNC_INVALID_PAYLOAD,
                    lastErrorMessage = "Every mutation needs a readable UUIDv7 `mutationId`.",
                ),
            ),
        )

        engine(store, ScriptedSyncApi())
        advanceUntilIdle()

        val queue = store.rowsInState(SyncMutationEntity.STATE_PENDING)
        val repaired = assertNotNull(queue.singleOrNull())
        assertNull(repaired.lastErrorCode)
        assertNull(repaired.lastErrorMessage)
        assertEquals(2, repaired.attemptCount)
        assertEquals(
            emptyList(),
            store.rowsInState(SyncMutationEntity.STATE_FAILED).map { it.mutationId },
        )
    }

    /** And one refused for a reason a new name cannot mend stays refused, and keeps its name. */
    @Test
    fun anIncurableFailedRowIsLeftWhereFrSync007PutIt() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert(legacy).copy(
                    state = SyncMutationEntity.STATE_FAILED,
                    attemptCount = 1,
                    lastErrorCode = SyncErrorCodes.SYNC_REVISION_CONFLICT,
                    lastErrorMessage = "The measurement has moved on since baseRevision 3.",
                ),
            ),
        )

        engine(store, ScriptedSyncApi())
        advanceUntilIdle()

        val held = assertNotNull(store.row(legacy))
        assertEquals(SyncMutationEntity.STATE_FAILED, held.state)
        assertEquals(SyncErrorCodes.SYNC_REVISION_CONFLICT, held.lastErrorCode)
        assertEquals(
            emptyList(),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.mutationId },
        )
    }

    /** A database with nothing wrong in it is not written to, and says so. */
    @Test
    fun anOutboxOfSoundRowsIsLeftUntouched() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert(sound),
                SyncFixtures.healthProfileUpsert(SyncFixtures.profileMutationId(1)),
            ),
        )

        assertEquals(0, store.repairUnsendableMutationIds())
        assertEquals(
            listOf(sound, SyncFixtures.profileMutationId(1)).sorted(),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.mutationId }.sorted(),
        )
    }

    private fun TestScope.engine(store: SyncStore, api: ScriptedSyncApi) =
        SyncEngine(store = store, api = api, now = { now }, scope = this)
}
