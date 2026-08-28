package fr.kristenjestin.mue.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import fr.kristenjestin.mue.domain.repository.ScanPreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The scanner's one flag, in the preferences file the app already keeps.
 *
 * Written exactly like [DataStoreTimerPreferencesRepository], down to the `IOException` guard: a
 * preferences file that cannot be read must not take a screen down, and a flag that reads `false`
 * costs one system prompt where a crash costs the app.
 *
 * The key is namespaced by module rather than by permission — `food_` and not `camera_` — because
 * the question it answers is "has the Food module asked", and a second feature that ever wants the
 * camera would be asking its own question at its own moment.
 */
class DataStoreScanPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScanPreferencesRepository {

    override val cameraPermissionRequested: Flow<Boolean> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored -> stored[KEY_CAMERA_PERMISSION_REQUESTED] ?: false }
        .flowOn(ioDispatcher)

    override suspend fun setCameraPermissionRequested(requested: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { it[KEY_CAMERA_PERMISSION_REQUESTED] = requested }
        }
    }

    private companion object {
        val KEY_CAMERA_PERMISSION_REQUESTED =
            booleanPreferencesKey("food_camera_permission_requested")
    }
}
