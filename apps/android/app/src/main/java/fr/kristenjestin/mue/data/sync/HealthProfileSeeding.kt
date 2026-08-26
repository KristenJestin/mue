package fr.kristenjestin.mue.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
 * So it is a startup task, run before the first synchronisation, guarded by
 * `sync_state.profile_seeded` and idempotent regardless: it re-reads the flag inside the
 * transaction, and it inserts rather than replaces, so a profile that already reached Room —
 * from the server, or from an earlier run — is never overwritten by a stale local copy.
 *
 * The Preferences keys are left in place. Deleting them would be a second write to a store that
 * cannot join this transaction, so a crash between the two would destroy the only copy of a
 * height the user typed before the upgrade.
 */
class HealthProfileSeeding(
    private val database: MueDatabase,
    private val profileDataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun seedOnce() {
        withContext(ioDispatcher) {
            if (database.syncDao().syncState()?.profileSeeded == true) return@withContext

            // Read outside the transaction: this is file I/O on another store, and holding the
            // database's write lock across it would block every screen for its duration.
            val legacy = profileDataStore.data
                .catch { throwable ->
                    if (throwable is IOException) emit(emptyPreferences()) else throw throwable
                }
                .first()
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
        }
    }
}
