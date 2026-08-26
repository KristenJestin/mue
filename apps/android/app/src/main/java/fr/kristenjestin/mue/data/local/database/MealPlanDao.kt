package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The propositions (PRD_FOOD 12). Writing an occupied moment replaces what was there, because
 * the primary key `(planned_on, slot)` says there is one row per moment and `REPLACE` is what
 * makes 8.5's "remplace la proposition précédente" a constraint rather than a convention the
 * interface has to remember.
 */
@Dao
interface MealPlanDao : SyncJournalDao {

    @Query(
        "SELECT * FROM meal_plan_entry WHERE planned_on = :date ORDER BY " +
            MealPlanEntryEntity.SLOT_ORDER
    )
    fun observeDay(date: String): Flow<List<MealPlanEntryEntity>>

    @Query(
        "SELECT * FROM meal_plan_entry " +
            "WHERE (:start IS NULL OR planned_on >= :start) " +
            "AND (:end IS NULL OR planned_on <= :end) " +
            "ORDER BY planned_on ASC, " + MealPlanEntryEntity.SLOT_ORDER
    )
    fun observeInWindow(start: String?, end: String?): Flow<List<MealPlanEntryEntity>>

    @Query("SELECT * FROM meal_plan_entry WHERE planned_on = :date AND slot = :slot")
    suspend fun find(date: String, slot: String): MealPlanEntryEntity?

    @Query("SELECT * FROM meal_plan_entry WHERE recipe_id = :recipeId ORDER BY planned_on ASC")
    suspend fun findReferencing(recipeId: String): List<MealPlanEntryEntity>

    @Query("SELECT COUNT(*) FROM meal_plan_entry")
    suspend fun count(): Int

    @Query("SELECT created_at FROM meal_plan_entry WHERE planned_on = :date AND slot = :slot")
    suspend fun findCreatedAt(date: String, slot: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MealPlanEntryEntity)

    @Query("DELETE FROM meal_plan_entry WHERE planned_on = :date AND slot = :slot")
    suspend fun delete(date: String, slot: String)

    @Query(
        "UPDATE meal_plan_entry SET consumed_log_entry_id = :logEntryId, updated_at = :updatedAt " +
            "WHERE planned_on = :date AND slot = :slot"
    )
    suspend fun setConsumed(date: String, slot: String, logEntryId: String?, updatedAt: Long)

    @Transaction
    suspend fun upsertWithMutation(entity: MealPlanEntryEntity, mutation: SyncMutationEntity) {
        val baseRevision = revisionOf(mutation.aggregateType, mutation.aggregateId)
        val createdAt = findCreatedAt(entity.plannedOn, entity.slot) ?: entity.createdAt
        upsert(entity.copy(createdAt = createdAt))
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(mutation.aggregateType, mutation.aggregateId)
        )
        markAggregateAlive(mutation.aggregateType, mutation.aggregateId, mutation.mutationId)
        enqueueMutation(mutation.copy(baseRevision = baseRevision))
    }

    /**
     * Confirming or un-confirming a proposition changes the aggregate, so it journals an upsert
     * like any other write. The change is a single `UPDATE` rather than a read-modify-write, so
     * a concurrent edit of the planned servings is not silently rolled back by a stale copy.
     */
    @Transaction
    suspend fun setConsumedWithMutation(
        date: String,
        slot: String,
        logEntryId: String?,
        updatedAt: Long,
        mutation: SyncMutationEntity,
    ) {
        val baseRevision = revisionOf(mutation.aggregateType, mutation.aggregateId)
        setConsumed(date, slot, logEntryId, updatedAt)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(mutation.aggregateType, mutation.aggregateId)
        )
        markAggregateAlive(mutation.aggregateType, mutation.aggregateId, mutation.mutationId)
        enqueueMutation(mutation.copy(baseRevision = baseRevision))
    }

    @Transaction
    suspend fun deleteWithMutation(date: String, slot: String, mutation: SyncMutationEntity) {
        val baseRevision = revisionOf(mutation.aggregateType, mutation.aggregateId)
        delete(date, slot)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(mutation.aggregateType, mutation.aggregateId)
        )
        markAggregateDeleted(
            aggregateType = mutation.aggregateType,
            aggregateId = mutation.aggregateId,
            deletedAt = mutation.createdAt,
            mutationId = mutation.mutationId,
        )
        enqueueMutation(mutation.copy(baseRevision = baseRevision))
    }
}
