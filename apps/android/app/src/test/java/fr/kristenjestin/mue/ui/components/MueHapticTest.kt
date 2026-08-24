package fr.kristenjestin.mue.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two sensations of PRD FR-ENTRY-002 and FR-ENTRY-006 have to stay distinguishable by the
 * thumb on both kinds of actuator, and the tick has to stay short enough to fire five times a
 * second during a drag without turning into a buzz.
 */
class MueHapticTest {

    private fun pulse(haptic: MueHaptic, hasAmplitudeControl: Boolean): MueMotorRequest.Pulse =
        haptic.requestFor(hasAmplitudeControl) as? MueMotorRequest.Pulse
            ?: fail("${haptic.name} is not a single pulse here")

    // A linear actuator reaches its target within a frame, so strength separates the two.

    @Test
    fun `on a linear motor the tick is lighter and shorter than the confirmation`() {
        val tick = pulse(MueHaptic.Tick, hasAmplitudeControl = true)
        val confirm = pulse(MueHaptic.Confirm, hasAmplitudeControl = true)
        assertTrue(
            tick.durationMillis < confirm.durationMillis,
            "the graduation tick must not outlast the save",
        )
        assertTrue(tick.amplitude < confirm.amplitude)
    }

    /** Half a kilogram of travel can pass under the finger five times a second. */
    @Test
    fun `on a linear motor the tick is over well inside a frame`() {
        val tick = pulse(MueHaptic.Tick, hasAmplitudeControl = true)
        assertTrue(tick.durationMillis <= 16L, "got ${tick.durationMillis} ms")
    }

    @Test
    fun `a linear motor is asked for a real amplitude`() {
        MueHaptic.entries.forEach {
            val request = pulse(it, hasAmplitudeControl = true)
            assertTrue(request.amplitude in 1..255, "${it.name} asks for ${request.amplitude}")
        }
    }

    // An eccentric mass has to spin up before anything reaches the hand, and ignores amplitude.

    /**
     * Measured on a Galaxy A71, whose motor reports no amplitude control: the system's own
     * touch feedback runs 45 ms. Below about twenty the mass never leaves the floor.
     */
    @Test
    fun `on a plain motor the tick lasts long enough to be felt`() {
        val tick = pulse(MueHaptic.Tick, hasAmplitudeControl = false)
        assertTrue(tick.durationMillis >= 20L, "got ${tick.durationMillis} ms, too short to spin up")
    }

    @Test
    fun `on a plain motor the tick still clears the next graduation`() {
        val tick = pulse(MueHaptic.Tick, hasAmplitudeControl = false)
        val gapBetweenGraduations = 1000L / 5
        assertTrue(
            tick.durationMillis * 4 < gapBetweenGraduations,
            "got ${tick.durationMillis} ms, five a second would read as a buzz",
        )
    }

    /** Duration alone cannot carry the difference here, so the confirmation changes shape. */
    @Test
    fun `on a plain motor the confirmation is a pattern and the tick is not`() {
        assertTrue(MueHaptic.Tick.requestFor(hasAmplitudeControl = false) is MueMotorRequest.Pulse)
        val confirm = MueHaptic.Confirm.requestFor(hasAmplitudeControl = false)
        assertTrue(confirm is MueMotorRequest.Pattern, "got $confirm")
    }

    @Test
    fun `the confirmation pattern is a flare and a longer settle`() {
        val confirm = MueHaptic.Confirm.requestFor(hasAmplitudeControl = false)
        val timings = (confirm as MueMotorRequest.Pattern).timingsMillis
        assertEquals(0L, timings.first(), "a waveform starts on an off step")
        assertEquals(4, timings.size, "two beats means four steps")
        val (flare, gap, settle) = Triple(timings[1], timings[2], timings[3])
        assertTrue(flare >= 20L, "the flare must still spin the mass up, got $flare ms")
        assertTrue(settle >= 20L, "the settle must still spin the mass up, got $settle ms")
        // Android's own double-click fallback leaves 100 ms; an eccentric mass needs about
        // that long to wind down before a second beat reads as separate.
        assertTrue(gap >= 80L, "the two beats would smear into one, gap $gap ms")
        assertTrue(timings.sum() <= 250L, "a save must not drone, got ${timings.sum()} ms")
    }

    @Test
    fun `a motor without amplitude control is left to pick its own strength`() {
        MueHaptic.entries.forEach {
            val amplitude = when (val request = it.requestFor(hasAmplitudeControl = false)) {
                is MueMotorRequest.Pulse -> request.amplitude
                is MueMotorRequest.Pattern -> request.amplitude
            }
            assertEquals(DEFAULT_AMPLITUDE, amplitude, "${it.name} names a strength")
        }
    }
}
