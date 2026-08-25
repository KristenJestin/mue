package fr.kristenjestin.mue.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import fr.kristenjestin.mue.domain.repository.TimerPreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The one flag of FR-TIMER-012, in the preferences file the app already keeps.
 *
 * It has a repository of its own rather than a sixth field on `UserPreferences`: no screen shows
 * it and nothing but the permission request reads it, so widening the shipped value would touch
 * five tested files to carry a flag none of them cares about.
 */
class DataStoreTimerPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TimerPreferencesRepository {

    override val notificationPermissionRequested: Flow<Boolean> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored -> stored[KEY_NOTIFICATION_PERMISSION_REQUESTED] ?: false }
        .flowOn(ioDispatcher)

    override suspend fun setNotificationPermissionRequested(requested: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { it[KEY_NOTIFICATION_PERMISSION_REQUESTED] = requested }
        }
    }

    private companion object {
        val KEY_NOTIFICATION_PERMISSION_REQUESTED =
            booleanPreferencesKey("timer_notification_permission_requested")
    }
}
