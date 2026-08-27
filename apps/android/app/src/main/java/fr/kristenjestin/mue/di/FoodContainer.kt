package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.data.local.database.CiqualSeeding
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.datastore.foodCatalogueDataStore
import fr.kristenjestin.mue.data.local.datastore.userPreferencesDataStore
import fr.kristenjestin.mue.data.remote.openfoodfacts.KtorProductLookup
import fr.kristenjestin.mue.data.repository.DataStoreScanPreferencesRepository
import fr.kristenjestin.mue.data.repository.RoomFoodCatalogueRepository
import fr.kristenjestin.mue.data.repository.RoomFoodLogRepository
import fr.kristenjestin.mue.data.repository.RoomMealPlanRepository
import fr.kristenjestin.mue.data.repository.RoomRecipeRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodLogRepository
import fr.kristenjestin.mue.domain.repository.MealPlanRepository
import fr.kristenjestin.mue.domain.repository.ProductLookup
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import fr.kristenjestin.mue.domain.repository.ScanPreferencesRepository
import io.ktor.client.HttpClient

/**
 * Everything the Food module needs, registered in one place.
 *
 * [AppContainer] gains a **single** property for the whole module, exactly as the Activity Timer
 * and server synchronisation did before it, so the six screens still to be built — `Day`,
 * `Trends`, the catalogue, the recipe editor, the scanner and the planner — can be wired against
 * this surface without the shipped container having to move again for each of them.
 *
 * Lazy, like everything in [AppContainer]: the four repositories open the database, and a cold
 * start that never reaches the Food tab must not pay for it. [outbox] is taken from the sync
 * container rather than built again — one mint point for `mutation_id` across every aggregate is
 * what makes the outbox drainable in one pass.
 */
class FoodContainer(
    private val applicationContext: Context,
    private val database: MueDatabase,
    private val outbox: SyncOutbox,
) {
    /** Ciqual, the Open Food Facts copies and the custom foods, in one table (PRD_FOOD 9). */
    val foodCatalogueRepository: FoodCatalogueRepository by lazy {
        RoomFoodCatalogueRepository(
            database = database,
            dao = database.foodDao(),
            catalogueDataStore = applicationContext.foodCatalogueDataStore,
            outbox = outbox,
        )
    }

    val recipeRepository: RecipeRepository by lazy {
        RoomRecipeRepository(database.recipeDao(), outbox)
    }

    val foodLogRepository: FoodLogRepository by lazy {
        RoomFoodLogRepository(database.foodLogDao(), outbox)
    }

    val mealPlanRepository: MealPlanRepository by lazy {
        RoomMealPlanRepository(database.mealPlanDao(), outbox)
    }

    /**
     * The embedded catalogue's one-shot install (PRD_FOOD 20.2), for
     * `MueApplication.onCreate` to launch beside `healthProfileSeeding`. Its guard is a
     * DataStore preference against an asset file name, so calling it on a start with nothing to
     * do opens neither the database nor the asset.
     */
    val ciqualSeeding: CiqualSeeding by lazy {
        CiqualSeeding(applicationContext.assets, foodCatalogueRepository)
    }

    /**
     * FR-FOOD-003's one network call (PRD_FOOD 9.2), on a client of its own.
     *
     * The client is **not** `SyncContainer.httpClient`, and [KtorProductLookup] carries the whole
     * argument: this request goes to a third party, and the shared client exists to carry a
     * bearer to the server the user paired with. Building it here rather than taking it from
     * `AppContainer` is also what keeps this container's constructor unchanged — the Food module
     * still costs the application exactly one property.
     *
     * Both are lazy, so a phone that never opens the scan path never creates an OkHttp dispatcher,
     * never opens a connection pool and never loads the engine.
     */
    val productLookupClient: HttpClient by lazy { KtorProductLookup.defaultClient() }

    val productLookup: ProductLookup by lazy { KtorProductLookup(productLookupClient) }

    /**
     * PRD_FOOD 17 and 18: whether the camera has already been asked for once.
     *
     * In the app's existing preferences file, beside the timer's own flag and for the same reason
     * — nothing shows it and only the permission request reads it.
     */
    val scanPreferencesRepository: ScanPreferencesRepository by lazy {
        DataStoreScanPreferencesRepository(applicationContext.userPreferencesDataStore)
    }
}
