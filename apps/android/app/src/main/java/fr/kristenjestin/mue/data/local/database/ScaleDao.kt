package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Les balances appairées.
 *
 * **N'hérite pas de [SyncJournalDao], et c'est la décision que ce fichier existe pour consigner.**
 * PRD_SCALE 22 range les balances enregistrées dans la colonne « non synchronisé » de sa matrice :
 * associer la même balance à un second téléphone est un geste physique de quelques secondes, alors
 * que synchroniser une adresse Bluetooth propre à un appareil créerait des conflits sans bénéfice
 * — et PRD_SCALE 16.2 interdit de toute façon à cette adresse de quitter le téléphone. Aucune
 * écriture d'ici ne produit donc de ligne d'outbox ni de ligne dans `sync_aggregate_state`.
 *
 * [rename] et [markSeen] sont des `UPDATE` ciblés plutôt que des `upsert` d'une ligne entière lue
 * plus tôt : renommer une balance depuis l'écran de gestion pendant qu'un scan rafraîchit son
 * adresse ne doit pas réécrire l'adresse avec une valeur lue avant le scan, ce qui annulerait
 * silencieusement le contact le plus récent (FR-SCALE-001).
 */
@Dao
interface ScaleDao {

    /**
     * Ordre d'appairage, l'identifiant départageant les ex æquo.
     *
     * La liste de FR-SCALE-013 n'a pas de tri utile à proposer : elle compte une ou deux lignes.
     * L'ordre d'ajout est stable, ne bouge pas quand une balance est renommée ni quand elle est
     * revue, et c'est exactement ce qu'on attend d'une liste que l'on relit.
     */
    @Query("SELECT * FROM scale ORDER BY created_at ASC, id ASC")
    fun observeAll(): Flow<List<ScaleEntity>>

    @Query("SELECT * FROM scale ORDER BY created_at ASC, id ASC")
    suspend fun getAll(): List<ScaleEntity>

    @Query("SELECT * FROM scale WHERE id = :id")
    suspend fun findById(id: String): ScaleEntity?

    @Query("SELECT COUNT(*) FROM scale")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScaleEntity)

    @Query("UPDATE scale SET display_name = :displayName WHERE id = :id")
    suspend fun rename(id: String, displayName: String)

    /**
     * Un contact réussi : l'adresse et le nom annoncé du moment, plus l'instant.
     *
     * C'est ici que vit l'identité composite de FR-SCALE-001 — l'adresse et le nom annoncé sont
     * des indices rafraîchis à chaque rencontre, tandis que l'identifiant, le nom donné par
     * l'utilisateur et l'historique de mesures restent intacts.
     */
    @Query(
        "UPDATE scale SET address = :address, advertised_name = :advertisedName, " +
            "last_seen_at = :at WHERE id = :id"
    )
    suspend fun markSeen(id: String, address: String, advertisedName: String, at: Long)

    /**
     * Oublie la balance.
     *
     * **Ne supprime aucune mesure** (BR-SCALE-010). C'est `measurements.source_scale_id` en
     * `ON DELETE SET NULL` qui le garantit, pas cette requête : les poids produits par cette
     * balance gardent leur provenance `scale` et perdent seulement un identifiant qui ne désigne
     * plus rien.
     */
    @Query("DELETE FROM scale WHERE id = :id")
    suspend fun delete(id: String)
}
