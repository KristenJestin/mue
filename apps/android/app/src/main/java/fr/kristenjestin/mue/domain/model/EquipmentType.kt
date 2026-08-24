package fr.kristenjestin.mue.domain.model

/**
 * The gear a session or an exercise was done on (PRD 8.4).
 *
 * [isTitling] marks the four machines PRD 11.1 lets into a session label: they are the only
 * ones that change what the activity *is* to the person doing it. A yoga mat does not.
 */
enum class EquipmentType(
    val id: String,
    val displayName: String,
    val isTitling: Boolean = false,
) {
    TREADMILL("treadmill", "Treadmill", isTitling = true),
    STATIONARY_BIKE("stationary_bike", "Stationary bike", isTitling = true),
    BICYCLE("bicycle", "Bicycle"),
    ROWING_MACHINE("rowing_machine", "Rowing machine", isTitling = true),
    ELLIPTICAL_MACHINE("elliptical_machine", "Elliptical machine", isTitling = true),
    YOGA_MAT("yoga_mat", "Yoga mat"),
    RESISTANCE_BANDS("resistance_bands", "Resistance bands"),
    BARBELL("barbell", "Barbell"),
    DUMBBELLS("dumbbells", "Dumbbells"),
    KETTLEBELL("kettlebell", "Kettlebell"),
    MACHINE("machine", "Machine"),
    BODYWEIGHT("bodyweight", "Bodyweight"),
    CLIMBING_WALL("climbing_wall", "Climbing wall"),
    POOL("pool", "Pool"),

    /** The only type that carries a free name (PRD 8.4). */
    OTHER("other", "Other"),
    ;

    companion object {
        private val byId: Map<String, EquipmentType> = entries.associateBy { it.id }

        /** Total and non-throwing; an unreadable type falls back on the free-name one. */
        fun fromId(id: String): EquipmentType = byId[id] ?: OTHER
    }
}
