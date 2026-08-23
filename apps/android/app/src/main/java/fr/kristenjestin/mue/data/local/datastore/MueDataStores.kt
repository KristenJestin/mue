package fr.kristenjestin.mue.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The two preference files. The delegates guarantee a single instance per process,
 * which DataStore requires; both are read through the app container only.
 */
private const val PROFILE_STORE_NAME = "user_profile"
private const val PREFERENCES_STORE_NAME = "user_preferences"

val Context.userProfileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PROFILE_STORE_NAME,
)

val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFERENCES_STORE_NAME,
)
