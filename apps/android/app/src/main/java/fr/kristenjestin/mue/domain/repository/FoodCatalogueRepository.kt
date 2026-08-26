package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import kotlinx.coroutines.flow.Flow

/**
 * The food catalogue: the embedded Ciqual subset, the products copied from Open Food Facts and
 * the personal foods, behind one contract (PRD_FOOD 9).
 *
 * One contract and not three because PRD_FOOD 9.4 gives them one search bar, one result list and
 * one recency order. What separates them is `Food.source`, which decides only two things: a
 * Ciqual entry cannot be written or removed (PRD_FOOD 9.1), and a Ciqual entry is not
 * synchronised (PRD_FOOD 21.1).
 *
 * Reads are flows so no screen blocks the main thread; writes are suspending and atomic.
 */
interface FoodCatalogueRepository {

    /**
     * What PRD_FOOD 9.4 puts at the top of an empty search: the foods most recently used,
     * most recent first. Recency comes from the journal, not from any column on the food.
     */
    fun observeRecentlyUsed(limit: Int): Flow<List<Food>>

    /**
     * The single search bar of PRD_FOOD 9.4 — case- and accent-insensitive, offline, over every
     * source at once. [source] is the one filter that restricts it; null means all of them.
     *
     * A blank [query] is not an error: it yields the catalogue in name order, which is what the
     * source filter alone shows.
     */
    fun search(query: String, source: FoodSource? = null, limit: Int): Flow<List<Food>>

    /** The card of one food, still observed because editing it must redraw what quotes it. */
    fun observeById(id: FoodId): Flow<Food?>

    suspend fun findById(id: FoodId): Food?

    /**
     * Several foods at once, for the ingredients of a recipe. Missing ids are simply absent from
     * the result: PRD_FOOD 21.2 requires a recipe to render even when a food has not arrived yet.
     */
    suspend fun findByIds(ids: Collection<FoodId>): List<Food>

    /** PRD_FOOD 9.2: a scan first looks for a product already copied into the local catalogue. */
    suspend fun findByBarcode(barcode: String): Food?

    /**
     * The entry a given source already produced, if any — the Ciqual `alim_code` or the Open
     * Food Facts identifier. What keeps a re-seeding or a second scan from creating a twin.
     */
    suspend fun findBySourceId(source: FoodSource, sourceId: String): Food?

    /**
     * Creates or replaces one food.
     *
     * A food whose source is [FoodSource.CIQUAL] is refused: PRD_FOOD 9.1 makes the embedded
     * subset read-only and PRD_FOOD 21.4 keeps even an authorised MCP client out of it. Refused
     * means "left unchanged", not "thrown" — the caller learns from the returned flag.
     */
    suspend fun save(food: Food): Boolean

    /**
     * PRD_FOOD 9.3 and 17: a food used by a recipe cannot be deleted, and Mue names the recipes
     * that have to release it first.
     *
     * Deleting never touches the journal — PRD_FOOD 8.4 froze those values — and the outcome is
     * a value rather than an exception because "you cannot delete this yet" is a normal answer
     * this screen has to render, not a failure.
     */
    suspend fun delete(id: FoodId): FoodDeletion

    /** The recipes that would block a deletion, by name, in the order the dialog lists them. */
    suspend fun recipeNamesUsing(id: FoodId): List<String>

    /**
     * PRD_FOOD 20.2: the subset is inserted on first launch with its version, and a later update
     * never modifies a personal food nor a journal line. Replaces the Ciqual rows only.
     */
    suspend fun seedCiqual(foods: List<Food>, version: String)

    /** The version of the embedded subset already installed, or null before the first seeding. */
    suspend fun installedCiqualVersion(): String?
}

/**
 * What became of a deletion request (PRD_FOOD 9.3 and 17).
 *
 * Three refusals rather than a boolean, because the three say different things on screen: one
 * names recipes to edit, one explains that reference data is not yours to remove, and one is
 * simply a stale id.
 */
sealed interface FoodDeletion {

    /** Gone from the catalogue. Journal lines that quoted it keep their frozen values. */
    data object Deleted : FoodDeletion

    /** No such food; already deleted, or an id from a screen that has not been refreshed. */
    data object NotFound : FoodDeletion

    /** PRD_FOOD 9.1: an entry of the embedded Ciqual subset. Duplicate it instead. */
    data object ReadOnly : FoodDeletion

    /** PRD_FOOD 17: "suppression refusée, recettes concernées nommées". */
    data class UsedByRecipes(val recipeNames: List<String>) : FoodDeletion
}
