package fr.kristenjestin.mue

import android.app.Application
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
 */
class MueApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** Outlives every screen, because the work below must finish whatever the user does next. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // The height and the birth date moved from DataStore to Room in database version 5, and
        // the profile screen reads them from Room from this launch onwards. So the copy cannot
        // wait for a server to be paired: a phone that upgrades and never synchronises would
        // otherwise open Profile on a blank height it had typed years ago. Off the main thread,
        // guarded by `sync_state.profile_seeded`, and a no-op on every later start.
        applicationScope.launch { container.sync.healthProfileSeeding.seedOnce() }
    }
}
