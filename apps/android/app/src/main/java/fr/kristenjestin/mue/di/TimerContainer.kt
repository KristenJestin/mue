package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.datastore.userPreferencesDataStore
import fr.kristenjestin.mue.data.repository.AndroidTimerClock
import fr.kristenjestin.mue.data.repository.DataStoreTimerPreferencesRepository
import fr.kristenjestin.mue.data.repository.RoomTimedActivityRepository
import fr.kristenjestin.mue.domain.model.TimerClock
import fr.kristenjestin.mue.domain.repository.TimedActivityRepository
import fr.kristenjestin.mue.domain.repository.TimerPreferencesRepository

/**
 * Everything the Activity Timer needs, registered in one place.
 *
 * [AppContainer] gains a single property for the whole module, so the timer screen, the
 * notification, the receivers and the review hand-off can be built against this surface without
 * any of them having to touch the shipped container again.
 *
 * Lazy for the same reason as everything in [AppContainer]: the timer's repository opens the
 * database, and a cold start that shows no timer must not pay for it. The clock is the one
 * exception — it owns nothing, so there is nothing to defer.
 */
class TimerContainer(
    private val applicationContext: Context,
    private val database: MueDatabase,
) {
    /** PRD 9: injected, so PRD 14 supplies a reboot as a value instead of reproducing one. */
    val clock: TimerClock = AndroidTimerClock

    val timedActivityRepository: TimedActivityRepository by lazy {
        RoomTimedActivityRepository(
            database = database,
            timerDao = database.timerDao(),
            activityDao = database.activityDao(),
        )
    }

    /** The `POST_NOTIFICATIONS` flag of FR-TIMER-012, in the existing preferences file. */
    val timerPreferencesRepository: TimerPreferencesRepository by lazy {
        DataStoreTimerPreferencesRepository(applicationContext.userPreferencesDataStore)
    }
}
