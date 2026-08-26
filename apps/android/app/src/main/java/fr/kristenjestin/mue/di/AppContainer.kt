package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.data.export.CsvExportWriter
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.datastore.userPreferencesDataStore
import fr.kristenjestin.mue.data.local.datastore.userProfileDataStore
import fr.kristenjestin.mue.data.repository.DataStoreUserPreferencesRepository
import fr.kristenjestin.mue.data.repository.DataStoreUserProfileRepository
import fr.kristenjestin.mue.data.repository.RoomActivityRepository
import fr.kristenjestin.mue.data.repository.RoomExerciseCatalogRepository
import fr.kristenjestin.mue.data.repository.RoomMeasurementRepository
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import fr.kristenjestin.mue.domain.repository.ExerciseCatalogRepository
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository

/**
 * Manual dependency container. Repositories and data sources are registered here
 * as they are implemented, and read by ViewModel factories.
 *
 * Everything is lazy: opening the database on the first read keeps it off the
 * application's startup path.
 */
class AppContainer(private val applicationContext: Context) {
    val appContext: Context get() = applicationContext

    private val database: MueDatabase by lazy { MueDatabase.build(applicationContext) }

    val measurementRepository: MeasurementRepository by lazy {
        RoomMeasurementRepository(database.measurementDao(), sync.outbox)
    }

    val activityRepository: ActivityRepository by lazy {
        RoomActivityRepository(database.activityDao())
    }

    val exerciseCatalogRepository: ExerciseCatalogRepository by lazy {
        RoomExerciseCatalogRepository(database.exerciseCatalogDao())
    }

    val userProfileRepository: UserProfileRepository by lazy {
        DataStoreUserProfileRepository(
            applicationContext.userProfileDataStore,
            database.healthProfileDao(),
            // The same outbox the measurement repository uses. The health profile is a
            // synchronised aggregate (sync PRD 13.4), so its writes are journalled too.
            sync.outbox,
        )
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        DataStoreUserPreferencesRepository(applicationContext.userPreferencesDataStore)
    }

    val csvExportWriter: CsvExportWriter by lazy {
        CsvExportWriter(applicationContext.cacheDir)
    }

    /**
     * The Activity Timer, whole. One property rather than five, so the module can be built and
     * changed without this file moving again.
     */
    val timer: TimerContainer by lazy { TimerContainer(applicationContext, database) }

    /** Server synchronisation, whole, for the same reason as [timer]. */
    val sync: SyncContainer by lazy { SyncContainer(applicationContext, database) }

    /**
     * The Food module, whole — its four repositories and the Ciqual seeding — for the same
     * reason as [timer] and [sync]. It shares [SyncContainer.outbox] rather than minting a
     * second one, so every aggregate's mutation comes from the same place.
     */
    val food: FoodContainer by lazy { FoodContainer(applicationContext, database, sync.outbox) }

    /**
     * Le module balance, entier, pour la même raison que [timer], [sync] et [food]. Il ne prend
     * pas [SyncContainer.outbox] : les balances enregistrées ne sont pas synchronisées
     * (PRD_SCALE 22), et les mesures qu'elles produisent sont journalisées par
     * [measurementRepository], qui l'a déjà.
     */
    val scale: ScaleContainer by lazy { ScaleContainer(applicationContext, database) }
}
