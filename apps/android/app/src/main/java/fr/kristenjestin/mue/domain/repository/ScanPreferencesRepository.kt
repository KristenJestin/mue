package fr.kristenjestin.mue.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The one flag the barcode scanner stores (PRD_FOOD 17 and 18).
 *
 * It sits apart from [UserPreferencesRepository] for [TimerPreferencesRepository]'s reason, and
 * apart from *that* one because the two modules ask about two different permissions: no screen
 * shows either, nothing lets either be changed, and widening a shipped preferences value would
 * touch tested files to carry a flag none of them cares about.
 */
interface ScanPreferencesRepository {

    /**
     * Whether `CAMERA` has already been asked for, so a refusal is never asked again
     * automatically (PRD_FOOD 17: "le scan est désactivé avec explication").
     *
     * A persisted boolean is the only correct implementation, and the reason is the same one
     * `TimerPreferencesRepository` records: `shouldShowRequestPermissionRationale` answers
     * `false` **both** before the first request and after a permanent denial, so the platform
     * cannot tell apart the two states an "ask once" rule exists to distinguish. Emits `false`
     * until something has been saved.
     */
    val cameraPermissionRequested: Flow<Boolean>

    suspend fun setCameraPermissionRequested(requested: Boolean)
}
