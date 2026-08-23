package fr.kristenjestin.mue.ui.entry

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A half-width of 195 dp on a 1x screen: roughly a phone with the strip running full bleed. */
private const val HALF_WIDTH = 195f

/**
 * The save echo of PRD 13: the marker's flare, the wave that leaves it and the lift of the
 * graduations standing next to it.
 *
 * All three are shapes over one progress value, so what is worth pinning is that they start
 * and end at rest, peak where they are meant to, and never leak into a frame where no save is
 * running — the draw loop calls them once per graduation, including while the finger is on
 * the ruler.
 */
class RulerEchoTest {

    @Test
    fun `nothing is lit when no save is running`() {
        listOf(0f, 1f).forEach { progress ->
            assertEquals(0f, RulerPhysics.echoFlare(progress))
            assertEquals(0f, RulerPhysics.echoWave(0f, HALF_WIDTH, progress))
            assertEquals(0f, RulerPhysics.echoLift(0f, HALF_WIDTH, progress))
        }
    }

    @Test
    fun `the marker's flare blooms fast and falls slowly`() {
        assertEquals(1f, RulerPhysics.echoFlare(RulerPhysics.ECHO_FLARE_PEAK))
        val rising = RulerPhysics.echoFlare(RulerPhysics.ECHO_FLARE_PEAK / 2f)
        val falling = RulerPhysics.echoFlare(RulerPhysics.ECHO_FLARE_PEAK * 2f)
        assertTrue(rising < 1f && falling < 1f)
        assertTrue(
            falling > rising,
            "expected the fall to be gentler than the rise, got $rising then $falling",
        )
    }

    @Test
    fun `the wave front leaves the marker and slows as it goes`() {
        assertEquals(0f, RulerPhysics.echoFront(0f, HALF_WIDTH))
        val early = RulerPhysics.echoFront(0.25f, HALF_WIDTH)
        val middle = RulerPhysics.echoFront(0.5f, HALF_WIDTH)
        val late = RulerPhysics.echoFront(0.75f, HALF_WIDTH)

        assertTrue(early < middle && middle < late, "the front must keep moving outward")
        assertTrue(
            middle - early > late - middle,
            "expected the front to decelerate, got ${middle - early} then ${late - middle}",
        )
        assertEquals(HALF_WIDTH * RulerPhysics.ECHO_REACH, RulerPhysics.echoFront(1f, HALF_WIDTH))
    }

    @Test
    fun `the wave crests on its front and dies out beyond it`() {
        val progress = 0.4f
        val front = RulerPhysics.echoFront(progress, HALF_WIDTH)
        val onCrest = RulerPhysics.echoWave(front, HALF_WIDTH, progress)
        val behind = RulerPhysics.echoWave(front / 2f, HALF_WIDTH, progress)
        val faraway = RulerPhysics.echoWave(front + HALF_WIDTH, HALF_WIDTH, progress)

        assertTrue(onCrest > behind, "the crest must be the brightest point of the wave")
        assertEquals(0f, faraway)
    }

    @Test
    fun `the wave is symmetric about the marker`() {
        val progress = 0.3f
        val front = RulerPhysics.echoFront(progress, HALF_WIDTH)
        assertEquals(
            RulerPhysics.echoWave(front, HALF_WIDTH, progress),
            RulerPhysics.echoWave(-front, HALF_WIDTH, progress),
        )
    }

    @Test
    fun `the wave weakens as it travels`() {
        val near = RulerPhysics.echoWave(RulerPhysics.echoFront(0.2f, HALF_WIDTH), HALF_WIDTH, 0.2f)
        val far = RulerPhysics.echoWave(RulerPhysics.echoFront(0.9f, HALF_WIDTH), HALF_WIDTH, 0.9f)
        assertTrue(far < near, "expected the wave to weaken, got $near then $far")
    }

    @Test
    fun `the lift is strongest on the marker and gone before the echo ends`() {
        val onMarker = RulerPhysics.echoLift(0f, HALF_WIDTH, 0.05f)
        val beside = RulerPhysics.echoLift(HALF_WIDTH * 0.2f, HALF_WIDTH, 0.05f)

        assertTrue(onMarker > beside && beside > 0f)
        assertEquals(0f, RulerPhysics.echoLift(0f, HALF_WIDTH, RulerPhysics.ECHO_LIFT_END))
        assertEquals(0f, RulerPhysics.echoLift(0f, HALF_WIDTH, 0.9f))
    }

    @Test
    fun `the lift reaches no further than its own span`() {
        val outside = HALF_WIDTH * RulerPhysics.ECHO_LIFT_REACH + 1f
        assertEquals(0f, RulerPhysics.echoLift(outside, HALF_WIDTH, 0.1f))
    }

    @Test
    fun `a strip with no width lights nothing`() {
        assertEquals(0f, RulerPhysics.echoWave(0f, 0f, 0.5f))
        assertEquals(0f, RulerPhysics.echoLift(0f, 0f, 0.1f))
    }
}
