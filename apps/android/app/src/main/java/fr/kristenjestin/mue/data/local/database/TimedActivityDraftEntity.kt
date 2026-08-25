package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The one live timer and every draft waiting to be reviewed (PRD 8.1).
 *
 * The row carries both representations of its start on purpose, and that is this module's one
 * documented exception to PRD 16.3's rule that a calendar value stores no zone: `started_at_millis`
 * is the physical instant the elapsed arithmetic of PRD 8.3 needs, while `started_on` and
 * `started_at_local_time` are the calendar reading frozen at `Start timer`, so a phone that
 * crosses a timezone between `Finish` and `Save activity` cannot file the session on another day.
 *
 * `accumulated_active_seconds` is the safety property of the whole module: written at each pause
 * and finish, never revised, so a reboot or a clock correction can spoil at most the open segment.
 * The two segment columns are the open segment on both clocks, and `boot_reference_millis` is
 * rewritten at every update — it is the single comparison FR-TIMER-003 allows for deciding
 * whether the monotonic one is still worth anything.
 *
 * `review_form_state` is opaque here and everywhere below the ViewModel (PRD 8.2). The column is
 * stored and never read, which is what lets the form grow fields without a migration; its version
 * sits beside it so a blob written by another build is rebuilt from the typed columns instead of
 * being decoded. No mirror of the five session tables is created — PRD 8.2 forbids it in as many
 * words.
 *
 * The index is on `status`: every read of this table asks for the live draft or for the reviewed
 * ones, and never for a draft by movement or by date.
 */
@Entity(
    tableName = TimedActivityDraftEntity.TABLE_NAME,
    indices = [Index(value = ["status"])],
)
data class TimedActivityDraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "movement")
    val movement: String,

    @ColumnInfo(name = "custom_movement_name")
    val customMovementName: String?,

    @ColumnInfo(name = "environment")
    val environment: String,

    @ColumnInfo(name = "started_at_millis")
    val startedAtMillis: Long,

    @ColumnInfo(name = "started_on")
    val startedOn: String,

    @ColumnInfo(name = "started_at_local_time")
    val startedAtLocalTime: String,

    @ColumnInfo(name = "accumulated_active_seconds")
    val accumulatedActiveSeconds: Int,

    @ColumnInfo(name = "current_segment_started_at_millis")
    val currentSegmentStartedAtMillis: Long?,

    @ColumnInfo(name = "current_segment_started_elapsed_realtime_millis")
    val currentSegmentStartedElapsedRealtimeMillis: Long?,

    @ColumnInfo(name = "boot_reference_millis")
    val bootReferenceMillis: Long?,

    @ColumnInfo(name = "finished_at_millis")
    val finishedAtMillis: Long?,

    @ColumnInfo(name = "review_form_state")
    val reviewFormState: String?,

    @ColumnInfo(name = "review_form_schema_version", defaultValue = "0")
    val reviewFormSchemaVersion: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val TABLE_NAME = "timed_activity_drafts"
    }
}

/**
 * `HH:mm:ss`, unlike the session's own `started_at_time`, which is and stays `HH:mm`.
 *
 * A timer knows the second it started on and FR-TIMER-005 truncates to the minute only when it
 * prefills the form — the truncation belongs to the session column, which has no seconds, and
 * not to the draft that measured them.
 */
private val DRAFT_TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * A negative stored total would be a row no build of Mue can write, and falling back on zero
 * rather than throwing only ever affects a file some other tool corrupted — the same choice
 * [ActivitySessionEntity.toDomain] makes about a duration.
 */
fun TimedActivityDraftEntity.toDomain(
    equipment: List<TimedDraftEquipmentEntity>,
): TimedActivityDraft = TimedActivityDraft(
    id = TimedDraftId(id),
    status = TimedDraftStatus.fromId(status),
    movement = Movement.fromId(movement),
    startedAtMillis = startedAtMillis,
    startedOn = startedOn.toLocalDateColumn(),
    startedAtLocalTime = LocalTime.parse(startedAtLocalTime, DRAFT_TIME_OF_DAY),
    accumulatedActive = ActivityDuration.ofSecondsOrNull(accumulatedActiveSeconds)
        ?: ActivityDuration.ZERO,
    customMovementName = customMovementName,
    environment = ActivityEnvironment.fromId(environment),
    equipment = equipment.sortedBy { it.position }.map { it.toDomain() },
    currentSegmentStartedAtMillis = currentSegmentStartedAtMillis,
    currentSegmentStartedElapsedRealtimeMillis = currentSegmentStartedElapsedRealtimeMillis,
    bootReferenceMillis = bootReferenceMillis,
    finishedAtMillis = finishedAtMillis,
    reviewFormState = reviewFormState,
    reviewFormSchemaVersion = reviewFormSchemaVersion,
)

fun TimedActivityDraft.toEntity(createdAt: Long, updatedAt: Long): TimedActivityDraftEntity =
    TimedActivityDraftEntity(
        id = id.value,
        status = status.id,
        movement = movement.id,
        customMovementName = customMovementName,
        environment = environment.id,
        startedAtMillis = startedAtMillis,
        startedOn = startedOn.toColumn(),
        startedAtLocalTime = startedAtLocalTime.format(DRAFT_TIME_OF_DAY),
        accumulatedActiveSeconds = accumulatedActive.seconds,
        currentSegmentStartedAtMillis = currentSegmentStartedAtMillis,
        currentSegmentStartedElapsedRealtimeMillis = currentSegmentStartedElapsedRealtimeMillis,
        bootReferenceMillis = bootReferenceMillis,
        finishedAtMillis = finishedAtMillis,
        reviewFormState = reviewFormState,
        reviewFormSchemaVersion = reviewFormSchemaVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
