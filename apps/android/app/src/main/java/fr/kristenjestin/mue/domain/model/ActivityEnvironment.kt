package fr.kristenjestin.mue.domain.model

/**
 * Where the activity happened (PRD 8.2). [UNKNOWN] is a real answer, not a missing one:
 * it is what a preset that does not impose a place stores, and what the `Not set` choice
 * of PRD FR-ACTIVITY-008 selects by default.
 */
enum class ActivityEnvironment(val id: String, val displayName: String) {
    INDOOR("indoor", "Indoor"),
    OUTDOOR("outdoor", "Outdoor"),
    UNKNOWN("unknown", "Not set"),
    ;

    companion object {
        private val byId: Map<String, ActivityEnvironment> = entries.associateBy { it.id }

        /** Total and non-throwing; an unreadable place is exactly [UNKNOWN]. */
        fun fromId(id: String): ActivityEnvironment = byId[id] ?: UNKNOWN
    }
}

/**
 * How a session entered Mue (PRD 8.2, amended by PRD 17).
 *
 * [TIMER] is written by FR-TIMER-007 and is what tells a chronometered session from a typed one,
 * which is also what the `Start again` shortcut of the timer's PRD 6.1 looks for.
 * [HEALTH_CONNECT] is still written by nothing and exists so a future import needs no migration.
 */
enum class ActivitySource(val id: String) {
    MANUAL("manual"),
    HEALTH_CONNECT("health_connect"),
    TIMER("timer"),
    ;

    companion object {
        private val byId: Map<String, ActivitySource> = entries.associateBy { it.id }

        /** Total and non-throwing; an unreadable origin is treated as hand-entered. */
        fun fromId(id: String): ActivitySource = byId[id] ?: MANUAL
    }
}

/**
 * Where one measurement came from (PRD 8.3). PRD 11.3 requires an estimation to keep its
 * provenance, which is why a treadmill's calorie readout is [EQUIPMENT] and not [MANUAL].
 */
enum class MetricSource(val id: String) {
    MANUAL("manual"),
    EQUIPMENT("equipment"),
    WEARABLE("wearable"),
    CALCULATED("calculated"),
    ;

    companion object {
        private val byId: Map<String, MetricSource> = entries.associateBy { it.id }

        /** Total and non-throwing; an unreadable provenance is treated as hand-entered. */
        fun fromId(id: String): MetricSource = byId[id] ?: MANUAL
    }
}
