package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
interface ExerciseCatalogDao {

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
}
