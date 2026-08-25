package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.StartTimerOutcome
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimerInstant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * The one live timer and every draft waiting to be reviewed (PRD 8.1).
 *
 * Reads are flows so no screen ever blocks the main thread, and writes are suspending and
 * atomic, exactly as in [ActivityRepository]. Every write also rewrites the boot reference of
 * FR-TIMER-003, which is why each of them takes the [TimerInstant] rather than reading a clock
 * of its own.
 *
 * **Every transition is idempotent** (PRD 12, a button pressed twice): asking for a status a
 * draft already has returns it unchanged and writes nothing.
 */
interface TimedActivityRepository {

    /**
     * The single `running` or `paused` draft, or null when no timer exists (FR-TIMER-001).
     *
     * This one flow feeds the timer screen, the chassis banner and the notification; PRD 9 asks
     * for exactly one shared observable rather than three readers of the same row.
     */
    fun observeLiveDraft(): Flow<TimedActivityDraft?>

    /**
     * The same row read once. What the boot receiver and the notification refresh of PRD 6.5
     * use: neither is a screen, and neither should hold a collector open.
     */
    suspend fun findLiveDraft(): TimedActivityDraft?

    /**
     * The `pending_review` drafts of FR-TIMER-008, most recent first and with no limit: the
     * dashboard shows three and rolls the rest out in place. Nothing here ever expires — Mue
     * never destroys a measured duration without an explicit action.
     */
    fun observeDraftsToReview(): Flow<List<TimedActivityDraft>>

    suspend fun findDraft(id: TimedDraftId): TimedActivityDraft?

    /**
     * PRD 6.1: the axes of the most recent session whose source is `timer`, which is what the
     * `Start again` shortcut reopens the start screen with. Null until one has been saved.
     */
    fun observeLastTimedStart(): Flow<StartTimerRequest?>

    /**
     * FR-TIMER-001: writes the draft before the chronometer is ever drawn.
     *
     * FR-TIMER-002 lives here too. A second timer is never created and never raises: the call
     * answers [StartTimerOutcome.AlreadyLive] carrying the timer that is already running, which
     * the caller opens while announcing the notice. Refusing is a product behaviour.
     *
     * [zone] resolves the calendar date and local start time once, here, and they are then
     * frozen for the life of the draft (FR-TIMER-005).
     */
    suspend fun start(
        request: StartTimerRequest,
        now: TimerInstant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StartTimerOutcome

    /**
     * FR-TIMER-004: closes the open segment into the accumulated total and clears it in the same
     * transaction. Returns null when no such draft exists, and the draft unchanged when it is
     * not running.
     */
    suspend fun pause(id: TimedDraftId, now: TimerInstant): TimedActivityDraft?

    /** FR-TIMER-004: opens a new segment without touching the original start time. */
    suspend fun resume(id: TimedDraftId, now: TimerInstant): TimedActivityDraft?

    /** FR-TIMER-005: closes the last segment for good and moves the draft to `pending_review`. */
    suspend fun finish(id: TimedDraftId, now: TimerInstant): TimedActivityDraft?

    /** FR-TIMER-009: the draft and its equipment go; a missing draft is not an error. */
    suspend fun discard(id: TimedDraftId)

    /**
     * PRD 8.2: the review form's own state, rewritten at every significant change rather than at
     * save time.
     *
     * [state] is opaque all the way down — the repository stores the string and never reads it,
     * and only the ViewModel encodes and decodes it. [schemaVersion] is stored beside it so a
     * blob written by another build is rebuilt from the typed columns instead of being decoded;
     * an unreadable or unknown state is never a blocking error.
     */
    suspend fun saveReviewFormState(id: TimedDraftId, state: String?, schemaVersion: Int)

    /**
     * FR-TIMER-007: creates the session, its equipment, its metrics and its strength detail, and
     * deletes the draft — all of it in one transaction, or none of it. A failure here leaves the
     * draft and its form state exactly where they were (PRD 12).
     */
    suspend fun commitToSession(id: TimedDraftId, detail: ActivitySessionDetail)
}
