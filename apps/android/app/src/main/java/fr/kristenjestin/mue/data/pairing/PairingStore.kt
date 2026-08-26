package fr.kristenjestin.mue.data.pairing

import fr.kristenjestin.mue.data.local.database.SyncDao
import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.sync.SyncTokenStore

/**
 * The two things [ServerPairing] does to storage, as an interface — and the whole of what it is
 * allowed to know about Room.
 *
 * Same shape and same reason as `SyncStore`, which the sync engine already sits behind: the
 * decisions this package makes — nothing stored until the bearer has been proved, `account_id`
 * kept across a disconnect, no business row ever deleted — are the ones PRD 9.2 and 9.3 are
 * about, and they have to be provable in milliseconds on every commit. Behind this interface they
 * are; behind a `SyncDao` they would need an emulator.
 *
 * There is deliberately no `delete` and no `clear`. PRD 9.3's "aucune donnée métier locale n'est
 * supprimée" is not a rule this class remembers to follow, it is a rule it has no verb for.
 */
interface PairingStore {

    /** The single `sync_state` row, or null before anything has ever written it. */
    suspend fun state(): SyncStateEntity?

    /**
     * Writes the row whole.
     *
     * `SyncStateEntity`'s primary key is the constant [SyncStateEntity.ROW_ID], so a replace both
     * inserts the row on a phone that has never had one and overwrites the row on a phone that
     * has — which is why the caller composes the complete entity rather than issuing an update
     * per column, and why a field it forgets to carry over is visible in one place.
     */
    suspend fun save(state: SyncStateEntity)
}

/** [PairingStore] over the shipped [SyncDao]. Two statements, no transaction, no logic. */
class RoomPairingStore(private val syncDao: SyncDao) : PairingStore {

    override suspend fun state(): SyncStateEntity? = syncDao.syncState()

    override suspend fun save(state: SyncStateEntity) = syncDao.putSyncState(state)
}

/**
 * The device bearer, as the two calls the pairing makes of it.
 *
 * [SyncTokenStore] is a concrete class over `AndroidKeyStore` and a DataStore, so it cannot exist
 * on the JVM at all: `KeyStore.getInstance("AndroidKeyStore")` throws before the first assertion.
 * The interface is what lets "the token is written only after the bearer is proved" and "a failed
 * pairing leaves no token behind" be unit tests rather than notes in a review.
 */
interface TokenStore {
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun clear()
}

/** [TokenStore] over the shipped Keystore-backed store. Nothing is added, nothing is decided. */
class KeystoreTokenStore(private val delegate: SyncTokenStore) : TokenStore {

    override suspend fun read(): String? = delegate.read()

    override suspend fun write(token: String) = delegate.write(token)

    override suspend fun clear() = delegate.clear()
}
