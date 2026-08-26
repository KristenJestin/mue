package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.remote.sync.DeleteMutationDto
import fr.kristenjestin.mue.data.remote.sync.PullRequestDto
import fr.kristenjestin.mue.data.remote.sync.PullResponseDto
import fr.kristenjestin.mue.data.remote.sync.PushRequestDto
import fr.kristenjestin.mue.data.remote.sync.PushResponseDto
import fr.kristenjestin.mue.data.remote.sync.SyncApi
import fr.kristenjestin.mue.data.remote.sync.MeasurementUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.OriginDto
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes
import fr.kristenjestin.mue.data.remote.sync.SyncTransportException
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
 * The engine's guarantees, on the JVM, with no Room, no Ktor and no emulator.
 *
 * Every test here is one line of PRD 11 to 13 made executable. They are fast on purpose: the
 * properties they assert — nothing is lost on a failure, a retry does not duplicate, a cursor
 * never advances past a change that cannot be applied — are the ones that are impossible to
 * notice by using the app, so they have to be impossible to break without a red test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private val now = 1_770_000_100_000L

    // --- gap 1: `inflight` is no longer a one-way door -----------------------------------------

    /**
     * Process death simulated as the state it leaves behind: rows stuck `inflight`, which
     * `pendingMutations` does not select and nothing used to move back.
     *
     * Constructing the engine is what recovers them — the assertion is made *before any
     * synchronisation is attempted*, because a recovery that only happened as part of a
     * successful sync would never run on a phone that is out of range, which is the phone this
     * is about.
     */
    @Test
    fun constructingTheEngineReturnsStrandedInflightRowsToThePendingQueue() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert("m-1", state = SyncMutationEntity.STATE_INFLIGHT),
                SyncFixtures.measurementUpsert(
                    "m-2",
                    date = "2026-08-26",
                    state = SyncMutationEntity.STATE_INFLIGHT,
                ),
                SyncFixtures.measurementUpsert(
                    "m-3",
                    date = "2026-08-27",
                    state = SyncMutationEntity.STATE_FAILED,
                ),
            ),
        )

        engine(store, ScriptedSyncApi())
        advanceUntilIdle()

        assertEquals(
            listOf("m-1", "m-2"),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.mutationId },
        )
        assertEquals(
            emptyList(),
            store.rowsInState(SyncMutationEntity.STATE_INFLIGHT).map { it.mutationId },
        )
        // FR-SYNC-007: a mutation the server refused stays out of the queue. Recovery must not
        // undo that, or one bad row would be retried at every start for ever.
        assertEquals(
            listOf("m-3"),
            store.rowsInState(SyncMutationEntity.STATE_FAILED).map { it.mutationId },
        )
    }

    /** Recovery is the first thing that touches the store, before the outbox is even read. */
    @Test
    fun recoveryRunsBeforeAnythingElse() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert("m-1", state = SyncMutationEntity.STATE_INFLIGHT),
            ),
        )
        val api = ScriptedSyncApi()
            .onPush(SyncFixtures.pushResponse(SyncFixtures.applied("m-1")))
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        val outcome = engine(store, api).sync()

        assertEquals("requeueInflight", store.calls.first())
        val completed = assertIs<SyncOutcome.Completed>(outcome)
        assertEquals(1, completed.recovered)
        // And the recovered row really was sent: this is the loss FR-SYNC-001 forbids, closed.
        assertEquals(listOf("m-1"), api.pushRequests.single().mutations.map { it.mutationId })
    }

    /** Recovery happens once per engine, not once per synchronisation. */
    @Test
    fun recoveryIsNotRepeatedOnEverySync() = runTest {
        val store = FakeSyncStore()
        val api = ScriptedSyncApi()
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_B))
        val engine = engine(store, api)

        engine.sync()
        engine.sync()

        assertEquals(1, store.requeueInflightCalls)
    }

    // --- push ---------------------------------------------------------------------------------

    @Test
    fun anUpsertAndADeleteGoOutInOutboxOrderAsTheirOwnWireShapes() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementDelete("m-delete", createdAt = 1_000),
                SyncFixtures.measurementUpsert("m-upsert", createdAt = 2_000),
            ),
        )
        val api = ScriptedSyncApi()
            .onPush(
                SyncFixtures.pushResponse(
                    SyncFixtures.applied("m-delete", revision = "11"),
                    SyncFixtures.applied("m-upsert", revision = "5"),
                ),
            )
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        engine(store, api).sync()

        val sent = api.pushRequests.single().mutations
        assertEquals(listOf("m-delete", "m-upsert"), sent.map { it.mutationId })
        val delete = assertIs<DeleteMutationDto>(sent[0])
        assertNull(delete.payload)
        val upsert = assertIs<MeasurementUpsertMutationDto>(sent[1])
        assertEquals(7_845, upsert.payload.weightCg)
        assertEquals(OriginDto(OriginDto.TYPE_ANDROID, "device-7f3c1a04"), upsert.origin)
        // PRD 12.3: the author's own clock travels for audit, never for ordering.
        assertEquals("1970-01-01T00:00:02Z", upsert.clientOccurredAt)
    }

    /**
     * FR-SYNC-006, and the whole of the client's no-duplicate guarantee.
     *
     * The response to the first attempt is lost — a transport failure after the server applied
     * it, which is the case PRD 18 calls "réponse réseau perdue après écriture serveur". The
     * second attempt must carry **the same mutation id**, because that is what lets the server
     * replay its stored result instead of writing a second weight.
     */
    @Test
    fun aRetryCarriesTheSameMutationIdAndIsAnsweredAsADuplicate() = runTest {
        val store = FakeSyncStore(mutations = listOf(SyncFixtures.measurementUpsert("m-1")))
        val api = ScriptedSyncApi()
            .onPushFail(
                SyncTransportException(
                    SyncErrorCodes.CLIENT_UNREACHABLE,
                    "the response never arrived",
                    retryable = true,
                ),
            )
            .onPush(SyncFixtures.pushResponse(SyncFixtures.duplicate("m-1", revision = "4")))
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))
        val engine = engine(store, api)

        val first = engine.sync()
        val second = engine.sync()

        assertIs<SyncOutcome.Failed>(first)
        val completed = assertIs<SyncOutcome.Completed>(second)
        assertEquals(1, completed.duplicates)
        assertEquals(0, completed.applied)

        assertEquals(2, api.pushRequests.size)
        assertEquals(
            api.pushRequests[0].mutations.single().mutationId,
            api.pushRequests[1].mutations.single().mutationId,
            "a retry that minted a new id would be a second weight on the server",
        )
        // A duplicate is the protocol working: the row leaves the outbox exactly as an applied
        // one does, and the revision the server replayed is recorded.
        assertNull(store.row("m-1"))
        assertEquals(4L, store.revisions.getValue("measurement" to "2026-08-25"))
    }

    /** FR-SYNC-001: a failed send loses nothing. Every row goes back to `pending`. */
    @Test
    fun aFailedPushReturnsEveryRowToPending() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert("m-1", createdAt = 1_000),
                SyncFixtures.measurementUpsert("m-2", date = "2026-08-26", createdAt = 2_000),
            ),
        )
        val api = ScriptedSyncApi().onPushFail(
            SyncTransportException(
                SyncErrorCodes.CLIENT_UNREACHABLE,
                "The server could not be reached.",
                retryable = true,
            ),
        )

        val outcome = engine(store, api).sync()

        val failed = assertIs<SyncOutcome.Failed>(outcome)
        assertTrue(failed.retryable)
        assertEquals(
            listOf("m-1", "m-2"),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.mutationId },
        )
        assertEquals(0, store.rowsInState(SyncMutationEntity.STATE_INFLIGHT).size)
        assertEquals(SyncErrorCodes.CLIENT_UNREACHABLE, store.lastFailure?.first)
        // FR-SYNC-008: an unreachable server never moves the cursor and never touches the data.
        assertNull(store.cursor)
    }

    /** A crash in the middle of handling results must not strand the batch either. */
    @Test
    fun anUnexpectedFailureMidBatchStillReturnsTheRowsToPending() = runTest {
        val store = FakeSyncStore(mutations = listOf(SyncFixtures.measurementUpsert("m-1")))
        val api = object : SyncApi {
            override suspend fun push(
                request: PushRequestDto,
            ): PushResponseDto =
                throw IllegalStateException("the OkHttp engine blew up")

            override suspend fun pull(
                request: PullRequestDto,
            ): PullResponseDto = error("unreachable")
        }

        val thrown = runCatching { engine(store, api).sync() }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        assertEquals(
            listOf("m-1"),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.mutationId },
            "the `finally` is what makes this true for failures nobody predicted",
        )
    }

    /**
     * FR-SYNC-007: one refused mutation does not block the batch. The refused row is kept with
     * its payload and its error; the others are acknowledged and gone.
     */
    @Test
    fun oneRejectedMutationDoesNotBlockTheRest() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert("m-1", createdAt = 1_000),
                SyncFixtures.measurementUpsert("m-2", date = "2026-08-26", createdAt = 2_000),
                SyncFixtures.measurementUpsert("m-3", date = "2026-08-27", createdAt = 3_000),
            ),
        )
        val api = ScriptedSyncApi()
            .onPush(
                SyncFixtures.pushResponse(
                    SyncFixtures.applied("m-1"),
                    SyncFixtures.rejected("m-2"),
                    SyncFixtures.applied("m-3", revision = "2"),
                ),
            )
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        val outcome = engine(store, api).sync()

        val completed = assertIs<SyncOutcome.Completed>(outcome)
        assertEquals(2, completed.applied)
        assertEquals(1, completed.rejected)
        assertTrue(completed.hasIssues)

        val kept = assertNotNull(store.row("m-2"))
        assertEquals(SyncMutationEntity.STATE_FAILED, kept.state)
        assertEquals(SyncErrorCodes.SYNC_REVISION_CONFLICT, kept.lastErrorCode)
        assertNotNull(kept.payload, "no local data is deleted to repair an error")
        assertNull(store.row("m-1"))
        assertNull(store.row("m-3"))
    }

    /** A server that answers about fewer mutations than it was sent must not cost a change. */
    @Test
    fun aMutationTheServerDidNotAnswerAboutGoesBackToPending() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.measurementUpsert("m-1", createdAt = 1_000),
                SyncFixtures.measurementUpsert("m-2", date = "2026-08-26", createdAt = 2_000),
            ),
        )
        val api = ScriptedSyncApi()
            .onPush(SyncFixtures.pushResponse(SyncFixtures.applied("m-1")))
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        engine(store, api).sync()

        assertNull(store.row("m-1"))
        assertEquals(
            SyncMutationEntity.STATE_PENDING,
            assertNotNull(store.row("m-2")).state,
        )
    }

    /**
     * Gap 2's other half: the health profile is journalled (FR-SYNC-001) and cannot yet be
     * expressed on the wire, because `AGGREGATE_TYPES` in `packages/contracts` is
     * `["measurement"]` while PRD 13.4 already makes the profile a synchronised aggregate.
     *
     * It must stay `pending` — not `failed`. A `failed` row would show the user `Sync issue`
     * for a limitation of the contract, and would never be retried once the contract grew.
     */
    @Test
    fun anAggregateTheContractCannotCarryStaysPendingAndIsNotSent() = runTest {
        val store = FakeSyncStore(
            mutations = listOf(
                SyncFixtures.healthProfileUpsert("h-1", createdAt = 1_000),
                SyncFixtures.measurementUpsert("m-1", createdAt = 2_000),
            ),
        )
        val api = ScriptedSyncApi()
            .onPush(SyncFixtures.pushResponse(SyncFixtures.applied("m-1")))
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        val outcome = engine(store, api).sync()

        val completed = assertIs<SyncOutcome.Completed>(outcome)
        assertEquals(1, completed.deferred)
        assertEquals(listOf("m-1"), api.pushRequests.single().mutations.map { it.mutationId })
        assertEquals(
            SyncMutationEntity.STATE_PENDING,
            assertNotNull(store.row("h-1")).state,
            "a change the contract cannot carry is kept, not refused",
        )
        assertTrue(!completed.hasIssues, "a deferred aggregate is not a `Sync issue`")
    }

    /** A batch of nothing but deferred rows still pulls: an agent's writes must still arrive. */
    @Test
    fun aBatchOfOnlyDeferredRowsStillPulls() = runTest {
        val store = FakeSyncStore(mutations = listOf(SyncFixtures.healthProfileUpsert("h-1")))
        val api = ScriptedSyncApi()
            .onPull(SyncFixtures.page(listOf(SyncFixtures.upsertChange("41")), SyncFixtures.CURSOR_A))

        val completed = assertIs<SyncOutcome.Completed>(engine(store, api).sync())

        assertEquals(0, api.pushRequests.size)
        assertEquals(1, completed.changes)
    }

    /** An unreadable stored payload is one bad row, kept and marked, never a failed sync. */
    @Test
    fun anUnreadableStoredPayloadIsRejectedRatherThanCrashingTheRun() = runTest {
        val corrupt = SyncFixtures.measurementUpsert("m-bad").copy(payload = "{not json")
        val store = FakeSyncStore(
            mutations = listOf(
                corrupt.copy(createdAt = 1_000),
                SyncFixtures.measurementUpsert("m-1", createdAt = 2_000),
            ),
        )
        val api = ScriptedSyncApi()
            .onPush(SyncFixtures.pushResponse(SyncFixtures.applied("m-1")))
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        val completed = assertIs<SyncOutcome.Completed>(engine(store, api).sync())

        assertEquals(1, completed.unreadable)
        assertEquals(1, completed.applied)
        assertEquals(
            SyncMutationEntity.STATE_FAILED,
            assertNotNull(store.row("m-bad")).state,
        )
    }

    // --- pull ---------------------------------------------------------------------------------

    /** FR-SYNC-002: the mutations go out first, then the changes come back. In that order. */
    @Test
    fun theBatchIsSentBeforeTheJournalIsRead() = runTest {
        val store = FakeSyncStore(mutations = listOf(SyncFixtures.measurementUpsert("m-1")))
        val api = ScriptedSyncApi()
            .onPush(SyncFixtures.pushResponse(SyncFixtures.applied("m-1")))
            .onPull(SyncFixtures.page(listOf(SyncFixtures.upsertChange("41")), SyncFixtures.CURSOR_A))

        engine(store, api).sync()

        val push = store.calls.indexOfFirst { it.startsWith("markInflight") }
        val apply = store.calls.indexOfFirst { it.startsWith("applyPage") }
        assertTrue(push in 0 until apply, "push must precede pull: ${store.calls}")
    }

    /**
     * The cursor is opaque: the bytes the server sent are the bytes stored, and the bytes stored
     * are the bytes sent back. Nothing in between parses, orders by or reconstructs one — and
     * the `sequence` on each change, which is the only thing a cursor could be rebuilt from, is
     * carried and never read.
     */
    @Test
    fun theCursorIsStoredAndReturnedVerbatim() = runTest {
        val store = FakeSyncStore(initialCursor = SyncFixtures.CURSOR_A)
        val api = ScriptedSyncApi()
            .onPull(
                SyncFixtures.page(
                    listOf(SyncFixtures.upsertChange("9007199254740993")),
                    SyncFixtures.CURSOR_B,
                    hasMore = true,
                ),
            )
            .onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_C))

        engine(store, api).sync()

        assertEquals(SyncFixtures.CURSOR_A, api.pullRequests[0].cursor)
        assertEquals(SyncFixtures.CURSOR_B, api.pullRequests[1].cursor)
        assertEquals(SyncFixtures.CURSOR_C, store.cursor)
        assertEquals(
            listOf(SyncFixtures.CURSOR_B, SyncFixtures.CURSOR_C),
            store.cursorHistory,
            "the cursor takes only values the server sent",
        )
    }

    /** FR-SYNC-003: an initial sync asks from the beginning, which is a null cursor. */
    @Test
    fun anInitialSyncAsksWithANullCursorAndDeclaresWhatItCanApply() = runTest {
        val store = FakeSyncStore(initialCursor = null)
        val api = ScriptedSyncApi().onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_A))

        engine(store, api).sync()

        assertNull(api.pullRequests.single().cursor)
        assertEquals(mapOf("measurement" to listOf(1)), api.pullRequests.single().supportedSchemaVersions)
    }

    @Test
    fun everyPageOfAHistoryIsReadUntilTheServerSaysThereIsNoMore() = runTest {
        val store = FakeSyncStore()
        val api = ScriptedSyncApi()
            .onPull(SyncFixtures.page(listOf(SyncFixtures.upsertChange("1")), SyncFixtures.CURSOR_A, hasMore = true))
            .onPull(SyncFixtures.page(listOf(SyncFixtures.upsertChange("2", date = "2026-08-26")), SyncFixtures.CURSOR_B, hasMore = true))
            .onPull(SyncFixtures.page(listOf(SyncFixtures.deleteChange("3")), SyncFixtures.CURSOR_C))

        val completed = assertIs<SyncOutcome.Completed>(engine(store, api).sync())

        assertEquals(3, completed.pages)
        assertEquals(3, completed.changes)
        assertTrue(!completed.moreAvailable)
        assertEquals(SyncFixtures.CURSOR_C, store.cursor)
    }

    /**
     * PRD 12.4 and PRD 18: an unapplicable payload leaves the cursor exactly where it was and
     * the local data untouched. This is the one outcome where "do nothing" is the whole
     * requirement, so the assertion is on what did *not* happen.
     */
    @Test
    fun anUpgradeDemandDoesNotAdvanceTheCursor() = runTest {
        val store = FakeSyncStore(initialCursor = SyncFixtures.CURSOR_A)
        val api = ScriptedSyncApi().onPull(SyncFixtures.upgradeRequired())

        val outcome = engine(store, api).sync()

        val upgrade = assertIs<SyncOutcome.UpgradeRequired>(outcome)
        assertEquals(SyncErrorCodes.SYNC_UPGRADE_REQUIRED, upgrade.error.code)
        assertEquals(SyncFixtures.CURSOR_A, store.cursor)
        assertEquals(emptyList(), store.cursorHistory)
        assertEquals(emptyList(), store.applied)
        assertEquals(SyncErrorCodes.SYNC_UPGRADE_REQUIRED, store.lastFailure?.first)
    }

    /**
     * The client's own half of PRD 12.4. The server's check passed — it had
     * `supportedSchemaVersions` — and this build still cannot apply the change. Advancing past
     * it would drop a change silently, so the page is refused whole.
     */
    @Test
    fun aPayloadVersionThisBuildCannotApplyStopsThePageWithoutAdvancing() = runTest {
        val store = FakeSyncStore(initialCursor = SyncFixtures.CURSOR_A)
        val api = ScriptedSyncApi().onPull(
            SyncFixtures.page(
                listOf(
                    SyncFixtures.upsertChange("41"),
                    SyncFixtures.upsertChange("42", date = "2026-08-26", payloadSchemaVersion = 2),
                ),
                SyncFixtures.CURSOR_B,
            ),
        )

        val outcome = engine(store, api).sync()

        assertIs<SyncOutcome.UpgradeRequired>(outcome)
        assertEquals(SyncFixtures.CURSOR_A, store.cursor)
        assertEquals(
            emptyList(),
            store.applied,
            "the applicable change ahead of it is not applied either: the page is one transaction",
        )
    }

    /**
     * A revision no `Long` can hold has no truthful local representation, so the page is
     * refused rather than stored truncated. `sync_aggregate_state.revision` is a signed
     * 64-bit column and the contract's counters are unsigned 64-bit.
     */
    @Test
    fun aRevisionThatDoesNotFitTheLocalColumnStopsThePage() = runTest {
        val store = FakeSyncStore(initialCursor = SyncFixtures.CURSOR_A)
        val api = ScriptedSyncApi().onPull(
            SyncFixtures.page(
                listOf(SyncFixtures.upsertChange("41", revision = "18446744073709551615")),
                SyncFixtures.CURSOR_B,
            ),
        )

        assertIs<SyncOutcome.UpgradeRequired>(engine(store, api).sync())
        assertEquals(SyncFixtures.CURSOR_A, store.cursor)
    }

    @Test
    fun aFailedPullRecordsTheErrorAndLeavesTheCursorAlone() = runTest {
        val store = FakeSyncStore(initialCursor = SyncFixtures.CURSOR_A)
        val api = ScriptedSyncApi().onPullFail(
            SyncTransportException(SyncErrorCodes.SERVER_UNAVAILABLE, "restarting", retryable = true),
        )

        val failed = assertIs<SyncOutcome.Failed>(engine(store, api).sync())

        assertTrue(failed.retryable)
        assertEquals(SyncFixtures.CURSOR_A, store.cursor)
        assertEquals(SyncErrorCodes.SERVER_UNAVAILABLE, store.lastFailure?.first)
    }

    /** A page loop that hits its bound says so, so the worker asks for another run. */
    @Test
    fun aHistoryLongerThanOneRunReportsThatMoreIsWaiting() = runTest {
        val store = FakeSyncStore()
        val api = ScriptedSyncApi()
        repeat(SyncEngine.MAX_PAGES_PER_RUN) {
            api.onPull(SyncFixtures.page(emptyList(), SyncFixtures.CURSOR_B, hasMore = true))
        }

        val completed = assertIs<SyncOutcome.Completed>(engine(store, api).sync())

        assertEquals(SyncEngine.MAX_PAGES_PER_RUN, completed.pages)
        assertTrue(completed.moreAvailable)
        assertEquals(SyncFixtures.CURSOR_B, store.cursor, "the cursor is persisted between pages")
    }

    // --- not paired ---------------------------------------------------------------------------

    /** PRD 21: Mue with no server is Mue working. Nothing is sent, nothing is recorded. */
    @Test
    fun anUnpairedPhoneDoesNothingAndCallsItNormal() = runTest {
        val store = FakeSyncStore(serverUrl = null)
        val api = ScriptedSyncApi()

        assertEquals(SyncOutcome.NotPaired, engine(store, api).sync())
        assertEquals(0, api.pushRequests.size)
        assertEquals(0, api.pullRequests.size)
        assertNull(store.lastFailure)
    }

    /** A paired server with no device identity cannot stamp an origin, so it is not paired. */
    @Test
    fun aServerWithoutADeviceIdentityIsNotPaired() = runTest {
        val store = FakeSyncStore(deviceId = null)

        assertEquals(SyncOutcome.NotPaired, engine(store, ScriptedSyncApi()).sync())
    }

    /** A local change made offline is still there afterwards, untouched (FR-SYNC-008). */
    @Test
    fun anUnpairedPhoneKeepsItsQueuedChanges() = runTest {
        val store = FakeSyncStore(
            serverUrl = null,
            mutations = listOf(SyncFixtures.measurementUpsert("m-1")),
        )

        engine(store, ScriptedSyncApi()).sync()

        assertEquals(
            listOf("m-1"),
            store.rowsInState(SyncMutationEntity.STATE_PENDING).map { it.mutationId },
        )
    }

    private fun TestScope.engine(store: SyncStore, api: SyncApi) =
        SyncEngine(store = store, api = api, now = { now }, scope = this)
}
