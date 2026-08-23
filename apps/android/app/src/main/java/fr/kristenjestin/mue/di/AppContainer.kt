package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.data.export.CsvExportWriter
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.datastore.userPreferencesDataStore
import fr.kristenjestin.mue.data.local.datastore.userProfileDataStore
import fr.kristenjestin.mue.data.repository.DataStoreUserPreferencesRepository
import fr.kristenjestin.mue.data.repository.DataStoreUserProfileRepository
import fr.kristenjestin.mue.data.repository.RoomMeasurementRepository
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
        RoomMeasurementRepository(database.measurementDao())
    }

    val userProfileRepository: UserProfileRepository by lazy {
        DataStoreUserProfileRepository(applicationContext.userProfileDataStore)
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        DataStoreUserPreferencesRepository(applicationContext.userPreferencesDataStore)
    }

    val csvExportWriter: CsvExportWriter by lazy {
        CsvExportWriter(applicationContext.cacheDir)
    }
}
