package fr.kristenjestin.mue.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.data.remote.sync.WIRE_PUSH_MAX_MUTATIONS
import fr.kristenjestin.mue.data.sync.PAYLOAD_SCHEMA_VERSION
import fr.kristenjestin.mue.domain.model.FoodAggregates
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue a send selects from, in SQLite.
 *
 * The four food aggregates are journalled at every save (FR-SYNC-001) and `AGGREGATE_TYPES` in
 * `packages/contracts` has no branch for them, so those rows are `pending` and undeliverable for
 * as long as the contract lacks it — they never drain. A send that took the oldest
 * [WIRE_PUSH_MAX_MUTATIONS] rows whatever their type would therefore, once that many saves had
 * accumulated, get back a window with nothing sendable in it, and every measurement queued behind
 * them would stop going out permanently and with no error anywhere. FR-SYNC-007 forbids exactly
 * that block, so the type is part of the `WHERE` rather than a filter applied after the window
 * has already been filled.
 *
 * `healthProfile` was the aggregate this file used to name here, and it is now on the other
 * side of the `WHERE`: [theProfileIsSelectedByASendNowThatTheContractCarriesIt] is what says
 * so, and it is the SQL half of the pending count that could never reach zero.
 *
 * `SyncEngineTest.aFullWindowOfUndeliverableRowsDoesNotStallTheMeasurementBehindThem` asserts the
 * engine's half on the JVM; this asserts that the SQL it rests on selects and orders the same way.
 */
@RunWith(AndroidJUnit4::class)
class SyncQueueSelectionDaoTest {

    private lateinit var database: MueDatabase
    private lateinit var syncDao: SyncDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        syncDao = database.syncDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /** The blockage itself: a full window of undeliverable rows ahead of one measurement. */
    @Test
    fun afullWindowOfUndeliverableRowsDoesNotHideTheMeasurementBehindThem() = runTest {
        repeat(WIRE_PUSH_MAX_MUTATIONS) { index ->
            syncDao.enqueueMutation(
                row(
                    "h-$index",
                    FoodAggregates.TYPE_FOOD_LOG_ENTRY,
                    "b7c1e2f0-0000-7000-8000-00000000000$index",
                    createdAt = index.toLong(),
                ),
            )
        }
        syncDao.enqueueMutation(
            row("m-1", SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-25", createdAt = 100_000),
        )

        assertEquals(
            listOf("m-1"),
            syncDao.pendingMutationsOfTypes(
                SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES,
                WIRE_PUSH_MAX_MUTATIONS,
            ).map { it.mutationId },
        )
        assertEquals(
            WIRE_PUSH_MAX_MUTATIONS,
            syncDao.countPendingOfOtherTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES),
        )
        // And nothing was lost to make that true: the whole queue is still there, in order.
        assertEquals(
            WIRE_PUSH_MAX_MUTATIONS + 1,
            syncDao.pendingMutations(WIRE_PUSH_MAX_MUTATIONS + 10).size,
        )
    }

    /**
     * The row the owner's phone was holding, on the sendable side of the `WHERE`.
     *
     * His `sync_mutations` had exactly this: `aggregate_type "healthProfile"`, `aggregate_id
     * "me"`, `state "pending"`, `attempt_count 0`. The zero was the tell — it was never
     * attempted, because this query did not select it. It does now, and in the queue's order,
     * which matters: the server assigns revisions in the order it accepts mutations.
     */
    @Test
    fun theProfileIsSelectedByASendNowThatTheContractCarriesIt() = runTest {
        syncDao.enqueueMutation(
            row(
                "hp-1",
                SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                HealthProfileEntity.ROW_ID,
                createdAt = 1_000,
            ),
        )
        syncDao.enqueueMutation(
            row("m-1", SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-25", createdAt = 2_000),
        )

        assertEquals(
            listOf("hp-1", "m-1"),
            syncDao.pendingMutationsOfTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES, 10)
                .map { it.mutationId },
        )
        assertEquals(
            "nothing is held back, so `Data & sync` can reach zero",
            0,
            syncDao.countPendingOfOtherTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES),
        )
    }

    /** The selection keeps `created_at`, then `rowid` — the outbox's order, not the table's. */
    @Test
    fun theSelectionKeepsTheOutboxOrder() = runTest {
        syncDao.enqueueMutation(
            row("m-2", SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-26", createdAt = 2_000),
        )
        syncDao.enqueueMutation(
            row("m-1", SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-25", createdAt = 1_000),
        )
        syncDao.enqueueMutation(
            row("m-3", SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-27", createdAt = 2_000),
        )

        assertEquals(
            listOf("m-1", "m-2", "m-3"),
            syncDao.pendingMutationsOfTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES, 10)
                .map { it.mutationId },
        )
    }

    /** A `failed` row is out of both queues: FR-SYNC-007 keeps it and never selects it. */
    @Test
    fun aFailedRowIsNeitherSentNorCountedAsHeldBack() = runTest {
        syncDao.enqueueMutation(
            row("m-1", SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-25", createdAt = 1_000),
        )
        syncDao.enqueueMutation(
            row(
                "h-1",
                FoodAggregates.TYPE_FOOD_LOG_ENTRY,
                "b7c1e2f0-0000-7000-8000-000000000001",
                createdAt = 2_000,
            ),
        )
        syncDao.markFailed("m-1", "sync.revision_conflict", "stale")
        syncDao.markFailed("h-1", "sync.invalid_payload", "unreadable")

        assertEquals(
            emptyList<String>(),
            syncDao.pendingMutationsOfTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES, 10)
                .map { it.mutationId },
        )
        assertEquals(0, syncDao.countPendingOfOtherTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES))
    }

    private fun row(
        mutationId: String,
        aggregateType: String,
        aggregateId: String,
        createdAt: Long,
    ) = SyncMutationEntity(
        mutationId = mutationId,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        op = SyncMutationEntity.OP_UPSERT,
        baseRevision = null,
        payload = """{"date":"$aggregateId","weightCg":7845}""",
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = SyncMutationEntity.STATE_PENDING,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )
}
