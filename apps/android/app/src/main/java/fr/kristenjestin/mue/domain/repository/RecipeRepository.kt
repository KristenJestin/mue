package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import kotlinx.coroutines.flow.Flow

/**
 * The saved recipes (PRD_FOOD 11).
 *
 * A recipe is written and read **whole**: PRD_FOOD 21.2 synchronises it with its ingredients
 * atomically and PRD_FOOD 21.3 resolves a conflict on the entire aggregate, so [save] takes a
 * [RecipeDetail] and there is no contract here for adding one ingredient.
 *
 * Nothing in this interface knows who wrote a recipe. PRD_FOOD FR-RECIPE-005 and 8.3 keep the
 * origin of a mutation in the server's audit; a repository that could filter by it would invite
 * a screen that shows it.
 */
interface RecipeRepository {

    /**
     * The recipe list of PRD_FOOD 11: favourites first, then by name. [type] and [favouritesOnly]
     * are the filters FR-RECIPE-005 offers; a null type means every type.
     */
    fun observeAll(type: RecipeType? = null, favouritesOnly: Boolean = false): Flow<List<Recipe>>

    /** PRD_FOOD FR-RECIPE-005: found by name, folded the way `Food.fold` folds one. */
    fun search(query: String, type: RecipeType? = null): Flow<List<Recipe>>

    /** How many recipes exist at all, so PRD_FOOD 17 can tell an empty list from a filtered one. */
    fun observeCount(): Flow<Int>

    /** The recipe and its ingredients, as the card and the editor both load it. */
    fun observeDetail(id: RecipeId): Flow<RecipeDetail?>

    suspend fun findDetail(id: RecipeId): RecipeDetail?

    /**
     * Creates or replaces a whole recipe in one transaction: all of it, or none.
     *
     * Ingredients are replaced wholesale rather than merged row by row, which is PRD_FOOD 21.3's
     * rule applied locally so that a local save and a received mutation cannot diverge.
     */
    suspend fun save(detail: RecipeDetail)

    suspend fun setFavourite(id: RecipeId, isFavourite: Boolean)

    /**
     * PRD_FOOD 11 and FR-RECIPE-006: deleting touches neither the journal nor the meals already
     * eaten. The proposals that referenced it are released — their slot becomes free again — and
     * their keys are returned so the day screen can say which ones.
     */
    suspend fun delete(id: RecipeId): List<MealPlanKey>

    /** The recipes that use a given food, for the deletion refusal of PRD_FOOD 9.3. */
    suspend fun findUsing(food: FoodId): List<Recipe>
}
