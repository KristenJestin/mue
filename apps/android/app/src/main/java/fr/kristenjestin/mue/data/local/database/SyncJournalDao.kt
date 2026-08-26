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

    /** The highest stamp any waiting mutation carries, or null when the outbox is empty. */
    @Query("SELECT MAX(created_at) FROM sync_mutations")
    suspend fun highestMutationStamp(): Long?

    /**
     * The same mutation, stamped with the next value of the outbox's **local sequence**.
     *
     * `pendingMutations` sends in `created_at` order, and `created_at` used to be
     * `System.currentTimeMillis()` alone. PRD 12.3 and 13.1 forbid an order that rests on the
     * phone's clock, and the `rowid` tie-break added for the same-millisecond case does not
     * cover the case that actually happens: a phone syncing its time **steps its clock
     * backwards** between two saves, and the second save is then sent before the first. For a
     * measurement whose date moved, that is the server applying the deletion to the row it has
     * just created.
     *
     * So the stamp is `max(wall clock, highest stamp in the outbox + 1)`: monotonically
     * increasing by construction, and still a millisecond count that reads as one. It is
     * computed from the table rather than from a counter in memory, which is what makes it
     * survive process death without any recovery step — and there is nothing to recover, since
     * an empty outbox has no order left to preserve.
     *
     * It must be called inside the same transaction as the insert, which is where every caller
     * already is: reading the maximum in one transaction and inserting in another would let two
     * concurrent writers read the same maximum and stamp the same value.
     */
    suspend fun sequenced(mutation: SyncMutationEntity): SyncMutationEntity {
        val floor = (highestMutationStamp() ?: 0L) + 1
        return if (mutation.createdAt >= floor) mutation else mutation.copy(createdAt = floor)
    }

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
