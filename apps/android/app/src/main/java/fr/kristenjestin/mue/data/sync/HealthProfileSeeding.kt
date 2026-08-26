package fr.kristenjestin.mue.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.withTransaction
import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.repository.DataStoreUserProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Copies the height and the birth date out of the version 4 Preferences file into the
 * `health_profile` table, once.
 *
 * **It cannot be a Room `Migration`.** A migration is handed a `SupportSQLiteDatabase`, which
 * can execute SQL against one file and nothing else; the Preferences file is a protobuf in
 * another directory that SQL has no way to open. A migration that tried would either fail or,
 * far worse, quietly create an empty profile and hide the loss behind a green test.
 *
 * So it is a startup task, run before the first synchronisation, guarded twice and idempotent
 * regardless: it re-reads `sync_state.profile_seeded` inside the transaction, and it inserts
 * rather than replaces, so a profile that already reached Room — from the server, or from an
 * earlier run — is never overwritten by a stale local copy.
 *
 * The Preferences keys are left in place. Deleting them would be a second write to a store that
 * cannot join this transaction, so a crash between the two would destroy the only copy of a
 * height the user typed before the upgrade.
 *
 * ## Why the outer guard is a preference and not the Room flag
 *
 * `MueApplication.onCreate` calls [seedOnce] unconditionally at every cold start, and reading
 * `sync_state.profile_seeded` means **opening the database to learn that there is nothing to
 * do**. That contradicts `AppContainer`'s own contract — everything in it is lazy so a launch
 * that never touches Room does not pay for it — and it puts a disk open, a schema check and a
 * migration check on the startup path of every launch for the rest of the app's life, for a
 * task that runs exactly once ever.
 *
 * So the fast path is [KEY_SEEDED] in the Preferences file this class already had to read. A
 * preference read is a small protobuf on the IO dispatcher; a Room open is not. The Room flag
 * stays as the *inner* guard, because it is the only one that can be written inside the same
 * transaction as the copy — a preference written next to a transaction that rolled back would
 * claim a seeding that never happened, and the height would be lost for good. Belt outside,
 * braces inside, and the braces are the ones that hold.
 */
class HealthProfileSeeding(
    private val database: MueDatabase,
    private val profileDataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun seedOnce() {
        withContext(ioDispatcher) {
            // Read outside the transaction: this is file I/O on another store, and holding the
            // database's write lock across it would block every screen for its duration.
            val legacy = profileDataStore.data
                .catch { throwable ->
                    if (throwable is IOException) emit(emptyPreferences()) else throw throwable
                }
                .first()

            // The whole point of the outer guard: on every start after the first, this returns
            // without ever asking for a database handle.
            if (legacy[KEY_SEEDED] == true) return@withContext

            if (database.syncDao().syncState()?.profileSeeded == true) {
                // Room says it is done and the preference did not. That is an upgrade from the
                // build that had no preference, so record it and take the fast path next time.
                markSeededInPreferences()
                return@withContext
            }

            val heightCm = legacy[DataStoreUserProfileRepository.KEY_HEIGHT_CM]
            val birthDate = legacy[DataStoreUserProfileRepository.KEY_BIRTH_DATE]

            database.withTransaction {
                val syncDao = database.syncDao()
                syncDao.insertSyncStateIfAbsent(SyncStateEntity())
                if (syncDao.syncState()?.profileSeeded == true) return@withTransaction

                if (heightCm != null || birthDate != null) {
                    database.healthProfileDao().insertIfAbsent(
                        HealthProfileEntity(heightCm = heightCm, birthDate = birthDate)
                    )
                }
                syncDao.markProfileSeeded()
            }

            // After the transaction, never before: a preference written first and a transaction
            // that then rolled back would skip the copy for good.
            markSeededInPreferences()
        }
    }

    private suspend fun markSeededInPreferences() {
        profileDataStore.edit { preferences -> preferences[KEY_SEEDED] = true }
    }

    companion object {
        /**
         * The cold-start guard. It lives in the profile Preferences file rather than in one of
         * its own because this class already reads that file for the two legacy keys, so the
         * fast path costs the reads it was going to make anyway and no extra store is opened.
         */
        val KEY_SEEDED = booleanPreferencesKey("health_profile_seeded")
    }
}
