package fr.kristenjestin.mue.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserPreferencesRepository {

    override val preferences: Flow<UserPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored ->
            UserPreferences(
                hapticsEnabled = stored[KEY_HAPTICS_ENABLED]
                    ?: UserPreferences.DEFAULT.hapticsEnabled,
            )
        }
        .flowOn(ioDispatcher)

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { it[KEY_HAPTICS_ENABLED] = enabled }
        }
    }

    private companion object {
        val KEY_HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    }
}
