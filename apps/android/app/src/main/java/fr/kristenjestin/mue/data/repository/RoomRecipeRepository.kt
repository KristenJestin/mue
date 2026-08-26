package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.RecipeDao
import fr.kristenjestin.mue.data.local.database.RecipeWithIngredients
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toDomainOrNull
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The recipes of PRD_FOOD 11, over Room.
 *
 * A recipe and its ingredients are one aggregate (21.2), so every write replaces the whole list
 * in one transaction and journals one mutation for the pair. Ingredient positions are
 * renumbered from the order they arrive in, exactly as the activity module renumbers a session's
 * sets: dropping the third of four must leave three consecutive positions and not a hole a
 * reader would take for a missing row.
 *
 * Nothing nutritional is stored (8.3). What this returns is the ingredient list; the totals are
 * the domain's pure function, recomputed at display so that correcting one food corrects every
 * recipe at once.
 */
class RoomRecipeRepository(
    private val dao: RecipeDao,
    private val outbox: SyncOutbox = SyncOutbox(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : RecipeRepository {

    override fun observeAll(type: RecipeType?, favouritesOnly: Boolean): Flow<List<Recipe>> =
        dao.observeAll(type?.id, favouritesOnly)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override fun search(query: String, type: RecipeType?): Flow<List<Recipe>> {
        val folded = RoomFoodCatalogueRepository.foldForSearch(query)
        return dao.search(pattern = "%$folded%", prefix = "$folded%", type = type?.id)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)
    }

    override fun observeCount(): Flow<Int> = dao.observeCount().flowOn(ioDispatcher)

    /**
     * Two queries combined rather than one `@Transaction`-annotated relation read, because the
     * ingredient list has to re-emit on its own: editing one quantity changes no column of
     * `recipe`, and a single-table observer would show the old list until something else moved.
     */
    override fun observeDetail(id: RecipeId): Flow<RecipeDetail?> =
        combine(dao.observeById(id.value), dao.observeIngredients(id.value)) { recipe, rows ->
            recipe?.let { RecipeWithIngredients(it, rows).toDomain() }
        }.flowOn(ioDispatcher)

    override suspend fun findDetail(id: RecipeId): RecipeDetail? = withContext(ioDispatcher) {
        dao.findDetailRows(id.value)?.toDomain()
    }

    override suspend fun save(detail: RecipeDetail) = withContext(ioDispatcher) {
        val stamp = now()
        val ordered = detail.ingredients
            .mapIndexed { index, ingredient -> ingredient.copy(position = index) }
        val normalised = detail.copy(ingredients = ordered)

        dao.saveDetailWithMutation(
            recipe = normalised.recipe.toEntity(createdAt = stamp, updatedAt = stamp),
            ingredients = ordered.map { it.toEntity(normalised.id.value) },
            mutation = outbox.recipeUpsert(normalised),
        )
    }

    /**
     * A favourite is part of the recipe (8.3), so flipping it journals the aggregate like any
     * other change. The row is re-read afterwards to build the payload: sending the recipe as it
     * was before the flip would tell the server the opposite of what just happened.
     */
    override suspend fun setFavourite(id: RecipeId, isFavourite: Boolean) =
        withContext(ioDispatcher) {
            val existing = dao.findDetailRows(id.value)?.toDomain() ?: return@withContext
            val updated = existing.copy(recipe = existing.recipe.copy(isFavourite = isFavourite))

            dao.setFavouriteWithMutation(
                id = id.value,
                isFavourite = isFavourite,
                updatedAt = now(),
                mutation = outbox.recipeUpsert(updated),
            )
        }

    /**
     * Deleting a recipe empties the moments that proposed it (8.5: a proposition always
     * references a recipe). The keys are read **before** the delete, because the foreign key
     * cascade removes the rows without reporting which, and each of them is an aggregate that
     * needs its own tombstone — a proposition deleted with no tombstone would come back on the
     * next pull from any device that still had it queued.
     *
     * The ingredients need no tombstone: they are part of this aggregate, and the cascade is
     * what removes them.
     */
    override suspend fun delete(id: RecipeId): List<MealPlanKey> = withContext(ioDispatcher) {
        val plans = dao.findPlansReferencing(id.value)
        val keys = plans.map { MealPlanKey(LocalDate.parse(it.plannedOn), MealSlot.fromId(it.slot)) }

        dao.deleteWithMutations(
            id = id.value,
            mutation = outbox.recipeDelete(id),
            planMutations = keys.map(outbox::mealPlanDelete),
        )
        keys
    }

    override suspend fun findUsing(food: FoodId): List<Recipe> = withContext(ioDispatcher) {
        dao.findUsing(food.value).map { it.toDomain() }
    }
}

/**
 * An ingredient whose stored quantity cannot be read back is dropped rather than shown as
 * nothing: `Quantity` refuses zero, so there is no honest value to put in its place, and a
 * silently zeroed row would make a recipe's total look complete while being wrong.
 */
private fun RecipeWithIngredients.toDomain(): RecipeDetail = RecipeDetail(
    recipe = recipe.toDomain(),
    ingredients = ingredients.mapNotNull { it.toDomainOrNull() },
)
