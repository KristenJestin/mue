package fr.kristenjestin.mue.ui.food.add

import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodDeletion
import fr.kristenjestin.mue.domain.repository.ProductLookup
import fr.kristenjestin.mue.domain.repository.ProductLookupResult
import kotlinx.coroutines.CompletableDeferred
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

    /**
     * Every food actually written, in order.
     *
     * PRD_FOOD 9.2's rule about a scanned product is a rule about a **write** — the copy happens
     * at the moment of adding, once, carrying its provenance — and a fake that only answered
     * reads could not tell "copied" from "shown".
     */
    val saved = mutableListOf<Food>()

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
        saved += food
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

/**
 * A barcode lookup that answers whatever a test told it to, and **records what it was asked**.
 *
 * The recording half is the point, and it is the same argument
 * [RecordingFoodCatalogueRepository] makes: FR-FOOD-003's rules are about *whether* the network
 * is reached at all — a product already copied locally must not be fetched again (PRD_FOOD 9.2),
 * a code that is not a barcode must not be sent, and a second scan must not leave the first
 * request racing it. A fake that only returned values would let all three pass untested.
 */
internal class FakeProductLookup(
    private val answers: MutableMap<String, ProductLookupResult> = mutableMapOf(),
) : ProductLookup {

    /** Every barcode that actually reached the transport, in order. */
    val requested = mutableListOf<String>()

    /** Held open by [hold]; null while answers come back at once. */
    private var gate: CompletableDeferred<Unit>? = null

    fun answer(barcode: String, result: ProductLookupResult) {
        answers[barcode] = result
    }

    /**
     * Makes the next lookups hang until [release], so `LookingUp` can be observed at all.
     *
     * A suspending call that returns immediately never lets a test see the state in between, and
     * "the panel says it is looking" is a rule PRD_FOOD 17 cares about.
     */
    fun hold() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun byBarcode(barcode: String): ProductLookupResult {
        requested += barcode
        gate?.await()
        // The default is the one an unknown barcode really produces, so a test that forgets to
        // stub gets the honest answer rather than a crash or an invented product.
        return answers[barcode] ?: ProductLookupResult.NotFound
    }
}
