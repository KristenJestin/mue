package fr.kristenjestin.mue.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The three preference files. The delegates guarantee a single instance per process,
 * which DataStore requires; all three are read through the app container only.
 */
private const val PROFILE_STORE_NAME = "user_profile"
private const val PREFERENCES_STORE_NAME = "user_preferences"

/**
 * The session bearer's ciphertext lives alone (sync PRD 9.2). A store of its own means
 * `Disconnect server` clears a file nothing else writes, and no unrelated preference read ever
 * loads the encrypted token into memory.
 */
private const val SYNC_TOKEN_STORE_NAME = "sync_token"

val Context.userProfileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PROFILE_STORE_NAME,
)

val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFERENCES_STORE_NAME,
)

val Context.syncTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SYNC_TOKEN_STORE_NAME,
)
