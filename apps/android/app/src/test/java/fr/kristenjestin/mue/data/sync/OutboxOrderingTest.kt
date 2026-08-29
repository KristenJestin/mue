package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.InMemoryHealthProfileDao
import fr.kristenjestin.mue.data.local.database.InMemoryJournal
import fr.kristenjestin.mue.data.local.database.InMemoryMeasurementDao
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.data.repository.RoomMeasurementRepository
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gap 3, on the JVM: **the send order no longer rests on the wall clock.**
 *
 * `SyncOutbox` stamps `created_at` from `System.currentTimeMillis()` and the outbox is drained in
 * `created_at` order. The `rowid` tie-break that was already there covers two rows written inside
 * one millisecond; what it does not cover is the case a phone actually produces — **the clock
 * stepping backwards between two saves**, which is what happens the first time a device with a
 * drifted clock reaches a network and NTP corrects it. The second save would then carry a smaller
 * stamp than the first, be sent first, and for a measurement whose date moved that means the
 * server applying the deletion to the row it has just created. PRD 12.3 and 13.1 forbid an order
 * that depends on the device's clock; this is the client half of that rule.
 *
 * The fix is `SyncJournalDao.sequenced`, which floors every stamp at one past the highest already
 * in the outbox, inside the transaction that inserts the row. It is a default method, so the code
 * under test here is the shipped code and not a re-statement of it — see [InMemoryJournal].
 *
 * The instrumented `SyncOrderingDaoTest` asserts the same properties through real SQLite, which
 * is what proves the `ORDER BY` and the `MAX(created_at)` read the same way. This one runs on
 * every commit.
 */
class OutboxOrderingTest {

    private val journal = InMemoryJournal()
    private val measurements = InMemoryMeasurementDao(journal)
    private val profiles = InMemoryHealthProfileDao(journal)

    private var wallClock = 2_000_000_000_000L
    private var nextId = 0

    private fun repository() = RoomMeasurementRepository(
        measurements,
        SyncOutbox(newMutationId = { "mutation-${nextId++}" }, now = { wallClock }),
        Dispatchers.Unconfined,
    )

    /** The failure this fix exists for, written the way the phone produces it. */
    @Test
    fun aClockThatStepsBackwardsDoesNotReorderTheOutbox() = runTest {
        val repository = repository()

        repository.save(measurement("2026-08-21", 7_400))
        // NTP corrects a phone that was an hour fast. Everything after this is "earlier".
        wallClock -= 3_600_000L
        repository.save(measurement("2026-08-22", 7_420))
        repository.save(measurement("2026-08-23", 7_450))

        assertEquals(
            listOf("mutation-0", "mutation-1", "mutation-2"),
            journal.pending().map { it.mutationId },
        )
        assertEquals(
            listOf("2026-08-21", "2026-08-22", "2026-08-23"),
            journal.pending().map { it.aggregateId },
        )
    }

    /** The stamps are strictly increasing, which is what makes the order total rather than lucky. */
    @Test
    fun everyStampIsGreaterThanTheOneBeforeIt() = runTest {
        val repository = repository()

        repository.save(measurement("2026-08-21", 7_400))
        wallClock = 1_000L
        repository.save(measurement("2026-08-22", 7_420))
        wallClock = 500L
        repository.save(measurement("2026-08-23", 7_450))

        assertEquals(
            listOf(2_000_000_000_000L, 2_000_000_000_001L, 2_000_000_000_002L),
            journal.pending().map { it.createdAt },
        )
    }

    /**
     * The two mutations of one edit, in the order that is not merely nicer but correct: the date
     * moved, so the old aggregate is deleted and the new one written, and sending the upsert
     * first would have the server apply the deletion to the row it had just created. The wall
     * clock is frozen, so only the sequence can separate them.
     */
    @Test
    fun movingAMeasurementSendsTheDeleteBeforeTheUpsert() = runTest {
        val repository = repository()

        repository.save(measurement("2026-08-22", 7_400))
        repository.replace(LocalDate.parse("2026-08-22"), measurement("2026-08-23", 7_420))

        val pending = journal.pending()
        assertEquals(
            listOf(
                SyncMutationEntity.OP_UPSERT,
                SyncMutationEntity.OP_DELETE,
                SyncMutationEntity.OP_UPSERT,
            ),
            pending.map { it.op },
        )
        assertEquals(
            listOf("2026-08-22", "2026-08-22", "2026-08-23"),
            pending.map { it.aggregateId },
        )
        assertEquals(
            listOf(wallClock, wallClock + 1, wallClock + 2),
            pending.map { it.createdAt },
        )
    }

    /**
     * The floor is read from the table and not from a counter in memory, so it survives process
     * death with nothing to recover: a new [SyncOutbox] on a phone that has just rebooted with a
     * wrong clock still cannot undercut the rows already waiting.
     */
    @Test
    fun theFloorSurvivesANewOutboxAndANewClock() = runTest {
        repository().save(measurement("2026-08-21", 7_400))

        val restarted = RoomMeasurementRepository(
            measurements,
            SyncOutbox(newMutationId = { "restarted-0" }, now = { 0L }),
            Dispatchers.Unconfined,
        )
        restarted.save(measurement("2026-08-22", 7_420))

        val pending = journal.pending()
        assertEquals(listOf("mutation-0", "restarted-0"), pending.map { it.mutationId })
        assertTrue(
            pending[1].createdAt > pending[0].createdAt,
            "a restarted process must not undercut the row already waiting",
        )
    }

    /**
     * A drained outbox has no order left to preserve, so the stamp goes back to being the wall
     * clock. That is what keeps this from becoming a counter that only ever climbs: an empty
     * table is the reset, and it needs no code.
     */
    @Test
    fun anEmptyOutboxImposesNoFloor() = runTest {
        val repository = repository()

        repository.save(measurement("2026-08-21", 7_400))
        journal.delete("mutation-0")

        wallClock = 1_000L
        repository.save(measurement("2026-08-22", 7_420))

        assertEquals(1_000L, journal.pending().single().createdAt)
    }

    /** A `failed` row is still in the table, so it still holds the floor up (FR-SYNC-007). */
    @Test
    fun aFailedRowStillHoldsTheFloor() = runTest {
        val repository = repository()

        repository.save(measurement("2026-08-21", 7_400))
        journal.markFailed("mutation-0")

        wallClock = 1_000L
        repository.save(measurement("2026-08-22", 7_420))

        assertEquals(2_000_000_000_001L, journal.mutation("mutation-1")?.createdAt)
    }

    /**
     * The profile and the measurements share one queue and therefore one order. A profile save
     * between two weights must not be able to reset the floor for the weight that follows it.
     */
    @Test
    fun theProfileAndTheMeasurementsShareOneOrderedQueue() = runTest {
        val repository = repository()
        val profileOutbox = SyncOutbox(newMutationId = { "profile-0" }, now = { 1_000L })

        repository.save(measurement("2026-08-21", 7_400))
        profiles.upsertWithMutation(
            HealthProfileEntity(heightCm = 178),
            profileOutbox.healthProfileUpsert(heightCm = 178, birthDate = null, sex = null),
        )
        wallClock = 500L
        repository.save(measurement("2026-08-22", 7_420))

        assertEquals(
            listOf("mutation-0", "profile-0", "mutation-1"),
            journal.pending().map { it.mutationId },
        )
        assertEquals(
            listOf(2_000_000_000_000L, 2_000_000_000_001L, 2_000_000_000_002L),
            journal.pending().map { it.createdAt },
        )
    }

    /**
     * And a push now selects the profile too, in the order the shared queue put it in.
     *
     * This test asserted the opposite until `AGGREGATE_TYPES` grew the branch: the profile was
     * journalled, kept its place, and was skipped by every send for ever — which is what made
     * `Data & sync` count a change that could not fall. The order matters as much as the
     * selection: a profile saved before a weight has to reach the server before it, because the
     * server assigns revisions in the order it accepts mutations.
     */
    @Test
    fun theSendableQueueNowCarriesTheProfileInItsQueuedOrder() = runTest {
        val repository = repository()
        val profileOutbox = SyncOutbox(newMutationId = { "profile-0" }, now = { 1_000L })

        profiles.upsertWithMutation(
            HealthProfileEntity(heightCm = 171, birthDate = "1998-11-18"),
            profileOutbox.healthProfileUpsert(
                heightCm = 171,
                birthDate = LocalDate.of(1998, 11, 18),
                sex = null,
            ),
        )
        repository.save(measurement("2026-08-21", 7_400))

        assertEquals(
            listOf("profile-0", "mutation-0"),
            journal.pending().map { it.mutationId },
            "the profile is journalled and keeps its place in the queue",
        )
        assertEquals(
            listOf("profile-0", "mutation-0"),
            journal.pendingOfTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES, limit = 200)
                .map { it.mutationId },
            "and a push selects it, in that same order",
        )
        assertEquals(
            SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
            journal.mutation("profile-0")?.aggregateType,
        )
    }

    private fun measurement(date: String, hundredths: Int): Measurement =
        Measurement(LocalDate.parse(date), Weight.ofHundredthsClamped(hundredths))
}
