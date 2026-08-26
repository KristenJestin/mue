package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The two synchronised fields of the health profile (sync PRD 10.1), in Room rather than in the
 * Preferences file that has held them until now.
 *
 * They move because PRD 19 requires a remote aggregate to be applied *and* its cursor advanced
 * in one local transaction, and DataStore does not join a Room transaction — no amount of care
 * in the sync engine can make two stores commit together. `displayName` stays in DataStore: it
 * is not synchronised, so it has nothing to be atomic with.
 *
 * Both fields stay optional, exactly as `UserProfile` has them: the app is fully usable with an
 * empty profile. The birth date is ISO text like every other date here, so lexicographic order
 * is chronological order and no wire format has to be agreed separately.
 *
 * `id` is always [ROW_ID]. As in [SyncStateEntity], the single-row rule is a constant primary
 * key and not a `CHECK`, which Room cannot emit and which would therefore exist on a migrated
 * file and not on a fresh one.
 *
 * @property sex Le sexe du profil santé, facultatif (PRD_SCALE FR-PROFILE-007), stocké par sa
 *   forme de fil — `female` ou `male` — et décodé par `Sex.fromWire`.
 *
 *   **Ici et non dans DataStore, contrairement à la lettre de PRD_SCALE 21.1.** Le motif est
 *   exactement celui qui a déjà fait déménager [heightCm] et [birthDate] en version 5 :
 *   PRD_SCALE 22 fait du sexe un champ de l'agrégat `HealthProfile` synchronisé, et sync PRD 19
 *   exige qu'un agrégat distant soit appliqué *et son curseur avancé* dans une seule transaction
 *   locale. DataStore ne rejoint pas une transaction Room — un `dataStore.edit` réussi à côté
 *   d'une écriture Room annulée laisserait le téléphone affirmer un sexe que le serveur n'a
 *   jamais accepté. Le champ suit donc les deux autres champs synchronisés, et le nom d'affichage
 *   reste seul dans le fichier de préférences parce qu'il n'a rien à quoi être atomique.
 *
 *   Nullable de bout en bout : « non renseigné » est une absence et non une troisième valeur
 *   (voir `Sex`), et un profil sans sexe s'enregistre normalement — le poids est écrit, la
 *   composition est simplement absente (FR-BODY-001).
 */
@Entity(tableName = HealthProfileEntity.TABLE_NAME)
data class HealthProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = ROW_ID,

    @ColumnInfo(name = "height_cm")
    val heightCm: Int? = null,

    @ColumnInfo(name = "birth_date")
    val birthDate: String? = null,

    @ColumnInfo(name = "sex")
    val sex: String? = null,
) {
    companion object {
        const val TABLE_NAME = "health_profile"
        const val ROW_ID = "me"
    }
}
