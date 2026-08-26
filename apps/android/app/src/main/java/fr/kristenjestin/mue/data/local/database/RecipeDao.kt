package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The recipes and their ingredients, which PRD_FOOD 21.2 makes **one** aggregate: "une recette
 * n'apparaît jamais sans ses ingrédients". Every write here therefore replaces the ingredient
 * list wholesale in the same transaction as the recipe row, and journals a single mutation for
 * the pair — 21.3 says the ingredients are not merged line by line, so there is nothing a
 * per-ingredient mutation could express that the whole aggregate does not.
 */
@Dao
interface RecipeDao : SyncJournalDao {

    @Query(
        """
        SELECT * FROM recipe
        WHERE (:type IS NULL OR type = :type)
          AND (:favouritesOnly = 0 OR is_favourite = 1)
        ORDER BY is_favourite DESC, name_folded ASC, id ASC
        """
    )
    fun observeAll(type: String?, favouritesOnly: Boolean): Flow<List<RecipeEntity>>

    @Query(
        """
        SELECT * FROM recipe
        WHERE (:type IS NULL OR type = :type)
          AND name_folded LIKE :pattern ESCAPE '\'
        ORDER BY
          CASE WHEN name_folded LIKE :prefix ESCAPE '\' THEN 0 ELSE 1 END,
          name_folded ASC,
          id ASC
        """
    )
    fun search(pattern: String, prefix: String, type: String?): Flow<List<RecipeEntity>>

    @Query("SELECT COUNT(*) FROM recipe")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM recipe WHERE id = :id")
    fun observeById(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipe WHERE id = :id")
    suspend fun findById(id: String): RecipeEntity?

    @Query("SELECT created_at FROM recipe WHERE id = :id")
    suspend fun findCreatedAt(id: String): Long?

    @Query("SELECT * FROM recipe_ingredient WHERE recipe_id = :recipeId ORDER BY position ASC")
    fun observeIngredients(recipeId: String): Flow<List<RecipeIngredientEntity>>

    @Query("SELECT * FROM recipe_ingredient WHERE recipe_id = :recipeId ORDER BY position ASC")
    suspend fun findIngredients(recipeId: String): List<RecipeIngredientEntity>

    /** PRD_FOOD 9.3: an aliment used by a recipe cannot be deleted until it is released. */
    @Query(
        """
        SELECT DISTINCT recipe.* FROM recipe
        JOIN recipe_ingredient ON recipe_ingredient.recipe_id = recipe.id
        WHERE recipe_ingredient.food_id = :foodId
        ORDER BY recipe.name_folded ASC, recipe.id ASC
        """
    )
    suspend fun findUsing(foodId: String): List<RecipeEntity>

    /**
     * The propositions a recipe deletion takes with it. Read **before** the delete, because
     * SQLite's cascade removes them without saying which, and each one is an aggregate of its
     * own that needs its own tombstone (PRD_FOOD 21.2).
     */
    @Query("SELECT * FROM meal_plan_entry WHERE recipe_id = :recipeId ORDER BY planned_on ASC")
    suspend fun findPlansReferencing(recipeId: String): List<MealPlanEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecipe(recipe: RecipeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngredients(ingredients: List<RecipeIngredientEntity>)

    @Query("DELETE FROM recipe_ingredient WHERE recipe_id = :recipeId")
    suspend fun deleteIngredientsOf(recipeId: String)

    @Query("DELETE FROM recipe WHERE id = :id")
    suspend fun deleteRecipe(id: String)

    @Query("UPDATE recipe SET is_favourite = :isFavourite, updated_at = :updatedAt WHERE id = :id")
    suspend fun setFavourite(id: String, isFavourite: Boolean, updatedAt: Long)

    @Transaction
    suspend fun findDetailRows(id: String): RecipeWithIngredients? {
        val recipe = findById(id) ?: return null
        return RecipeWithIngredients(recipe, findIngredients(id))
    }

    /**
     * The recipe, its ingredients and the outbox row, together. The old ingredients are removed
     * rather than merged: 21.3 resolves a recipe conflict by "dernière mutation acceptée,
     * agrégat entier", so a stored list that outlived its own recipe would be a row nobody
     * intended to keep.
     */
    @Transaction
    suspend fun saveDetailWithMutation(
        recipe: RecipeEntity,
        ingredients: List<RecipeIngredientEntity>,
        mutation: SyncMutationEntity,
    ) {
        val baseRevision = revisionOf(mutation.aggregateType, mutation.aggregateId)
        val createdAt = findCreatedAt(recipe.id) ?: recipe.createdAt
        deleteIngredientsOf(recipe.id)
        upsertRecipe(recipe.copy(createdAt = createdAt))
        insertIngredients(ingredients)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(mutation.aggregateType, mutation.aggregateId)
        )
        markAggregateAlive(mutation.aggregateType, mutation.aggregateId, mutation.mutationId)
        enqueueMutation(mutation.copy(baseRevision = baseRevision))
    }

    @Transaction
    suspend fun setFavouriteWithMutation(
        id: String,
        isFavourite: Boolean,
        updatedAt: Long,
        mutation: SyncMutationEntity,
    ) {
        val baseRevision = revisionOf(mutation.aggregateType, mutation.aggregateId)
        setFavourite(id, isFavourite, updatedAt)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(mutation.aggregateType, mutation.aggregateId)
        )
        markAggregateAlive(mutation.aggregateType, mutation.aggregateId, mutation.mutationId)
        enqueueMutation(mutation.copy(baseRevision = baseRevision))
    }

    /**
     * Deleting a recipe deletes its propositions too — PRD_FOOD 8.5 makes a proposition
     * meaningless without one — and each of them is a separate aggregate, so each gets its own
     * tombstone and its own mutation. The ingredient rows need neither: they are part of the
     * recipe aggregate, and SQLite's cascade is what removes them.
     */
    @Transaction
    suspend fun deleteWithMutations(
        id: String,
        mutation: SyncMutationEntity,
        planMutations: List<SyncMutationEntity>,
    ) {
        planMutations.forEach { planMutation ->
            val planBase = revisionOf(planMutation.aggregateType, planMutation.aggregateId)
            insertAggregateStateIfAbsent(
                SyncAggregateStateEntity(planMutation.aggregateType, planMutation.aggregateId)
            )
            markAggregateDeleted(
                aggregateType = planMutation.aggregateType,
                aggregateId = planMutation.aggregateId,
                deletedAt = planMutation.createdAt,
                mutationId = planMutation.mutationId,
            )
            enqueueMutation(planMutation.copy(baseRevision = planBase))
        }

        val baseRevision = revisionOf(mutation.aggregateType, mutation.aggregateId)
        deleteRecipe(id)
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
