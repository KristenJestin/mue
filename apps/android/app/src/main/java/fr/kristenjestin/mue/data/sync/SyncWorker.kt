package fr.kristenjestin.mue.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.kristenjestin.mue.MueApplication
import java.util.concurrent.TimeUnit

/**
 * The deferred synchronisation of PRD 19: WorkManager, under network and battery constraints,
 * with no promise of an exact hour and no foreground service.
 *
 * A `CoroutineWorker` and not a `Worker`: everything below it suspends, and `doWork` is
 * cancelled by the system when the constraints stop holding — which is the case
 * [SyncEngine.push]'s `NonCancellable` requeue exists for.
 *
 * ## What each result means
 *
 * - `success` — the run finished, whether or not it had anything to do. A rejected mutation is
 *   *not* a failed run (FR-SYNC-007): it is kept, marked and surfaced as `Sync issue`, and
 *   retrying it would only be refused again.
 * - `retry` — the server could not be reached, or answered something retryable. WorkManager's
 *   exponential backoff owns the waiting, which is PRD 9.4's "backoff, jamais une boucle
 *   agressive"; the client itself retries nothing.
 * - `failure` — a non-retryable outcome, including `upgrade_required`. Repeating it would burn
 *   the battery on a decision only a new build can change.
 *
 * `NotPaired` is a `success`. Mue with no server is Mue working (PRD 21), and a phone that has
 * never been paired must not accumulate failed work.
 */
class SyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? MueApplication ?: return Result.success()
        val engine = application.container.sync.engine

        return when (val outcome = engine.sync()) {
            SyncOutcome.NotPaired -> Result.success()

            is SyncOutcome.Completed -> {
                // The page loop stopped on its own bound with more waiting. Asking for another
                // run rather than looping here keeps the wakelock short and lets the constraints
                // be re-checked between pages of a long initial history.
                if (outcome.moreAvailable) Result.retry() else Result.success()
            }

            is SyncOutcome.UpgradeRequired -> Result.failure()

            is SyncOutcome.Failed -> if (outcome.retryable) Result.retry() else Result.failure()
        }
    }
}

/**
 * Where PRD 9.4's triggers are registered. One object, so nothing else in the app has to know
 * the work names, the constraints or the backoff.
 *
 * The constraints are the PRD's: a connected network, and a battery that is not low. Nothing
 * asks for an unmetered network — a private server on the home network is exactly the case a
 * metered check would refuse, and the payload is a handful of kilobytes.
 */
object SyncScheduler {

    /** The unique name the period is registered under. Public so a test can go and read it. */
    const val PERIODIC_WORK: String = "mue.sync.periodic"
    /**
     * The unique name every one-shot is registered under. Public for the same reason
     * [PERIODIC_WORK] is: `SyncSchedulerCollapseTest` has to read the queue this names to prove
     * that a burst of enqueues leaves one request behind and not forty.
     */
    const val ONE_SHOT_WORK: String = "mue.sync.now"

    /**
     * How long the phone may stay wrong while nobody is looking at it.
     *
     * PRD 9.4 asks for "périodiquement par le mécanisme Android approprié, sans promesse d'heure
     * exacte" and names no figure, so this is an implementation choice and has to be argued.
     *
     * It was six hours, and six hours is indefensible now that there is something to compare it
     * to. A weight written on the Web at nine in the morning could be invisible to the phone until
     * three in the afternoon; the owner had no way to tell, and pressing `Sync now` to find out is
     * exactly the question [LiveSyncChannel] exists to stop him having to ask.
     *
     * One hour, and not the fifteen-minute floor:
     *
     * - **The live channel already owns the case that matters.** While the app is open the phone
     *   is current within seconds, and opening it synchronises anyway (PRD 9.4's first three
     *   triggers). What is left for this worker is the phone in a pocket: the outbox of a change
     *   made offline, waiting for a network, and a bound on how stale Room is when something other
     *   than a screen reads it. An hour bounds both without pretending to be live.
     * - **Fifteen minutes would be ninety-six attempts a day**, most of them away from home where
     *   every one is a TCP connection that will not complete — and a connection that fails slowly
     *   holds the radio up for the length of the connect timeout. Four times the wakeups to shorten
     *   a window nobody is watching.
     * - **An hour is comfortably above the floor**, so Doze and App Standby batch it with whatever
     *   else the device is already waking for instead of deferring a request that was too eager to
     *   be honoured. WorkManager makes no promise of the exact minute, and this asks for none.
     *
     * ### What it costs
     *
     * Twenty-four runs a day rather than four. A run that finds nothing is one TLS handshake, one
     * `push` with an empty batch and one `pull` that answers an empty page — a few kilobytes and
     * well under a second of radio, and only when the constraints already hold. Because Doze
     * coalesces deferrable work into maintenance windows, the marginal cost of most of those runs
     * is close to zero: the radio is up for something else. The honest worst case is a phone in
     * constant use with a reachable server, where twenty extra wakeups a day are on the order of a
     * tenth of a percent of a battery — less than the screen spends rendering the list once.
     *
     * Away from home the cost is *lower* than at home, not higher: the connection is refused and
     * the run ends in milliseconds.
     */
    const val PERIOD_HOURS: Long = 1L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Registered at every application start, and **updated** rather than kept.
     *
     * `KEEP` was right while there was only ever one period to register, and wrong the moment
     * there were two. A phone that installed the six-hour build has that period written into its
     * WorkManager database; `KEEP` sees a registration under this name, does nothing, and the new
     * period never arrives — not on this launch, not on any launch, not until the app is
     * reinstalled. A constant nobody can ever change is not a constant, it is a fossil.
     *
     * `UPDATE` rewrites the specification in place and keeps the schedule: it does not restart the
     * current interval, so an app started ten times in a minute still does not push the next run
     * ten times further away, which is the property `KEEP` was chosen for. What it costs is one
     * small write per start, and what it buys is that [PERIOD_HOURS] means something.
     */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * `Sync now`, the initial association, the return to the foreground (PRD 9.4) — and, since
     * [PushOnWrite], the local write that PRD 9.4 forgot to list.
     *
     * The last of those is the reason `REPLACE` is now load-bearing rather than merely tidy, so
     * it is worth stating what it does and does not buy. It collapses any number of pending
     * requests into one: `SyncSchedulerCollapseTest` enqueues forty in a row on a device and
     * finds a single work info. What it does not do is spare a run that has already started —
     * `REPLACE` cancels it — which is why the write trigger holds its own quiet window and hands
     * this one request per burst rather than leaning on the policy to sort out forty.
     *
     * `REPLACE` because the newest request is the one the user is waiting on, and because two
     * concurrent runs would be serialised by the engine's own gate anyway — replacing is honest
     * about that rather than queueing a run that will find nothing left to do.
     *
     * Not expedited: PRD 19 says an ordinary synchronisation must not demand a foreground
     * service, and an expedited request that cannot get a quota slot falls back to exactly one.
     */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** PRD 9.4's "au démarrage de l'application", off the startup path and under constraints. */
    fun onApplicationStart(context: Context) {
        ensurePeriodic(context)
        syncNow(context)
    }
}
