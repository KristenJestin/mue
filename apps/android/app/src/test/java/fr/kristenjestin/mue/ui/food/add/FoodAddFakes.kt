package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodDeletion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A catalogue that answers like the real one and **records how it was asked**.
 *
 * `FoodDayFakes` already has a catalogue fake, and this is not a second copy of it: the picker's
 * whole job is the shape of the query — which limit, which source filter, and whether an empty
 * search reads the recents or the catalogue — and a fake that ignored those arguments would let
 * every one of those rules pass untested.
 *
 * It filters on the folded name exactly as `Food.fold` and the DAO do, so a test can assert that
 * an accent and a capital find the same row without the assertion being a copy of the SQL.
 */
internal class RecordingFoodCatalogueRepository(
    foods: List<Food> = emptyList(),
    recent: List<Food> = emptyList(),
) : FoodCatalogueRepository {

    /** Every `(query, source, limit)` the screen asked for, in order. */
    val searches = mutableListOf<Triple<String, FoodSource?, Int>>()

    /** Every limit the recently-used list was asked for. */
    val recentLimits = mutableListOf<Int>()

    private val state = MutableStateFlow(foods)
    private val recentState = MutableStateFlow(recent)

    override fun observeRecentlyUsed(limit: Int): Flow<List<Food>> {
        recentLimits += limit
        return recentState.map { it.take(limit) }
    }

    override fun search(query: String, source: FoodSource?, limit: Int): Flow<List<Food>> {
        searches += Triple(query, source, limit)
        val folded = Food.fold(query)
        return state.map { all ->
            all.asSequence()
                .filter { source == null || it.source == source }
                .filter { folded.isEmpty() || it.nameFolded.contains(folded) }
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
