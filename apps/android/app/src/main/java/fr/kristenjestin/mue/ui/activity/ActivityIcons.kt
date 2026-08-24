package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.MetricKind

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

    /** PRD 14.1: both walks share `footprints`, and a run is titled by `route`. */
    fun forPreset(preset: ActivityPreset): String = when (preset) {
        ActivityPreset.TREADMILL_WALK, ActivityPreset.OUTDOOR_WALK -> FOOTPRINTS
        ActivityPreset.RUN -> ROUTE
        ActivityPreset.CYCLING -> BIKE
        ActivityPreset.STRENGTH_TRAINING -> DUMBBELL
        ActivityPreset.OTHER -> SHAPES
    }

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
