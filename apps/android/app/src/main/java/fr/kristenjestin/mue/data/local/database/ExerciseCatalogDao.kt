package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A definition with the day it was last performed, which is all the ordering of
 * PRD FR-ACTIVITY-009's `Recent & common` needs.
 */
data class CataloguedExercise(
    @Embedded val definition: ExerciseDefinitionEntity,
    @ColumnInfo(name = "last_used_on") val lastUsedOn: String?,
)

@Dao
interface ExerciseCatalogDao : SyncJournalDao {

    /**
     * Recently used first, then the rest of the catalogue by name.
     *
     * The last-used day is grouped once in a subquery rather than correlated per definition, so
     * a catalogue of two hundred exercises still costs two scans. An unused definition has no
     * day at all, and `COALESCE` to the empty string sorts it below every real date under `DESC`
     * without a second ordering term.
     */
    @Query(
        """
        SELECT d.*, u.last_used_on AS last_used_on
        FROM exercise_definitions d
        LEFT JOIN (
            SELECT e.exercise_definition_id AS definition_id, MAX(s.started_on) AS last_used_on
            FROM strength_exercises e
            JOIN activity_sessions s ON s.id = e.session_id
            GROUP BY e.exercise_definition_id
        ) u ON u.definition_id = d.id
        ORDER BY COALESCE(u.last_used_on, '') DESC, d.name COLLATE NOCASE ASC
        """
    )
    fun observeCatalogue(): Flow<List<CataloguedExercise>>

    @Query("SELECT * FROM exercise_definitions WHERE id = :id")
    suspend fun findById(id: String): ExerciseDefinitionEntity?

    @Query("SELECT * FROM exercise_definitions WHERE name_folded = :nameFolded")
    suspend fun findByFoldedName(nameFolded: String): ExerciseDefinitionEntity?

    @Query("SELECT COUNT(*) FROM exercise_definitions")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(definition: ExerciseDefinitionEntity)

    @Upsert
    suspend fun upsertDefinition(definition: ExerciseDefinitionEntity)

    /** Frees a folded name without touching the row that holds it. See [applyRemote]. */
    @Query("UPDATE exercise_definitions SET name_folded = :nameFolded WHERE id = :id")
    suspend fun renameFolded(id: String, nameFolded: String)

    /**
     * A definition that arrived from the server, applied and journalling nothing.
     *
     * The name is freed first, and that is not a nicety. `exercise_definitions` is unique on
     * `name_folded`, so a definition another device created under a different identifier — which
     * PRD_ACTIVITIES 9.2 says is *the same exercise* — would make this insert violate the index.
     * `@Upsert` would then try to update by primary key and violate it again, and the exception
     * would roll back the transaction that carries the cursor: the phone would stop synchronising
     * on a page it could never get past.
     *
     * The incumbent yields its folded name and keeps everything else — its row, its identifier and
     * every `strength_exercises` row pointing at it, which a `RESTRICT` foreign key would not have
     * let go anyway. Nothing is deleted, which is what PRD 13.1 requires of any resolution, and it
     * mirrors what `packages/domain` does on the server for the same collision.
     */
    @Transaction
    suspend fun applyRemote(definition: ExerciseDefinitionEntity) {
        val holder = findByFoldedName(definition.nameFolded)
        if (holder != null && holder.id != definition.id) {
            renameFolded(holder.id, "${holder.nameFolded}#${holder.id}")
        }
        upsertDefinition(definition)
    }

    /**
     * PRD 9.2: a name already in the catalogue reuses its definition instead of adding a second
     * one, whatever its case or padding — so [candidate] describes an exercise that may well not
     * be created, and its tracking mode and equipment only apply when it really is new.
     *
     * The lookup and the insert share a transaction, and the insert ignores the unique index
     * rather than aborting, so the re-read below always returns the row that won.
     */
    @Transaction
    suspend fun findOrCreate(candidate: ExerciseDefinitionEntity): ExerciseDefinitionEntity {
        findByFoldedName(candidate.nameFolded)?.let { return it }
        insert(candidate)
        return findByFoldedName(candidate.nameFolded) ?: candidate
    }

    /**
     * The same lookup, and an outbox row **only when a definition is really created**
     * (FR-SYNC-001).
     *
     * The condition is the whole point. PRD 9.2 reuses an existing definition for a name already
     * in the catalogue, so most calls create nothing: journalling every call would mint a mutation
     * per exercise the user picks from the list, and the server would receive a stream of
     * identical upserts of definitions it already holds. Worse, reusing one of the seventeen Mue
     * ships would push it as personal data, which PRD 10.1 marks `Synchronisé: Non`.
     *
     * So [mutationFor] is a *function of the row that was inserted*, evaluated inside the
     * transaction and only on the branch that inserted one. `SyncOutbox.customExerciseUpsert` mints
     * a row and announces a send as a side effect, so calling it speculatively would schedule a
     * synchronisation for a change that was never made.
     */
    @Transaction
    suspend fun findOrCreateWithMutation(
        candidate: ExerciseDefinitionEntity,
        mutationFor: (ExerciseDefinitionEntity) -> SyncMutationEntity,
    ): ExerciseDefinitionEntity {
        findByFoldedName(candidate.nameFolded)?.let { return it }
        insert(candidate)
        // The row that won the unique index, which on the losing side of a race is not the
        // candidate. Journalling the candidate would send a definition this database does not
        // hold, under an identifier nothing here references.
        val stored = findByFoldedName(candidate.nameFolded) ?: candidate
        if (stored.id != candidate.id) return stored

        val row = sequenced(mutationFor(stored))
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateAlive(row.aggregateType, row.aggregateId, row.mutationId)
        enqueueMutation(row.copy(baseRevision = baseRevision))
        return stored
    }
}
