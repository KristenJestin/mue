package fr.kristenjestin.mue.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The claim `PushOnWrite` leans on, checked against a real WorkManager rather than against its
 * documentation: **`ExistingWorkPolicy.REPLACE` on a unique name leaves one request, not forty.**
 *
 * It is checked here because it cannot be checked anywhere else. `enqueueUniqueWork` is a call
 * into a library with its own database and its own scheduler, and the collapsing is a property of
 * that database — a JVM test could only assert that `SyncScheduler` passes the enum, which is a
 * restatement of the code and not a fact about the queue.
 *
 * What the queue actually does turned out to be stronger than "the earlier requests are
 * cancelled": `REPLACE` **deletes** the pending work it replaces, so forty enqueues leave a single
 * row behind and the fortieth carries an id the first one never had. That is worth knowing
 * precisely, because it is also where the policy stops helping.
 *
 * ## Why the quiet window still exists on top of it
 *
 * `REPLACE` collapses what is *waiting* and cancels what is *running*. A burst that reached
 * WorkManager one row at a time would therefore keep cancelling the run started by the row before
 * it, and the send would not finish until the burst stopped — the engine survives that, its
 * requeue is `NonCancellable`, but the user's change waits. Collapsing in memory first means
 * WorkManager is handed one request per gesture and never has to arbitrate. The forty database
 * transactions saved are the smaller half of the argument.
 */
@RunWith(AndroidJUnit4::class)
class SyncSchedulerCollapseTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    /** A burst the size of the recipe that prompted all this. */
    private val burst = 40

    @Before
    fun clearTheQueue() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        // The app under test enqueues one of these at every start, and previous runs of this test
        // leave their own history. Both go, so what is counted below is what this test did.
        workManager.cancelUniqueWork(SyncScheduler.ONE_SHOT_WORK).result.get()
        workManager.pruneWork().result.get()
    }

    @After
    fun leaveNothingBehind() {
        workManager.cancelUniqueWork(SyncScheduler.ONE_SHOT_WORK).result.get()
        workManager.pruneWork().result.get()
    }

    @Test
    fun aBurstOfEnqueuesLeavesOneRequestBehind() {
        repeat(burst) { SyncScheduler.syncNow(context) }

        val infos = awaitUniqueWork()

        // `APPEND` here would leave forty rows and forty runs. `REPLACE` leaves one row.
        assertTrue(
            "a burst of $burst enqueues left ${infos.size} work rows under one unique name",
            infos.size <= 2,
        )
        assertTrue(
            "${infos.count { !it.state.isFinished }} requests from the burst are still runnable",
            infos.count { !it.state.isFinished } <= 1,
        )
    }

    /**
     * The same fact stated as the app experiences it: a second request does not queue behind the
     * first, it takes its place — a different row, with a different id.
     */
    @Test
    fun aSecondEnqueueReplacesTheFirstRatherThanQueueingBehindIt() {
        SyncScheduler.syncNow(context)
        val firstId = awaitUniqueWork().single().id

        SyncScheduler.syncNow(context)
        val live = awaitUniqueWork().filterNot { it.state.isFinished }

        assertEquals("exactly one request may still run under a unique name", 1, live.size)
        assertNotEquals(
            "the survivor is the first request, so the second queued behind it",
            firstId,
            live.single().id,
        )
    }

    /**
     * `enqueueUniqueWork` returns before its own database write is visible, so a read taken in the
     * same breath can legitimately see nothing. Polling for the first row removes that race
     * without weakening anything the tests above assert.
     */
    private fun awaitUniqueWork(): List<WorkInfo> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_WORK).get()
            if (infos.isNotEmpty()) return infos
            Thread.sleep(50)
        }
        throw AssertionError("no work was ever registered under ${SyncScheduler.ONE_SHOT_WORK}")
    }
}
