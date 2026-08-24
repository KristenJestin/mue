package fr.kristenjestin.mue.domain.model

/**
 * The six form configurations of PRD 8.5.
 *
 * A preset is presentation, never data: `Treadmill walk` is a way to fill a form, not a stored
 * activity type, and nothing needs a preset to read an old session back. [id] exists only to key
 * the per-preset values a draft keeps while the screen is open (PRD FR-ACTIVITY-004); it is
 * never a column.
 *
 * [movement] is null for the builder alone, which asks the person which activity they mean.
 */
enum class ActivityPreset(
    val id: String,
    val label: String,
    val movement: Movement?,
    val environment: ActivityEnvironment,
    val equipment: EquipmentType?,
    val metrics: List<MetricKind>,
    val energySource: MetricSource = MetricSource.MANUAL,
    val choosesEnvironment: Boolean = false,
    val choosesEquipment: Boolean = false,
    val offersStrengthDetail: Boolean = false,
) {
    /** PRD FR-ACTIVITY-006. The machine reports the calories, so they keep its provenance. */
    TREADMILL_WALK(
        id = "treadmill_walk",
        label = "Treadmill walk",
        movement = Movement.WALKING,
        environment = ActivityEnvironment.INDOOR,
        equipment = EquipmentType.TREADMILL,
        metrics = listOf(
            MetricKind.DISTANCE,
            MetricKind.REPORTED_SPEED,
            MetricKind.ESTIMATED_ENERGY,
            MetricKind.INCLINE,
        ),
        energySource = MetricSource.EQUIPMENT,
    ),

    /** PRD FR-ACTIVITY-007. */
    OUTDOOR_WALK(
        id = "outdoor_walk",
        label = "Outdoor walk",
        movement = Movement.WALKING,
        environment = ActivityEnvironment.OUTDOOR,
        equipment = null,
        metrics = listOf(MetricKind.DISTANCE, MetricKind.AVERAGE_PACE, MetricKind.ESTIMATED_ENERGY),
    ),

    /** Outdoor, because PRD 8.1 titles the example `Outdoor run`. */
    RUN(
        id = "run",
        label = "Run",
        movement = Movement.RUNNING,
        environment = ActivityEnvironment.OUTDOOR,
        equipment = null,
        metrics = listOf(MetricKind.DISTANCE, MetricKind.AVERAGE_PACE, MetricKind.ESTIMATED_ENERGY),
    ),

    /** The PRD imposes no place on cycling, so the preset imposes none either (PRD 8.2). */
    CYCLING(
        id = "cycling",
        label = "Cycling",
        movement = Movement.CYCLING,
        environment = ActivityEnvironment.UNKNOWN,
        equipment = null,
        metrics = listOf(MetricKind.DISTANCE, MetricKind.AVERAGE_SPEED, MetricKind.ESTIMATED_ENERGY),
    ),

    /** PRD 9.1: the quick form already offers energy, effort, equipment and a note. */
    STRENGTH_TRAINING(
        id = "strength_training",
        label = "Strength training",
        movement = Movement.STRENGTH_TRAINING,
        environment = ActivityEnvironment.UNKNOWN,
        equipment = null,
        metrics = listOf(MetricKind.ESTIMATED_ENERGY),
        choosesEquipment = true,
        offersStrengthDetail = true,
    ),

    /** The builder of PRD 8.5 and FR-ACTIVITY-008. */
    OTHER(
        id = "other",
        label = "Other",
        movement = null,
        environment = ActivityEnvironment.UNKNOWN,
        equipment = null,
        metrics = listOf(MetricKind.ESTIMATED_ENERGY),
        choosesEnvironment = true,
        choosesEquipment = true,
    ),
    ;

    /** PRD FR-ACTIVITY-006: only an estimation carries a machine's provenance, not a speed. */
    fun sourceOf(kind: MetricKind): MetricSource =
        if (kind == MetricKind.ESTIMATED_ENERGY) energySource else MetricSource.MANUAL

    companion object {
        /** PRD FR-ACTIVITY-004 preselects it on a new entry. */
        val DEFAULT: ActivityPreset = TREADMILL_WALK

        private val byId: Map<String, ActivityPreset> = entries.associateBy { it.id }

        /** Total and non-throwing; an unreadable draft key falls back on the preselection. */
        fun fromId(id: String): ActivityPreset = byId[id] ?: DEFAULT

        /**
         * The searchable list the builder opens with (PRD FR-ACTIVITY-008), derived rather than
         * written out: a second hardcoded list would drift away from [Movement] on the first
         * addition. `other` is excluded because reaching it is the `Create` action, not a choice.
         */
        val OTHER_CATALOGUE: List<Movement> = Movement.entries.filterNot { movement ->
            movement == Movement.OTHER || ActivityPreset.entries.any { it.movement == movement }
        }

        /**
         * Which form reopens an existing session (PRD 7, `Edit activity`). The stored axes are
         * enough: nothing about the preset was ever written down.
         */
        fun of(movement: Movement, equipment: List<SessionEquipment>): ActivityPreset =
            when (movement) {
                Movement.WALKING ->
                    if (equipment.any { it.equipmentType == EquipmentType.TREADMILL }) {
                        TREADMILL_WALK
                    } else {
                        OUTDOOR_WALK
                    }
                Movement.RUNNING -> RUN
                Movement.CYCLING -> CYCLING
                Movement.STRENGTH_TRAINING -> STRENGTH_TRAINING
                else -> OTHER
            }
    }
}
