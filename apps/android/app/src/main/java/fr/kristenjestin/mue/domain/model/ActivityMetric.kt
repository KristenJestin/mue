package fr.kristenjestin.mue.domain.model

import kotlin.math.roundToLong

/**
 * The canonical integer units of PRD 8.3. No quantity of the module is ever a float, in
 * memory or at rest, so every unit here counts whole somethings.
 */
enum class MetricUnit(val id: String) {
    METRE("metre"),
    CENTI_KM_PER_HOUR("centi_km_per_hour"),
    SECOND_PER_KILOMETRE("second_per_kilometre"),
    KCAL("kcal"),
    DECI_PERCENT("deci_percent"),
    COUNT("count"),
    BPM("bpm"),
    WATT("watt"),
    RPM("rpm"),
    ;

    companion object {
        private val byId: Map<String, MetricUnit> = entries.associateBy { it.id }

        /** Total and non-throwing; an unreadable unit is a bare count. */
        fun fromId(id: String): MetricUnit = byId[id] ?: COUNT
    }
}

/**
 * Everything a session can measure (PRD 8.3). A closed set on purpose: extending the module
 * means adding a constant here, never registering a type at runtime.
 *
 * [unit] is derived rather than stored. PRD 8.3 lists it as a field of a measurement, but says
 * in the same row that the kind determines it; keeping it as a column would allow the
 * contradiction `kind = distance, unit = kcal` to be written.
 *
 * [canonicalPerDisplayUnit] is the whole of the unit conversion PRD 12 asks for: a distance
 * is typed in kilometres and stored in metres, an incline in percent and stored in tenths.
 * Kinds that are already shown in their stored unit simply carry `1`.
 *
 * [displayDecimals] is stated per kind rather than derived from that scale. Deriving it gave
 * every converted quantity one decimal, which suits a speed and destroys a distance: `2950 m`
 * came back as `3`, and saving that `3` wrote `3000 m`. The loss happened on re-editing, not
 * on entry, so nothing warned anyone. Each kind now carries the count that renders it without
 * dropping anything it can hold.
 */
enum class MetricKind(
    val id: String,
    val unit: MetricUnit,
    val label: String,
    val displayUnit: String,
    val canonicalPerDisplayUnit: Int,
    val displayDecimals: Int,
    val editableInV1: Boolean,
) {
    /** Two decimals: ten-metre precision, finer than any treadmill or watch reports. */
    DISTANCE("distance", MetricUnit.METRE, "Distance", "km", 1_000, 2, editableInV1 = true),

    /** Two decimals is exactly the stored hundredth of a km/h, so nothing typed is ever lost. */
    REPORTED_SPEED(
        "reported_speed",
        MetricUnit.CENTI_KM_PER_HOUR,
        "Reported speed",
        "km/h",
        100,
        2,
        editableInV1 = true,
    ),
    AVERAGE_SPEED(
        "average_speed",
        MetricUnit.CENTI_KM_PER_HOUR,
        "Average speed",
        "km/h",
        100,
        2,
        editableInV1 = true,
    ),

    /**
     * Typed and shown as `m:ss /km` (PRD FR-ACTIVITY-007), which is why its scale is one and
     * its decimal count is none: the fraction of a minute is the `ss` box, not a decimal.
     */
    AVERAGE_PACE(
        "average_pace",
        MetricUnit.SECOND_PER_KILOMETRE,
        "Average pace",
        "/km",
        1,
        0,
        editableInV1 = true,
    ),

    /** Whole kilocalories: an estimation to the tenth would claim a precision nobody has. */
    ESTIMATED_ENERGY(
        "estimated_energy",
        MetricUnit.KCAL,
        "Estimated energy",
        "kcal",
        1,
        0,
        editableInV1 = true,
    ),

    /** One decimal is exactly the stored tenth of a percent, and the step a treadmill offers. */
    INCLINE("incline", MetricUnit.DECI_PERCENT, "Incline", "%", 10, 1, editableInV1 = true),

    /* The five kinds below are whole counts of their own unit, so none of them has a fraction. */
    STEPS("steps", MetricUnit.COUNT, "Steps", "", 1, 0, editableInV1 = false),
    AVERAGE_HEART_RATE(
        "average_heart_rate",
        MetricUnit.BPM,
        "Average heart rate",
        "bpm",
        1,
        0,
        editableInV1 = false,
    ),
    ELEVATION_GAIN("elevation_gain", MetricUnit.METRE, "Elevation gain", "m", 1, 0, editableInV1 = false),
    POWER("power", MetricUnit.WATT, "Power", "W", 1, 0, editableInV1 = false),
    CADENCE("cadence", MetricUnit.RPM, "Cadence", "rpm", 1, 0, editableInV1 = false),
    ;

    /** Null when the value does not survive the trip into an `Int`, or when it is negative. */
    fun toCanonicalOrNull(displayValue: Double): Int? {
        if (!displayValue.isFinite() || displayValue < 0.0) return null
        val canonical = (displayValue * canonicalPerDisplayUnit).roundToLong()
        return if (canonical > Int.MAX_VALUE) null else canonical.toInt()
    }

    /** The inverse of [toCanonicalOrNull]; the locale-aware rendering is the caller's job. */
    fun toDisplayValue(canonical: Int): Double = canonical / canonicalPerDisplayUnit.toDouble()

    companion object {
        private val byId: Map<String, MetricKind> = entries.associateBy { it.id }

        /** Null for an id this build does not know: a metric has no meaningful fallback. */
        fun fromIdOrNull(id: String): MetricKind? = byId[id]

        /** The kinds a V1 form may offer (PRD 8.3). */
        val EDITABLE: List<MetricKind> = entries.filter { it.editableInV1 }
    }
}

/** One measurement of a session (PRD 8.3). [value] is always expressed in `kind.unit`. */
data class ActivityMetric(
    val kind: MetricKind,
    val value: Int,
    val source: MetricSource = MetricSource.MANUAL,
)

/**
 * The measurements of one session, at most one per kind (PRD 8.3).
 *
 * The uniqueness rule lives in SQLite as a composite primary key; wrapping a map keeps it
 * true in memory as well, so no caller can build a session carrying two distances.
 */
@JvmInline
value class ActivityMetrics private constructor(val byKind: Map<MetricKind, ActivityMetric>) {

    operator fun get(kind: MetricKind): ActivityMetric? = byKind[kind]

    operator fun contains(kind: MetricKind): Boolean = kind in byKind

    /** The stored value in `kind.unit`, or null when the session simply has no such row. */
    fun valueOf(kind: MetricKind): Int? = byKind[kind]?.value

    /** In the declaration order of [MetricKind], so a session always reads the same way. */
    val values: List<ActivityMetric> get() = byKind.values.toList()

    val isEmpty: Boolean get() = byKind.isEmpty()

    val isNotEmpty: Boolean get() = byKind.isNotEmpty()

    fun with(metric: ActivityMetric): ActivityMetrics =
        of(byKind.values + metric)

    fun without(kind: MetricKind): ActivityMetrics =
        of(byKind.values.filterNot { it.kind == kind })

    companion object {
        val EMPTY: ActivityMetrics = ActivityMetrics(emptyMap())

        /**
         * The last measurement of a repeated kind wins. Throwing would turn a duplicated row
         * read back from a future schema into a crash on the dashboard.
         */
        fun of(metrics: Iterable<ActivityMetric>): ActivityMetrics =
            ActivityMetrics(
                metrics.sortedBy { it.kind.ordinal }.associateBy { it.kind }
            )

        fun of(vararg metrics: ActivityMetric): ActivityMetrics = of(metrics.asIterable())
    }
}
