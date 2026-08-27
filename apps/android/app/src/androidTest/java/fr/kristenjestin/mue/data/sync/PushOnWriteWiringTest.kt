package fr.kristenjestin.mue.data.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/**
 * The wiring, on a device, through the container the application actually builds.
 *
 * `PushOnWriteTest` proves the rule on the JVM with a fake scheduler; this proves that the rule is
 * *connected* — that `SyncContainer` really collects the outbox its repositories mint into, and
 * that the other end really is `SyncScheduler.syncNow` with its constraints. Between the two there
 * is no seam left where a save could be journalled with nobody listening.
 *
 * It mints rows without writing them. `SyncOutbox` builds the row and the DAO writes it, so
 * calling the outbox directly exercises the trigger and touches neither the database nor the
 * paired server — which is what makes this test safe to run against a phone that has real data on
 * it.
 */
@RunWith(AndroidJUnit4::class)
class PushOnWriteWiringTest {

    private lateinit var application: MueApplication
    private lateinit var workManager: WorkManager

    /** A recipe's worth of ingredients, which is where the number forty comes from. */
    private val burst = 40

    @Before
    fun clearTheQueue() {
        application = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(application)

        // `MueApplication.onCreate` enqueues PRD 9.4's start trigger from a coroutine, so on the
        // launch that instrumentation itself causes it lands a second or two into the first test
        // and is indistinguishable from a schedule this test caused. Waiting for the name to fall
        // quiet before clearing it is what makes the counts below counts of what the outbox did.
        awaitQuiet()
        workManager.cancelUniqueWork(SyncScheduler.ONE_SHOT_WORK).result.get()
        workManager.pruneWork().result.get()
    }

    @After
    fun leaveNothingBehind() {
        workManager.cancelUniqueWork(SyncScheduler.ONE_SHOT_WORK).result.get()
        workManager.pruneWork().result.get()
    }

    /** The defect, at the level it was reported: a local write schedules a send. */
    @Test
    fun aSingleWriteSchedulesASend() {
        assertEquals("the queue must start empty", 0, requestIdsNow().size)

        application.container.sync.outbox.healthProfileUpsert(
            heightCm = 178,
            birthDate = LocalDate.of(1998, 11, 18),
        )

        val ids = collectRequestIds(forMillis = QUIET_WINDOW_PLUS_SLACK)
        assertEquals("one write must schedule exactly one send, and it scheduled $ids", 1, ids.size)
    }

    /**
     * Forty writes in one gesture, and one send at the end of it — not forty, and not one per
     * write. Ids are collected rather than rows counted, because `REPLACE` deletes the request it
     * replaces: forty separate schedules would show up as forty *different* ids passing through
     * the same name, and one gesture shows up as one.
     */
    @Test
    fun aBurstOfWritesSchedulesOneSend() {
        val outbox = application.container.sync.outbox

        repeat(burst) {
            outbox.measurementUpsert(
                Measurement(LocalDate.of(2026, 8, 27), Weight.ofHundredthsClamped(7_845)),
            )
        }

        // The burst is over in milliseconds and the window has not closed, so nothing has been
        // handed to WorkManager yet. This is the half `ExistingWorkPolicy.REPLACE` cannot do.
        assertTrue(
            "a request was enqueued while the burst was still arriving",
            requestIdsNow().isEmpty(),
        )

        val ids = collectRequestIds(forMillis = QUIET_WINDOW_PLUS_SLACK)
        assertEquals("a burst of $burst writes scheduled $ids", 1, ids.size)
    }

    /** An app nobody writes in enqueues nothing at all. */
    @Test
    fun anIdleAppSchedulesNothing() {
        val ids = collectRequestIds(forMillis = QUIET_WINDOW_PLUS_SLACK)
        assertEquals("an idle app enqueued $ids", 0, ids.size)
    }

    /** Returns once no request has appeared or left under the unique name for a full second. */
    private fun awaitQuiet() {
        val deadline = System.currentTimeMillis() + 20_000
        var lastChange = System.currentTimeMillis()
        var seen = requestIdsNow()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            val now = requestIdsNow()
            if (now != seen) {
                seen = now
                lastChange = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChange >= 1_000) {
                return
            }
        }
    }

    private fun requestIdsNow(): Set<UUID> = workManager
        .getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_WORK)
        .get()
        .map { it.id }
        .toSet()

    /** Every distinct request that passed under the unique name while this was watching. */
    private fun collectRequestIds(forMillis: Long): Set<UUID> {
        val seen = mutableSetOf<UUID>()
        val deadline = System.currentTimeMillis() + forMillis
        while (System.currentTimeMillis() < deadline) {
            seen += requestIdsNow()
            Thread.sleep(50)
        }
        return seen
    }

    private companion object {
        /** The window, plus enough for WorkManager to have written its row. */
        const val QUIET_WINDOW_PLUS_SLACK: Long = PushOnWrite.QUIET_WINDOW_MILLIS + 2_000L
    }
}
