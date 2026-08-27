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
     * `created_at` is the outbox's **local sequence**, not the wall clock: every insert goes
     * through [SyncJournalDao.sequenced], which floors the stamp at one past the highest one
     * already waiting. Ordering on it is therefore safe even across the phone syncing its own
     * time backwards, which PRD 12.3 and 13.1 require and which a bare
     * `System.currentTimeMillis()` could not give.
     *
     * `rowid` stays as the tie-break. The sequence makes a tie impossible between two rows this
     * DAO wrote, but a database restored, migrated or written by an older build may still hold
     * two rows sharing a stamp, and the two mutations of one edit — the delete of the old date
     * and the upsert of the new one — must not be reordered: sending the upsert first would
     * have the server apply a deletion to the row it had just created.
     */
    @Query(
        "SELECT * FROM sync_mutations WHERE state = 'pending' " +
            "ORDER BY created_at ASC, rowid ASC LIMIT :limit"
    )
    suspend fun pendingMutations(limit: Int): List<SyncMutationEntity>

    /**
     * The next mutations to send **of the aggregate types this build can put on the wire**,
     * oldest first — the query a send actually uses.
     *
     * [pendingMutations] is the queue as the user's `Data & sync` screen thinks of it; this is
     * the queue as the network sees it, and the difference is not cosmetic. The four food
     * aggregates were journalled at every save (FR-SYNC-001) while `AGGREGATE_TYPES` in
     * `packages/contracts` had no branch for any of them, so those rows were `pending` and
     * undeliverable *for as long as the contract lacked the branch* — they never drained. A send
     * that took the oldest rows regardless of type would, once that many meals had accumulated,
     * get back a window holding nothing sendable, and every measurement behind them would stop
     * going out for good. FR-SYNC-007 forbids exactly that, so the type is part of the selection
     * rather than a check made after the rows have already filled the window.
     *
     * Every aggregate of PRD 10.1 is sendable today, so the filter excludes nothing — which is
     * precisely when it is worth keeping, because the next one journalled ahead of its contract
     * will find it already in place.
     *
     * The types are bound rather than spelled out, unlike `state` above: they are owned by
     * `SyncWire`, which is where the wire's vocabulary is translated, and a literal here would
     * be a second place to remember when the contract grows an aggregate.
     */
    @Query(
        "SELECT * FROM sync_mutations WHERE state = 'pending' " +
            "AND aggregate_type IN (:aggregateTypes) " +
            "ORDER BY created_at ASC, rowid ASC LIMIT :limit"
    )
    suspend fun pendingMutationsOfTypes(
        aggregateTypes: List<String>,
        limit: Int,
    ): List<SyncMutationEntity>

    /**
     * How many `pending` rows this build has no wire branch for. Kept, blocking nothing, and
     * reported so a run can say how much it is holding back rather than pretend it is idle.
     */
    @Query(
        "SELECT COUNT(*) FROM sync_mutations WHERE state = 'pending' " +
            "AND aggregate_type NOT IN (:aggregateTypes)"
    )
    suspend fun countPendingOfOtherTypes(aggregateTypes: List<String>): Int

    @Query("SELECT * FROM sync_mutations WHERE mutation_id = :mutationId")
    suspend fun mutation(mutationId: String): SyncMutationEntity?

    @Query("SELECT COUNT(*) FROM sync_mutations WHERE state = :state")
    suspend fun countInState(state: String): Int

    /** `Sync issue` in `Data & sync` (FR-SYNC-007) is this count being greater than zero. */
    @Query("SELECT COUNT(*) FROM sync_mutations WHERE state = 'failed'")
    fun observeFailedCount(): Flow<Int>

    /**
     * PRD 9.1's "le nombre de changements locaux en attente", live.
     *
     * The observable twin of [countInState] with `'pending'` bound, and it exists because
     * `Data & sync` has to be able to contradict itself the moment a weight is saved: the
     * section is shown beside `Synced`, and a screen that says `Synced` while a row sits in the
     * outbox lies about the only thing the outbox is for. [observeSyncState] cannot carry it —
     * Room invalidates a Flow per table, and that one watches `sync_state`, which a new mutation
     * does not touch.
     *
     * Every `pending` row is counted, of every aggregate type, including the ones this build has
     * no wire branch for. They are what [countPendingOfOtherTypes] names separately, and they are
     * still local changes that are not on the server, so hiding them here would produce the same
     * comfortable lie in a smaller number.
     */
    @Query("SELECT COUNT(*) FROM sync_mutations WHERE state = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE sync_mutations SET state = :state WHERE mutation_id IN (:mutationIds)")
    suspend fun setState(mutationIds: List<String>, state: String)

    /**
     * Returns every stranded `inflight` row to `pending`, and closes a one-way door.
     *
     * [setState] moves rows into `inflight` when a batch leaves the phone, and until this
     * existed nothing ever moved them back. A process killed between the request and its
     * response — the ordinary fate of a background sync on Android — left those rows `inflight`
     * for good: `pendingMutations` does not select them, no later send would ever look at them
     * again, and the user's change existed on the phone and nowhere else, forever. That is
     * exactly the loss FR-SYNC-001 forbids.
     *
     * It is safe to call unconditionally because sending is idempotent: [SyncMutationEntity]'s
     * `mutation_id` is FR-SYNC-006's key, so a mutation the server did receive before the crash
     * comes back as `duplicate` with its stored result rather than as a second application.
     * Re-sending costs a round trip; not re-sending costs the change.
     *
     * `failed` rows are deliberately untouched: FR-SYNC-007 keeps them out of the queue.
     *
     * @return how many rows were recovered, so a caller can log a real number.
     */
    @Query("UPDATE sync_mutations SET state = 'pending' WHERE state = 'inflight'")
    suspend fun requeueInflight(): Int

    /**
     * Every row a repair pass might have something to say about, as the four columns it decides
     * on — and no payload.
     *
     * `inflight` is excluded in SQL rather than in Kotlin so that a row on the wire is not even
     * *read* by the pass, whatever `OutboxRepair.verdict` may later be changed to say about it.
     * The projection is narrow for the same reason `pendingMutations` is not reused: this asks
     * a question about identifiers, and hauling every stored payload through it to answer would
     * make the cost of the pass the size of the outbox rather than its length.
     */
    @Query(
        "SELECT mutation_id, state, attempt_count, last_error_code FROM sync_mutations " +
            "WHERE state <> 'inflight' ORDER BY created_at ASC, rowid ASC"
    )
    suspend fun repairCandidates(): List<OutboxRepairCandidate>

    /**
     * Gives one stored row the identifier a current build would have minted for it, and puts it
     * back in the queue.
     *
     * Four things it does *not* do, each on purpose:
     *
     * - It does not touch `created_at`. That column is the outbox's local sequence, and the two
     *   mutations of one edit — the delete of the old date and the upsert of the new one — must
     *   keep their order or the server applies a deletion to a row it has just created. A
     *   `rowid` is unaffected by rewriting a primary key in SQLite, so the tie-break survives too.
     * - It does not reset `attempt_count`. That is history, and the row really was refused.
     * - It does not touch the payload, the aggregate or the base revision. This repairs an
     *   identifier; anything else would be repairing the user's data, which FR-SYNC-007 forbids.
     * - It does not widen its own `WHERE` beyond the id it was given. The caller has already
     *   decided, per row, and a statement that re-decided would be a second place to keep the
     *   rule.
     *
     * `last_error_code` and `last_error_message` **are** cleared: they describe a refusal of an
     * identifier this row no longer carries, and leaving them would have `Data & sync` go on
     * quoting "Every mutation needs a readable UUIDv7 `mutationId`" at a row that now has one.
     *
     * Rewriting a primary key is what this is, and it is sound here precisely because these rows
     * were never accepted: no `sync_aggregate_state.last_mutation_id` can name one, since that
     * column is only ever written from a server acknowledgement or a server change.
     */
    @Query(
        "UPDATE sync_mutations SET mutation_id = :mutationId, state = 'pending', " +
            "last_error_code = NULL, last_error_message = NULL " +
            "WHERE mutation_id = :previousMutationId"
    )
    suspend fun remintMutationId(previousMutationId: String, mutationId: String)

    /**
     * Every outbox row whose **aggregate identifier** a repair pass might have something to say
     * about, as the three columns it decides on.
     *
     * Narrower than [repairCandidates] on purpose: the identifier defect this answers belongs to
     * one aggregate type, so the type is in the `WHERE` rather than in Kotlin, and a phone whose
     * outbox holds a thousand weights reads none of them. `inflight` is excluded in SQL for the
     * same reason it is there: a row that may be on the wire is not even read.
     */
    @Query(
        "SELECT mutation_id, aggregate_type, aggregate_id, state FROM sync_mutations " +
            "WHERE state <> 'inflight' AND aggregate_type = :aggregateType " +
            "ORDER BY created_at ASC, rowid ASC"
    )
    suspend fun aggregateIdRepairCandidates(aggregateType: String): List<AggregateIdRepairCandidate>

    /**
     * Gives one stored outbox row the aggregate identifier a current build would have written.
     *
     * It touches the identifier and nothing else — not the payload, not `created_at`, not
     * `attempt_count`, not the mutation id. `MealPlanIdRepair` explains why rewriting *this*
     * column is safe where rewriting it in general would not be: no row of this aggregate type
     * has ever been sendable, so no server has recorded the old spelling and there is nothing to
     * fork away from.
     *
     * `last_error_code` and `last_error_message` are cleared, as in [remintMutationId]: they would
     * otherwise go on describing a refusal of an identifier the row no longer carries.
     */
    @Query(
        "UPDATE sync_mutations SET aggregate_id = :aggregateId, state = 'pending', " +
            "last_error_code = NULL, last_error_message = NULL " +
            "WHERE mutation_id = :mutationId"
    )
    suspend fun renameMutationAggregateId(mutationId: String, aggregateId: String)

    /** The per-aggregate metadata rows of one type, so the same rename reaches both tables. */
    @Query("SELECT * FROM sync_aggregate_state WHERE aggregate_type = :aggregateType")
    suspend fun aggregateStatesOfType(aggregateType: String): List<SyncAggregateStateEntity>

    /**
     * Moves one `sync_aggregate_state` row to the identifier its outbox rows now use.
     *
     * It has to happen, and it has to happen in the same transaction: this table is keyed by
     * `(aggregate_type, aggregate_id)` and holds the local tombstone of FR-SYNC-005. A repair that
     * renamed the outbox and left this behind would have the next save insert a *second* metadata
     * row under the new spelling, with no `deleted_at` — and a proposal the user had deleted would
     * quietly lose the tombstone that stops an old copy resurrecting it.
     *
     * `IGNORE` on the primary key would silently drop the row, so the rename is a plain `UPDATE`
     * and the caller skips a row whose destination already exists.
     */
    @Query(
        "UPDATE sync_aggregate_state SET aggregate_id = :aggregateId " +
            "WHERE aggregate_type = :aggregateType AND aggregate_id = :previousAggregateId"
    )
    suspend fun renameAggregateState(
        aggregateType: String,
        previousAggregateId: String,
        aggregateId: String,
    )

    /**
     * The revision the server assigned to an accepted mutation, written on acknowledgement.
     *
     * Without it the next edit of the same aggregate would quote the revision it had before the
     * push as its `baseRevision`, and PRD 13.3 makes an update founded on an old revision a
     * detected conflict — so the second edit of any measurement would be refused for as long as
     * the phone had not pulled the change back.
     *
     * A targeted `UPDATE` and not `putAggregateState`: replacing the row would take `deleted_at`
     * with it and resurrect a tombstone.
     */
    @Query(
        "UPDATE sync_aggregate_state SET revision = :revision, last_mutation_id = :mutationId, " +
            "server_updated_at = :serverUpdatedAt " +
            "WHERE aggregate_type = :aggregateType AND aggregate_id = :aggregateId"
    )
    suspend fun recordAcceptedRevision(
        aggregateType: String,
        aggregateId: String,
        revision: Long,
        mutationId: String,
        serverUpdatedAt: Long,
    )

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
