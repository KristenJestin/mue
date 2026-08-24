package fr.kristenjestin.mue.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityDurationTest {

    @Test
    fun `one minute is the shortest session PRD FR-ACTIVITY-005 accepts`() {
        assertEquals(60, ActivityDuration.SESSION_MIN_SECONDS)
        assertNotNull(ActivityDuration.ofSessionOrNull(0, 1))
        assertNull(ActivityDuration.ofSessionOrNull(0, 0))
    }

    @Test
    fun `ninety-nine hours and fifty-nine minutes is the longest`() {
        assertEquals(359_940, ActivityDuration.SESSION_MAX_SECONDS)
        val longest = assertNotNull(ActivityDuration.ofSessionOrNull(99, 59))
        assertEquals(359_940, longest.seconds)
        assertNull(ActivityDuration.ofSessionOrNull(100, 0))
        assertNull(ActivityDuration.ofSessionOrNull(99, 60))
    }

    @Test
    fun `a session length always splits back into the hours and minutes it was typed as`() {
        val longest = secondsOf(359_940)
        assertEquals(99, longest.hoursPart)
        assertEquals(59, longest.minutesPart)
        assertEquals(0, longest.secondsPart)
    }

    @Test
    fun `a set duration reads as minutes and seconds, as PRD 11-4 shows it`() {
        val plank = secondsOf(90)
        assertEquals(1, plank.totalMinutes)
        assertEquals(30, plank.secondsPart)
        assertEquals(0, secondsOf(45).totalMinutes)
        assertEquals(45, secondsOf(45).secondsPart)
    }

    @Test
    fun `zero is a real daily total but a negative span is not a duration`() {
        assertEquals(0, ActivityDuration.ZERO.seconds)
        assertNotNull(ActivityDuration.ofSecondsOrNull(0))
        assertNull(ActivityDuration.ofSecondsOrNull(-1))
        assertNull(ActivityDuration.ofHoursAndMinutesOrNull(-1, 0))
        assertNull(ActivityDuration.ofHoursAndMinutesOrNull(0, -1))
    }

    @Test
    fun `an hours field big enough to overflow an integer is refused rather than wrapped`() {
        assertNull(ActivityDuration.ofHoursAndMinutesOrNull(Int.MAX_VALUE, 0))
    }

    @Test
    fun `only a session-length span answers to the session bounds`() {
        assertFalse(secondsOf(59).isSessionLength)
        assertTrue(secondsOf(60).isSessionLength)
        assertTrue(secondsOf(359_940).isSessionLength)
        assertFalse(secondsOf(359_941).isSessionLength)
    }

    @Test
    fun `a timed session goes down to one second while the manual floor stays at a minute`() {
        assertEquals(1, ActivityDuration.TIMED_MIN_SECONDS)
        assertEquals(60, ActivityDuration.SESSION_MIN_SECONDS)
        assertEquals(40, ActivityDuration.ofTimedSessionOrNull(0, 0, 40)?.seconds)
        assertNull(ActivityDuration.ofSessionOrNull(0, 0))
        assertNull(ActivityDuration.ofTimedSessionOrNull(0, 0, 0))
        assertTrue(secondsOf(1).isTimedSessionLength)
        assertFalse(secondsOf(1).isSessionLength)
    }

    @Test
    fun `both modes of entry stop at the same ceiling`() {
        assertEquals(359_940, ActivityDuration.ofTimedSessionOrNull(99, 59, 0)?.seconds)
        assertNull(ActivityDuration.ofTimedSessionOrNull(99, 59, 1))
        assertNull(ActivityDuration.ofHoursMinutesAndSecondsOrNull(0, 0, -1))
        assertEquals(3_661, ActivityDuration.ofHoursMinutesAndSecondsOrNull(1, 1, 1)?.seconds)
        assertNull(ActivityDuration.ofHoursMinutesAndSecondsOrNull(Int.MAX_VALUE, 0, 0))
    }

    @Test
    fun `a running total is judged in Long and never wraps into the valid range`() {
        assertEquals(0, ActivityDuration.ofElapsedOrNull(0L)?.seconds)
        assertEquals(359_940, ActivityDuration.ofElapsedOrNull(359_940L)?.seconds)
        assertNull(ActivityDuration.ofElapsedOrNull(359_941L))
        assertNull(ActivityDuration.ofElapsedOrNull(-1L))

        // Narrowed first, this one would read as a plausible 1_000 s inside the valid range.
        val wraps = 4_294_967_296L + 1_000L
        assertEquals(1_000, wraps.toInt())
        assertNull(ActivityDuration.ofElapsedOrNull(wraps))
    }

    @Test
    fun `durations add up and sum from nothing`() {
        assertEquals(secondsOf(3_600), minutesOf(45) + minutesOf(15))
        assertEquals(ActivityDuration.ZERO, ActivityDuration.sum(emptyList()))
        assertEquals(minutesOf(100), ActivityDuration.sum(listOf(minutesOf(45), minutesOf(55))))
    }
}

class LoadTest {

    @Test
    fun `the plate and the step of a gym are exact in grams`() {
        assertEquals(60_000, loadOf(60.0).grams)
        assertEquals(62_500, loadOf(62.5).grams)
        assertEquals(1_250, loadOf(1.25).grams)
        assertEquals(20_500, loadOf(20.5).grams)
    }

    @Test
    fun `a third decimal rounds to the nearest ten grams instead of being refused`() {
        assertEquals(62_570, loadOf(62.567).grams)
        assertEquals(62_560, loadOf(62.564).grams)
        assertEquals(10, loadOf(0.005).grams)
    }

    @Test
    fun `a load that rounds away to nothing is not a load at all`() {
        assertNull(Load.ofKilogramsOrNull(0.004))
        assertNull(Load.ofKilogramsOrNull(0.0))
        assertNull(Load.ofKilogramsOrNull(-1.0))
    }

    @Test
    fun `a text field offering an absurd number cannot overflow the stored grams`() {
        assertNotNull(Load.ofKilogramsOrNull(1_000.0))
        assertNull(Load.ofKilogramsOrNull(1_000.01))
        assertNull(Load.ofKilogramsOrNull(1e30))
        assertNull(Load.ofKilogramsOrNull(Double.NaN))
        assertNull(Load.ofKilogramsOrNull(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `grams read back as the kilograms they were typed as`() {
        assertEquals(62.5, loadOf(62.5).kilograms, 0.0)
        assertEquals(60.0, loadOf(60.0).kilograms, 0.0)
    }

    @Test
    fun `a stored gram count is trusted as long as it is positive and in range`() {
        assertEquals(60_000, Load.ofGramsOrNull(60_000)?.grams)
        assertNull(Load.ofGramsOrNull(0))
        assertNull(Load.ofGramsOrNull(-10))
        assertNull(Load.ofGramsOrNull(Load.MAX_GRAMS + 1))
    }

    @Test
    fun `loads compare by weight`() {
        assertTrue(loadOf(60.0) < loadOf(62.5))
    }
}

class PerceivedEffortTest {

    @Test
    fun `the scale of PRD 8-2 runs from one to ten and no further`() {
        assertNull(PerceivedEffort.ofOrNull(0))
        assertEquals(1, PerceivedEffort.ofOrNull(1)?.value)
        assertEquals(10, PerceivedEffort.ofOrNull(10)?.value)
        assertNull(PerceivedEffort.ofOrNull(11))
        assertNull(PerceivedEffort.ofOrNull(-1))
    }
}
