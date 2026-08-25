package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.ActivityDao
import fr.kristenjestin.mue.data.local.database.ActivityDetailRows
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.domain.logic.StrengthRules
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivityMetrics
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The activity history on top of Room.
 *
 * The write path is the module's only gate: `StrengthRules` decides what is worth storing, so
 * the invariant "the database never holds an invalid set, nor an exercise with none" is enforced
 * here, once, and every query downstream may take a stored set at face value.
 */
class RoomActivityRepository(
    private val dao: ActivityDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val newRowId: () -> String = { UUID.randomUUID().toString() },
) : ActivityRepository {

    override fun observeRecentSummaries(limit: Int): Flow<List<ActivitySummary>> =
        summaries(null, null, limit)

    override fun observeAllSummaries(): Flow<List<ActivitySummary>> =
        summaries(null, null, NO_LIMIT)

    override fun observeSummariesIn(window: DateWindow): Flow<List<ActivitySummary>> =
        summaries(window.start?.toString(), window.endInclusive?.toString(), NO_LIMIT)

    override fun observeSessionCount(): Flow<Int> =
        dao.observeSessionCount().flowOn(ioDispatcher)

    override suspend fun findDetail(id: ActivityId): ActivitySessionDetail? =
        withContext(ioDispatcher) { dao.findDetailRows(id.value)?.toDomain() }

    override suspend fun save(detail: ActivitySessionDetail) = withContext(ioDispatcher) {
        val sessionId = detail.session.id.value
        val stamp = now()

        // Renumbered rather than merely filtered: dropping the third set of four must leave
        // three consecutive positions, not a hole where the reader would infer a missing row.
        val exercises = StrengthRules.persistableExercises(detail.exercises)
        val equipment = detail.equipment
            .distinctBy { it.equipmentType to it.customNameFolded }
            .mapIndexed { index, item -> item.copy(position = index) }

        dao.saveDetail(
            session = detail.session.toEntity(createdAt = stamp, updatedAt = stamp),
            metrics = detail.metrics.values.map { it.toEntity(sessionId) },
            equipment = equipment.map { it.toEntity(newRowId(), sessionId) },
            exercises = exercises.map { it.toEntity(sessionId) },
            sets = exercises.flatMap { exercise ->
                exercise.sets.map { it.toEntity(exercise.exercise.id.value) }
            },
        )
    }

    /** The metrics, equipment, exercises and sets follow through SQLite's own cascade. */
    override suspend fun delete(id: ActivityId) = withContext(ioDispatcher) {
        dao.deleteSession(id.value)
    }

    override suspend fun findLastPerformance(
        exercise: ExerciseDefinitionId,
        excludingSession: ActivityId?,
    ): LastPerformance? = withContext(ioDispatcher) {
        dao.findLastPerformance(exercise.value, excludingSession?.value)?.toDomain()
    }

    private fun summaries(start: String?, end: String?, limit: Int): Flow<List<ActivitySummary>> =
        dao.observeSummaries(start, end, limit)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    private companion object {
        /** SQLite's own way of saying "every row"; a sentinel count would have to be guessed. */
        const val NO_LIMIT = -1
    }
}

/** The sets arrive for the whole session in one read, so they are grouped once rather than rescanned per exercise. */
private fun ActivityDetailRows.toDomain(): ActivitySessionDetail {
    val setsByExercise = sets.groupBy { it.strengthExerciseId }
    return ActivitySessionDetail(
        session = session.toDomain(),
        metrics = ActivityMetrics.of(metrics.mapNotNull { it.toDomain() }),
        equipment = equipment.map { it.toDomain() },
        exercises = exercises.map { row ->
            StrengthExerciseDetail(
                exercise = row.exercise.toDomain(),
                definition = row.definition.toDomain(),
                sets = setsByExercise[row.exercise.id].orEmpty().map { it.toDomain() },
            )
        },
    )
}
