package fr.kristenjestin.mue.data.local.database

import android.content.res.AssetManager
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one run of [CiqualSeeding] did, so a caller and a test can tell the four cases apart. */
sealed interface CiqualSeedOutcome {
    /** No catalogue is shipped in this APK. Nothing is installed and nothing is recorded. */
    data object NoAsset : CiqualSeedOutcome

    /** The shipped version is already installed. The database was not opened. */
    data class AlreadyInstalled(val version: String) : CiqualSeedOutcome

    data class Installed(val version: String, val foods: Int) : CiqualSeedOutcome

    /** The asset is there but unreadable or unparsable; the recorded version does not move. */
    data class Unreadable(val version: String) : CiqualSeedOutcome
}

/**
 * Installs the embedded Ciqual subset on the first start that ships a new one (PRD_FOOD 9.1,
 * 20.2), and does nothing at all on every start after it.
 *
 * **The guard opens no database.** It compares a version discovered from an asset *file name*
 * with a version held in a DataStore preference — a directory listing against one small
 * preference read. This is the finding the sync chunk left behind: `HealthProfileSeeding` guards
 * itself on `sync_state.profile_seeded`, so it builds a Room connection at every cold start to
 * read one boolean it has read a thousand times. The catalogue is far larger and the same
 * mistake would be far more expensive, so the flag lives where reading it is free.
 *
 * It is a startup task rather than a `Migration` or a `Callback.onCreate`, and for a reason that
 * mirrors the health profile's: an `AssetManager` cannot be reached from a
 * `SupportSQLiteDatabase`. It also has to run on both populations — a fresh install and a phone
 * upgrading from version 5 — and `onCreate` fires only for the first while `MIGRATION_5_6` fires
 * only for the second. One task, guarded by a version rather than by a lifecycle, covers both,
 * and covers the third case neither of them can: a new subset shipped by a later release of the
 * app, on a database that needs no migration at all.
 *
 * Seeding never touches a custom food or a journal line (20.2). It only writes rows the asset
 * names, all of them `FoodSource.CIQUAL`, and the repository drops anything else it is handed.
 */
class CiqualSeeding(
    private val assets: AssetManager,
    private val repository: FoodCatalogueRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun seedIfNeeded(): CiqualSeedOutcome = withContext(ioDispatcher) {
        val available = CiqualCatalogueAsset.availableVersion(assets)
            ?: return@withContext CiqualSeedOutcome.NoAsset

        if (repository.installedCiqualVersion() == available) {
            return@withContext CiqualSeedOutcome.AlreadyInstalled(available)
        }

        val raw = CiqualCatalogueAsset.readOrNull(assets, available)
            ?: return@withContext CiqualSeedOutcome.Unreadable(available)

        val foods = CiqualCatalogueAsset.foodsOf(raw)
        if (foods.isEmpty()) return@withContext CiqualSeedOutcome.Unreadable(available)

        repository.seedCiqual(foods, available)
        CiqualSeedOutcome.Installed(available, foods.size)
    }
}
