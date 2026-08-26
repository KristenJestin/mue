package fr.kristenjestin.mue.data.local.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.repository.RoomMeasurementRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Sync FR-SYNC-001: the local write and its outbox row are one transaction, and FR-SYNC-007: a
 * mutation the server refused is kept and skipped rather than deleted or retried forever.
 *
 * The atomicity is proved by breaking it. Making the journal insert fail and then finding no
 * measurement is the only assertion that can tell one transaction from two: if the two writes
 * were separate, the measurement would survive the failure and the phone would hold a change
 * nothing would ever send.
 */
@RunWith(AndroidJUnit4::class)
class MeasurementOutboxDaoTest {

    private lateinit var database: MueDatabase
    private lateinit var measurementDao: MeasurementDao
    private lateinit var syncDao: SyncDao

    /** A frozen clock, so two mutations of one edit really do share a millisecond. */
    private val fixedNow = 1_770_000_000_000L
    private var nextId = 0

    private val outbox = SyncOutbox(
        newMutationId = { "mutation-${nextId++}" },
        now = { fixedNow },
    )

    private lateinit var repository: RoomMeasurementRepository

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        measurementDao = database.measurementDao()
        syncDao = database.syncDao()
        repository = RoomMeasurementRepository(measurementDao, outbox)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aSavedMeasurementLeavesExactlyOnePendingMutation() = runTest {
        repository.save(measurement("2026-08-23", 7_450))

        assertNotNull(measurementDao.findByDate("2026-08-23"))

        val pending = syncDao.pendingMutations(10)
        assertEquals(1, pending.size)
        assertEquals(SyncMutationEntity.OP_UPSERT, pending.single().op)
        assertEquals(SyncAggregateStateEntity.TYPE_MEASUREMENT, pending.single().aggregateType)
        assertEquals("2026-08-23", pending.single().aggregateId)
        assertEquals(0, pending.single().attemptCount)
        assertEquals("""{"date":"2026-08-23","weightCg":7450}""", pending.single().payload)
    }

    /**
     * The proof of the transaction boundary. The journal insert is made to fail on a duplicate
     * mutation id; if the measurement were written by a transaction of its own it would still
     * be there afterwards.
     */
    @Test
    fun aFailingJournalWriteTakesTheMeasurementWithIt() = runTest {
        syncDao.enqueueMutation(outbox.measurementUpsert(measurement("1999-01-01", 7_000)))
        nextId = 0

        val error = runCatching { repository.save(measurement("2026-08-23", 7_450)) }
            .exceptionOrNull()

        assertTrue("expected a constraint failure, got $error", error is SQLiteConstraintException)
        assertNull("the write must not survive its own journal", measurementDao.findByDate("2026-08-23"))
        assertEquals(1, syncDao.countInState(SyncMutationEntity.STATE_PENDING))
    }

    /**
     * FR-SYNC-007. The refused mutation stays in the table with its payload and its error, and
     * the two queued behind it go out as if it were not there.
     */
    @Test
    fun aFailedMutationIsSkippedAndKept() = runTest {
        repository.save(measurement("2026-08-21", 7_400))
        repository.save(measurement("2026-08-22", 7_420))
        repository.save(measurement("2026-08-23", 7_450))

        syncDao.markFailed("mutation-1", "sync.invalid_payload", "weight out of range")

        assertEquals(
            listOf("mutation-0", "mutation-2"),
            syncDao.pendingMutations(10).map { it.mutationId },
        )

        val failed = syncDao.mutation("mutation-1")
        assertNotNull("a failed mutation is kept, never deleted", failed)
        assertEquals(SyncMutationEntity.STATE_FAILED, failed?.state)
        assertEquals("sync.invalid_payload", failed?.lastErrorCode)
        assertEquals(1, failed?.attemptCount)
        assertEquals("""{"date":"2026-08-22","weightCg":7420}""", failed?.payload)
        assertEquals(1, syncDao.countInState(SyncMutationEntity.STATE_FAILED))
    }

    /** The measurement it carried is untouched: no local data is deleted to repair an error. */
    @Test
    fun aFailedMutationDoesNotCostTheUserTheirMeasurement() = runTest {
        repository.save(measurement("2026-08-22", 7_420))
        syncDao.markFailed("mutation-0", "sync.invalid_payload", "weight out of range")

        assertEquals(7_420, measurementDao.findByDate("2026-08-22")?.weightCg)
    }

    /**
     * An edit that moves a date is two aggregates changing, and the delete must go out first:
     * sending the upsert first would have the server apply a deletion to the row it had just
     * created.
     *
     * The wall clock is frozen at [fixedNow], so both mutations propose the same stamp and only
     * the outbox's local sequence can separate them — `SyncJournalDao.sequenced` floors each
     * stamp at one past the highest already waiting, which is what makes `created_at` a total
     * order rather than a reading of the phone's clock (PRD 12.3).
     */
    @Test
    fun movingAMeasurementEnqueuesTheDeleteBeforeTheUpsert() = runTest {
        repository.save(measurement("2026-08-22", 7_420))
        syncDao.deleteMutation("mutation-0")

        repository.replace(LocalDate.parse("2026-08-22"), measurement("2026-08-23", 7_420))

        val pending = syncDao.pendingMutations(10)
        assertEquals(listOf(fixedNow, fixedNow + 1), pending.map { it.createdAt })
        assertEquals(
            listOf(SyncMutationEntity.OP_DELETE, SyncMutationEntity.OP_UPSERT),
            pending.map { it.op },
        )
        assertEquals(listOf("2026-08-22", "2026-08-23"), pending.map { it.aggregateId })
        assertNull(measurementDao.findByDate("2026-08-22"))
    }

    /** An edit that keeps the date is one aggregate changing, so one mutation and no tombstone. */
    @Test
    fun editingInPlaceEnqueuesOnlyTheUpsert() = runTest {
        repository.save(measurement("2026-08-23", 7_420))
        syncDao.deleteMutation("mutation-0")

        repository.replace(LocalDate.parse("2026-08-23"), measurement("2026-08-23", 7_450))

        val pending = syncDao.pendingMutations(10)
        assertEquals(listOf(SyncMutationEntity.OP_UPSERT), pending.map { it.op })
        assertEquals(
            emptyList<String>(),
            syncDao.tombstones(SyncAggregateStateEntity.TYPE_MEASUREMENT).map { it.aggregateId },
        )
    }

    /** FR-SYNC-005: the row goes, the tombstone stays so an offline copy cannot resurrect it. */
    @Test
    fun aDeleteLeavesATombstoneBehindTheRow() = runTest {
        repository.save(measurement("2026-08-23", 7_450))

        repository.delete(LocalDate.parse("2026-08-23"))

        assertNull(measurementDao.findByDate("2026-08-23"))
        val tombstone = syncDao.aggregateState(
            SyncAggregateStateEntity.TYPE_MEASUREMENT,
            "2026-08-23",
        )
        // The tombstone carries the stamp the mutation actually went out with — one past the
        // upsert that preceded it — so the local record and the mutation cannot disagree.
        assertEquals(fixedNow + 1, tombstone?.deletedAt)
        assertEquals(fixedNow + 1, syncDao.mutation("mutation-1")?.createdAt)
        assertEquals("mutation-1", tombstone?.lastMutationId)
    }

    /** Typing the date again is an ordinary edit; a tombstone left behind would undo it. */
    @Test
    fun writingADeletedDateAgainClearsItsTombstone() = runTest {
        repository.save(measurement("2026-08-23", 7_450))
        repository.delete(LocalDate.parse("2026-08-23"))

        repository.save(measurement("2026-08-23", 7_500))

        assertNull(
            syncDao.aggregateState(SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-23")
                ?.deletedAt,
        )
        assertEquals(
            emptyList<String>(),
            syncDao.tombstones(SyncAggregateStateEntity.TYPE_MEASUREMENT).map { it.aggregateId },
        )
    }

    /** PRD 13.3: the mutation carries the revision it was computed from, read in-transaction. */
    @Test
    fun theMutationCarriesTheRevisionTheServerAcknowledged() = runTest {
        syncDao.putAggregateState(
            SyncAggregateStateEntity(
                aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
                aggregateId = "2026-08-23",
                revision = 7L,
                serverUpdatedAt = fixedNow,
                originType = SyncAggregateStateEntity.ORIGIN_ANDROID,
            )
        )

        repository.save(measurement("2026-08-23", 7_450))

        assertEquals(7L, syncDao.pendingMutations(10).single().baseRevision)
    }

    /**
     * A first write has no revision to quote. Null and not zero: zero would claim a revision the
     * server issued, and the server would reject the mutation as a stale edit of an aggregate it
     * has never heard of.
     */
    @Test
    fun aFirstWriteQuotesNoRevision() = runTest {
        repository.save(measurement("2026-08-23", 7_450))

        assertNull(syncDao.pendingMutations(10).single().baseRevision)
        assertNull(
            syncDao.aggregateState(SyncAggregateStateEntity.TYPE_MEASUREMENT, "2026-08-23")
                ?.revision,
        )
    }

    /** A second write before any sync still has nothing to quote — one unknown, not a zero. */
    @Test
    fun aSecondWriteBeforeAnySyncStillQuotesNoRevision() = runTest {
        repository.save(measurement("2026-08-23", 7_450))
        syncDao.deleteMutation("mutation-0")

        repository.save(measurement("2026-08-23", 7_500))

        assertNull(syncDao.pendingMutations(10).single().baseRevision)
    }

    private fun measurement(date: String, hundredths: Int): Measurement =
        Measurement(LocalDate.parse(date), Weight.ofHundredthsClamped(hundredths))
}
