package fr.kristenjestin.mue.ui.components

import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.SetMeasure
import fr.kristenjestin.mue.domain.model.TrackingMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic behind the build contract's known layout risk (§7).
 *
 * The prototype lays a set row out as `26px 1fr 1fr 30px`. PRD 15 requires 48 dp on the delete
 * target, which is 18 dp the two value columns have to give up; these tests say where that
 * still works and where it stops.
 */
class MueSetRowMetricsTest {

    /** 390 dp screen − two 28 dp gutters − two 12 dp of card padding. */
    private val rowOn390 = 310.dp

    /** The same row on the narrowest phone the app targets, with the wider card padding. */
    private val rowOn360 = 264.dp

    @Test
    fun `every tracking mode offers exactly two numeric columns`() {
        TrackingMode.entries.forEach { mode ->
            assertEquals(2, MueSetRowMetrics.measuresOf(mode).size, mode.id)
        }
    }

    @Test
    fun `a set row always carries the measure its mode is validated on`() {
        TrackingMode.entries.forEach { mode ->
            val expected = when (mode.primary) {
                SetMeasure.REPETITIONS -> MueSetMeasure.REPETITIONS
                SetMeasure.DURATION -> MueSetMeasure.DURATION
            }
            assertTrue(expected in MueSetRowMetrics.measuresOf(mode), mode.id)
        }
    }

    /** Contract decision 3: the effort column exists only where a column is free. */
    @Test
    fun `per-set effort appears only in the two modes without a load`() {
        val withEffort = TrackingMode.entries.filter {
            MueSetMeasure.EFFORT in MueSetRowMetrics.measuresOf(it)
        }
        assertEquals(listOf(TrackingMode.REPS_ONLY, TrackingMode.DURATION), withEffort)
    }

    @Test
    fun `a load column appears exactly where the mode uses one`() {
        TrackingMode.entries.forEach { mode ->
            assertEquals(
                mode.usesLoad,
                MueSetMeasure.LOAD in MueSetRowMetrics.measuresOf(mode),
                mode.id,
            )
        }
    }

    @Test
    fun `the four columns hold on a 390 dp phone`() {
        assertTrue(MueSetRowMetrics.fitsIn(rowOn390, valueColumns = 2, actionCount = 1))
        assertEquals(107.dp, MueSetRowMetrics.valueColumnWidth(rowOn390, 2, 1))
    }

    @Test
    fun `they still hold on the narrowest phone the app targets`() {
        assertTrue(MueSetRowMetrics.fitsIn(rowOn360, valueColumns = 2, actionCount = 1))
        assertEquals(84.dp, MueSetRowMetrics.valueColumnWidth(rowOn360, 2, 1))
    }

    /**
     * Why `Duplicate last set` is a list action rather than a second column in the row: two
     * 48 dp targets take the value columns under the floor on a 360 dp screen.
     */
    @Test
    fun `a second trailing action does not fit on a 360 dp phone`() {
        assertEquals(56.dp, MueSetRowMetrics.valueColumnWidth(rowOn360, 2, 2))
        assertFalse(MueSetRowMetrics.fitsIn(rowOn360, valueColumns = 2, actionCount = 2))
    }

    @Test
    fun `the minimum row width is the width at which the row just fits`() {
        listOf(1, 2).forEach { actions ->
            val minimum = MueSetRowMetrics.minimumRowWidth(valueColumns = 2, actionCount = actions)
            assertTrue(MueSetRowMetrics.fitsIn(minimum, 2, actions), "$actions actions")
            assertFalse(MueSetRowMetrics.fitsIn(minimum - 1.dp, 2, actions), "$actions actions")
        }
    }

    @Test
    fun `a row narrower than its fixed columns reports no width rather than a negative one`() {
        assertEquals(0.dp, MueSetRowMetrics.valueColumnWidth(40.dp, valueColumns = 2, actionCount = 1))
    }

    @Test
    fun `the delete target is never smaller than the platform minimum`() {
        assertTrue(MueSetRowMetrics.Action >= 48.dp)
    }
}
