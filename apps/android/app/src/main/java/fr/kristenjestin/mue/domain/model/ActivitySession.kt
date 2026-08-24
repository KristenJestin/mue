package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * One finished activity (PRD 8.2).
 *
 * The calendar date carries no time and no zone, exactly like a weight measurement, so no
 * zone change can move a session by a day; the optional start time is a separate minute-precision
 * value and its absence stays distinct from midnight.
 *
 * `createdAt` and `updatedAt` of PRD 8.2 are audit columns of the stored row alone: nothing
 * in PRD 10 to 13 reads them, and an instant inside this class would make equality depend on
 * the clock and force every fixture to invent one.
 */
data class ActivitySession(
    val id: ActivityId,
    val movement: Movement,
    val startedOn: LocalDate,
    val duration: ActivityDuration,
    val customMovementName: String? = null,
    val environment: ActivityEnvironment = ActivityEnvironment.UNKNOWN,
    val startedAtTime: LocalTime? = null,
    val perceivedEffort: PerceivedEffort? = null,
    val notes: String? = null,
    val source: ActivitySource = ActivitySource.MANUAL,
) {
    companion object {
        /** PRD 8.2: required and capped when the movement is `other`, absent otherwise. */
        const val MAX_CUSTOM_MOVEMENT_NAME_LENGTH: Int = 60

        /** PRD 8.2 and FR-ACTIVITY-005. */
        const val MAX_NOTES_LENGTH: Int = 500
    }
}

/**
 * One piece of gear attached to a session (PRD 8.4).
 *
 * The row keeps a primary key of its own — `other` lets one session carry several custom
 * names — but nothing in the domain refers to a piece of equipment by id, so none appears here.
 */
data class SessionEquipment(
    val equipmentType: EquipmentType,
    val customName: String? = null,
    val position: Int = 0,
) {
    /** The free name wins whenever there is one, per PRD FR-ACTIVITY-008. */
    val displayName: String
        get() = customName?.trim()?.takeIf { it.isNotEmpty() } ?: equipmentType.displayName

    /**
     * What makes `Treadmill` and `treadmill` the same equipment (PRD FR-ACTIVITY-008), and what
     * the unique index of a session stores. Folded with [Locale.ROOT] because `"I".lowercase()`
     * yields `"ı"` on a Turkish phone and the same name would then fold two ways on two devices.
     */
    val customNameFolded: String
        get() = customName?.trim()?.lowercase(Locale.ROOT).orEmpty()
}

/**
 * A whole session as the editor loads it and as the repository writes it in one transaction
 * (PRD 16.1).
 */
data class ActivitySessionDetail(
    val session: ActivitySession,
    val metrics: ActivityMetrics = ActivityMetrics.EMPTY,
    val equipment: List<SessionEquipment> = emptyList(),
    val exercises: List<StrengthExerciseDetail> = emptyList(),
)

/**
 * The read model of the dashboard and of the history (PRD FR-ACTIVITY-002 and 012).
 *
 * The card shows a label, a date, a duration and at most two secondary facts, so the query
 * projects exactly those three optional numbers rather than loading whole sessions.
 */
data class ActivitySummary(
    val id: ActivityId,
    val label: String,
    val movement: Movement,
    val startedOn: LocalDate,
    val startedAtTime: LocalTime?,
    val duration: ActivityDuration,
    val distanceMetres: Int? = null,
    val validSetCount: Int? = null,
    val estimatedEnergyKcal: Int? = null,
)
