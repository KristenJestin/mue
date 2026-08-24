package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivitySource
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import kotlinx.coroutines.flow.Flow

/** A draft and its gear read together, so a reader can never see one without the other. */
data class TimedDraftWithEquipment(
    @Embedded val draft: TimedActivityDraftEntity,
    @Relation(parentColumn = "id", entityColumn = "draft_id")
    val equipment: List<TimedDraftEquipmentEntity>,
)

/** PRD 6.1: the axes of the last timed session, which is all `Start again` reopens. */
data class LastTimedSessionRow(
    @Embedded val session: ActivitySessionEntity,
    @Relation(parentColumn = "id", entityColumn = "session_id")
    val equipment: List<SessionEquipmentEntity>,
)

/**
 * The timer's own tables (PRD 8.1), plus the one read of the session table that only the timer
 * has a use for.
 *
 * [observeLastTimedSession] queries `activity_sessions` rather than living in [ActivityDao]:
 * `Start again` is a timer feature no activity screen asks for, and a DAO owns the queries of a
 * feature rather than the tables of one.
 */
@Dao
interface TimerDao {

    /**
     * The single live timer of FR-TIMER-001. `LIMIT 1` is belt and braces — [startTimerIfIdle] is
     * what makes there be at most one — and the ordering only decides which row answers on a file
     * some other tool corrupted.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM timed_activity_drafts
        WHERE status = :running OR status = :paused
        ORDER BY started_at_millis DESC, created_at DESC
        LIMIT 1
        """
    )
    fun observeLiveRow(running: String, paused: String): Flow<TimedDraftWithEquipment?>

    /** The status ids are bound rather than written into the SQL, so renaming one breaks loudly. */
    fun observeLiveRow(): Flow<TimedDraftWithEquipment?> =
        observeLiveRow(TimedDraftStatus.RUNNING.id, TimedDraftStatus.PAUSED.id)

    @Transaction
    @Query(
        """
        SELECT * FROM timed_activity_drafts
        WHERE status = :running OR status = :paused
        ORDER BY started_at_millis DESC, created_at DESC
        LIMIT 1
        """
    )
    suspend fun findLiveRow(running: String, paused: String): TimedDraftWithEquipment?

    suspend fun findLiveRow(): TimedDraftWithEquipment? =
        findLiveRow(TimedDraftStatus.RUNNING.id, TimedDraftStatus.PAUSED.id)

    /**
     * FR-TIMER-008, most recent first and with no limit: the dashboard shows three and rolls the
     * rest out in place.
     *
     * The key is the finish, not the start. A timer that ran across midnight started yesterday
     * and finished today, and only the instant it stopped says how recently the user was left
     * with something to review. SQLite sorts nulls last under `DESC`, so a row with no finish
     * instant — which no build of Mue writes — sinks rather than heading the list.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM timed_activity_drafts
        WHERE status = :pendingReview
        ORDER BY finished_at_millis DESC, created_at DESC
        """
    )
    fun observeReviewRows(pendingReview: String): Flow<List<TimedDraftWithEquipment>>

    fun observeReviewRows(): Flow<List<TimedDraftWithEquipment>> =
        observeReviewRows(TimedDraftStatus.PENDING_REVIEW.id)

    @Transaction
    @Query("SELECT * FROM timed_activity_drafts WHERE id = :draftId")
    suspend fun findRow(draftId: String): TimedDraftWithEquipment?

    /**
     * PRD 6.1. The ordering is the one the whole app sorts sessions by, so `Start again` reopens
     * the session the history shows at the top rather than a different one.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM activity_sessions
        WHERE source = :timerSource
        ORDER BY started_on DESC, started_at_time DESC, created_at DESC
        LIMIT 1
        """
    )
    fun observeLastTimedSession(timerSource: String): Flow<LastTimedSessionRow?>

    fun observeLastTimedSession(): Flow<LastTimedSessionRow?> =
        observeLastTimedSession(ActivitySource.TIMER.id)

    @Upsert
    suspend fun upsertDraft(draft: TimedActivityDraftEntity)

    @Insert
    suspend fun insertEquipment(equipment: List<TimedDraftEquipmentEntity>)

    /**
     * The columns a transition rewrites, and not one more (PRD 8.3).
     *
     * A pause has no business touching the movement, the start instant or the equipment, so the
     * transitions update rather than upsert: the axes of a running timer are settled at `Start`,
     * and only the review form may change them afterwards.
     */
    @Query(
        """
        UPDATE timed_activity_drafts SET
            status = :status,
            accumulated_active_seconds = :accumulatedActiveSeconds,
            current_segment_started_at_millis = :currentSegmentStartedAtMillis,
            current_segment_started_elapsed_realtime_millis = :segmentElapsedRealtimeMillis,
            boot_reference_millis = :bootReferenceMillis,
            finished_at_millis = :finishedAtMillis,
            updated_at = :updatedAt
        WHERE id = :draftId
        """
    )
    suspend fun updateState(
        draftId: String,
        status: String,
        accumulatedActiveSeconds: Int,
        currentSegmentStartedAtMillis: Long?,
        segmentElapsedRealtimeMillis: Long?,
        bootReferenceMillis: Long?,
        finishedAtMillis: Long?,
        updatedAt: Long,
    )

    /** PRD 8.2: the blob and its version move together, or a version would describe another blob. */
    @Query(
        """
        UPDATE timed_activity_drafts SET
            review_form_state = :state,
            review_form_schema_version = :schemaVersion,
            updated_at = :updatedAt
        WHERE id = :draftId
        """
    )
    suspend fun updateReviewFormState(
        draftId: String,
        state: String?,
        schemaVersion: Int,
        updatedAt: Long,
    )

    /** FR-TIMER-009: the equipment follows through SQLite's own cascade. */
    @Query("DELETE FROM timed_activity_drafts WHERE id = :draftId")
    suspend fun deleteDraft(draftId: String)

    /**
     * FR-TIMER-001 and 002, and the whole of the single-timer rule.
     *
     * It is a transaction rather than a partial unique index because Room's `TableInfo`
     * validation reads every index a file carries and fails `runMigrationsAndValidate` against
     * one it did not declare — and because FR-TIMER-002 wants a second attempt to open the timer
     * that is already running, which an index could only express by raising.
     *
     * Returns the timer that was already live, or null when this call is the one that wrote
     * [draft]. A null meaning success reads oddly for a moment, until you notice the caller
     * already holds the draft it asked to be written.
     */
    @Transaction
    suspend fun startTimerIfIdle(
        draft: TimedActivityDraftEntity,
        equipment: List<TimedDraftEquipmentEntity>,
        running: String,
        paused: String,
    ): TimedDraftWithEquipment? {
        val live = findLiveRow(running, paused)
        if (live != null) return live
        upsertDraft(draft)
        insertEquipment(equipment)
        return null
    }

    suspend fun startTimerIfIdle(
        draft: TimedActivityDraftEntity,
        equipment: List<TimedDraftEquipmentEntity>,
    ): TimedDraftWithEquipment? = startTimerIfIdle(
        draft,
        equipment,
        TimedDraftStatus.RUNNING.id,
        TimedDraftStatus.PAUSED.id,
    )
}

fun TimedDraftWithEquipment.toDomain(): TimedActivityDraft = draft.toDomain(equipment)

fun LastTimedSessionRow.toDomain(): StartTimerRequest = StartTimerRequest(
    movement = Movement.fromId(session.movement),
    customMovementName = session.customMovementName,
    environment = ActivityEnvironment.fromId(session.environment),
    equipment = equipment.sortedBy { it.position }.map { it.toDomain() },
)
