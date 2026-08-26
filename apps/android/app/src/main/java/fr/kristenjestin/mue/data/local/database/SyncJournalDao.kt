package fr.kristenjestin.mue.data.local.database

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The three journal writes a business DAO has to make in its own transaction.
 *
 * It is a super-interface rather than a separate DAO because of how Room composes: a
 * `@Transaction` default method may only call methods declared on the DAO it lives in, so a
 * `MeasurementDao` that must write `measurements` and `sync_mutations` atomically has to own
 * both statements. Inheriting them keeps one definition for every DAO that will need it —
 * [SyncDao] today, the activity DAO in a later phase.
 *
 * There is no `@Dao` here on purpose: only the interfaces the database exposes carry it.
 */
interface SyncJournalDao {

    /**
     * `ABORT` rather than `REPLACE`: a mutation id is a fresh UUID at every call site, so a
     * collision is a bug worth a crash in a test rather than a change silently overwritten.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueueMutation(mutation: SyncMutationEntity)

    /**
     * The revision the server last acknowledged, read in the writer's own transaction. Null
     * covers both cases that mean the same thing: no row at all, and a row the server has never
     * acknowledged.
     */
    @Query(
        "SELECT revision FROM sync_aggregate_state " +
            "WHERE aggregate_type = :aggregateType AND aggregate_id = :aggregateId"
    )
    suspend fun revisionOf(aggregateType: String, aggregateId: String): Long?

    /**
     * `IGNORE` so an aggregate the server already knows keeps its revision and origin: this
     * only has to make the row exist before the two updates below can find it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAggregateStateIfAbsent(state: SyncAggregateStateEntity)

    /** FR-SYNC-005: the business row goes, this one stays and carries the tombstone. */
    @Query(
        "UPDATE sync_aggregate_state SET deleted_at = :deletedAt, last_mutation_id = :mutationId " +
            "WHERE aggregate_type = :aggregateType AND aggregate_id = :aggregateId"
    )
    suspend fun markAggregateDeleted(
        aggregateType: String,
        aggregateId: String,
        deletedAt: Long,
        mutationId: String,
    )

    /**
     * Clears the tombstone. Writing the same date again after deleting it is an ordinary edit,
     * and a tombstone left behind would have the next pull delete the row the user just typed.
     */
    @Query(
        "UPDATE sync_aggregate_state SET deleted_at = NULL, last_mutation_id = :mutationId " +
            "WHERE aggregate_type = :aggregateType AND aggregate_id = :aggregateId"
    )
    suspend fun markAggregateAlive(
        aggregateType: String,
        aggregateId: String,
        mutationId: String,
    )
}
