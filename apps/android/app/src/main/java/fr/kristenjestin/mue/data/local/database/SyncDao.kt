package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Everything the sync engine reads and writes that is not a business row: the outbox, the
 * remote identity of each aggregate, and the one row of [SyncStateEntity].
 *
 * It inherits [SyncJournalDao] so a change arriving from the server is journalled through the
 * same three statements a local write uses — one definition of what the journal means,
 * whichever side the change came from.
 *
 * The literals in the SQL below — `'pending'`, `'failed'`, `id = 0` — are the constants on
 * [SyncMutationEntity] and [SyncStateEntity]. They are spelled out rather than bound, because
 * a bound parameter would let a caller ask for a state that does not exist and get an empty
 * list instead of a compile error.
 */
@Dao
interface SyncDao : SyncJournalDao {

    /**
     * The next mutations to send, oldest first. `state = 'pending'` is what implements
     * FR-SYNC-007: a `failed` row stays in the table, keeps the user's change, and is simply
     * never selected, so it cannot hold up anything queued behind it.
     *
     * `rowid` breaks ties on purpose. The two mutations of one edit — the delete of the old
     * date and the upsert of the new one — are written in the same transaction and can share a
     * millisecond, and sending the upsert first would have the server apply a deletion to the
     * row it had just created.
     */
    @Query(
        "SELECT * FROM sync_mutations WHERE state = 'pending' " +
            "ORDER BY created_at ASC, rowid ASC LIMIT :limit"
    )
    suspend fun pendingMutations(limit: Int): List<SyncMutationEntity>

    @Query("SELECT * FROM sync_mutations WHERE mutation_id = :mutationId")
    suspend fun mutation(mutationId: String): SyncMutationEntity?

    @Query("SELECT COUNT(*) FROM sync_mutations WHERE state = :state")
    suspend fun countInState(state: String): Int

    /** `Sync issue` in `Data & sync` (FR-SYNC-007) is this count being greater than zero. */
    @Query("SELECT COUNT(*) FROM sync_mutations WHERE state = 'failed'")
    fun observeFailedCount(): Flow<Int>

    @Query("UPDATE sync_mutations SET state = :state WHERE mutation_id IN (:mutationIds)")
    suspend fun setState(mutationIds: List<String>, state: String)

    /**
     * A rejected mutation keeps its payload and its error so `Data & sync` can say what the
     * server refused. FR-SYNC-007 forbids deleting local data to repair an error automatically.
     */
    @Query(
        "UPDATE sync_mutations SET state = 'failed', attempt_count = attempt_count + 1, " +
            "last_error_code = :errorCode, last_error_message = :errorMessage " +
            "WHERE mutation_id = :mutationId"
    )
    suspend fun markFailed(mutationId: String, errorCode: String?, errorMessage: String?)

    /** An accepted mutation has done its work; only accepted ones ever leave the table. */
    @Query("DELETE FROM sync_mutations WHERE mutation_id = :mutationId")
    suspend fun deleteMutation(mutationId: String)

    @Query(
        "SELECT * FROM sync_aggregate_state " +
            "WHERE aggregate_type = :aggregateType AND aggregate_id = :aggregateId"
    )
    suspend fun aggregateState(
        aggregateType: String,
        aggregateId: String,
    ): SyncAggregateStateEntity?

    /** The tombstones of one aggregate type, which a pull consults before re-creating a row. */
    @Query(
        "SELECT * FROM sync_aggregate_state " +
            "WHERE aggregate_type = :aggregateType AND deleted_at IS NOT NULL"
    )
    suspend fun tombstones(aggregateType: String): List<SyncAggregateStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAggregateState(state: SyncAggregateStateEntity)

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun observeSyncState(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun syncState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncStateIfAbsent(state: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncState(state: SyncStateEntity)

    /**
     * FR-SYNC-002 step 5: the cursor advances only once the changes it covers are applied, so
     * this update belongs inside the transaction that applied them.
     */
    @Query(
        "UPDATE sync_state SET cursor = :cursor, last_success_at = :at, " +
            "last_error_code = NULL, last_error_message = NULL WHERE id = 0"
    )
    suspend fun recordSuccess(cursor: String?, at: Long)

    /** FR-SYNC-008: an unreachable server is a normal state, so this records but never alarms. */
    @Query(
        "UPDATE sync_state SET last_error_code = :errorCode, last_error_message = :errorMessage " +
            "WHERE id = 0"
    )
    suspend fun recordFailure(errorCode: String?, errorMessage: String?)

    @Query("UPDATE sync_state SET profile_seeded = 1 WHERE id = 0")
    suspend fun markProfileSeeded()
}
