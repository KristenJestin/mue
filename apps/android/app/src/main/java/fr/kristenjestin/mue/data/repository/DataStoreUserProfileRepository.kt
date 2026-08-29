package fr.kristenjestin.mue.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.kristenjestin.mue.data.local.database.HealthProfileDao
import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The profile of PRD 11.2, now assembled from two stores.
 *
 * The display name stays in the Preferences file: sync PRD 10.1 keeps it on the phone, so it
 * has nothing to be transactional with. The height and the birth date moved to Room, because
 * sync PRD 19 requires a remote aggregate to be applied and its cursor advanced in one local
 * transaction and DataStore cannot join one — a `dataStore.edit` that succeeded beside a Room
 * write that rolled back would leave the phone claiming a height the server never accepted.
 *
 * [UserProfileRepository] is unchanged on purpose: every screen and every fake still sees one
 * profile, and none of them has to know it now comes from two files.
 *
 * Le sexe de PRD_SCALE FR-PROFILE-007 rejoint la moitié Room, et non le fichier de préférences que
 * la lettre de PRD_SCALE 21.1 désignait. Le motif est celui qui a déjà fait déménager la taille et
 * la date de naissance : PRD_SCALE 22 le synchronise dans l'agrégat `HealthProfile`, et une donnée
 * synchronisée doit pouvoir être appliquée dans la même transaction que son curseur.
 */
class DataStoreUserProfileRepository(
    private val dataStore: DataStore<Preferences>,
    private val healthProfileDao: HealthProfileDao,
    /**
     * Defaulted, exactly as on `RoomMeasurementRepository`, so every existing construction site
     * keeps compiling *and keeps journalling*. An opt-in journal would leave the shipped path
     * untested by the very tests that exercise this class.
     */
    private val outbox: SyncOutbox = SyncOutbox(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserProfileRepository {

    override val profile: Flow<UserProfile> = combine(
        dataStore.data
            // A corrupted or unreadable file must not crash the app; an empty profile is
            // the honest fallback, and every field is optional anyway.
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .map { MueValidation.normalizeDisplayName(it[KEY_DISPLAY_NAME]) },
        healthProfileDao.observe(),
    ) { displayName, health ->
        UserProfile(
            displayName = displayName,
            heightCm = health?.heightCm,
            birthDate = health?.birthDate?.toLocalDateOrNull(),
            // `Sex.fromWire` accepte le `null` de la colonne et renvoie `null` aussi bien pour
            // l'absence que pour une valeur illisible : dans les deux cas le profil est incomplet
            // au sens de FR-BODY-001, et le comportement attendu est le même — le poids
            // s'enregistre, la composition est simplement absente.
            sex = Sex.fromWire(health?.sex),
        )
    }.flowOn(ioDispatcher)

    /**
     * Two stores, two writes, and no transaction spanning them — which is exactly why the two
     * synchronised fields are the ones in Room. A display name written without its height is a
     * cosmetic loss on a crash; a height written without its cursor would be a lie to the
     * server.
     *
     * The Room half now goes through `upsertWithMutation`, so the health profile is journalled
     * in the transaction that writes it (FR-SYNC-001). Until it was, this method wrote the row
     * and nothing else: PRD 13.4 has called the profile a synchronised aggregate all along and
     * `SyncAggregateStateEntity.TYPE_HEALTH_PROFILE` already existed, but a height the user
     * typed left no trace that it had to be sent, so the guarantee held for measurements only.
     *
     * The display name is not journalled and must not be: sync PRD 10.1 keeps it on the phone.
     */
    override suspend fun save(profile: UserProfile) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences.put(
                    KEY_DISPLAY_NAME,
                    MueValidation.normalizeDisplayName(profile.displayName),
                )
            }
            healthProfileDao.upsertWithMutation(
                HealthProfileEntity(
                    heightCm = profile.heightCm,
                    birthDate = profile.birthDate?.toString(),
                    sex = profile.sex?.wireValue,
                ),
                outbox.healthProfileUpsert(
                    heightCm = profile.heightCm,
                    birthDate = profile.birthDate,
                    sex = profile.sex,
                ),
            )
        }
    }

    private fun String.toLocalDateOrNull(): LocalDate? =
        try {
            LocalDate.parse(this)
        } catch (_: DateTimeParseException) {
            null
        }

    companion object {
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")

        /**
         * The two keys version 4 wrote. Nothing reads them for the profile any more —
         * `HealthProfileSeeding` copies them into Room once — but they are the only record of
         * a height typed before the upgrade, so they are read by name rather than guessed.
         */
        val KEY_HEIGHT_CM = intPreferencesKey("height_cm")
        val KEY_BIRTH_DATE = stringPreferencesKey("birth_date")
    }
}

/** A null value clears the key so an absent field never lingers as a stale one. */
private fun <T : Any> androidx.datastore.preferences.core.MutablePreferences.put(
    key: Preferences.Key<T>,
    value: T?,
) {
    if (value == null) remove(key) else set(key, value)
}
