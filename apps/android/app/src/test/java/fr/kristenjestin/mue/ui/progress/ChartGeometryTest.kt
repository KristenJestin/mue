package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.ui.progress.ChartGeometry.ValueRange
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPSILON = 1e-4f

class ChartGeometryTest {

    private val fallback = ValueRange(69f, 71f)

    @Test
    fun `an empty series falls back to the given range`() {
        assertEquals(fallback, ChartGeometry.rangeOf(emptyList(), fallback))
    }

    @Test
    fun `a range covers the values with head and foot room`() {
        val range = ChartGeometry.rangeOf(listOf(74.5f, 75.6f, 74.8f), fallback)

        assertTrue(range.min < 74.5f, "the lowest weight must not sit on the floor")
        assertTrue(range.max > 75.6f, "the highest weight must not sit on the ceiling")
        val padding = (75.6f - 74.5f) * ChartGeometry.RANGE_PADDING_FRACTION
        assertEquals(74.5f - padding, range.min, EPSILON)
        assertEquals(75.6f + padding, range.max, EPSILON)
    }

    @Test
    fun `identical weights still produce a span, so a flat line never divides by zero`() {
        val range = ChartGeometry.rangeOf(listOf(74.5f, 74.5f, 74.5f), fallback)

        assertTrue(range.span >= ChartGeometry.MIN_SPAN_KG, "span was ${range.span}")
        assertEquals(74.5f, (range.min + range.max) / 2f, EPSILON)
    }

    @Test
    fun `a single weight is centred in its window`() {
        val range = ChartGeometry.rangeOf(listOf(74.5f), fallback)

        assertTrue(range.span > 0f)
        assertEquals(74.5f, (range.min + range.max) / 2f, EPSILON)
    }

    @Test
    fun `ranges blend linearly`() {
        val blended = ValueRange(70f, 80f).lerpTo(ValueRange(80f, 100f), 0.5f)

        assertEquals(75f, blended.min, EPSILON)
        assertEquals(90f, blended.max, EPSILON)
    }

    @Test
    fun `x positions are proportional to the days between measurements`() {
        val fractions = ChartGeometry.xFractions(
            listOf(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 21),
                LocalDate.of(2026, 8, 31),
            ),
        )

        assertEquals(0f, fractions[0], EPSILON)
        assertEquals(1f / 3f, fractions[1], EPSILON)
        assertEquals(2f / 3f, fractions[2], EPSILON)
        assertEquals(1f, fractions[3], EPSILON)
    }

    @Test
    fun `a gap in the history shows as a gap on the curve`() {
        val fractions = ChartGeometry.xFractions(
            listOf(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 11),
            ),
        )

        assertEquals(0f, fractions[0], EPSILON)
        assertEquals(0.1f, fractions[1], EPSILON)
        assertEquals(1f, fractions[2], EPSILON)
    }

    @Test
    fun `a lone measurement is centred and an empty series has no positions`() {
        assertEquals(
            listOf(ChartGeometry.SINGLE_POINT_FRACTION),
            ChartGeometry.xFractions(listOf(LocalDate.of(2026, 8, 23))),
        )
        assertTrue(ChartGeometry.xFractions(emptyList()).isEmpty())
    }

    @Test
    fun `resampling keeps both ends and interpolates in between`() {
        val samples = ChartGeometry.resample(listOf(0f, 1f), listOf(70f, 80f), sampleCount = 5)

        assertEquals(floatArrayOf(70f, 72.5f, 75f, 77.5f, 80f).toList(), samples.toList())
    }

    @Test
    fun `resampling a single measurement gives a flat array`() {
        val samples = ChartGeometry.resample(listOf(0.5f), listOf(74.5f), sampleCount = 9)

        assertTrue(samples.all { it == 74.5f })
    }

    @Test
    fun `resampling follows every segment of a multi point curve`() {
        val samples = ChartGeometry.resample(
            xFractions = listOf(0f, 0.5f, 1f),
            values = listOf(70f, 80f, 70f),
            sampleCount = 5,
        )

        assertEquals(listOf(70f, 75f, 80f, 75f, 70f), samples.toList())
    }

    @Test
    fun `resampling a flat history never divides by zero`() {
        val samples = ChartGeometry.resample(
            xFractions = listOf(0f, 0.5f, 1f),
            values = listOf(74.5f, 74.5f, 74.5f),
            sampleCount = 17,
        )

        assertTrue(samples.all { it.isFinite() && it == 74.5f })
    }

    @Test
    fun `sampling reads a curve at any fraction and clamps outside it`() {
        val samples = floatArrayOf(70f, 75f, 80f)

        assertEquals(70f, ChartGeometry.sampleAt(samples, 0f), EPSILON)
        assertEquals(72.5f, ChartGeometry.sampleAt(samples, 0.25f), EPSILON)
        assertEquals(80f, ChartGeometry.sampleAt(samples, 1f), EPSILON)
        assertEquals(70f, ChartGeometry.sampleAt(samples, -3f), EPSILON)
        assertEquals(80f, ChartGeometry.sampleAt(samples, 9f), EPSILON)
    }

    @Test
    fun `blending two curves of the same length morphs them halfway`() {
        val blended = ChartGeometry.lerpSamples(
            from = floatArrayOf(70f, 70f, 70f),
            to = floatArrayOf(80f, 90f, 100f),
            fraction = 0.5f,
        )

        assertEquals(listOf(75f, 80f, 85f), blended.toList())
    }

    @Test
    fun `x maps into the padded width`() {
        assertEquals(12f, ChartGeometry.xToPixel(0f, width = 312f, horizontalPadding = 12f), EPSILON)
        assertEquals(300f, ChartGeometry.xToPixel(1f, width = 312f, horizontalPadding = 12f), EPSILON)
        assertEquals(156f, ChartGeometry.xToPixel(0.5f, width = 312f, horizontalPadding = 12f), EPSILON)
    }

    @Test
    fun `the highest weight sits at the top of the canvas`() {
        val range = ValueRange(70f, 80f)

        assertEquals(10f, ChartGeometry.valueToPixel(80f, range, height = 220f, verticalPadding = 10f), EPSILON)
        assertEquals(210f, ChartGeometry.valueToPixel(70f, range, height = 220f, verticalPadding = 10f), EPSILON)
        assertEquals(110f, ChartGeometry.valueToPixel(75f, range, height = 220f, verticalPadding = 10f), EPSILON)
    }

    @Test
    fun `a degenerate range draws down the middle instead of crashing`() {
        val middle = ChartGeometry.valueToPixel(
            value = 74.5f,
            range = ValueRange(74.5f, 74.5f),
            height = 200f,
            verticalPadding = 10f,
        )

        assertEquals(100f, middle, EPSILON)
    }

    @Test
    fun `a tap picks the closest measurement`() {
        val fractions = listOf(0f, 0.5f, 1f)

        assertEquals(
            1,
            ChartGeometry.nearestPointIndex(fractions, touchX = 150f, width = 312f, horizontalPadding = 12f, maxDistance = 96f),
        )
        assertEquals(
            2,
            ChartGeometry.nearestPointIndex(fractions, touchX = 290f, width = 312f, horizontalPadding = 12f, maxDistance = 96f),
        )
    }

    @Test
    fun `a tap far from every measurement selects nothing`() {
        val index = ChartGeometry.nearestPointIndex(
            xFractions = listOf(0f, 1f),
            touchX = 156f,
            width = 312f,
            horizontalPadding = 12f,
            maxDistance = 32f,
        )

        assertNull(index)
    }

    @Test
    fun `hit testing an empty chart selects nothing`() {
        assertNull(
            ChartGeometry.nearestPointIndex(
                xFractions = emptyList(),
                touchX = 100f,
                width = 312f,
                horizontalPadding = 12f,
                maxDistance = 32f,
            ),
        )
    }

    @Test
    fun `the production sample count is odd so the middle is a sample`() {
        assertTrue(ChartGeometry.SAMPLE_COUNT % 2 == 1)
        val samples = ChartGeometry.resample(listOf(0f, 0.5f, 1f), listOf(70f, 80f, 70f))
        assertNotNull(samples)
        assertEquals(80f, samples[ChartGeometry.SAMPLE_COUNT / 2], EPSILON)
    }
}
