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

    private const val PERIODIC_WORK = "mue.sync.periodic"
    private const val ONE_SHOT_WORK = "mue.sync.now"

    /** PRD 9.4 gives no exact hour, so this is the shortest period WorkManager will honour. */
    private const val PERIOD_HOURS = 6L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Registered once per install and kept: `KEEP` means an app start does not reset the period
     * or the backoff of a run already waiting, which is what an app started ten times in a
     * minute would otherwise do.
     */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * `Sync now`, the initial association and the return to the foreground (PRD 9.4).
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
