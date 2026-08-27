package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.ActivityDao
import fr.kristenjestin.mue.data.local.database.ActivityDetailRows
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
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
 *
 * ## The outbox, and what its absence cost
 *
 * PRD 10.1 marks finished sessions `Synchronisé: Oui`, and this class took no [SyncOutbox] and
 * minted nothing. Not "journalled and undeliverable", as the four food aggregates were —
 * *nothing*: every session ever recorded, with its metrics, its equipment, its exercises and its
 * sets, existed on one phone and in no outbox row, and an uninstall took the lot. A food row at
 * least survives a reinstall through the Ciqual catalogue and can be typed again; a session
 * cannot.
 *
 * The row is minted here and written by [ActivityDao.saveDetailWithMutation], in the same
 * transaction as the five business tables, which is FR-SYNC-001 for the aggregate rather than
 * for its pieces. One mutation carries the whole session, because PRD 10.2 forbids a session
 * appearing without its mandatory children and five rows would be five chances for four of them
 * to arrive.
 *
 * The outbox is defaulted, as it is on every other repository here, so a test that does not care
 * about synchronisation constructs one without arranging for it — and still journals, which is
 * what keeps a test honest about what a save really does.
 */
class RoomActivityRepository(
    private val dao: ActivityDao,
    private val outbox: SyncOutbox = SyncOutbox(),
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

        // The payload is built from what is *about* to be written, not from `detail`: the sets
        // `StrengthRules` dropped and the equipment the fold deduplicated are not in the database,
        // so a payload built from the argument would send an aggregate this phone does not hold.
        val normalised = detail.copy(exercises = exercises, equipment = equipment)

        dao.saveDetailWithMutation(
            session = detail.session.toEntity(createdAt = stamp, updatedAt = stamp),
            metrics = detail.metrics.values.map { it.toEntity(sessionId) },
            equipment = equipment.map { it.toEntity(newRowId(), sessionId) },
            exercises = exercises.map { it.toEntity(sessionId) },
            sets = exercises.flatMap { exercise ->
                exercise.sets.map { it.toEntity(exercise.exercise.id.value) }
            },
            mutation = outbox.activitySessionUpsert(normalised),
        )
    }

    /**
     * The metrics, equipment, exercises and sets follow through SQLite's own cascade; the
     * tombstone stays behind (FR-SYNC-005).
     */
    override suspend fun delete(id: ActivityId) = withContext(ioDispatcher) {
        dao.deleteSessionWithMutation(id.value, outbox.activitySessionDelete(id))
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
