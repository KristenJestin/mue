package fr.kristenjestin.mue.data.local.database

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The send order does not rest on the phone's clock (PRD 12.3 and 13.1).
 *
 * `SyncOutbox` stamps `created_at` from `System.currentTimeMillis()` and `pendingMutations`
 * orders on it. The `rowid` tie-break covers two rows written in the same millisecond; what it
 * does not cover is the case that actually happens on a phone — **the clock stepping backwards
 * between two saves**, which is what NTP does the first time a device with a drifted clock finds
 * a network. The second save would then carry a smaller stamp than the first and be sent before
 * it, and for a measurement whose date moved that means the server applying a deletion to the
 * row it has just created.
 *
 * So every insert goes through `SyncJournalDao.sequenced`, which floors the stamp at one past the
 * highest already waiting, inside the transaction that writes the row. The clock below goes
 * backwards on purpose; the order must not.
 */
@RunWith(AndroidJUnit4::class)
class SyncOrderingDaoTest {

    private lateinit var database: MueDatabase
    private lateinit var syncDao: SyncDao
    private lateinit var repository: RoomMeasurementRepository

    /** A clock that reads whatever the test last set it to, including a value in the past. */
    private var wallClock = 2_000_000_000_000L
    private var nextId = 0

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        syncDao = database.syncDao()
        repository = RoomMeasurementRepository(
            database.measurementDao(),
            SyncOutbox(newMutationId = { "mutation-${nextId++}" }, now = { wallClock }),
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /** The failure this fix exists for, written the way the phone produces it. */
    @Test
    fun aClockThatStepsBackwardsDoesNotReorderTheOutbox() = runTest {
        wallClock = 2_000_000_000_000L
        repository.save(measurement("2026-08-21", 7_400))

        // NTP corrects a phone that was an hour fast. Everything after this is "earlier".
        wallClock = 2_000_000_000_000L - 3_600_000L
        repository.save(measurement("2026-08-22", 7_420))
        repository.save(measurement("2026-08-23", 7_450))

        val pending = syncDao.pendingMutations(10)
        assertEquals(
            listOf("mutation-0", "mutation-1", "mutation-2"),
            pending.map { it.mutationId },
        )
        assertEquals(
            listOf("2026-08-21", "2026-08-22", "2026-08-23"),
            pending.map { it.aggregateId },
        )
    }

    /** The stamps themselves are strictly increasing, which is what makes the order total. */
    @Test
    fun everyStampIsGreaterThanTheOneBeforeIt() = runTest {
        wallClock = 2_000_000_000_000L
        repository.save(measurement("2026-08-21", 7_400))
        wallClock = 1_000L
        repository.save(measurement("2026-08-22", 7_420))
        wallClock = 500L
        repository.save(measurement("2026-08-23", 7_450))

        val stamps = syncDao.pendingMutations(10).map { it.createdAt }
        assertEquals(listOf(2_000_000_000_000L, 2_000_000_000_001L, 2_000_000_000_002L), stamps)
    }

    /**
     * The case the fix must not break: a clock moving forward normally still produces the wall
     * clock, because `created_at` is also read as an instant by `Data & sync` and by the
     * `clientOccurredAt` a mutation carries for audit (PRD 12.1).
     */
    @Test
    fun aClockThatMovesForwardIsUsedAsItIs() = runTest {
        wallClock = 2_000_000_000_000L
        repository.save(measurement("2026-08-21", 7_400))
        wallClock = 2_000_000_060_000L
        repository.save(measurement("2026-08-22", 7_420))

        assertEquals(
            listOf(2_000_000_000_000L, 2_000_000_060_000L),
            syncDao.pendingMutations(10).map { it.createdAt },
        )
    }

    /**
     * The floor is read from the table, not from a counter in memory, so it survives process
     * death with nothing to recover: a new `SyncOutbox` on a phone that has just rebooted with a
     * wrong clock still cannot undercut the rows already waiting.
     */
    @Test
    fun theFloorSurvivesANewOutboxAndANewClock() = runTest {
        wallClock = 2_000_000_000_000L
        repository.save(measurement("2026-08-21", 7_400))

        // A fresh process: a new outbox, a new id sequence, and a clock reset to the epoch.
        val restarted = RoomMeasurementRepository(
            database.measurementDao(),
            SyncOutbox(newMutationId = { "restarted-0" }, now = { 0L }),
        )
        restarted.save(measurement("2026-08-22", 7_420))

        val pending = syncDao.pendingMutations(10)
        assertEquals(listOf("mutation-0", "restarted-0"), pending.map { it.mutationId })
        assertTrue(
            "the restarted process must not undercut the row already waiting",
            pending[1].createdAt > pending[0].createdAt,
        )
    }

    /**
     * A drained outbox has no order left to preserve, so the stamp goes back to being the wall
     * clock. That is what keeps this from becoming a counter that only ever climbs: an empty
     * table is the reset, and it needs no code.
     */
    @Test
    fun anEmptyOutboxImposesNoFloor() = runTest {
        wallClock = 2_000_000_000_000L
        repository.save(measurement("2026-08-21", 7_400))
        syncDao.deleteMutation("mutation-0")

        wallClock = 1_000L
        repository.save(measurement("2026-08-22", 7_420))

        assertEquals(1_000L, syncDao.pendingMutations(10).single().createdAt)
    }

    /** A `failed` row is still in the table, so it still holds the floor up. */
    @Test
    fun aFailedRowStillHoldsTheFloor() = runTest {
        wallClock = 2_000_000_000_000L
        repository.save(measurement("2026-08-21", 7_400))
        syncDao.markFailed("mutation-0", "sync.invalid_payload", "out of range")

        wallClock = 1_000L
        repository.save(measurement("2026-08-22", 7_420))

        assertEquals(
            2_000_000_000_001L,
            syncDao.mutation("mutation-1")?.createdAt,
        )
    }

    private fun measurement(date: String, hundredths: Int): Measurement =
        Measurement(LocalDate.parse(date), Weight.ofHundredthsClamped(hundredths))
}
