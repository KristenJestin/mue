package fr.kristenjestin.mue.data.local.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.repository.RoomFoodCatalogueRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.FoodSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The seeding of PRD_FOOD 9.1 and 20.2, against the catalogue actually packaged in the APK.
 */
@RunWith(AndroidJUnit4::class)
class CiqualSeedingTest {

    private lateinit var context: Context
    private lateinit var database: MueDatabase
    private lateinit var preferencesFile: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: RoomFoodCatalogueRepository
    private lateinit var seeding: CiqualSeeding

    @Before
    fun createSeeding() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MueDatabase::class.java).build()
        preferencesFile = File(context.cacheDir, "ciqual_${System.nanoTime()}.preferences_pb")
        store = PreferenceDataStoreFactory.create { preferencesFile }
        repository = RoomFoodCatalogueRepository(
            database = database,
            dao = database.foodDao(),
            catalogueDataStore = store,
            outbox = SyncOutbox(),
        )
        seeding = CiqualSeeding(context.assets, repository)
    }

    @After
    fun closeDatabase() {
        database.close()
        preferencesFile.delete()
    }

    @Test
    fun aCatalogueIsPackagedInTheApk() {
        assertNotNull(CiqualCatalogueAsset.availableVersion(context.assets))
    }

    @Test
    fun theFirstStartInstallsTheCatalogue() = runTest {
        val outcome = seeding.seedIfNeeded()

        assertTrue("$outcome", outcome is CiqualSeedOutcome.Installed)
        val installed = outcome as CiqualSeedOutcome.Installed
        assertTrue(installed.foods > 0)
        assertEquals(installed.foods, database.foodDao().countBySource(FoodSource.CIQUAL.id))
    }

    @Test
    fun theInstalledVersionIsRecordedWhereReadingItOpensNoDatabase() = runTest {
        assertNull(repository.installedCiqualVersion())

        seeding.seedIfNeeded()

        assertEquals(
            CiqualCatalogueAsset.availableVersion(context.assets),
            repository.installedCiqualVersion(),
        )
    }

    /** The guard: a start with nothing to do writes nothing and installs nothing. */
    @Test
    fun theSecondStartDoesNothingAtAll() = runTest {
        seeding.seedIfNeeded()
        val installedCount = database.foodDao().countBySource(FoodSource.CIQUAL.id)

        val outcome = seeding.seedIfNeeded()

        assertTrue("$outcome", outcome is CiqualSeedOutcome.AlreadyInstalled)
        assertEquals(installedCount, database.foodDao().countBySource(FoodSource.CIQUAL.id))
    }

    /** PRD_FOOD 21.1: the embedded catalogue is reference data and never reaches the outbox. */
    @Test
    fun seedingJournalsNothing() = runTest {
        seeding.seedIfNeeded()

        assertEquals(0, database.syncDao().pendingMutations(50).size)
    }

    @Test
    fun everySeededFoodIsAReadOnlyCiqualEntryWithItsVersion() = runTest {
        seeding.seedIfNeeded()

        val version = CiqualCatalogueAsset.availableVersion(context.assets)
        database.foodDao().findByIds(seededIds()).forEach { row ->
            assertEquals(FoodSource.CIQUAL.id, row.source)
            assertEquals(version, row.sourceVersion)
        }
    }

    /** The identifiers are the asset's own, so two installs agree on what a food is. */
    @Test
    fun theSeededIdentifiersAreTheOnesTheAssetCarries() = runTest {
        seeding.seedIfNeeded()

        val raw = requireNotNull(
            CiqualCatalogueAsset.readOrNull(
                context.assets,
                requireNotNull(CiqualCatalogueAsset.availableVersion(context.assets)),
            ),
        )
        val expected = CiqualCatalogueAsset.idsByCode(raw).values.sorted()

        assertEquals(expected, seededIds().sorted())
    }

    /** The null-not-zero rule, all the way from the asset to SQLite and back. */
    @Test
    fun aMetricTheAssetLeavesOutIsNullInTheColumn() = runTest {
        seeding.seedIfNeeded()

        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM food WHERE fibre_milligrams IS NULL")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("the asset must carry an unreported fibre", cursor.getInt(0) > 0)
            }
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM food WHERE carbs_milligrams = 0")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("the asset must carry a measured zero", cursor.getInt(0) > 0)
            }
    }

    private fun seededIds(): List<String> {
        val ids = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT id FROM food WHERE source = 'ciqual'")
            .use { cursor -> while (cursor.moveToNext()) ids.add(cursor.getString(0)) }
        return ids
    }
}
