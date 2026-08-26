package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Le poids d'un jour et ses enfants.
 *
 * Depuis le module balance, une mesure n'est plus une ligne mais un **petit agrégat** : poids,
 * provenance, impédance et composition facultative (PRD_SCALE 21.1). Toutes les lectures
 * renvoient donc [MeasurementWithComposition] et toutes les écritures passent par
 * [upsertAggregate], sur le modèle de `RecipeDao.saveDetailWithMutation` — un `@Transaction` qui
 * remplace les enfants en bloc plutôt que de les fusionner ligne à ligne.
 */
@Dao
interface MeasurementDao : SyncJournalDao {

    @Transaction
    @Query("SELECT * FROM measurements ORDER BY date ASC")
    fun observeAll(): Flow<List<MeasurementWithComposition>>

    /**
     * A null bound means "unbounded", which is how the `All` period is expressed
     * without inventing sentinel dates.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM measurements
        WHERE (:start IS NULL OR date >= :start)
          AND (:end IS NULL OR date <= :end)
        ORDER BY date ASC
        """
    )
    fun observeInWindow(start: String?, end: String?): Flow<List<MeasurementWithComposition>>

    @Transaction
    @Query("SELECT * FROM measurements ORDER BY date DESC LIMIT 1")
    fun observeLatest(): Flow<MeasurementWithComposition?>

    @Transaction
    @Query("SELECT * FROM measurements ORDER BY date ASC")
    suspend fun getAll(): List<MeasurementWithComposition>

    @Transaction
    @Query("SELECT * FROM measurements WHERE date = :date")
    suspend fun findByDate(date: String): MeasurementWithComposition?

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM body_composition")
    suspend fun compositionCount(): Int

    @Query("SELECT * FROM body_composition WHERE date = :date")
    suspend fun findComposition(date: String): BodyCompositionEntity?

    /** REPLACE is what makes PRD BR-002 silent: writing an existing date overwrites it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MeasurementEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComposition(entity: BodyCompositionEntity)

    @Query("DELETE FROM body_composition WHERE date = :date")
    suspend fun deleteCompositionOf(date: String)

    @Query("DELETE FROM measurements WHERE date = :date")
    suspend fun deleteByDate(date: String)

    /**
     * L'agrégat entier : le poids, sa provenance, son impédance et sa composition — ou l'absence
     * de composition, qui est une information et non un silence.
     *
     * **Le `deleteCompositionOf` sans condition est BR-SCALE-007** : « un payload complet sans
     * composition retire l'ancienne composition ». Une saisie manuelle qui remplace une pesée
     * reçue doit effacer l'estimation calculée à partir de l'ancien poids, sans quoi l'écran
     * afficherait une masse grasse dérivée d'une valeur que plus personne ne peut lire.
     * BR-SCALE-015 dit la même chose par l'autre bout : `inputWeightCg` est toujours égal au poids
     * de sa mesure parente, donc un poids qui change sans composition fournie ne peut pas garder
     * l'ancienne.
     *
     * L'ordre des trois écritures n'est pas indifférent. La composition est écrite **après** le
     * poids, parce que sa clé étrangère exige que le parent existe ; et le `DELETE` est placé
     * après l'`upsert` du parent parce qu'un `INSERT OR REPLACE` sur `measurements` supprime la
     * ligne existante, ce qui déclenche déjà la cascade `ON DELETE` de `body_composition`. Faire
     * confiance à cette cascade suffirait ici, mais elle est un effet de bord du mode de
     * résolution de conflit choisi par Room, pas une règle du domaine — l'effacement explicite
     * survit à un `REPLACE` qui deviendrait un `UPDATE`.
     */
    @Transaction
    suspend fun upsertAggregate(entity: MeasurementEntity, composition: BodyCompositionEntity?) {
        upsert(entity)
        deleteCompositionOf(entity.date)
        if (composition != null) upsertComposition(composition)
    }

    /**
     * Edits a measurement whose date may have moved (PRD FR-PROGRESS-005). Removing
     * the old row and writing the new one must not be observable as two steps
     * (PRD 16.3).
     *
     * La composition de l'ancienne date part avec elle par cascade ; celle de la nouvelle est
     * remplacée par [upsertAggregate]. Les deux moitiés de BR-SCALE-007 sont donc tenues sans
     * qu'aucune ligne enfant ne soit nommée ici.
     */
    @Transaction
    suspend fun replace(
        originalDate: String,
        entity: MeasurementEntity,
        composition: BodyCompositionEntity?,
    ) {
        if (originalDate != entity.date) {
            deleteByDate(originalDate)
        }
        upsertAggregate(entity, composition)
    }

    /**
     * The three writes below are the same three above with the outbox row added — and the
     * addition is the whole point of them existing separately.
     *
     * Sync FR-SYNC-001 says *the same transaction* enqueues the mutation. Calling `upsert` and
     * then a journal method would be two transactions, and a process death between them keeps
     * the measurement while losing every trace that it has to be sent: the change would then
     * exist on the phone forever and never on the server, with nothing to detect it.
     *
     * Depuis PRD_SCALE 21.1, cette transaction couvre l'agrégat entier : « créer ou remplacer un
     * poids écrit le `Measurement` complet dans une seule transaction ». Poids, provenance,
     * impédance, composition et ligne d'outbox commettent ensemble ou pas du tout.
     *
     * The base revision is read here rather than passed in, so it is read under the same lock
     * that writes the row; a revision fetched before the transaction could already be stale.
     *
     * [SyncJournalDao.sequenced] is applied for the same reason and in the same place: the send
     * order is the outbox's local sequence, and a stamp taken outside this transaction could be
     * overtaken by a concurrent writer or undercut by a clock that stepped backwards.
     */
    @Transaction
    suspend fun upsertWithMutation(
        entity: MeasurementEntity,
        composition: BodyCompositionEntity?,
        mutation: SyncMutationEntity,
    ) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        upsertAggregate(entity, composition)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateAlive(row.aggregateType, row.aggregateId, row.mutationId)
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }

    /**
     * The row goes, the tombstone stays (FR-SYNC-005). Without it, a copy of the same date
     * still sitting in another client's outbox would come back on the next pull and the
     * deletion would silently undo itself.
     *
     * La composition suit le poids par la cascade du schéma, dans cette même transaction
     * (BR-SCALE-007). Elle n'a pas de pierre tombale : ce n'est pas un agrégat synchronisé
     * indépendant mais un enfant du payload de la mesure (PRD_SCALE 22).
     */
    @Transaction
    suspend fun deleteWithMutation(date: String, mutation: SyncMutationEntity) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        deleteByDate(date)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateDeleted(
            aggregateType = row.aggregateType,
            aggregateId = row.aggregateId,
            // The tombstone's instant is the stamp the mutation actually went out with, so the
            // local record and the mutation that will create the remote one cannot disagree.
            deletedAt = row.createdAt,
            mutationId = row.mutationId,
        )
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }

    /**
     * An edit that moves a measurement to another date is two aggregates changing, because the
     * date *is* the aggregate id: the old one is deleted and the new one written. One mutation
     * carrying both would be unapplicable on a server that stores measurements by date, so
     * [deleteMutation] is spent exactly when the shipped [replace] deletes the old row.
     *
     * La composition n'ajoute pas de troisième agrégat : celle de l'ancienne date disparaît avec
     * elle par cascade, celle de la nouvelle voyage dans le payload de l'upsert.
     */
    @Transaction
    suspend fun replaceWithMutation(
        originalDate: String,
        entity: MeasurementEntity,
        composition: BodyCompositionEntity?,
        deleteMutation: SyncMutationEntity,
        upsertMutation: SyncMutationEntity,
    ) {
        if (originalDate != entity.date) {
            deleteWithMutation(originalDate, deleteMutation)
        }
        upsertWithMutation(entity, composition, upsertMutation)
    }
}
