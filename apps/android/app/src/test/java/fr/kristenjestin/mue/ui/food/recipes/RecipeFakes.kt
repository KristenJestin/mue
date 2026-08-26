package fr.kristenjestin.mue.ui.food.recipes

import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodDeletion
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-ins for the two stores the recipe screens read.
 *
 * The Room implementations exist and are what the screens run against — `AppContainer.food` hands
 * them to every factory here. These fakes are not a stand-in for something missing; they are what
 * keeps the ViewModels' own tests on the JVM. The filters of FR-RECIPE-005, the strict totals of
 * PRD_FOOD 13.1 and the freed proposals of FR-RECIPE-006 are proved in milliseconds with no
 * emulator, no Robolectric and no database, and a bug in a DAO cannot make one of them red.
 *
 * They store what they are given and hand it straight back. Nothing here rounds, scales or sums:
 * a fake that computed would be a second implementation of the domain, and the tests would then
 * agree with themselves rather than with the module.
 *
 * Two behaviours *are* reproduced, because a screen is built on them and would otherwise be
 * tested against a contract nobody keeps: the ordering `observeAll` promises — favourites first,
 * then by name — and the fold `search` applies, which is `Food.fold`'s and not a second one.
 */
internal class FakeRecipeRepository(
    details: List<RecipeDetail> = emptyList(),
    /** What `delete` reports as freed, keyed by recipe (PRD_FOOD 8.5 and FR-RECIPE-006). */
    private val plans: Map<RecipeId, List<MealPlanKey>> = emptyMap(),
) : RecipeRepository {

    val saved = mutableListOf<RecipeDetail>()
    val favourited = mutableListOf<Pair<RecipeId, Boolean>>()
    val deleted = mutableListOf<RecipeId>()

    private val state = MutableStateFlow(details)

    override fun observeAll(type: RecipeType?, favouritesOnly: Boolean): Flow<List<Recipe>> =
        state.map { all ->
            all.map { it.recipe }
                .filter { type == null || it.type == type }
                .filter { !favouritesOnly || it.isFavourite }
                .sortedWith(compareByDescending<Recipe> { it.isFavourite }.thenBy { it.nameFolded })
        }

    override fun search(query: String, type: RecipeType?): Flow<List<Recipe>> {
        val folded = Food.fold(query)
        return state.map { all ->
            all.map { it.recipe }
                .filter { type == null || it.type == type }
                .filter { it.nameFolded.contains(folded) }
                .sortedWith(compareByDescending<Recipe> { it.isFavourite }.thenBy { it.nameFolded })
        }
    }

    override fun observeCount(): Flow<Int> = state.map { it.size }

    override fun observeDetail(id: RecipeId): Flow<RecipeDetail?> =
        state.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun findDetail(id: RecipeId): RecipeDetail? =
        state.value.firstOrNull { it.id == id }

    override suspend fun save(detail: RecipeDetail) {
        saved += detail
        state.value = state.value.filterNot { it.id == detail.id } + detail
    }

    override suspend fun setFavourite(id: RecipeId, isFavourite: Boolean) {
        favourited += id to isFavourite
        state.value = state.value.map { detail ->
            if (detail.id == id) {
                detail.copy(recipe = detail.recipe.copy(isFavourite = isFavourite))
            } else {
                detail
            }
        }
    }

    override suspend fun delete(id: RecipeId): List<MealPlanKey> {
        deleted += id
        state.value = state.value.filterNot { it.id == id }
        return plans[id].orEmpty()
    }

    override suspend fun findUsing(food: FoodId): List<Recipe> =
        state.value.filter { it.foodIds.contains(food) }.map { it.recipe }
}

/**
 * The catalogue, as far as the recipe screens use it: resolving the foods an ingredient names,
 * and answering the picker.
 *
 * A food that is simply absent is the ordinary case of PRD_FOOD 21.2 rather than an error, so
 * [findByIds] returns what it has and says nothing about what it has not.
 */
internal class FakeFoodCatalogueRepository(
    foods: List<Food> = emptyList(),
    private val recentlyUsed: List<Food> = emptyList(),
) : FoodCatalogueRepository {

    private val state = MutableStateFlow(foods)

    override fun observeRecentlyUsed(limit: Int): Flow<List<Food>> =
        MutableStateFlow(recentlyUsed.take(limit))

    override fun search(query: String, source: FoodSource?, limit: Int): Flow<List<Food>> {
        val folded = Food.fold(query)
        return state.map { all ->
            all.filter { source == null || it.source == source }
                .filter { folded.isEmpty() || it.nameFolded.contains(folded) }
                .sortedBy { it.nameFolded }
                .take(limit)
        }
    }

    override fun observeById(id: FoodId): Flow<Food?> =
        state.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun findById(id: FoodId): Food? = state.value.firstOrNull { it.id == id }

    override suspend fun findByIds(ids: Collection<FoodId>): List<Food> =
        state.value.filter { it.id in ids }

    override suspend fun findByBarcode(barcode: String): Food? =
        state.value.firstOrNull { it.barcode == barcode }

    override suspend fun findBySourceId(source: FoodSource, sourceId: String): Food? =
        state.value.firstOrNull { it.source == source && it.sourceId == sourceId }

    override suspend fun save(food: Food): Boolean {
        state.value = state.value.filterNot { it.id == food.id } + food
        return true
    }

    override suspend fun delete(id: FoodId): FoodDeletion {
        state.value = state.value.filterNot { it.id == id }
        return FoodDeletion.Deleted
    }

    override suspend fun recipeNamesUsing(id: FoodId): List<String> = emptyList()

    override suspend fun seedCiqual(foods: List<Food>, version: String) {
        state.value = foods
    }

    override suspend fun installedCiqualVersion(): String? = null
}
