package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.ScaleDevice
import java.time.Instant

/**
 * Une balance appairée (PRD_SCALE 9.3, 21.1).
 *
 * **Table purement locale.** PRD_SCALE 22 range les balances enregistrées dans la colonne « non
 * synchronisé » de sa matrice : elles sont spécifiques à l'appareil, comme les préférences. Elle
 * ne porte donc aucune métadonnée de synchronisation, [ScaleDao] n'hérite pas de [SyncJournalDao]
 * et aucune écriture d'ici ne produit de ligne d'outbox. Le précédent du dépôt est le catalogue
 * d'aliments, donnée technique tenue hors du journal ; la règle générale du dépôt — aucune colonne
 * de synchronisation dans une table métier, `sync_aggregate_state` porte cette métadonnée — dit la
 * même chose par l'autre bout.
 *
 * **[id] n'est pas dérivé de [address].** L'adresse de la balance de référence est une adresse
 * statique aléatoire (PRD_SCALE 10.1) : elle peut être régénérée au changement de piles. Une
 * balance identifiée par son adresse disparaîtrait ce jour-là avec tout son historique. [id] est
 * un UUID tiré par Mue à l'appairage ; [address] et [advertisedName] sont des **indices**
 * rafraîchis à chaque contact, ce qui est exactement ce que le rattachement proposé de
 * FR-SCALE-001 manipule.
 *
 * L'index sur [address] sert le scan : chaque annonce reçue est confrontée aux balances déjà
 * enregistrées, plusieurs fois par seconde pendant deux minutes (FR-SCALE-021).
 *
 * Les instants sont des millisecondes depuis l'époque, comme partout ailleurs dans ce fichier de
 * base (`activity_sessions.created_at`, `sync_mutations.created_at`) : un entier, jamais un texte,
 * et aucun `@TypeConverter` — la conversion vit dans les fonctions d'extension en fin de fichier.
 */
@Entity(
    tableName = ScaleEntity.TABLE_NAME,
    indices = [Index(value = ["address"])],
)
data class ScaleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "driver_id")
    val driverId: String,

    @ColumnInfo(name = "address")
    val address: String,

    @ColumnInfo(name = "advertised_name")
    val advertisedName: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    /** `null` tant qu'aucun contact n'a eu lieu depuis l'appairage. */
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    companion object {
        const val TABLE_NAME = "scale"
    }
}

internal fun ScaleEntity.toDomain(): ScaleDevice = ScaleDevice(
    id = id,
    driverId = driverId,
    address = address,
    advertisedName = advertisedName,
    displayName = displayName,
    lastSeenAt = lastSeenAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
)

internal fun ScaleDevice.toEntity(): ScaleEntity = ScaleEntity(
    id = id,
    driverId = driverId,
    address = address,
    advertisedName = advertisedName,
    displayName = displayName,
    lastSeenAt = lastSeenAt?.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
)
