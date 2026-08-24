package fr.kristenjestin.mue.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricKindTest {

    @Test
    fun `every kind stores the canonical unit PRD 8-3 gives it`() {
        assertEquals(MetricUnit.METRE, MetricKind.DISTANCE.unit)
        assertEquals(MetricUnit.CENTI_KM_PER_HOUR, MetricKind.REPORTED_SPEED.unit)
        assertEquals(MetricUnit.CENTI_KM_PER_HOUR, MetricKind.AVERAGE_SPEED.unit)
        assertEquals(MetricUnit.SECOND_PER_KILOMETRE, MetricKind.AVERAGE_PACE.unit)
        assertEquals(MetricUnit.KCAL, MetricKind.ESTIMATED_ENERGY.unit)
        assertEquals(MetricUnit.DECI_PERCENT, MetricKind.INCLINE.unit)
        assertEquals(MetricUnit.COUNT, MetricKind.STEPS.unit)
        assertEquals(MetricUnit.BPM, MetricKind.AVERAGE_HEART_RATE.unit)
        assertEquals(MetricUnit.METRE, MetricKind.ELEVATION_GAIN.unit)
        assertEquals(MetricUnit.WATT, MetricKind.POWER.unit)
        assertEquals(MetricUnit.RPM, MetricKind.CADENCE.unit)
    }

    @Test
    fun `the examples of the PRD 8-3 table convert exactly`() {
        assertEquals(4_200, MetricKind.DISTANCE.toCanonicalOrNull(4.2))
        assertEquals(560, MetricKind.REPORTED_SPEED.toCanonicalOrNull(5.6))
        assertEquals(2_450, MetricKind.AVERAGE_SPEED.toCanonicalOrNull(24.5))
        assertEquals(280, MetricKind.ESTIMATED_ENERGY.toCanonicalOrNull(280.0))
        assertEquals(25, MetricKind.INCLINE.toCanonicalOrNull(2.5))
        assertEquals(6_200, MetricKind.STEPS.toCanonicalOrNull(6_200.0))
        assertEquals(132, MetricKind.AVERAGE_HEART_RATE.toCanonicalOrNull(132.0))
        assertEquals(180, MetricKind.ELEVATION_GAIN.toCanonicalOrNull(180.0))
        assertEquals(210, MetricKind.POWER.toCanonicalOrNull(210.0))
        assertEquals(82, MetricKind.CADENCE.toCanonicalOrNull(82.0))
    }

    @Test
    fun `a canonical value reads back as the number that was typed`() {
        assertEquals(4.2, MetricKind.DISTANCE.toDisplayValue(4_200), 1e-9)
        assertEquals(5.6, MetricKind.REPORTED_SPEED.toDisplayValue(560), 1e-9)
        assertEquals(2.5, MetricKind.INCLINE.toDisplayValue(25), 1e-9)
        assertEquals(280.0, MetricKind.ESTIMATED_ENERGY.toDisplayValue(280), 1e-9)
    }

    @Test
    fun `each kind carries the decimal count its own precision needs`() {
        assertEquals(2, MetricKind.DISTANCE.displayDecimals)
        assertEquals(2, MetricKind.REPORTED_SPEED.displayDecimals)
        assertEquals(2, MetricKind.AVERAGE_SPEED.displayDecimals)
        assertEquals(1, MetricKind.INCLINE.displayDecimals)
        assertEquals(0, MetricKind.ESTIMATED_ENERGY.displayDecimals)
        assertEquals(0, MetricKind.AVERAGE_PACE.displayDecimals)
        assertEquals(0, MetricKind.STEPS.displayDecimals)
        assertEquals(0, MetricKind.AVERAGE_HEART_RATE.displayDecimals)
        assertEquals(0, MetricKind.ELEVATION_GAIN.displayDecimals)
        assertEquals(0, MetricKind.POWER.displayDecimals)
        assertEquals(0, MetricKind.CADENCE.displayDecimals)
    }

    /**
     * The rule the counts above have to obey: rendering a kind must never claim a precision
     * finer than the unit it is stored in, or the field would offer digits the database
     * cannot keep.
     */
    @Test
    fun `no kind shows more decimals than its stored unit can hold`() {
        MetricKind.entries.forEach { kind ->
            val expressible = generateSequence(1) { it * 10 }
                .first { it >= kind.canonicalPerDisplayUnit }
            assertTrue(
                Math.pow(10.0, kind.displayDecimals.toDouble()).toInt() <= expressible,
                "${kind.id} shows ${kind.displayDecimals} decimals of a ${kind.unit.id}",
            )
        }
    }

    @Test
    fun `a negative or unbounded number never becomes a stored value`() {
        assertNull(MetricKind.DISTANCE.toCanonicalOrNull(-0.1))
        assertNull(MetricKind.DISTANCE.toCanonicalOrNull(1e30))
        assertNull(MetricKind.DISTANCE.toCanonicalOrNull(Double.NaN))
        assertNull(MetricKind.ESTIMATED_ENERGY.toCanonicalOrNull(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `only the six kinds PRD 8-3 marks as editable may reach a V1 form`() {
        assertEquals(
            listOf(
                MetricKind.DISTANCE,
                MetricKind.REPORTED_SPEED,
                MetricKind.AVERAGE_SPEED,
                MetricKind.AVERAGE_PACE,
                MetricKind.ESTIMATED_ENERGY,
                MetricKind.INCLINE,
            ),
            MetricKind.EDITABLE,
        )
    }

    @Test
    fun `an id from a newer build is refused rather than guessed at`() {
        assertEquals(MetricKind.DISTANCE, MetricKind.fromIdOrNull("distance"))
        assertNull(MetricKind.fromIdOrNull("swim_stroke_count"))
    }
}

class ActivityMetricsTest {

    @Test
    fun `a session never carries two measurements of the same kind`() {
        val metrics = ActivityMetrics.of(
            ActivityMetric(MetricKind.DISTANCE, 4_200),
            ActivityMetric(MetricKind.DISTANCE, 5_000),
        )
        assertEquals(1, metrics.values.size)
        assertEquals(5_000, metrics.valueOf(MetricKind.DISTANCE))
    }

    @Test
    fun `measurements always read in the declaration order of their kinds`() {
        val metrics = ActivityMetrics.of(
            ActivityMetric(MetricKind.INCLINE, 25),
            ActivityMetric(MetricKind.DISTANCE, 4_200),
            ActivityMetric(MetricKind.ESTIMATED_ENERGY, 280, MetricSource.EQUIPMENT),
        )
        assertEquals(
            listOf(MetricKind.DISTANCE, MetricKind.ESTIMATED_ENERGY, MetricKind.INCLINE),
            metrics.values.map { it.kind },
        )
    }

    @Test
    fun `an absent measurement has no row rather than a zero`() {
        val metrics = ActivityMetrics.of(ActivityMetric(MetricKind.DISTANCE, 4_200))
        assertNull(metrics.valueOf(MetricKind.ESTIMATED_ENERGY))
        assertNull(metrics[MetricKind.ESTIMATED_ENERGY])
        assertFalse(MetricKind.ESTIMATED_ENERGY in metrics)
        assertTrue(MetricKind.DISTANCE in metrics)
    }

    @Test
    fun `adding replaces the measurement of the same kind and removing leaves no trace`() {
        val metrics = ActivityMetrics.EMPTY
            .with(ActivityMetric(MetricKind.DISTANCE, 4_200))
            .with(ActivityMetric(MetricKind.DISTANCE, 4_300))
            .with(ActivityMetric(MetricKind.INCLINE, 25))
        assertEquals(4_300, metrics.valueOf(MetricKind.DISTANCE))
        assertEquals(2, metrics.values.size)

        val without = metrics.without(MetricKind.DISTANCE)
        assertNull(without.valueOf(MetricKind.DISTANCE))
        assertEquals(1, without.values.size)
    }

    @Test
    fun `an empty set of measurements says so`() {
        assertTrue(ActivityMetrics.EMPTY.isEmpty)
        assertFalse(ActivityMetrics.EMPTY.isNotEmpty)
        assertEquals(ActivityMetrics.EMPTY, ActivityMetrics.of(emptyList()))
    }

    @Test
    fun `a measurement keeps the provenance PRD 11-3 requires`() {
        val metrics = ActivityMetrics.of(
            ActivityMetric(MetricKind.ESTIMATED_ENERGY, 280, MetricSource.EQUIPMENT),
        )
        assertEquals(MetricSource.EQUIPMENT, metrics[MetricKind.ESTIMATED_ENERGY]?.source)
        assertEquals(MetricSource.MANUAL, ActivityMetric(MetricKind.DISTANCE, 1).source)
    }
}
