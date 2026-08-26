package fr.kristenjestin.mue.ui.food.catalogue

import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodDeletion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The catalogue, in memory, told to answer exactly what the real one would.
 *
 * `RoomFoodCatalogueRepository` exists and is what the screens run against; this is what keeps
 * the catalogue's own tests on the JVM. It stores what it is given and applies the two things the
 * screen genuinely depends on — the fold of PRD_FOOD 9.4 and the row limit — because a fake that
 * ignored the limit would let a test pass that the database would fail.
 *
 * [searches] is the point of the class as much as the rows are. PRD_FOOD 9.1 seeds 1 038 entries,
 * and what makes the browse view usable at that size is *which query is issued and with what
 * limit*, which nothing about the returned rows can show. Recording the calls is how that becomes
 * an assertion instead of a claim.
 *
 * [deletion] and [saveAccepts] exist because PRD_FOOD 9.1, 9.3 and 17 give four different answers
 * to a deletion and the screen has to explain all four. Three of them cannot be produced by
 * loading rows into a list.
 */
internal class FakeFoodCatalogue(
    foods: List<Food> = emptyList(),
    recent: List<Food> = emptyList(),
) : FoodCatalogueRepository {

    /** Every call to [search], in order, with the arguments the screen chose. */
    data class SearchCall(val query: String, val source: FoodSource?, val limit: Int)

    val searches = mutableListOf<SearchCall>()
    val saved = mutableListOf<Food>()
    val deleted = mutableListOf<FoodId>()

    /** What [delete] answers next. `Deleted` unless a test is exercising a refusal. */
    var deletion: FoodDeletion = FoodDeletion.Deleted

    /** PRD_FOOD 9.1: the real repository refuses a write onto a reference row. */
    var saveAccepts: Boolean = true

    var recipeNames: List<String> = emptyList()

    private val state = MutableStateFlow(foods)
    private val recentState = MutableStateFlow(recent)

    override fun observeRecentlyUsed(limit: Int): Flow<List<Food>> =
        recentState.map { it.take(limit) }

    override fun search(query: String, source: FoodSource?, limit: Int): Flow<List<Food>> {
        searches += SearchCall(query, source, limit)
        val folded = Food.fold(query)
        return state.map { all ->
            all.asSequence()
                .filter { source == null || it.source == source }
                .filter { folded.isEmpty() || it.nameFolded.contains(folded) }
                .sortedBy { it.nameFolded }
                .take(limit)
                .toList()
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
        if (!saveAccepts) return false
        saved += food
        state.value = state.value.filterNot { it.id == food.id } + food
        return true
    }

    override suspend fun delete(id: FoodId): FoodDeletion {
        deleted += id
        if (deletion is FoodDeletion.Deleted) {
            state.value = state.value.filterNot { it.id == id }
        }
        return deletion
    }

    override suspend fun recipeNamesUsing(id: FoodId): List<String> = recipeNames

    override suspend fun seedCiqual(foods: List<Food>, version: String) {
        state.value = foods
    }

    override suspend fun installedCiqualVersion(): String? = null
}

/**
 * A catalogue the size of the shipped one (PRD_FOOD 9.1: 1 038 entries).
 *
 * Built rather than loaded, because what is being proved is that the *screen* never asks for all
 * of them — the asset itself is the seeding's business and is tested there.
 */
internal fun aLargeCatalogue(size: Int = 1_038): List<Food> = List(size) { index ->
    Food(
        id = FoodId("ciqual-$index"),
        name = "Catalogue food ${index.toString().padStart(4, '0')}",
        source = FoodSource.CIQUAL,
        sourceId = index.toString(),
    )
}
