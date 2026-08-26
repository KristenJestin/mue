package fr.kristenjestin.mue.data.local.database

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.repository.RoomFoodCatalogueRepository
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.model.FoodSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val SEEDING_TAG = "CiqualSeeding"

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

    /**
     * The requirement behind the DataStore preference, asserted rather than argued: on a start
     * with nothing to install, the guard must not build a Room connection.
     *
     * `MueDatabase` is constructed in [createSeeding] and its DAO is handed to the repository
     * there, so if either of those opened the file this would already be false before
     * `seedIfNeeded` is called. That is the point — a phone opening Mue on the weight tab pays
     * for one small protobuf read and nothing else.
     */
    @Test
    fun theGuardOpensNoDatabaseWhenTheCatalogueIsAlreadyInstalled() = runTest {
        val available = requireNotNull(CiqualCatalogueAsset.availableVersion(context.assets))
        store.edit { it[RoomFoodCatalogueRepository.KEY_INSTALLED_CIQUAL_VERSION] = available }
        assertFalse("building the container must not open Room", database.isOpen)

        val outcome = seeding.seedIfNeeded()

        assertTrue("$outcome", outcome is CiqualSeedOutcome.AlreadyInstalled)
        assertFalse("a cold start with nothing to do must not open Room", database.isOpen)
    }

    /**
     * PRD_FOOD 9.1 promises the catalogue "dès la première ouverture", so what the first launch
     * costs is a number this suite has to produce rather than assume.
     *
     * The work runs off the main thread, so this is not a frame budget; the ceiling is only
     * there to catch an order-of-magnitude regression — a per-row transaction instead of one
     * batch, say — on an emulator that is also the slowest machine this will ever run on. The
     * measured value is logged under [SEEDING_TAG] so a run can report it.
     */
    @Test
    fun theWholeShippedCatalogueInstallsWithinAFirstLaunchBudget() = runTest {
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = seeding.seedIfNeeded()
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAt

        assertTrue("$outcome", outcome is CiqualSeedOutcome.Installed)
        val installed = outcome as CiqualSeedOutcome.Installed
        Log.i(SEEDING_TAG, "seeded ${installed.foods} foods in $elapsedMillis ms")

        assertEquals(installed.foods, database.foodDao().countBySource(FoodSource.CIQUAL.id))
        assertTrue("the shipped subset must be the real one", installed.foods > 1_000)
        assertTrue(
            "seeding ${installed.foods} foods took $elapsedMillis ms",
            elapsedMillis < 15_000,
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

    /**
     * Raw SQL rather than `findByIds`: a thousand bound parameters is a limit to run into for
     * nothing, and the point here is what the columns hold, not what the mapper makes of them.
     */
    @Test
    fun everySeededFoodIsAReadOnlyCiqualEntryWithItsVersion() = runTest {
        seeding.seedIfNeeded()

        val version = CiqualCatalogueAsset.availableVersion(context.assets)
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM food WHERE source <> 'ciqual' OR source_version <> ?", arrayOf(version))
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        assertTrue(seededIds().isNotEmpty())
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
