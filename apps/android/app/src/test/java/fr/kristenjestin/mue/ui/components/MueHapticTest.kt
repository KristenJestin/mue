package fr.kristenjestin.mue.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two sensations of PRD FR-ENTRY-002 and FR-ENTRY-006 have to stay distinguishable by
 * the thumb, and the tick has to stay short enough to fire five times a second during a drag
 * without turning into a buzz.
 */
class MueHapticTest {

    @Test
    fun `the tick is lighter and shorter than the confirmation`() {
        assertTrue(
            MueHaptic.Tick.fallbackDurationMillis < MueHaptic.Confirm.fallbackDurationMillis,
            "the graduation tick must not outlast the save",
        )
        assertTrue(MueHaptic.Tick.fallbackAmplitude < MueHaptic.Confirm.fallbackAmplitude)
    }

    /** Half a kilogram of travel can pass under the finger five times a second. */
    @Test
    fun `the tick is over well inside a frame`() {
        assertTrue(
            MueHaptic.Tick.fallbackDurationMillis <= 16L,
            "got ${MueHaptic.Tick.fallbackDurationMillis} ms",
        )
    }

    /** Two ticks must never run into each other and read as one long buzz. */
    @Test
    fun `two consecutive ticks stay separable`() {
        val gapBetweenGraduations = 1000L / 5
        assertTrue(MueHaptic.Tick.fallbackDurationMillis * 4 < gapBetweenGraduations)
    }

    @Test
    fun `every amplitude is a legal motor request`() {
        MueHaptic.entries.forEach {
            assertTrue(
                it.fallbackAmplitude in 1..255,
                "${it.name} asks for ${it.fallbackAmplitude}",
            )
        }
    }

    @Test
    fun `a motor without amplitude control is left to pick its own strength`() {
        MueHaptic.entries.forEach {
            assertEquals(MueHaptic.DEFAULT_AMPLITUDE, it.amplitude(hasAmplitudeControl = false))
            assertEquals(it.fallbackAmplitude, it.amplitude(hasAmplitudeControl = true))
        }
    }
}
