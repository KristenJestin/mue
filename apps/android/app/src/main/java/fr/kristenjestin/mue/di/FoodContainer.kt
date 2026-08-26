package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.data.local.database.CiqualSeeding
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.datastore.foodCatalogueDataStore
import fr.kristenjestin.mue.data.repository.RoomFoodCatalogueRepository
import fr.kristenjestin.mue.data.repository.RoomFoodLogRepository
import fr.kristenjestin.mue.data.repository.RoomMealPlanRepository
import fr.kristenjestin.mue.data.repository.RoomRecipeRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodLogRepository
import fr.kristenjestin.mue.domain.repository.MealPlanRepository
import fr.kristenjestin.mue.domain.repository.RecipeRepository

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
}
