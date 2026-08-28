package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.kristenjestin.mue.domain.logic.ActivityLabel
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.TrackingMode
import kotlinx.coroutines.flow.Flow

/**
 * What a session card needs and nothing else (PRD FR-ACTIVITY-002 and 012).
 *
 * `valid_set_count` is a plain `COUNT(*)`: the save path filters every set through
 * `StrengthRules`, so an invalid one never reaches the table and this query has no business
 * restating the tracking-mode rule of PRD 9.4.
 *
 * The label of PRD 11.1 needs the session's equipment, but only ever to ask whether a single
 * titling machine describes the whole session — so the row carries a count and the first type
 * instead of the list, and a card costs one statement no matter how much gear it names.
 */
data class ActivitySummaryRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "movement") val movement: String,
    @ColumnInfo(name = "custom_movement_name") val customMovementName: String?,
    @ColumnInfo(name = "environment") val environment: String,
    @ColumnInfo(name = "started_on") val startedOn: String,
    @ColumnInfo(name = "started_at_time") val startedAtTime: String?,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int,
    @ColumnInfo(name = "distance_metres") val distanceMetres: Int?,
    @ColumnInfo(name = "estimated_energy_kcal") val estimatedEnergyKcal: Int?,
    @ColumnInfo(name = "valid_set_count") val validSetCount: Int,
    @ColumnInfo(name = "equipment_count") val equipmentCount: Int,
    @ColumnInfo(name = "first_equipment_type") val firstEquipmentType: String?,
)

/** PRD 11.4: a set, the mode that says how to read it, and the day it was performed. */
data class LastPerformanceRow(
    @ColumnInfo(name = "performed_on") val performedOn: String,
    @ColumnInfo(name = "tracking_mode") val trackingMode: String,
    @Embedded val set: StrengthSetEntity,
)

/** The five reads that make up one session, taken together so they cannot disagree. */
data class ActivityDetailRows(
    val session: ActivitySessionEntity,
    val metrics: List<ActivityMetricEntity>,
    val equipment: List<SessionEquipmentEntity>,
    val exercises: List<StrengthExerciseWithDefinition>,
    val sets: List<StrengthSetEntity>,
)

@Dao
interface ActivityDao : SyncJournalDao {

    /**
     * One ordering for the whole app: most recent day first, timed sessions before untimed ones
     * on a shared day — SQLite sorts nulls last under `DESC`, which is the wanted answer — and
     * `created_at` as the final tiebreak, so two identical sessions never swap between reads.
     *
     * A null bound means "unbounded" and `-1` means "no limit", both being SQLite's own idioms
     * rather than sentinel values this layer has to invent.
     */
    @Query(
        """
        SELECT
            s.id AS id,
            s.movement AS movement,
            s.custom_movement_name AS custom_movement_name,
            s.environment AS environment,
            s.started_on AS started_on,
            s.started_at_time AS started_at_time,
            s.duration_seconds AS duration_seconds,
            (
                SELECT m.value FROM activity_metrics m
                WHERE m.session_id = s.id AND m.kind = :distanceKind
            ) AS distance_metres,
            (
                SELECT m.value FROM activity_metrics m
                WHERE m.session_id = s.id AND m.kind = :energyKind
            ) AS estimated_energy_kcal,
            (
                SELECT COUNT(*) FROM strength_sets t
                JOIN strength_exercises e ON e.id = t.strength_exercise_id
                WHERE e.session_id = s.id
            ) AS valid_set_count,
            (
                SELECT COUNT(*) FROM session_equipment q WHERE q.session_id = s.id
            ) AS equipment_count,
            (
                SELECT q.equipment_type FROM session_equipment q
                WHERE q.session_id = s.id
                ORDER BY q.position ASC
                LIMIT 1
            ) AS first_equipment_type
        FROM activity_sessions s
        WHERE (:start IS NULL OR s.started_on >= :start)
          AND (:end IS NULL OR s.started_on <= :end)
        ORDER BY s.started_on DESC, s.started_at_time DESC, s.created_at DESC
        LIMIT :limit
        """
    )
    fun observeSummaries(
        start: String?,
        end: String?,
        limit: Int,
        distanceKind: String,
        energyKind: String,
    ): Flow<List<ActivitySummaryRow>>

    /** The two metric ids are bound rather than written into the SQL, so renaming one breaks loudly. */
    fun observeSummaries(start: String?, end: String?, limit: Int): Flow<List<ActivitySummaryRow>> =
        observeSummaries(start, end, limit, MetricKind.DISTANCE.id, MetricKind.ESTIMATED_ENERGY.id)

    @Query("SELECT COUNT(*) FROM activity_sessions")
    fun observeSessionCount(): Flow<Int>

    @Query("SELECT * FROM activity_sessions WHERE id = :sessionId")
    suspend fun findSession(sessionId: String): ActivitySessionEntity?

    @Query("SELECT * FROM activity_metrics WHERE session_id = :sessionId")
    suspend fun findMetrics(sessionId: String): List<ActivityMetricEntity>

    @Query("SELECT * FROM session_equipment WHERE session_id = :sessionId ORDER BY position ASC")
    suspend fun findEquipment(sessionId: String): List<SessionEquipmentEntity>

    @Query(
        """
        SELECT
            e.*,
            d.id AS definition_id,
            d.name AS definition_name,
            d.name_folded AS definition_name_folded,
            d.tracking_mode AS definition_tracking_mode,
            d.equipment AS definition_equipment,
            d.is_custom AS definition_is_custom
        FROM strength_exercises e
        JOIN exercise_definitions d ON d.id = e.exercise_definition_id
        WHERE e.session_id = :sessionId
        ORDER BY e.position ASC
        """
    )
    suspend fun findExercises(sessionId: String): List<StrengthExerciseWithDefinition>

    @Query(
        """
        SELECT t.* FROM strength_sets t
        JOIN strength_exercises e ON e.id = t.strength_exercise_id
        WHERE e.session_id = :sessionId
        ORDER BY e.position ASC, t.position ASC
        """
    )
    suspend fun findSets(sessionId: String): List<StrengthSetEntity>

    /**
     * PRD 11.4, and the reason nothing here mentions a tracking mode: every stored set is valid,
     * so the last row of the most recent session is already the answer.
     */
    @Query(
        """
        SELECT
            s.started_on AS performed_on,
            d.tracking_mode AS tracking_mode,
            t.*
        FROM strength_sets t
        JOIN strength_exercises e ON e.id = t.strength_exercise_id
        JOIN activity_sessions s ON s.id = e.session_id
        JOIN exercise_definitions d ON d.id = e.exercise_definition_id
        WHERE e.exercise_definition_id = :definitionId
          AND (:excludedSessionId IS NULL OR s.id <> :excludedSessionId)
        ORDER BY
            s.started_on DESC,
            s.started_at_time DESC,
            s.created_at DESC,
            e.position DESC,
            t.position DESC
        LIMIT 1
        """
    )
    suspend fun findLastPerformance(
        definitionId: String,
        excludedSessionId: String?,
    ): LastPerformanceRow?

    @Transaction
    suspend fun findDetailRows(sessionId: String): ActivityDetailRows? {
        val session = findSession(sessionId) ?: return null
        return ActivityDetailRows(
            session = session,
            metrics = findMetrics(sessionId),
            equipment = findEquipment(sessionId),
            exercises = findExercises(sessionId),
            sets = findSets(sessionId),
        )
    }

    /**
     * The identifier a received exercise should point at, resolving a definition this phone may
     * not hold.
     *
     * `strength_exercises.exercise_definition_id` is a `RESTRICT` foreign key, so applying a
     * session whose definition has not arrived would abort the transaction that carries the
     * cursor — the phone would stop synchronising on a page it could never get past. The session
     * payload therefore carries a snapshot of each definition, and this is what turns it back into
     * a row.
     *
     * Three outcomes, in order, and the third is PRD_ACTIVITIES 9.2 rather than an invention:
     *
     * 1. the identifier is already known — use it;
     * 2. it is not, and the folded name is free — insert the snapshot under its own identifier;
     * 3. it is not, and another definition already holds the folded name — *"un nom déjà présent
     *    dans le catalogue […] réutilise la définition existante"*, so the exercise points at the
     *    incumbent. Nothing is renamed and nothing is deleted here: this is a session pointing at
     *    an exercise, not a definition arriving as an aggregate of its own.
     */
    @Transaction
    suspend fun resolveDefinition(snapshot: ExerciseDefinitionEntity): String {
        findDefinitionById(snapshot.id)?.let { return it }
        findDefinitionIdByFoldedName(snapshot.nameFolded)?.let { return it }
        insertDefinitionIfAbsent(snapshot)
        return findDefinitionById(snapshot.id)
            ?: findDefinitionIdByFoldedName(snapshot.nameFolded)
            ?: snapshot.id
    }

    @Query("SELECT id FROM exercise_definitions WHERE id = :id")
    suspend fun findDefinitionById(id: String): String?

    @Query("SELECT id FROM exercise_definitions WHERE name_folded = :nameFolded")
    suspend fun findDefinitionIdByFoldedName(nameFolded: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefinitionIfAbsent(definition: ExerciseDefinitionEntity)

    @Upsert
    suspend fun upsertSession(session: ActivitySessionEntity)

    @Insert
    suspend fun insertMetrics(metrics: List<ActivityMetricEntity>)

    @Insert
    suspend fun insertEquipment(equipment: List<SessionEquipmentEntity>)

    @Insert
    suspend fun insertExercises(exercises: List<StrengthExerciseEntity>)

    @Insert
    suspend fun insertSets(sets: List<StrengthSetEntity>)

    @Query("SELECT created_at FROM activity_sessions WHERE id = :sessionId")
    suspend fun findCreatedAt(sessionId: String): Long?

    @Query("DELETE FROM activity_metrics WHERE session_id = :sessionId")
    suspend fun deleteMetricsOf(sessionId: String)

    @Query("DELETE FROM session_equipment WHERE session_id = :sessionId")
    suspend fun deleteEquipmentOf(sessionId: String)

    @Query("DELETE FROM strength_exercises WHERE session_id = :sessionId")
    suspend fun deleteExercisesOf(sessionId: String)

    @Query("DELETE FROM activity_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    /**
     * PRD 16.1: a whole detailed session is written in one transaction or not at all. Saving an
     * edited session clears its children first, so a removed metric or a deleted set leaves
     * nothing behind; the sets go with their exercises through SQLite's own cascade.
     *
     * The original `created_at` is read back and kept: an edit updates a session, it does not
     * make it new.
     */
    @Transaction
    suspend fun saveDetail(
        session: ActivitySessionEntity,
        metrics: List<ActivityMetricEntity>,
        equipment: List<SessionEquipmentEntity>,
        exercises: List<StrengthExerciseEntity>,
        sets: List<StrengthSetEntity>,
    ) {
        val createdAt = findCreatedAt(session.id) ?: session.createdAt
        deleteMetricsOf(session.id)
        deleteEquipmentOf(session.id)
        deleteExercisesOf(session.id)
        upsertSession(session.copy(createdAt = createdAt))
        insertMetrics(metrics)
        insertEquipment(equipment)
        insertExercises(exercises)
        insertSets(sets)
    }

    /**
     * The same save, plus the outbox row, in **one** transaction (FR-SYNC-001).
     *
     * The five business tables and `sync_mutations` commit together or not at all, which is the
     * whole of "toute création, modification ou suppression depuis Android est enregistrée
     * localement en premier ; la même transaction ajoute une mutation dans la file d'envoi". A
     * session written without its mutation is a session that exists on this phone and nowhere
     * else — which is exactly what happened to every session ever recorded, because nothing
     * journalled one.
     *
     * There is one mutation for the whole aggregate, not five. PRD 10.2 makes the session atomic,
     * and five rows would be five chances for four of them to arrive.
     */
    @Transaction
    suspend fun saveDetailWithMutation(
        session: ActivitySessionEntity,
        metrics: List<ActivityMetricEntity>,
        equipment: List<SessionEquipmentEntity>,
        exercises: List<StrengthExerciseEntity>,
        sets: List<StrengthSetEntity>,
        mutation: SyncMutationEntity,
    ) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        saveDetail(session, metrics, equipment, exercises, sets)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateAlive(row.aggregateType, row.aggregateId, row.mutationId)
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }

    /**
     * The rows go through SQLite's own cascade; the tombstone stays (FR-SYNC-005).
     *
     * Without the tombstone a session deleted here and still held by the server would come back
     * on the next pull as an ordinary change and be silently re-created — the resurrection
     * FR-SYNC-005 exists to prevent.
     */
    @Transaction
    suspend fun deleteSessionWithMutation(sessionId: String, mutation: SyncMutationEntity) {
        val row = sequenced(mutation)
        val baseRevision = revisionOf(row.aggregateType, row.aggregateId)
        deleteSession(sessionId)
        insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        markAggregateDeleted(
            aggregateType = row.aggregateType,
            aggregateId = row.aggregateId,
            deletedAt = row.createdAt,
            mutationId = row.mutationId,
        )
        enqueueMutation(row.copy(baseRevision = baseRevision))
    }
}

fun ActivitySummaryRow.toDomain(): ActivitySummary = ActivitySummary(
    id = ActivityId(id),
    label = ActivityLabel.of(
        movement = Movement.fromId(movement),
        customMovementName = customMovementName,
        environment = ActivityEnvironment.fromId(environment),
        equipment = titlingEquipment(),
    ),
    movement = Movement.fromId(movement),
    startedOn = startedOn.toLocalDateColumn(),
    startedAtTime = startedAtTime.toLocalTimeColumn(),
    duration = ActivityDuration.ofSecondsOrNull(durationSeconds) ?: ActivityDuration.ZERO,
    distanceMetres = distanceMetres,
    validSetCount = validSetCount.takeIf { it > 0 },
    estimatedEnergyKcal = estimatedEnergyKcal,
)

/**
 * PRD 11.1 lets a machine title a session only when it is the session's single piece of gear, so
 * anything past the first one can only make the list non-single — which the count already says.
 */
private fun ActivitySummaryRow.titlingEquipment(): List<SessionEquipment> = when {
    equipmentCount != 1 || firstEquipmentType == null -> emptyList()
    else -> listOf(SessionEquipment(EquipmentType.fromId(firstEquipmentType)))
}

fun LastPerformanceRow.toDomain(): LastPerformance = LastPerformance(
    performedOn = performedOn.toLocalDateColumn(),
    trackingMode = TrackingMode.fromId(trackingMode),
    set = set.toDomain(),
)
