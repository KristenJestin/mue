package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The identifier of a timed draft (PRD 8.1), a value class over the `TEXT` UUID that is stored,
 * so it can never be handed to a query expecting a session id.
 */
@JvmInline
value class TimedDraftId(val value: String) {
    companion object {
        fun random(): TimedDraftId = TimedDraftId(UUID.randomUUID().toString())
    }
}

/** Where a draft is in the three-state life of PRD 8.1 and FR-TIMER-001, 004 and 005. */
enum class TimedDraftStatus(val id: String) {
    RUNNING("running"),
    PAUSED("paused"),
    PENDING_REVIEW("pending_review"),
    ;

    /** The one timer FR-TIMER-001 allows: what the banner and the notification show. */
    val isLive: Boolean get() = this == RUNNING || this == PAUSED

    companion object {
        private val byId: Map<String, TimedDraftStatus> = entries.associateBy { it.id }

        /**
         * Total and non-throwing, falling back on [PAUSED]: it is the only status that loses no
         * measured time, performs no arithmetic on a column this build cannot vouch for, and
         * still leaves Resume, Finish and Discard reachable. It is also where FR-TIMER-010 puts
         * a timer whose duration cannot be trusted.
         */
        fun fromId(id: String): TimedDraftStatus = byId[id] ?: PAUSED
    }
}

/**
 * A timer, running or waiting to be reviewed (PRD 8.1).
 *
 * The draft carries the axes of the activity — movement, custom name, environment, equipment —
 * and never a preset id: a preset is presentation and never data, and `ActivityPreset.of` already
 * rebuilds the right form from the axes. Equipment reuses [SessionEquipment] because the review
 * hand-off of FR-TIMER-007 copies it into the session unchanged; a parallel type would be the
 * same three fields under another name.
 *
 * [startedOn] and [startedAtLocalTime] are resolved once at `Start timer` and frozen, alongside
 * the absolute [startedAtMillis]. That is this module's one documented exception to PRD 16.3's
 * rule that a calendar date carries no zone: a running timer is a point in physical time while a
 * recorded session is a calendar fact, and deriving the date at save time would put a session on
 * the wrong day for anyone who crosses a timezone between `Finish` and `Save activity`.
 *
 * [accumulatedActive] is the safety property of the whole module. It is written at each pause and
 * finish and never revised, so a reboot, a flight or a clock correction can corrupt at most the
 * current segment: measured history is unreachable by any clock.
 *
 * `createdAt` and `updatedAt` of PRD 8.1 are audit columns of the stored row alone, exactly as
 * they are for [ActivitySession]: nothing reads them, and an instant here would make equality
 * depend on the clock.
 */
data class TimedActivityDraft(
    val id: TimedDraftId,
    val status: TimedDraftStatus,
    val movement: Movement,
    val startedAtMillis: Long,
    val startedOn: LocalDate,
    val startedAtLocalTime: LocalTime,
    val accumulatedActive: ActivityDuration = ActivityDuration.ZERO,
    val customMovementName: String? = null,
    val environment: ActivityEnvironment = ActivityEnvironment.UNKNOWN,
    val equipment: List<SessionEquipment> = emptyList(),
    val currentSegmentStartedAtMillis: Long? = null,
    val currentSegmentStartedElapsedRealtimeMillis: Long? = null,
    val bootReferenceMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    /** PRD 8.2: opaque here and everywhere below the ViewModel, which alone encodes it. */
    val reviewFormState: String? = null,
    /** Zero until a form state is written; PRD 8.2 rebuilds from the columns on a mismatch. */
    val reviewFormSchemaVersion: Int = 0,
) {
    val isLive: Boolean get() = status.isLive

    /** The axes again, which is all `Start again` of PRD 6.1 needs to reopen the same timer. */
    val request: StartTimerRequest
        get() = StartTimerRequest(movement, customMovementName, environment, equipment)
}

/**
 * What `Start timer` is asked to start (PRD 6.2), and what the `Start again` shortcut of PRD 6.1
 * copies from the last session whose source is `timer`.
 *
 * PRD 6.2 asks for nothing else before the timer runs: no distance, no speed, no energy, no
 * effort and no note.
 */
data class StartTimerRequest(
    val movement: Movement,
    val customMovementName: String? = null,
    val environment: ActivityEnvironment = ActivityEnvironment.UNKNOWN,
    val equipment: List<SessionEquipment> = emptyList(),
)

/**
 * The answer to `Start timer` (FR-TIMER-002).
 *
 * A second timer is refused by opening the first one, never by raising: both cases carry a draft
 * because both end on the timer screen, and only [AlreadyLive] also carries the notice.
 */
sealed interface StartTimerOutcome {

    val draft: TimedActivityDraft

    data class Started(override val draft: TimedActivityDraft) : StartTimerOutcome

    /** FR-TIMER-002: `An activity is already in progress.`, and no second draft. */
    data class AlreadyLive(override val draft: TimedActivityDraft) : StartTimerOutcome
}

/**
 * The draft `Start timer` writes before the chronometer is ever drawn (FR-TIMER-001).
 *
 * The first segment opens immediately, on both clocks, and the calendar reading of [now] is
 * frozen here. The local time keeps its seconds: FR-TIMER-005 truncates to the minute only when
 * prefilling the form, because it is the session column that has no seconds.
 */
fun StartTimerRequest.startedAt(
    id: TimedDraftId,
    now: TimerInstant,
    zone: ZoneId,
): TimedActivityDraft {
    val local = now.atZone(zone)
    return TimedActivityDraft(
        id = id,
        status = TimedDraftStatus.RUNNING,
        movement = movement,
        startedAtMillis = now.wallMillis,
        startedOn = local.toLocalDate(),
        startedAtLocalTime = local.toLocalTime().truncatedTo(ChronoUnit.SECONDS),
        accumulatedActive = ActivityDuration.ZERO,
        customMovementName = customMovementName,
        environment = environment,
        equipment = equipment,
        currentSegmentStartedAtMillis = now.wallMillis,
        currentSegmentStartedElapsedRealtimeMillis = now.elapsedRealtimeMillis,
        bootReferenceMillis = now.bootReferenceMillis,
    )
}
