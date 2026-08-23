package fr.kristenjestin.mue.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException

class DataStoreUserProfileRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserProfileRepository {

    override val profile: Flow<UserProfile> = dataStore.data
        // A corrupted or unreadable file must not crash the app; an empty profile is
        // the honest fallback, and every field is optional anyway.
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it.toUserProfile() }
        .flowOn(ioDispatcher)

    override suspend fun save(profile: UserProfile) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences.put(KEY_DISPLAY_NAME, MueValidation.normalizeDisplayName(profile.displayName))
                preferences.put(KEY_HEIGHT_CM, profile.heightCm)
                preferences.put(KEY_BIRTH_DATE, profile.birthDate?.toString())
            }
        }
    }

    private fun Preferences.toUserProfile(): UserProfile = UserProfile(
        displayName = MueValidation.normalizeDisplayName(this[KEY_DISPLAY_NAME]),
        heightCm = this[KEY_HEIGHT_CM],
        birthDate = this[KEY_BIRTH_DATE]?.toLocalDateOrNull(),
    )

    private fun String.toLocalDateOrNull(): LocalDate? =
        try {
            LocalDate.parse(this)
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        val KEY_HEIGHT_CM = intPreferencesKey("height_cm")
        val KEY_BIRTH_DATE = stringPreferencesKey("birth_date")
    }
}

/** A null value clears the key so an absent field never lingers as a stale one. */
private fun <T : Any> androidx.datastore.preferences.core.MutablePreferences.put(
    key: Preferences.Key<T>,
    value: T?,
) {
    if (value == null) remove(key) else set(key, value)
}
