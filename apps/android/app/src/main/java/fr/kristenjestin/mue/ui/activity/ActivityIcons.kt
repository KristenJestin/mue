package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.Movement

/**
 * The Lucide drawables of PRD 14.1, named once so that the screens, the icon loader and the
 * resources agree without anyone having to coordinate.
 *
 * These are resource *names* rather than `R.drawable` references: the vectors are imported one
 * by one into the app, PRD 14.1 rules out pulling in an icon library, and naming them as text
 * keeps this file independent of the order in which the drawables land.
 */
object ActivityIcons {

    /** The four permanent tabs (PRD 14.1). */
    const val TAB_ENTRY: String = "ic_scale"
    const val TAB_PROGRESS: String = "ic_chart_no_axes_combined"
    const val TAB_ACTIVITY: String = "ic_activity"
    const val TAB_PROFILE: String = "ic_user_round"

    const val FOOTPRINTS: String = "ic_footprints"
    const val ROUTE: String = "ic_route"
    const val BIKE: String = "ic_bike"
    const val DUMBBELL: String = "ic_dumbbell"
    const val SHAPES: String = "ic_shapes"
    const val TIMER: String = "ic_timer"
    const val GAUGE: String = "ic_gauge"
    const val FLAME: String = "ic_flame"
    const val TRENDING_UP: String = "ic_trending_up"
    const val MAP_PIN: String = "ic_map_pin"
    const val TREES: String = "ic_trees"
    const val WRENCH: String = "ic_wrench"
    const val NOTEBOOK_PEN: String = "ic_notebook_pen"
    const val PLUS: String = "ic_plus"
    const val PLUS_CIRCLE: String = "ic_plus_circle"
    const val COPY_PLUS: String = "ic_copy_plus"
    const val SEARCH: String = "ic_search"
    const val SPARKLES: String = "ic_sparkles"

    /*
     * Beyond PRD 14.1, which tabulates the six presets only. Every other `Movement` reaches the
     * catalogue through the `Other` builder and would otherwise land on the same `shapes`, so a
     * history of nine different sports would read as nine identical cards.
     */

    const val WAVES: String = "ic_waves"
    const val SAILBOAT: String = "ic_sailboat"
    const val ORBIT: String = "ic_orbit"
    const val MOUNTAIN: String = "ic_mountain"
    const val MOUNTAIN_SNOW: String = "ic_mountain_snow"
    const val FLOWER: String = "ic_flower"
    const val MUSIC: String = "ic_music"
    const val PERSON_STANDING: String = "ic_person_standing"
    const val MOVE: String = "ic_move"
    const val VOLLEYBALL: String = "ic_volleyball"

    /**
     * The one movement-to-glyph table in the module.
     *
     * The first four rows are PRD 14.1's; the rest are the closest Lucide vector for a movement
     * the PRD does not tabulate. `shapes` is kept for [Movement.OTHER] alone, whose whole point
     * is that it is not any of these.
     */
    fun forMovement(movement: Movement): String = when (movement) {
        Movement.WALKING -> FOOTPRINTS
        Movement.RUNNING -> ROUTE
        Movement.CYCLING -> BIKE
        Movement.STRENGTH_TRAINING -> DUMBBELL
        Movement.SWIMMING -> WAVES
        // Lucide has no oar; a small boat is the nearest thing it draws.
        Movement.ROWING -> SAILBOAT
        // The one real stretch: `orbit` is the only Lucide glyph tracing a closed elliptical path.
        Movement.ELLIPTICAL -> ORBIT
        // A hill for the hike, and the peak above it for what is climbed rather than walked.
        Movement.HIKING -> MOUNTAIN
        Movement.CLIMBING -> MOUNTAIN_SNOW
        Movement.YOGA -> FLOWER
        Movement.DANCING -> MUSIC
        // A held posture for pilates, and travel in every direction for mobility work.
        Movement.PILATES -> PERSON_STANDING
        Movement.MOBILITY -> MOVE
        Movement.TEAM_SPORT -> VOLLEYBALL
        Movement.OTHER -> SHAPES
    }

    /**
     * PRD 14.1: both walks share `footprints`, and a run is titled by `route`.
     *
     * Delegating to [forMovement] keeps one table rather than two; the builder carries no
     * movement of its own and is the only preset that answers `shapes`.
     */
    fun forPreset(preset: ActivityPreset): String =
        preset.movement?.let(::forMovement) ?: SHAPES

    /** A speed, a pace and an effort all read on the same dial (PRD 14.1). */
    fun forMetric(kind: MetricKind): String = when (kind) {
        MetricKind.DISTANCE, MetricKind.ELEVATION_GAIN -> ROUTE
        MetricKind.REPORTED_SPEED,
        MetricKind.AVERAGE_SPEED,
        MetricKind.AVERAGE_PACE,
        MetricKind.CADENCE,
        MetricKind.POWER,
        MetricKind.AVERAGE_HEART_RATE,
        -> GAUGE
        MetricKind.ESTIMATED_ENERGY -> FLAME
        MetricKind.INCLINE -> TRENDING_UP
        MetricKind.STEPS -> FOOTPRINTS
    }

    /** An unknown place keeps the neutral pin rather than claiming the outdoors. */
    fun forEnvironment(environment: ActivityEnvironment): String = when (environment) {
        ActivityEnvironment.OUTDOOR -> TREES
        ActivityEnvironment.INDOOR, ActivityEnvironment.UNKNOWN -> MAP_PIN
    }
}
