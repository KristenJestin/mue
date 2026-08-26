package fr.kristenjestin.mue.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import fr.kristenjestin.mue.data.local.database.FoodDao
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodDeletion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The one catalogue of PRD_FOOD 9, over Ciqual, Open Food Facts copies and custom foods alike.
 *
 * Two policies live here rather than in a screen, because both have to hold for an MCP client
 * too (PRD_FOOD 21.4):
 *
 * - a Ciqual row is read only (9.1). [save] refuses to write one and [delete] answers
 *   [FoodDeletion.ReadOnly]; duplicating it into a `CUSTOM` food is the supported move.
 * - a food an existing recipe uses cannot be deleted (9.3). The answer names the recipes so the
 *   caller can say which ones to edit first, rather than failing with nothing to act on.
 *
 * Both are decided inside one transaction with the write they guard: read the source, read the
 * recipes, then delete is three statements, and a check made outside the transaction would be a
 * check made against a database that may already have moved.
 *
 * [installedCiqualVersion] reads a **DataStore preference, never Room**. The seeding guard runs
 * on every cold start, and the sync chunk already learned what a Room read on that path costs:
 * `HealthProfileSeeding` opens the database at every launch to consult one boolean. A phone that
 * opens Mue on the weight tab must not pay for the food database to discover it has nothing to
 * do.
 */
class RoomFoodCatalogueRepository(
    private val database: MueDatabase,
    private val dao: FoodDao,
    private val catalogueDataStore: DataStore<Preferences>,
    private val outbox: SyncOutbox = SyncOutbox(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : FoodCatalogueRepository {

    override fun observeRecentlyUsed(limit: Int): Flow<List<Food>> =
        dao.observeRecentlyUsed(limit)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override fun search(query: String, source: FoodSource?, limit: Int): Flow<List<Food>> {
        val folded = foldForSearch(query)
        return dao.search(
            pattern = "%$folded%",
            prefix = "$folded%",
            source = source?.id,
            limit = limit,
        ).map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)
    }

    override fun observeById(id: FoodId): Flow<Food?> =
        dao.observeById(id.value).map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun findById(id: FoodId): Food? = withContext(ioDispatcher) {
        dao.findById(id.value)?.toDomain()
    }

    /**
     * Empty in, empty out: `IN ()` is not valid SQLite, and a recipe with no ingredients has no
     * foods to resolve.
     */
    override suspend fun findByIds(ids: Collection<FoodId>): List<Food> =
        withContext(ioDispatcher) {
            if (ids.isEmpty()) {
                emptyList()
            } else {
                dao.findByIds(ids.map { it.value }.distinct()).map { it.toDomain() }
            }
        }

    override suspend fun findByBarcode(barcode: String): Food? = withContext(ioDispatcher) {
        dao.findByBarcode(barcode)?.toDomain()
    }

    override suspend fun findBySourceId(source: FoodSource, sourceId: String): Food? =
        withContext(ioDispatcher) {
            dao.findBySourceId(source.id, sourceId)?.toDomain()
        }

    /**
     * `false` means refused, and the only refusal is a Ciqual entry (9.1) — either because the
     * food offered is one, or because the id already belongs to one. Both are the same rule
     * seen from the two sides an MCP write can approach it from.
     *
     * A copied Open Food Facts product journals like a custom food: 21.1 synchronises the local
     * copy and never re-downloads it. Only Ciqual stays out of the outbox.
     */
    override suspend fun save(food: Food): Boolean = withContext(ioDispatcher) {
        if (food.source.isReadOnly) return@withContext false

        database.withTransaction {
            val existing = dao.findById(food.id.value)
            if (existing != null && FoodSource.fromId(existing.source).isReadOnly) {
                return@withTransaction false
            }

            val stamp = now()
            val entity = food.toEntity(
                createdAt = existing?.createdAt ?: stamp,
                updatedAt = stamp,
            )
            dao.upsertWithMutation(entity, outbox.foodUpsert(food))
            true
        }
    }

    override suspend fun delete(id: FoodId): FoodDeletion = withContext(ioDispatcher) {
        database.withTransaction {
            val existing = dao.findById(id.value) ?: return@withTransaction FoodDeletion.NotFound
            if (FoodSource.fromId(existing.source).isReadOnly) {
                return@withTransaction FoodDeletion.ReadOnly
            }

            val recipes = dao.recipeNamesUsing(id.value)
            if (recipes.isNotEmpty()) {
                return@withTransaction FoodDeletion.UsedByRecipes(recipes)
            }

            dao.deleteWithMutation(id.value, outbox.foodDelete(id))
            FoodDeletion.Deleted
        }
    }

    override suspend fun recipeNamesUsing(id: FoodId): List<String> = withContext(ioDispatcher) {
        dao.recipeNamesUsing(id.value)
    }

    /**
     * The embedded subset, installed or refreshed. It journals nothing — 21.1 lists the Ciqual
     * catalogue as not synchronised — and it writes the version **after** the rows, so a seeding
     * interrupted halfway is retried on the next start instead of being recorded as done.
     *
     * Anything the caller hands over that is not a Ciqual entry is dropped rather than written:
     * this is the one door into the read-only half of the catalogue, and it must not become a
     * way to write a custom food that no screen could then edit.
     */
    override suspend fun seedCiqual(foods: List<Food>, version: String) {
        withContext(ioDispatcher) {
            val stamp = now()
            val entities = foods
                .filter { it.source == FoodSource.CIQUAL }
                .map { it.toEntity(createdAt = stamp, updatedAt = stamp) }

            // `replaceCiqual` clears the shipped subset before writing the new one, so an empty
            // list would trade the whole catalogue for nothing. Refusing it here — rather than
            // trusting every caller to have checked — is what makes the replacement safe, and it
            // is also the honest answer for a list that named no Ciqual entry at all: no subset
            // was installed, so no version may be recorded as installed.
            if (entities.isEmpty()) return@withContext

            dao.replaceCiqual(entities)
            catalogueDataStore.edit { it[KEY_INSTALLED_CIQUAL_VERSION] = version }
        }
    }

    override suspend fun installedCiqualVersion(): String? = withContext(ioDispatcher) {
        catalogueDataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .first()[KEY_INSTALLED_CIQUAL_VERSION]
    }

    companion object {
        /**
         * Not a Room row. See the class comment: the guard runs on every cold start, and the
         * whole point of the preference is that consulting it opens no database.
         */
        val KEY_INSTALLED_CIQUAL_VERSION = stringPreferencesKey("installed_ciqual_version")

        /**
         * PRD_FOOD 9.4 wants a search insensitive to case and to accents, and `Food.fold` is the
         * single definition of that — the same one `name_folded` was written with. The three
         * characters `LIKE` treats as syntax are escaped so that a user typing `100%` searches
         * for `100%` rather than for everything.
         */
        internal fun foldForSearch(query: String): String = Food.fold(query)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
