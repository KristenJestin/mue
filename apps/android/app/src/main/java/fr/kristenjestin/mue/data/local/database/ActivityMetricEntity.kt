package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import fr.kristenjestin.mue.domain.model.ActivityMetric
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.MetricSource

/**
 * One measurement of one session (PRD 8.3), always an integer in the canonical unit of its kind.
 *
 * The pair `(session_id, kind)` is the primary key, so "a session never carries two measurements
 * of the same kind" is a SQLite constraint rather than a convention. Its b-tree is leftmost-
 * prefixed on `session_id`, so it already serves as the index PRD 16.3 asks for.
 *
 * The unit has no column: PRD 8.3 derives it from the kind, and storing it beside the kind would
 * make `kind = distance, unit = kcal` writable.
 */
@Entity(
    tableName = ActivityMetricEntity.TABLE_NAME,
    primaryKeys = ["session_id", "kind"],
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ActivityMetricEntity(
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "value")
    val value: Int,

    @ColumnInfo(name = "source")
    val source: String,
) {
    companion object {
        const val TABLE_NAME = "activity_metrics"
    }
}

/**
 * Null for a kind this build cannot read. A metric has no meaningful fallback — showing a
 * distance as a step count would be worse than not showing it — so an unknown row is skipped.
 */
fun ActivityMetricEntity.toDomain(): ActivityMetric? =
    MetricKind.fromIdOrNull(kind)?.let { known ->
        ActivityMetric(kind = known, value = value, source = MetricSource.fromId(source))
    }

fun ActivityMetric.toEntity(sessionId: String): ActivityMetricEntity = ActivityMetricEntity(
    sessionId = sessionId,
    kind = kind.id,
    value = value,
    source = source.id,
)
