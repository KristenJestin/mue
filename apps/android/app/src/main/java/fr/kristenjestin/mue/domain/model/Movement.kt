package fr.kristenjestin.mue.domain.model

/**
 * What the person did (PRD 8.2). The movement is one of the independent axes of a session:
 * it says nothing about where the activity happened or what it was done on.
 *
 * [id] is what reaches SQLite (PRD 16.1): renaming [displayName] must never touch the data.
 * [activityNoun] is the lowercase form the label rules of PRD 11.1 build on, as in
 * `Outdoor` + `run`.
 */
enum class Movement(
    val id: String,
    val displayName: String,
    val activityNoun: String,
) {
    WALKING("walking", "Walking", "walk"),
    RUNNING("running", "Running", "run"),
    CYCLING("cycling", "Cycling", "ride"),
    SWIMMING("swimming", "Swimming", "swim"),
    STRENGTH_TRAINING("strength_training", "Strength training", "strength training"),
    ROWING("rowing", "Rowing", "rowing"),
    ELLIPTICAL("elliptical", "Elliptical", "elliptical"),
    HIKING("hiking", "Hiking", "hike"),
    YOGA("yoga", "Yoga", "yoga"),
    CLIMBING("climbing", "Climbing", "climb"),
    DANCING("dancing", "Dancing", "dance"),
    PILATES("pilates", "Pilates", "pilates"),
    MOBILITY("mobility", "Mobility", "mobility"),
    TEAM_SPORT("team_sport", "Team sport", "team sport"),

    /** The only movement that carries a free name, and only through the `Other` builder. */
    OTHER("other", "Other", "activity"),
    ;

    companion object {
        private val byId: Map<String, Movement> = entries.associateBy { it.id }

        /**
         * Total and non-throwing. An id this build does not know can only come from a newer
         * one, and PRD 11.1 already specifies how an `other` session with no custom name
         * reads, so degrading is a described state rather than a crash.
         */
        fun fromId(id: String): Movement = byId[id] ?: OTHER
    }
}
