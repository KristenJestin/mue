package fr.kristenjestin.mue.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * PRD 9.4's missing trigger: **writing something in Mue schedules a send.**
 *
 * The specification lists the application start, the return to the foreground, `Sync now`, the
 * initial association, a suitable network and a period — every moment except the one the user
 * actually causes. The code had the same hole: `SyncScheduler.syncNow` had three callers and not
 * one of them was a save, so a birth date changed with the app open stayed at `Changes pending`
 * until the app was backgrounded or the hourly worker came round. This class is the bridge
 * between [SyncOutbox.minted] and that scheduler.
 *
 * ## Why it is here and not in `di/`
 *
 * Because none of it needs Android. It takes a flow of signals and a `() -> Unit`, so the
 * collapsing rule below is decided by `runTest`'s virtual clock in a JVM test rather than by a
 * stopwatch held against an emulator — while [schedule] itself, the only part that knows what a
 * `Context` is, is supplied by `SyncContainer`. The `data` layer gains no Android dependency
 * for any of it.
 *
 * ## Why a quiet window and not `ExistingWorkPolicy.REPLACE` alone
 *
 * `syncNow` enqueues unique work with `REPLACE`, and that genuinely does collapse a burst into a
 * single *pending* request — `SyncSchedulerCollapseTest` enqueues forty in a row on a device and
 * finds one. Relying on it alone is still wrong, for two reasons `REPLACE` cannot answer:
 *
 * 1. **`REPLACE` cancels a run that has already started.** A recipe with forty ingredients mints
 *    forty rows; if the tenth arrives while the worker spawned by the first is mid-`push`, that
 *    worker is cancelled and a new one queued, and the thirtieth cancels *that* one. The engine
 *    survives it — its requeue is `NonCancellable` — but the send does not finish until the burst
 *    stops. A window that ends before the first request is ever made removes the race instead of
 *    surviving it.
 * 2. **Forty enqueues are forty WorkManager transactions**, each one a write to its own database,
 *    scheduled off a save. Collapsing them in memory costs one `delay`.
 *
 * ## Trailing edge only
 *
 * [collapse] restarts the window at every signal and schedules once it has been quiet, so a burst
 * of any size produces exactly one send. The obvious alternative — schedule immediately, then
 * ignore signals for a window — would make the single-save case faster by [QUIET_WINDOW_MILLIS]
 * and the burst case produce two sends rather than one. Two is not forty, but one is the number
 * that was asked for, and three quarters of a second on a save nobody is watching finish is not a
 * latency anybody can perceive against a synchronisation that then has to open a TLS connection.
 *
 * `collectLatest` and a `delay` rather than the `debounce` operator: the two are the same
 * trailing-edge rule, and this one is spelled out in stable API a reader can follow into.
 */
class PushOnWrite(
    private val minted: Flow<Unit>,
    private val schedule: () -> Unit,
    private val quietWindowMillis: Long = QUIET_WINDOW_MILLIS,
) {

    /**
     * Collects until cancelled. It suspends for the life of the process, so it belongs on a scope
     * that outlives every screen.
     */
    suspend fun run() {
        minted.collapse(quietWindowMillis, schedule)
    }

    companion object {
        /**
         * How long a burst of local writes goes on collapsing into one send.
         *
         * Long enough that a save which mints several rows produces one request: a measurement
         * moved to another date mints two, a meal plan replaced mints two, and a recipe saved
         * with its ingredients mints one per aggregate touched — all of them inside a single
         * user gesture, milliseconds apart. Three quarters of a second covers a gesture that
         * writes, is followed by a second gesture the user makes without pausing, and still
         * closes long before the row could be described as waiting.
         *
         * Short enough that the whole point survives: the owner changes a date, looks at
         * `Data & sync`, and the count is already back to zero. The end-to-end delay this
         * contributes is under a second of a round trip measured at a little over two.
         *
         * It is not a retry interval and must never grow into one. Nothing here retries: a run
         * that fails is WorkManager's to back off, per PRD 9.4's "backoff, jamais une boucle
         * agressive".
         */
        const val QUIET_WINDOW_MILLIS: Long = 750L
    }
}

/**
 * One [schedule] per quiet [windowMillis], however many signals arrived during it.
 *
 * Split out from [PushOnWrite] so the rule can be read — and tested — as the few lines it is.
 */
private suspend fun Flow<Unit>.collapse(windowMillis: Long, schedule: () -> Unit) {
    collectLatest {
        delay(windowMillis)
        try {
            schedule()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // A scheduler that throws must not take the trigger down with it for the rest of the
            // process. `collectLatest` would let the exception out of the collection, the
            // coroutine would end, and every save after it would be journalled with nobody
            // listening — silently, which is the exact failure this class exists to remove.
            // Swallowing costs one send; the next write schedules again and the periodic worker
            // is still underneath.
            Unit
        }
    }
}
