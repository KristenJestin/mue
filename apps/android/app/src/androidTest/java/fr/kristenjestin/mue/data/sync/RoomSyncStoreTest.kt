package fr.kristenjestin.mue.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncDao
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.remote.sync.AggregateMetaDto
import fr.kristenjestin.mue.data.remote.sync.DeleteChangeDto
import fr.kristenjestin.mue.data.remote.sync.MeasurementPayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.MeasurementUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.OriginDto
import fr.kristenjestin.mue.data.remote.sync.SyncChangeDto
import fr.kristenjestin.mue.data.remote.sync.WIRE_AGGREGATE_MEASUREMENT
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine's storage half, against real SQLite.
 *
 * Two things can only be proved here. `requeueInflight` is one `UPDATE`, and the in-memory fake
 * the JVM tests use cannot show that its `WHERE` really spares a `failed` row; and `applyPage` is
 * the transaction PRD 19 requires — the changes and the cursor commit together or not at all —
 * which is a property of the database and of nothing above it.
 */
@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {

    private lateinit var database: MueDatabase
    private lateinit var syncDao: SyncDao
    private lateinit var store: RoomSyncStore

    private val at = 1_770_000_100_000L

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        syncDao = database.syncDao()
        store = RoomSyncStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // --- gap 1, in SQL --------------------------------------------------------------------

    /**
     * The one-way door, closed. `inflight` rows come back; `failed` rows do not, because
     * FR-SYNC-007 keeps a refused mutation out of the queue on purpose.
     */
    @Test
    fun requeueInflightRecoversOnlyTheStrandedRows() = runTest {
        syncDao.enqueueMutation(mutation("m-1", SyncMutationEntity.STATE_INFLIGHT, 1_000))
        syncDao.enqueueMutation(mutation("m-2", SyncMutationEntity.STATE_INFLIGHT, 2_000))
        syncDao.enqueueMutation(mutation("m-3", SyncMutationEntity.STATE_FAILED, 3_000))
        syncDao.enqueueMutation(mutation("m-4", SyncMutationEntity.STATE_PENDING, 4_000))

        assertEquals(2, store.requeueInflight())

        assertEquals(
            listOf("m-1", "m-2", "m-4"),
            syncDao.pendingMutations(10).map { it.mutationId },
        )
        assertEquals(0, syncDao.countInState(SyncMutationEntity.STATE_INFLIGHT))
        assertEquals(1, syncDao.countInState(SyncMutationEntity.STATE_FAILED))
    }

    /** Recovering an empty outbox is a no-op that reports zero, not a row touched by accident. */
    @Test
    fun requeueInflightOnAnEmptyOutboxChangesNothing() = runTest {
        assertEquals(0, store.requeueInflight())
    }

    /** A recovered row keeps its payload, its stamp and above all its id: nothing is reset. */
    @Test
    fun aRecoveredRowIsUnchangedApartFromItsState() = runTest {
        syncDao.enqueueMutation(mutation("m-1", SyncMutationEntity.STATE_INFLIGHT, 1_000))

        store.requeueInflight()

        val row = syncDao.mutation("m-1")
        assertNotNull(row)
        assertEquals(SyncMutationEntity.STATE_PENDING, row?.state)
        assertEquals("""{"date":"2026-08-25","weightCg":7845}""", row?.payload)
        assertEquals(1_000L, row?.createdAt)
        assertEquals(
            "the id the server already knows must survive recovery unchanged",
            "m-1",
            row?.mutationId,
        )
    }

    // --- applyPage ------------------------------------------------------------------------

    @Test
    fun aPageAppliesItsChangesAndAdvancesTheCursorTogether() = runTest {
        store.applyPage(
            listOf(upsert("2026-08-25", 7_845, "4"), deleteOf("2026-08-24", "10")),
            nextCursor = "eyJ2IjoxLCJzZXEiOiI0MiJ9",
            at = at,
        )

        assertEquals(7_845, database.measurementDao().findByDate("2026-08-25")?.measurement?.weightCg)
        assertNull(database.measurementDao().findByDate("2026-08-24"))

        val state = syncDao.syncState()
        assertEquals("eyJ2IjoxLCJzZXEiOiI0MiJ9", state?.cursor)
        assertEquals(at, state?.lastSuccessAt)
        assertNull("a success clears the previous error", state?.lastErrorCode)
    }

    /** The metadata comes from `meta` whole, so a revision and a tombstone cannot disagree. */
    @Test
    fun aTombstoneArrivesWithItsRevisionAndItsOrigin() = runTest {
        store.applyPage(listOf(deleteOf("2026-08-24", "10")), "eyJ2IjoxfQ", at)

        val aggregate =
            syncDao.aggregateState(SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-24")
        assertNotNull(aggregate)
        assertEquals(10L, aggregate?.revision)
        assertEquals(OriginDto.TYPE_AGENT, aggregate?.originType)
        assertEquals("agent-claude", aggregate?.originId)
        assertNotNull("FR-SYNC-005: the tombstone blocks resurrection", aggregate?.deletedAt)
        assertEquals(
            listOf("2026-08-24"),
            syncDao.tombstones(SyncAggregateStateEntity.TYPE_MEASUREMENT).map { it.aggregateId },
        )
    }

    /** A change that clears a tombstone must clear it: the server has decided (PRD 13.2). */
    @Test
    fun anUpsertAfterATombstoneBringsTheRowBack() = runTest {
        store.applyPage(listOf(deleteOf("2026-08-25", "10")), "eyJ2IjoxfQ", at)

        store.applyPage(listOf(upsert("2026-08-25", 7_900, "11")), "eyJ2IjoyfQ", at + 1)

        assertEquals(7_900, database.measurementDao().findByDate("2026-08-25")?.measurement?.weightCg)
        assertNull(
            syncDao.aggregateState(SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-25")
                ?.deletedAt,
        )
    }

    /**
     * FR-SYNC-006: "une page de changements serveur peut être redemandée sans réappliquer deux
     * fois ses effets". Replaying the same page must land on the same state.
     */
    @Test
    fun replayingAPageChangesNothing() = runTest {
        val page = listOf(upsert("2026-08-25", 7_845, "4"), deleteOf("2026-08-24", "10"))

        store.applyPage(page, "eyJ2IjoxfQ", at)
        store.applyPage(page, "eyJ2IjoxfQ", at + 1)

        assertEquals(1, database.measurementDao().count())
        assertEquals(7_845, database.measurementDao().findByDate("2026-08-25")?.measurement?.weightCg)
        assertEquals(
            listOf("2026-08-24"),
            syncDao.tombstones(SyncAggregateStateEntity.TYPE_MEASUREMENT).map { it.aggregateId },
        )
    }

    /**
     * The transaction, proved by breaking it. A change carrying an aggregate type with no local
     * table throws inside `applyPage`; if the cursor were written outside the transaction, the
     * phone would end up past changes it never applied.
     */
    @Test
    fun aPageThatFailsHalfwayAdvancesNothing() = runTest {
        syncDao.insertSyncStateIfAbsent(SyncStateEntity())
        syncDao.recordSuccess("eyJ2IjoxfQ", at)

        val error = runCatching {
            store.applyPage(
                listOf(
                    upsert("2026-08-25", 7_845, "4"),
                    upsert("2026-08-26", 7_850, "5", aggregateType = "recipe"),
                ),
                nextCursor = "eyJ2IjoyfQ",
                at = at + 1,
            )
        }.exceptionOrNull()

        assertTrue("the page must be refused whole, got $error", error is IllegalArgumentException)
        assertNull(
            "the applicable change ahead of the failure must have rolled back too",
            database.measurementDao().findByDate("2026-08-25"),
        )
        assertEquals("eyJ2IjoxfQ", syncDao.syncState()?.cursor)
    }

    /** The cursor is stored exactly as it arrived — nothing parses, trims or normalises it. */
    @Test
    fun theCursorIsStoredByteForByte() = runTest {
        val cursor = "eyJ2IjoxLCJzZXEiOiI5MDA3MTk5MjU0NzQwOTk0In0"

        store.applyPage(emptyList(), cursor, at)

        assertEquals(cursor, store.cursor())
        assertEquals(cursor, syncDao.syncState()?.cursor)
    }

    /** An empty page still records the success: `Data & sync` shows the age of the last one. */
    @Test
    fun anEmptyPageStillRecordsTheSuccess() = runTest {
        store.applyPage(emptyList(), "eyJ2IjoxfQ", at)

        assertEquals(at, syncDao.syncState()?.lastSuccessAt)
    }

    // --- acknowledge and reject ------------------------------------------------------------

    @Test
    fun acknowledgingAMutationDropsItAndRecordsItsRevision() = runTest {
        val row = mutation("m-1", SyncMutationEntity.STATE_INFLIGHT, 1_000)
        syncDao.enqueueMutation(row)

        store.acknowledge(row, revision = 4L, at = at)

        assertNull(syncDao.mutation("m-1"))
        val aggregate =
            syncDao.aggregateState(SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-25")
        assertNotNull(aggregate)
        assertEquals(4L, aggregate?.revision)
        assertEquals("m-1", aggregate?.lastMutationId)
    }

    /** A revision the local column cannot hold is not written; the row still leaves the outbox. */
    @Test
    fun acknowledgingWithNoUsableRevisionStillClearsTheRow() = runTest {
        val row = mutation("m-1", SyncMutationEntity.STATE_INFLIGHT, 1_000)
        syncDao.enqueueMutation(row)

        store.acknowledge(row, revision = null, at = at)

        assertNull(syncDao.mutation("m-1"))
        assertNull(syncDao.aggregateState(SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-25"))
    }

    /** Acknowledging a delete must not resurrect the aggregate by clearing its tombstone. */
    @Test
    fun acknowledgingADeleteKeepsItsTombstone() = runTest {
        val row = mutation("m-1", SyncMutationEntity.STATE_INFLIGHT, 1_000)
        syncDao.enqueueMutation(row)
        syncDao.putAggregateState(
            SyncAggregateStateEntity(
                aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
                aggregateId = "2026-08-25",
                deletedAt = 1_000,
            )
        )

        store.acknowledge(row, revision = 11L, at = at)

        val aggregate =
            syncDao.aggregateState(SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-25")
        assertEquals(11L, aggregate?.revision)
        assertEquals(1_000L, aggregate?.deletedAt)
    }

    /** FR-SYNC-007: kept, marked, and out of the queue with its payload intact. */
    @Test
    fun rejectingAMutationKeepsItWithItsError() = runTest {
        syncDao.enqueueMutation(mutation("m-1", SyncMutationEntity.STATE_INFLIGHT, 1_000))

        store.reject("m-1", "sync.revision_conflict", "moved on since baseRevision 3")

        val row = syncDao.mutation("m-1")
        assertNotNull(row)
        assertEquals(SyncMutationEntity.STATE_FAILED, row?.state)
        assertEquals("sync.revision_conflict", row?.lastErrorCode)
        assertEquals(1, row?.attemptCount)
        assertNotNull("no local data is deleted to repair an error", row?.payload)
        assertEquals(emptyList<String>(), syncDao.pendingMutations(10).map { it.mutationId })
    }

    /** A failure is recorded without touching the cursor or any business row (FR-SYNC-008). */
    @Test
    fun recordingAFailureLeavesTheCursorAndTheDataAlone() = runTest {
        store.applyPage(listOf(upsert("2026-08-25", 7_845, "4")), "eyJ2IjoxfQ", at)

        store.recordFailure("client.unreachable", "The server could not be reached.")

        assertEquals("eyJ2IjoxfQ", syncDao.syncState()?.cursor)
        assertEquals("client.unreachable", syncDao.syncState()?.lastErrorCode)
        assertEquals(7_845, database.measurementDao().findByDate("2026-08-25")?.measurement?.weightCg)
    }

    /** The paired identity the engine reads before it does anything at all. */
    @Test
    fun theStoreReportsThePairedServerAndDevice() = runTest {
        assertNull("an unpaired phone answers null and nothing else", store.serverUrl())

        syncDao.putSyncState(
            SyncStateEntity(serverUrl = "https://mue.home.arpa", deviceId = "device-7f3c1a04")
        )

        assertEquals("https://mue.home.arpa", store.serverUrl())
        assertEquals("device-7f3c1a04", store.deviceId())
        assertNull("nothing has been pulled yet", store.cursor())
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun mutation(id: String, state: String, createdAt: Long) = SyncMutationEntity(
        mutationId = id,
        aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId = "2026-08-25",
        op = SyncMutationEntity.OP_UPSERT,
        baseRevision = null,
        payload = """{"date":"2026-08-25","weightCg":7845}""",
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = state,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    private fun upsert(
        date: String,
        weightCg: Int,
        revision: String,
        aggregateType: String = WIRE_AGGREGATE_MEASUREMENT,
    ): SyncChangeDto = MeasurementUpsertChangeDto(
        sequence = revision,
        aggregateType = aggregateType,
        aggregateId = date,
        payloadSchemaVersion = 1,
        payload = MeasurementPayloadV1Dto(date, weightCg),
        meta = meta(date, revision, deletedAt = null),
    )

    private fun deleteOf(date: String, revision: String): SyncChangeDto = DeleteChangeDto(
        sequence = revision,
        aggregateType = WIRE_AGGREGATE_MEASUREMENT,
        aggregateId = date,
        payloadSchemaVersion = 1,
        meta = meta(date, revision, deletedAt = "2026-08-25T06:12:05.310Z"),
    )

    private fun meta(date: String, revision: String, deletedAt: String?) = AggregateMetaDto(
        id = date,
        revision = revision,
        createdAt = "2026-08-25T06:12:04.500Z",
        updatedAt = "2026-08-25T06:12:04.500Z",
        deletedAt = deletedAt,
        originType = OriginDto.TYPE_AGENT,
        originId = "agent-claude",
        lastMutationId = "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
    )
}
