package fr.kristenjestin.mue.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The one flag the timer stores outside the database (PRD FR-TIMER-012).
 *
 * It sits apart from [UserPreferencesRepository] because no screen shows it: it is not a
 * preference the user sets, it is what the app remembers about a question it has already asked.
 */
interface TimerPreferencesRepository {

    /**
     * Whether `POST_NOTIFICATIONS` has already been asked for, so a refusal is never asked
     * again automatically (FR-TIMER-012).
     *
     * A persisted boolean is the only correct implementation: the platform rationale hint
     * answers `false` both before the first request and after a permanent denial, so it cannot
     * tell the two apart. Emits `false` until something has been saved.
     */
    val notificationPermissionRequested: Flow<Boolean>

    suspend fun setNotificationPermissionRequested(requested: Boolean)
}
