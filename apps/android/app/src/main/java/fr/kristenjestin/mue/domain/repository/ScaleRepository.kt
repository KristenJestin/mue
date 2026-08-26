package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.ScaleDevice
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Les balances appairées (PRD_SCALE 9.3, 21.1).
 *
 * Collection **purement locale** : elle n'est ni synchronisée, ni exposée par MCP (PRD_SCALE 22).
 * Une balance est un accessoire de ce téléphone, pas une donnée de santé ; son identifiant n'a
 * aucun sens ailleurs. Le précédent du dépôt est le catalogue d'aliments, donnée technique tenue
 * hors du journal de synchronisation.
 *
 * Aucune opération n'expose l'adresse comme identité : tout se fait par [ScaleDevice.id], parce
 * que l'adresse d'une balance peut changer au remplacement des piles (PRD_SCALE 10.1).
 */
interface ScaleRepository {

    /** Toutes les balances appairées, pour l'écran de gestion. */
    fun observeAll(): Flow<List<ScaleDevice>>

    /**
     * Instantané des balances appairées.
     *
     * Nécessaire en plus de [observeAll] : le scan doit confronter chaque annonce à la liste
     * enregistrée à cet instant précis, dans une coroutine, sans s'abonner à un flux.
     */
    suspend fun getAll(): List<ScaleDevice>

    suspend fun findById(id: String): ScaleDevice?

    /** Crée la balance, ou remplace celle qui porte déjà cet [ScaleDevice.id]. */
    suspend fun save(device: ScaleDevice)

    /**
     * Renomme sans rien toucher d'autre (PRD_SCALE 9.3).
     *
     * Distinct de [save] à dessein : renommer depuis l'interface pendant qu'un scan met à jour
     * l'adresse ne doit pas réécrire la ligne entière avec un état lu avant le scan, ce qui
     * annulerait silencieusement le contact le plus récent.
     */
    suspend fun rename(id: String, displayName: String)

    /**
     * Enregistre un contact réussi : adresse et nom annoncé du moment, plus
     * [ScaleDevice.lastSeenAt].
     *
     * C'est ici que se matérialise l'identité composite de FR-SCALE-001 — l'adresse et le nom
     * annoncé sont des **indices** rafraîchis à chaque rencontre, tandis que [ScaleDevice.id], le
     * nom donné par l'utilisateur et l'historique de mesures restent intacts.
     */
    suspend fun markSeen(id: String, address: String, advertisedName: String, at: Instant)

    /**
     * Oublie la balance.
     *
     * **Ne supprime aucune mesure** (BR-SCALE-010). Les poids qu'elle a produits conservent leur
     * provenance `scale` ; seul le lien `sourceScaleId` est mis à `null` par la contrainte
     * `ON DELETE SET NULL` du schéma (PRD_SCALE 21.1).
     */
    suspend fun forget(id: String)
}
