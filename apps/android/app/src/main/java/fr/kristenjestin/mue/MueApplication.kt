package fr.kristenjestin.mue

import android.app.Application
import fr.kristenjestin.mue.data.sync.SyncScheduler
import fr.kristenjestin.mue.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

            // Sync PRD 9.4: attempt a synchronisation at application start, and register the
            // periodic one. Both are WorkManager requests, so an unpaired phone, a phone with no
            // network and a phone on a low battery all enqueue and none of them runs.
            SyncScheduler.onApplicationStart(this@MueApplication)
        }
    }
}
