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

private const val FOOD_CATALOGUE_STORE_NAME = "food_catalogue"

val Context.userProfileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PROFILE_STORE_NAME,
)

val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFERENCES_STORE_NAME,
)

val Context.syncTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SYNC_TOKEN_STORE_NAME,
)

/**
 * Which version of the embedded Ciqual subset is already installed (PRD_FOOD 20.2).
 *
 * It is a preference and not a Room row on purpose. The guard runs on **every cold start**, and
 * the sync chunk already paid for the other answer: `HealthProfileSeeding` opens the database at
 * each launch to read one boolean. A phone that opens Mue on the weight tab must not build a
 * Room connection to discover the food catalogue is up to date. A file of its own, rather than a
 * key in `user_preferences`, because this is not a user preference at all: nothing shows it,
 * nothing lets it be changed, and PRD_FOOD 21.1 keeps it out of synchronisation.
 */
val Context.foodCatalogueDataStore: DataStore<Preferences> by preferencesDataStore(
    name = FOOD_CATALOGUE_STORE_NAME,
)
