package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySession
import fr.kristenjestin.mue.domain.model.ActivitySource
import fr.kristenjestin.mue.domain.model.Movement

/**
 * One finished activity (PRD 8.2).
 *
 * The calendar date is ISO text and the optional start time is `HH:mm`, so lexicographic order
 * is chronological order and no zone is stored anywhere (PRD 16.3) — a phone that flies to
 * another continent cannot move a session by a day. An absent time stays a null column, which
 * is what keeps "no time given" distinct from midnight.
 *
 * `created_at` and `updated_at` are the module's only absolute timestamps. They belong to the
 * audit alone, which is why they stop here and never reach [ActivitySession].
 */
@Entity(
    tableName = ActivitySessionEntity.TABLE_NAME,
    indices = [Index(value = ["started_on"])],
)
data class ActivitySessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "movement")
    val movement: String,

    @ColumnInfo(name = "custom_movement_name")
    val customMovementName: String?,

    @ColumnInfo(name = "environment")
    val environment: String,

    @ColumnInfo(name = "started_on")
    val startedOn: String,

    @ColumnInfo(name = "started_at_time")
    val startedAtTime: String?,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int,

    @ColumnInfo(name = "perceived_effort")
    val perceivedEffort: Int?,

    @ColumnInfo(name = "notes")
    val notes: String?,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val TABLE_NAME = "activity_sessions"
    }
}

/**
 * A duration below one second cannot be written, so falling back on zero rather than throwing
 * only affects a row some other tool corrupted — and a dashboard that still opens beats one
 * that crashes on the whole history.
 */
fun ActivitySessionEntity.toDomain(): ActivitySession = ActivitySession(
    id = ActivityId(id),
    movement = Movement.fromId(movement),
    startedOn = startedOn.toLocalDateColumn(),
    duration = ActivityDuration.ofSecondsOrNull(durationSeconds) ?: ActivityDuration.ZERO,
    customMovementName = customMovementName,
    environment = ActivityEnvironment.fromId(environment),
    startedAtTime = startedAtTime.toLocalTimeColumn(),
    perceivedEffort = perceivedEffort.toPerceivedEffortColumn(),
    notes = notes,
    source = ActivitySource.fromId(source),
)

fun ActivitySession.toEntity(createdAt: Long, updatedAt: Long): ActivitySessionEntity =
    ActivitySessionEntity(
        id = id.value,
        movement = movement.id,
        customMovementName = customMovementName,
        environment = environment.id,
        startedOn = startedOn.toColumn(),
        startedAtTime = startedAtTime?.toColumn(),
        durationSeconds = duration.seconds,
        perceivedEffort = perceivedEffort?.value,
        notes = notes,
        source = source.id,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
