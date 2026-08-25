package fr.kristenjestin.mue.data.repository

import androidx.room.withTransaction
import fr.kristenjestin.mue.data.local.database.ActivityDao
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.TimerDao
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toDraftEntity
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.domain.logic.StrengthRules
import fr.kristenjestin.mue.domain.logic.finishedAt
import fr.kristenjestin.mue.domain.logic.pausedAt
import fr.kristenjestin.mue.domain.logic.resumedAt
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StartTimerOutcome
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.domain.model.startedAt
import fr.kristenjestin.mue.domain.repository.TimedActivityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/**
 * The timer on top of Room, and the single source of truth PRD 9 asks for.
 *
 * Unlike the three shipped repositories, which take a DAO and nothing else, this one also takes
 * the database: FR-TIMER-007 has to create a session through [ActivityDao] and delete a draft
 * through [TimerDao] atomically, and a `@Transaction` DAO method cannot span two DAOs. Only
 * `RoomDatabase.withTransaction` can, so the database is a constructor argument rather than a
 * hidden lookup.
 *
 * Nothing here re-expresses the elapsed arithmetic. `pausedAt`, `resumedAt` and `finishedAt` of
 * `domain/logic/TimerElapsed.kt` compute what a transition writes back with the very rule that
 * displays it, so a paused timer can never be worth one thing on screen and another at rest.
 */
class RoomTimedActivityRepository(
    private val database: MueDatabase,
    private val timerDao: TimerDao,
    private val activityDao: ActivityDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val newRowId: () -> String = { UUID.randomUUID().toString() },
) : TimedActivityRepository {

    override fun observeLiveDraft(): Flow<TimedActivityDraft?> =
        timerDao.observeLiveRow()
            .map { row -> row?.toDomain() }
            .flowOn(ioDispatcher)

    override suspend fun findLiveDraft(): TimedActivityDraft? = withContext(ioDispatcher) {
        timerDao.findLiveRow()?.toDomain()
    }

    override fun observeDraftsToReview(): Flow<List<TimedActivityDraft>> =
        timerDao.observeReviewRows()
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override suspend fun findDraft(id: TimedDraftId): TimedActivityDraft? =
        withContext(ioDispatcher) { timerDao.findRow(id.value)?.toDomain() }

    override fun observeLastTimedStart(): Flow<StartTimerRequest?> =
        timerDao.observeLastTimedSession()
            .map { row -> row?.toDomain() }
            .flowOn(ioDispatcher)

    /**
     * FR-TIMER-001 and 002. The draft is built before the write so that a start and a refusal
     * cost the same single transaction, and the equipment is normalised first: the returned
     * [StartTimerOutcome.Started] has to carry exactly the rows that were stored, or the screen
     * would show gear the database does not have.
     */
    override suspend fun start(
        request: StartTimerRequest,
        now: TimerInstant,
        zone: ZoneId,
    ): StartTimerOutcome = withContext(ioDispatcher) {
        val draft = request
            .copy(equipment = request.equipment.normalised())
            .startedAt(TimedDraftId(newRowId()), now, zone)
        val stamp = this@RoomTimedActivityRepository.now()

        val live = timerDao.startTimerIfIdle(
            draft = draft.toEntity(createdAt = stamp, updatedAt = stamp),
            equipment = draft.equipment.map { it.toDraftEntity(newRowId(), draft.id.value) },
        )

        live?.let { StartTimerOutcome.AlreadyLive(it.toDomain()) } ?: StartTimerOutcome.Started(draft)
    }

    override suspend fun pause(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? =
        transition(id) { it.pausedAt(now) }

    override suspend fun resume(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? =
        transition(id) { it.resumedAt(now) }

    override suspend fun finish(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? =
        transition(id) { it.finishedAt(now) }

    /** The equipment follows through SQLite's own cascade; a missing draft is not an error. */
    override suspend fun discard(id: TimedDraftId) = withContext(ioDispatcher) {
        timerDao.deleteDraft(id.value)
    }

    override suspend fun saveReviewFormState(id: TimedDraftId, state: String?, schemaVersion: Int) {
        withContext(ioDispatcher) {
            timerDao.updateReviewFormState(id.value, state, schemaVersion, now())
        }
    }

    /**
     * FR-TIMER-007: one session created, one draft deleted, or neither.
     *
     * The session is written through [ActivityDao] rather than through [RoomActivityRepository],
     * because that repository moves to its own dispatcher and `withTransaction` confines the
     * block to the one it opened — leaving it would take the work outside the transaction it was
     * meant to be inside. The normalisation the save path owes the unique index is therefore
     * repeated here, and only that.
     */
    override suspend fun commitToSession(id: TimedDraftId, detail: ActivitySessionDetail) {
        withContext(ioDispatcher) {
            database.withTransaction {
                val sessionId = detail.session.id.value
                val stamp = now()
                val exercises = StrengthRules.persistableExercises(detail.exercises)

                activityDao.saveDetail(
                    session = detail.session.toEntity(createdAt = stamp, updatedAt = stamp),
                    metrics = detail.metrics.values.map { it.toEntity(sessionId) },
                    equipment = detail.equipment.normalised()
                        .map { it.toEntity(newRowId(), sessionId) },
                    exercises = exercises.map { it.toEntity(sessionId) },
                    sets = exercises.flatMap { exercise ->
                        exercise.sets.map { it.toEntity(exercise.exercise.id.value) }
                    },
                )
                timerDao.deleteDraft(id.value)
            }
        }
    }

    /**
     * PRD 12, a button pressed twice: a transition that changes nothing writes nothing and
     * returns the draft it read. The domain decides that — every transition of `TimerElapsed.kt`
     * returns the receiver untouched when the status is already the target — so idempotence is
     * one rule in one place rather than a status test repeated four times here.
     */
    private suspend fun transition(
        id: TimedDraftId,
        transform: (TimedActivityDraft) -> TimedActivityDraft,
    ): TimedActivityDraft? = withContext(ioDispatcher) {
        val current = timerDao.findRow(id.value)?.toDomain() ?: return@withContext null
        val next = transform(current)
        if (next == current) return@withContext current

        timerDao.updateState(
            draftId = next.id.value,
            status = next.status.id,
            accumulatedActiveSeconds = next.accumulatedActive.seconds,
            currentSegmentStartedAtMillis = next.currentSegmentStartedAtMillis,
            segmentElapsedRealtimeMillis = next.currentSegmentStartedElapsedRealtimeMillis,
            bootReferenceMillis = next.bootReferenceMillis,
            finishedAtMillis = next.finishedAtMillis,
            updatedAt = now(),
        )
        next
    }
}

/**
 * What the folded-name unique index requires, and the renumbering it does not: dropping the
 * second of three pieces of gear must leave two consecutive positions rather than a hole a
 * reader would take for a missing row.
 */
private fun List<SessionEquipment>.normalised(): List<SessionEquipment> =
    distinctBy { it.equipmentType to it.customNameFolded }
        .mapIndexed { index, item -> item.copy(position = index) }
