package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The food journal (PRD_FOOD 10). One aggregate per row: 21.3 makes the lines independent —
 * "deux lignes créées séparément coexistent, elles ne fusionnent jamais" — so nothing here ever
 * merges two rows, and a day is however many rows the user wrote.
 *
 * Every ordering is `(date, moment, time)` with the moment ordered by
 * [FoodLogEntryEntity.SLOT_ORDER] rather than alphabetically, and `created_at` as the final
 * tiebreak so two lines logged at the same minute keep the order they were written in.
 */
@Dao
interface FoodLogDao : SyncJournalDao {

    @Query(
        "SELECT * FROM food_log_entry WHERE consumed_on = :date ORDER BY " +
            FoodLogEntryEntity.SLOT_ORDER + ", consumed_at ASC, created_at ASC"
    )
    fun observeDay(date: String): Flow<List<FoodLogEntryEntity>>

    @Query(
        "SELECT * FROM food_log_entry " +
            "WHERE (:start IS NULL OR consumed_on >= :start) " +
            "AND (:end IS NULL OR consumed_on <= :end) " +
            "ORDER BY consumed_on ASC, " + FoodLogEntryEntity.SLOT_ORDER +
            ", consumed_at ASC, created_at ASC"
    )
    fun observeInWindow(start: String?, end: String?): Flow<List<FoodLogEntryEntity>>

    /** `Trends` needs the days that have a line, not the lines (PRD_FOOD 10.5). */
    @Query(
        "SELECT DISTINCT consumed_on FROM food_log_entry " +
            "WHERE (:start IS NULL OR consumed_on >= :start) " +
            "AND (:end IS NULL OR consumed_on <= :end) " +
            "ORDER BY consumed_on ASC"
    )
    fun observeLoggedDatesInWindow(start: String?, end: String?): Flow<List<String>>

    @Query("SELECT * FROM food_log_entry WHERE id = :id")
    suspend fun findById(id: String): FoodLogEntryEntity?

    @Query(
        "SELECT * FROM food_log_entry WHERE planned_on = :date AND plan_slot = :slot " +
            "ORDER BY created_at ASC LIMIT 1"
    )
    suspend fun findByPlan(date: String, slot: String): FoodLogEntryEntity?

    @Query("SELECT COUNT(*) FROM food_log_entry")
    suspend fun count(): Int

    @Query("SELECT created_at FROM food_log_entry WHERE id = :id")
    suspend fun findCreatedAt(id: String): Long?

    /**
     * The distinct foods behind the most recent lines, most recent first (PRD_FOOD 9.4). Ordered
     * on the same expression it groups by, so a food logged twice ranks by its latest line.
     */
    @Query(
        "SELECT source_ref FROM food_log_entry " +
            "WHERE kind = 'food' AND source_ref IS NOT NULL " +
            "GROUP BY source_ref " +
            "ORDER BY MAX(consumed_on || 'T' || consumed_at) DESC " +
            "LIMIT :limit"
    )
    suspend fun recentlyUsedFoods(limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FoodLogEntryEntity)

    @Query("DELETE FROM food_log_entry WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * The line and its outbox row in one transaction (FR-SYNC-001). `created_at` is preserved
     * across an edit: a line corrected a week later was still written when it was written, and
     * the journal's own ordering leans on it.
     */
    @Transaction
    suspend fun upsertWithMutation(entity: FoodLogEntryEntity, mutation: SyncMutationEntity) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        val createdAt = findCreatedAt(entity.id) ?: entity.createdAt
        upsert(entity.copy(createdAt = createdAt))
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateAlive(row.aggregateType, row.aggregateId, row.mutationId)
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }

    @Transaction
    suspend fun deleteWithMutation(id: String, mutation: SyncMutationEntity) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        deleteById(id)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateDeleted(
            aggregateType = row.aggregateType,
            aggregateId = row.aggregateId,
            deletedAt = row.createdAt,
            mutationId = row.mutationId,
        )
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }
}
