package fr.kristenjestin.mue.ui.food.day

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodDeletion
import fr.kristenjestin.mue.domain.repository.FoodLogRepository
import fr.kristenjestin.mue.domain.repository.MealPlanRepository
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * In-memory stand-ins for the four stores the `Day` screen reads.
 *
 * The Room implementations exist and are what the screen runs against — `AppContainer.food`
 * hands them to [FoodDayViewModel.Factory]. These fakes are not a stand-in for something
 * missing; they are what keeps the ViewModel's own tests on the JVM. Date navigation, the strict
 * totals and the two writes of PRD_FOOD 12 are proved in milliseconds with no emulator, no
 * Robolectric and no database, and a bug in a DAO cannot make one of them red.
 *
 * They store what they are given and hand it straight back. Nothing here rounds, groups or sums
 * anything: a fake that computed would be a second implementation of the domain, and the tests
 * would then agree with themselves rather than with the module.
 *
 * The Room implementations are covered where they belong — against a real database, in
 * `data/repository`'s own suites — which is the split that keeps neither side testing the
 * other's job.
 */
internal class FakeFoodLogRepository(
    entries: List<FoodLogEntry> = emptyList(),
) : FoodLogRepository {

    val saved = mutableListOf<FoodLogEntry>()
    val deleted = mutableListOf<FoodLogEntryId>()

    private val state = MutableStateFlow(entries)

    override fun observeDay(date: LocalDate): Flow<List<FoodLogEntry>> =
        state.map { all -> all.filter { it.consumedOn == date } }

    override fun observeIn(window: DateWindow): Flow<List<FoodLogEntry>> = state

    override fun observeLoggedDatesIn(window: DateWindow): Flow<List<LocalDate>> =
        state.map { all -> all.map { it.consumedOn }.distinct().sorted() }

    override suspend fun findById(id: FoodLogEntryId): FoodLogEntry? =
        state.value.firstOrNull { it.id == id }

    override suspend fun findByPlan(key: MealPlanKey): FoodLogEntry? =
        state.value.firstOrNull { it.fromPlan == key }

    override suspend fun recentlyUsedFoods(limit: Int): List<FoodId> = emptyList()

    override suspend fun save(entry: FoodLogEntry) {
        saved += entry
        state.value = state.value.filterNot { it.id == entry.id } + entry
    }

    override suspend fun delete(id: FoodLogEntryId) {
        deleted += id
        state.value = state.value.filterNot { it.id == id }
    }
}

internal class FakeMealPlanRepository(
    plans: List<MealPlanEntry> = emptyList(),
) : MealPlanRepository {

    /**
     * Every proposal actually written, in order (PRD_FOOD 12).
     *
     * The list and not just the final state, because FR-PLAN-001's rule is about a **write**:
     * posing on an occupied moment replaces rather than duplicates, and a fake that only answered
     * reads could not tell one upsert from two.
     */
    val saved = mutableListOf<MealPlanEntry>()
    val deleted = mutableListOf<MealPlanKey>()
    val consumed = mutableListOf<Pair<MealPlanKey, FoodLogEntryId?>>()

    private val state = MutableStateFlow(plans)

    /** What the store holds for one day right now, for an assertion that is not about a flow. */
    fun onDay(date: LocalDate): List<MealPlanEntry> = state.value.filter { it.plannedOn == date }

    override fun observeDay(date: LocalDate): Flow<List<MealPlanEntry>> =
        state.map { all -> all.filter { it.plannedOn == date } }

    override fun observeIn(window: DateWindow): Flow<List<MealPlanEntry>> = state

    override suspend fun find(key: MealPlanKey): MealPlanEntry? =
        state.value.firstOrNull { it.key == key }

    override suspend fun save(entry: MealPlanEntry) {
        saved += entry
        state.value = state.value.filterNot { it.key == entry.key } + entry
    }

    override suspend fun setConsumed(key: MealPlanKey, logEntryId: FoodLogEntryId?) {
        consumed += key to logEntryId
        state.value = state.value.map {
            if (it.key == key) it.copy(consumedLogEntryId = logEntryId) else it
        }
    }

    override suspend fun delete(key: MealPlanKey) {
        deleted += key
        state.value = state.value.filterNot { it.key == key }
    }

    override suspend fun deleteReferencing(recipe: RecipeId): List<MealPlanKey> {
        val freed = state.value.filter { it.recipeId == recipe }.map { it.key }
        state.value = state.value.filterNot { it.recipeId == recipe }
        return freed
    }
}

internal class FakeRecipeRepository(
    details: List<RecipeDetail> = emptyList(),
) : RecipeRepository {

    private val state = MutableStateFlow(details)

    override fun observeAll(type: RecipeType?, favouritesOnly: Boolean): Flow<List<Recipe>> =
        state.map { all -> all.map { it.recipe } }

    override fun search(query: String, type: RecipeType?): Flow<List<Recipe>> =
        state.map { all -> all.map { it.recipe }.filter { it.name.contains(query, true) } }

    override fun observeCount(): Flow<Int> = state.map { it.size }

    override fun observeDetail(id: RecipeId): Flow<RecipeDetail?> =
        state.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun findDetail(id: RecipeId): RecipeDetail? =
        state.value.firstOrNull { it.id == id }

    override suspend fun save(detail: RecipeDetail) {
        state.value = state.value.filterNot { it.id == detail.id } + detail
    }

    override suspend fun setFavourite(id: RecipeId, isFavourite: Boolean) = Unit

    override suspend fun delete(id: RecipeId): List<MealPlanKey> {
        state.value = state.value.filterNot { it.id == id }
        return emptyList()
    }

    override suspend fun findUsing(food: FoodId): List<Recipe> =
        state.value.filter { detail -> detail.foodIds.contains(food) }.map { it.recipe }
}

internal class FakeFoodCatalogueRepository(
    foods: List<Food> = emptyList(),
) : FoodCatalogueRepository {

    private val state = MutableStateFlow(foods)

    override fun observeRecentlyUsed(limit: Int): Flow<List<Food>> = state.map { it.take(limit) }

    override fun search(query: String, source: FoodSource?, limit: Int): Flow<List<Food>> =
        state.map { all -> all.filter { it.name.contains(query, true) }.take(limit) }

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
