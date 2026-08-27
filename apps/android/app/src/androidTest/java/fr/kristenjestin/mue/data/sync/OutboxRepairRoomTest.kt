package fr.kristenjestin.mue.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncDao
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The repair pass against real SQLite, because one thing about it can only be proved here: it
 * rewrites a **primary key**.
 *
 * `sync_mutations.mutation_id` is the table's `@PrimaryKey`, so `UPDATE … SET mutation_id = ?`
 * is not an ordinary column write. An in-memory fake cannot show that SQLite keeps the row's
 * `rowid` across it — and `rowid` is the tie-break `pendingMutations` orders on, so if it moved,
 * two rows of one edit could swap and the server would apply a deletion to a row it had just
 * created. Nor can a fake show that the row is genuinely findable under the new name and gone
 * under the old.
 *
 * The rule itself is `OutboxRepair.verdict` and is asserted case by case on the JVM in
 * `OutboxRepairTest`. What is asserted here is the statement.
 */
@RunWith(AndroidJUnit4::class)
class OutboxRepairRoomTest {

    private lateinit var database: MueDatabase
    private lateinit var syncDao: SyncDao
    private lateinit var store: RoomSyncStore

    /** The identifier the owner's phone actually carried. Version nibble `4`. */
    private val legacy = "4317e938-539e-4c48-abd5-27311fb39b74"

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

    /**
     * His row: found under a name a server will read, gone under the one it could not, and
     * identical in every other column.
     */
    @Test
    fun aLegacyPendingRowIsFoundUnderItsNewNameAndNowhereElse() = runTest {
        syncDao.enqueueMutation(profile(legacy, SyncMutationEntity.STATE_PENDING, 1_000))

        assertEquals(1, store.repairUnsendableMutationIds())

        assertNull(
            "the row must not answer to the name the server refuses",
            syncDao.mutation(legacy),
        )
        val row = syncDao.pendingMutations(10).single()
        assertTrue("not a UUIDv7: ${row.mutationId}", MutationIds.isMutationId(row.mutationId))
        assertEquals(row, syncDao.mutation(row.mutationId))
        assertEquals("""{"heightCm":171,"birthDate":"1998-11-18"}""", row.payload)
        assertEquals(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, row.aggregateType)
        assertEquals("me", row.aggregateId)
        assertEquals(1_000L, row.createdAt)
        assertEquals(0, row.attemptCount)
    }

    /**
     * The order of the queue survives, including the `rowid` tie-break.
     *
     * Both rows carry the *same* `created_at`, which is what a database written by a build older
     * than `SyncJournalDao.sequenced` really can hold — so `rowid` alone decides, and the delete
     * has to stay in front of the upsert. Rewriting the primary key must not move it.
     */
    @Test
    fun twoLegacyRowsSharingAStampKeepTheirRowidOrder() = runTest {
        syncDao.enqueueMutation(
            profile(legacy, SyncMutationEntity.STATE_PENDING, 5_000)
                .copy(op = SyncMutationEntity.OP_DELETE, payload = null),
        )
        syncDao.enqueueMutation(
            profile("4317e938-539e-4c48-abd5-27311fb39b02", SyncMutationEntity.STATE_PENDING, 5_000),
        )

        assertEquals(2, store.repairUnsendableMutationIds())

        assertEquals(
            listOf(SyncMutationEntity.OP_DELETE, SyncMutationEntity.OP_UPSERT),
            syncDao.pendingMutations(10).map { it.op },
        )
    }

    /**
     * `inflight` is excluded in the SQL of `SyncDao.repairCandidates`, so the row is not even
     * read. It may be on the wire under exactly this identifier, and a response keyed by a name
     * the outbox no longer knows would be counted unanswered and the change sent twice.
     */
    @Test
    fun anInflightRowIsNotEvenLookedAt() = runTest {
        syncDao.enqueueMutation(profile(legacy, SyncMutationEntity.STATE_INFLIGHT, 1_000))

        assertEquals(0, store.repairUnsendableMutationIds())

        assertEquals(legacy, syncDao.mutation(legacy)?.mutationId)
        assertEquals(1, syncDao.countInState(SyncMutationEntity.STATE_INFLIGHT))
    }

    /**
     * `requeueInflight` first, then the repair — the order the engine runs them in. A row a
     * killed process stranded *and* an older build named is unstuck by both in one start.
     */
    @Test
    fun aStrandedLegacyRowIsRecoveredThenRepaired() = runTest {
        syncDao.enqueueMutation(profile(legacy, SyncMutationEntity.STATE_INFLIGHT, 1_000))

        assertEquals(1, store.requeueInflight())
        assertEquals(1, store.repairUnsendableMutationIds())

        val row = syncDao.pendingMutations(10).single()
        assertTrue(MutationIds.isMutationId(row.mutationId))
    }

    /**
     * A `failed` row refused for its identifier comes back to the queue with the error about its
     * old name cleared. FR-SYNC-007 keeps a refused mutation out of the queue because it was
     * judged; this one never was.
     */
    @Test
    fun aCurableFailedRowIsRequeuedAndForgetsTheErrorAboutItsOldName() = runTest {
        syncDao.enqueueMutation(profile(legacy, SyncMutationEntity.STATE_PENDING, 1_000))
        syncDao.markFailed(
            legacy,
            SyncErrorCodes.SYNC_INVALID_PAYLOAD,
            "Every mutation needs a readable UUIDv7 `mutationId`.",
        )

        assertEquals(1, store.repairUnsendableMutationIds())

        val row = syncDao.pendingMutations(10).single()
        assertEquals(SyncMutationEntity.STATE_PENDING, row.state)
        assertNull(row.lastErrorCode)
        assertNull(row.lastErrorMessage)
        assertEquals(
            "the row really was refused, and that is history",
            1L,
            row.attemptCount.toLong(),
        )
        assertEquals(0, syncDao.countInState(SyncMutationEntity.STATE_FAILED))
    }

    /** And one refused for something a new name cannot mend keeps its name and its place. */
    @Test
    fun anIncurableFailedRowIsLeftExactlyWhereItWas() = runTest {
        syncDao.enqueueMutation(profile(legacy, SyncMutationEntity.STATE_PENDING, 1_000))
        syncDao.markFailed(
            legacy,
            SyncErrorCodes.SYNC_REVISION_CONFLICT,
            "The profile has moved on since baseRevision 3.",
        )

        assertEquals(0, store.repairUnsendableMutationIds())

        val row = requireNotNull(syncDao.mutation(legacy))
        assertEquals(SyncMutationEntity.STATE_FAILED, row.state)
        assertEquals(SyncErrorCodes.SYNC_REVISION_CONFLICT, row.lastErrorCode)
        assertEquals(0, syncDao.pendingMutations(10).size)
    }

    /**
     * It cannot loop. The predicate is "this identifier is not a mutation id" and the action
     * makes it false, so no row is ever a candidate twice — which is what bounds the cost of
     * requeueing a row whose real defect turns out to be elsewhere.
     */
    @Test
    fun runningItAgainRepairsNothing() = runTest {
        syncDao.enqueueMutation(profile(legacy, SyncMutationEntity.STATE_PENDING, 1_000))

        assertEquals(1, store.repairUnsendableMutationIds())
        val afterFirst = syncDao.pendingMutations(10).single().mutationId

        assertEquals(0, store.repairUnsendableMutationIds())
        assertEquals(afterFirst, syncDao.pendingMutations(10).single().mutationId)
    }

    /** An outbox with nothing wrong in it is not written to, and an empty one is a no-op. */
    @Test
    fun aSoundOutboxIsLeftAlone() = runTest {
        val sound = MutationIds.random()
        assertEquals(0, store.repairUnsendableMutationIds())

        syncDao.enqueueMutation(profile(sound, SyncMutationEntity.STATE_PENDING, 1_000))
        assertEquals(0, store.repairUnsendableMutationIds())
        assertEquals(sound, syncDao.pendingMutations(10).single().mutationId)
    }

    /** The owner's row, to the character, with only its state and stamp as parameters. */
    private fun profile(id: String, state: String, createdAt: Long) = SyncMutationEntity(
        mutationId = id,
        aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
        aggregateId = "me",
        op = SyncMutationEntity.OP_UPSERT,
        baseRevision = null,
        payload = """{"heightCm":171,"birthDate":"1998-11-18"}""",
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = state,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )
}
