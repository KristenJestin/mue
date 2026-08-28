package fr.kristenjestin.mue

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import fr.kristenjestin.mue.data.sync.SyncScheduler
import fr.kristenjestin.mue.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the single dependency container for the whole app.
 *
 * Mue deliberately uses manual dependency injection: three screens do not justify
 * the build-time cost of an annotation-processed framework.
 *
 * ## What may run here
 *
 * `onCreate` runs before the first frame of every cold start, so nothing in it may open a file
 * that is not needed. In particular **nothing here opens Room.** [AppContainer] is lazy for that
 * reason, and the startup work below respects it: `seedOnce` decides whether it has anything to
 * do from a DataStore preference and only asks for a database handle on the one launch where the
 * answer is yes, and the synchronisation is handed to WorkManager rather than run inline, so it
 * happens under the network and battery constraints of sync PRD 19 and off this path entirely.
 */
class MueApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** Outlives every screen, because the work below must finish whatever the user does next. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        applicationScope.launch {
            // The height and the birth date moved from DataStore to Room in database version 5,
            // and the profile screen reads them from Room from this launch onwards. So the copy
            // cannot wait for a server to be paired: a phone that upgrades and never
            // synchronises would otherwise open Profile on a blank height it had typed years
            // ago. Guarded by a preference, so every start after the first costs one small
            // protobuf read and no database open at all.
            container.sync.healthProfileSeeding.seedOnce()

            // PRD_FOOD 9.1: the embedded Ciqual subset is available "dès la première
            // ouverture", so it installs here rather than on the first visit to the Food tab.
            // The guard is a DataStore preference compared against an asset file name, so a
            // start with nothing to install opens neither the database nor the catalogue —
            // which is why this can sit on every cold start beside the profile copy.
            container.food.ciqualSeeding.seedIfNeeded()

            // Sync PRD 9.4: attempt a synchronisation at application start, and register the
            // periodic one. Both are WorkManager requests, so an unpaired phone, a phone with no
            // network and a phone on a low battery all enqueue and none of them runs.
            SyncScheduler.onApplicationStart(this@MueApplication)
        }

        startLiveSync()
    }

    /**
     * Sync PRD 9.4's live channel, and the only place its lifetime is decided.
     *
     * ## Why the process lifecycle and not a screen
     *
     * "Foreground" is a property of the application, not of a composition. Scoped to
     * [ProcessLifecycleOwner] the connection opens once when Mue becomes visible and closes once
     * when it stops, so rotating the phone, walking between the five tabs or opening the timer
     * does not close and reopen a socket — the 700 ms debounce [ProcessLifecycleOwner] already
     * carries is exactly what a configuration change needs and exactly what a hand-rolled
     * activity counter gets wrong.
     *
     * It was first written as a `LaunchedEffect` in `MueApp`, which was wrong twice over: it tied
     * a network connection to the lifetime of a composition, and it made the channel invisible to
     * any test that does not pump a Compose frame clock. The instrumented test that proves a
     * server write arrives on the phone failed for exactly that reason and was right to.
     *
     * ## What it costs when nobody is looking
     *
     * Nothing. `repeatOnLifecycle` cancels the whole thing at `ON_STOP`: no socket, no timer, no
     * wakelock, and no work at all in a process that has been killed. The deferred worker of PRD
     * 19 remains the only thing that runs then, which is why this is an addition to PRD 9.4's
     * triggers and not a replacement for any of them.
     *
     * ## And on the cold start path
     *
     * Nothing either. Registering the observer is a couple of objects; [SyncContainer.liveSync]
     * is resolved *inside* the block, after the application is actually visible, so an HTTP
     * client is built by the launch that shows a screen rather than by the one that measures it.
     */
    private fun startLiveSync() {
        val owner = ProcessLifecycleOwner.get()
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // `lifecycleScope` is the main dispatcher, and the channel's loop has no business
                // there: it opens sockets, reads Room and waits. Cancellation still arrives
                // through `withContext`, so `ON_STOP` closes the connection as directly as if it
                // ran here.
                withContext(Dispatchers.IO) { container.sync.liveSync.run() }
            }
        }
    }
}
