package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao : SyncJournalDao {

    @Query("SELECT * FROM measurements ORDER BY date ASC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    /**
     * A null bound means "unbounded", which is how the `All` period is expressed
     * without inventing sentinel dates.
     */
    @Query(
        """
        SELECT * FROM measurements
        WHERE (:start IS NULL OR date >= :start)
          AND (:end IS NULL OR date <= :end)
        ORDER BY date ASC
        """
    )
    fun observeInWindow(start: String?, end: String?): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements ORDER BY date DESC LIMIT 1")
    fun observeLatest(): Flow<MeasurementEntity?>

    @Query("SELECT * FROM measurements ORDER BY date ASC")
    suspend fun getAll(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE date = :date")
    suspend fun findByDate(date: String): MeasurementEntity?

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    /** REPLACE is what makes PRD BR-002 silent: writing an existing date overwrites it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MeasurementEntity)

    @Query("DELETE FROM measurements WHERE date = :date")
    suspend fun deleteByDate(date: String)

    /**
     * Edits a measurement whose date may have moved (PRD FR-PROGRESS-005). Removing
     * the old row and writing the new one must not be observable as two steps
     * (PRD 16.3).
     */
    @Transaction
    suspend fun replace(originalDate: String, entity: MeasurementEntity) {
        if (originalDate != entity.date) {
            deleteByDate(originalDate)
        }
        upsert(entity)
    }

    /**
     * The three writes below are the same three above with the outbox row added — and the
     * addition is the whole point of them existing separately.
     *
     * Sync FR-SYNC-001 says *the same transaction* enqueues the mutation. Calling `upsert` and
     * then a journal method would be two transactions, and a process death between them keeps
     * the measurement while losing every trace that it has to be sent: the change would then
     * exist on the phone forever and never on the server, with nothing to detect it.
     *
     * The base revision is read here rather than passed in, so it is read under the same lock
     * that writes the row; a revision fetched before the transaction could already be stale.
     *
     * [SyncJournalDao.sequenced] is applied for the same reason and in the same place: the send
     * order is the outbox's local sequence, and a stamp taken outside this transaction could be
     * overtaken by a concurrent writer or undercut by a clock that stepped backwards.
     */
    @Transaction
    suspend fun upsertWithMutation(entity: MeasurementEntity, mutation: SyncMutationEntity) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        upsert(entity)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateAlive(row.aggregateType, row.aggregateId, row.mutationId)
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }

    /**
     * The row goes, the tombstone stays (FR-SYNC-005). Without it, a copy of the same date
     * still sitting in another client's outbox would come back on the next pull and the
     * deletion would silently undo itself.
     */
    @Transaction
    suspend fun deleteWithMutation(date: String, mutation: SyncMutationEntity) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        deleteByDate(date)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateDeleted(
            aggregateType = row.aggregateType,
            aggregateId = row.aggregateId,
            // The tombstone's instant is the stamp the mutation actually went out with, so the
            // local record and the mutation that will create the remote one cannot disagree.
            deletedAt = row.createdAt,
            mutationId = row.mutationId,
        )
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }

    /**
     * An edit that moves a measurement to another date is two aggregates changing, because the
     * date *is* the aggregate id: the old one is deleted and the new one written. One mutation
     * carrying both would be unapplicable on a server that stores measurements by date, so
     * [deleteMutation] is spent exactly when the shipped [replace] deletes the old row.
     */
    @Transaction
    suspend fun replaceWithMutation(
        originalDate: String,
        entity: MeasurementEntity,
        deleteMutation: SyncMutationEntity,
        upsertMutation: SyncMutationEntity,
    ) {
        if (originalDate != entity.date) {
            deleteWithMutation(originalDate, deleteMutation)
        }
        upsertWithMutation(entity, upsertMutation)
    }
}
