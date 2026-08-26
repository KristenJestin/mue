package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The food catalogue, and — through [SyncJournalDao] — the outbox row every catalogue write
 * leaves behind it.
 *
 * The journalling methods exist separately from the plain ones for the reason spelled out in
 * `MeasurementDao`: sync FR-SYNC-001 requires the mutation to be enqueued in *the same*
 * transaction as the business row, and two calls would be two transactions with a window in
 * between where the change survives and the instruction to send it does not.
 *
 * Ciqual rows are the one exception and never journal anything: PRD_FOOD 21.1 lists the embedded
 * catalogue as not synchronised, because it is a versioned reference and not personal data.
 */
@Dao
interface FoodDao : SyncJournalDao {

    @Query("SELECT * FROM food WHERE id = :id")
    fun observeById(id: String): Flow<FoodEntity?>

    @Query("SELECT * FROM food WHERE id = :id")
    suspend fun findById(id: String): FoodEntity?

    @Query("SELECT * FROM food WHERE id IN (:ids)")
    suspend fun findByIds(ids: Collection<String>): List<FoodEntity>

    @Query("SELECT * FROM food WHERE barcode = :barcode ORDER BY updated_at DESC LIMIT 1")
    suspend fun findByBarcode(barcode: String): FoodEntity?

    @Query(
        "SELECT * FROM food WHERE source = :source AND source_id = :sourceId " +
            "ORDER BY updated_at DESC LIMIT 1"
    )
    suspend fun findBySourceId(source: String, sourceId: String): FoodEntity?

    @Query("SELECT COUNT(*) FROM food WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("SELECT created_at FROM food WHERE id = :id")
    suspend fun findCreatedAt(id: String): Long?

    /**
     * PRD_FOOD 9.4: one bar over the three sources, optionally restricted to one of them, case
     * and accent insensitive. The folded columns are what make that insensitivity an index
     * lookup rather than a function SQLite would have to run per row; the caller folds and
     * escapes the term with `Food.fold`, the single definition of the same operation.
     *
     * A name match outranks a brand match — searching "yaourt" wants yoghurts before every
     * product of a brand that happens to contain the word.
     */
    @Query(
        """
        SELECT * FROM food
        WHERE (:source IS NULL OR source = :source)
          AND (name_folded LIKE :pattern ESCAPE '\' OR brand_folded LIKE :pattern ESCAPE '\')
        ORDER BY
          CASE WHEN name_folded LIKE :prefix ESCAPE '\' THEN 0
               WHEN name_folded LIKE :pattern ESCAPE '\' THEN 1
               ELSE 2 END,
          name_folded ASC,
          id ASC
        LIMIT :limit
        """
    )
    fun search(pattern: String, prefix: String, source: String?, limit: Int): Flow<List<FoodEntity>>

    /**
     * PRD_FOOD 9.4: "les aliments récemment utilisés apparaissent en tête lorsque la recherche
     * est vide". Recency comes from the journal rather than from a column on `food`, because
     * "used" means logged — editing a food's fibre is not using it — and a column would have to
     * be kept in step by every write path that touches a line.
     */
    @Query(
        """
        SELECT food.* FROM food
        JOIN (
            SELECT source_ref AS food_ref, MAX(consumed_on || 'T' || consumed_at) AS last_used
            FROM food_log_entry
            WHERE kind = 'food' AND source_ref IS NOT NULL
            GROUP BY source_ref
        ) AS used ON used.food_ref = food.id
        ORDER BY used.last_used DESC, food.id ASC
        LIMIT :limit
        """
    )
    fun observeRecentlyUsed(limit: Int): Flow<List<FoodEntity>>

    /** PRD_FOOD 9.3: the recipes that must release a food before it can be deleted. */
    @Query(
        """
        SELECT DISTINCT recipe.name FROM recipe
        JOIN recipe_ingredient ON recipe_ingredient.recipe_id = recipe.id
        WHERE recipe_ingredient.food_id = :foodId
        ORDER BY recipe.name_folded ASC
        """
    )
    suspend fun recipeNamesUsing(foodId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FoodEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FoodEntity>)

    @Query("DELETE FROM food WHERE id = :id")
    suspend fun deleteById(id: String)

    /** The catalogue write and its outbox row, in one transaction (FR-SYNC-001). */
    @Transaction
    suspend fun upsertWithMutation(entity: FoodEntity, mutation: SyncMutationEntity) {
        val baseRevision = revisionOf(mutation.aggregateType, mutation.aggregateId)
        upsert(entity)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(mutation.aggregateType, mutation.aggregateId)
        )
        markAggregateAlive(mutation.aggregateType, mutation.aggregateId, mutation.mutationId)
        enqueueMutation(mutation.copy(baseRevision = baseRevision))
    }

    /** The row goes, the tombstone stays (FR-SYNC-005), or the deletion would undo itself. */
    @Transaction
    suspend fun deleteWithMutation(id: String, mutation: SyncMutationEntity) {
        val baseRevision = revisionOf(mutation.aggregateType, mutation.aggregateId)
        deleteById(id)
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

    /**
     * The embedded catalogue, installed or refreshed in one transaction and journalling nothing
     * (PRD_FOOD 21.1). Rows absent from the new subset are left alone rather than deleted: a
     * recipe or a journal line may still name one, and PRD_FOOD 20.2 says an update "ne modifie
     * jamais un aliment personnalisé ni une ligne de journal".
     */
    @Transaction
    suspend fun replaceCiqual(entities: List<FoodEntity>) {
        upsertAll(entities)
    }
}
