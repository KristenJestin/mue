package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerClock
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.domain.model.equipmentOf
import fr.kristenjestin.mue.domain.model.secondsOf
import fr.kristenjestin.mue.domain.model.startedAt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The clock suite of PRD 14, driven entirely by the injected clock: a reboot, a deep sleep and a
 * manual time change are three ways of moving two numbers, and none of them needs a device.
 *
 * Every assertion names the [ElapsedBasis] as well as the number. A test that only checked the
 * number would pass with the wrong clock answering, which is the one failure this module cannot
 * afford.
 */
class TimerElapsedTest {

    @Test
    fun `both clocks are read together and derive one boot reference`() {
        val instant = TimerInstant(wallMillis = 1_787_000_000_000, elapsedRealtimeMillis = 86_400_000)

        assertEquals(1_786_913_600_000, instant.bootReferenceMillis)
    }

    @Test
    fun `a start opens a segment on both clocks and freezes the calendar reading`() {
        val started = Instant.parse("2026-08-24T23:50:12.900Z").toEpochMilli()
        val clock = FakeTimerClock(wallMillis = started)
        val draft = StartTimerRequest(
            movement = Movement.WALKING,
            equipment = listOf(equipmentOf(EquipmentType.TREADMILL)),
        ).startedAt(TimedDraftId("draft-1"), clock.now(), ZoneId.of("Europe/Paris"))

        assertEquals(TimedDraftStatus.RUNNING, draft.status)
        assertEquals(started, draft.startedAtMillis)
        assertEquals(started, draft.currentSegmentStartedAtMillis)
        assertEquals(
            clock.now().elapsedRealtimeMillis,
            draft.currentSegmentStartedElapsedRealtimeMillis,
        )
        assertEquals(clock.now().bootReferenceMillis, draft.bootReferenceMillis)
        assertEquals(ActivityDuration.ZERO, draft.accumulatedActive)
        assertNull(draft.finishedAtMillis)

        // PRD FR-TIMER-011: the day of the session is the day it started on, Paris time, and it
        // stays that day however long the timer then runs past midnight.
        assertEquals(LocalDate.of(2026, 8, 25), draft.startedOn)
        assertEquals(LocalTime.of(1, 50, 12), draft.startedAtLocalTime)

        clock.advance(hours(2))
        val finished = draft.finishedAt(clock.now())
        assertEquals(LocalDate.of(2026, 8, 25), finished.startedOn)
        assertEquals(LocalTime.of(1, 50, 12), finished.startedAtLocalTime)
    }

    @Test
    fun `a timer started and finished without a pause keeps the seconds it measured`() {
        val clock = FakeTimerClock()
        val draft = startedDraft(clock)

        // PRD 6.3 and the contract: a chronometer that has just started reads `00:00:00`, and
        // that zero is a real reading rather than a fault.
        assertEquals(sound(0, ElapsedBasis.MONOTONIC), TimerElapsed.of(draft, clock.now()))

        clock.advance(seconds(2_538) + 400)
        assertEquals(sound(2_538, ElapsedBasis.MONOTONIC), TimerElapsed.of(draft, clock.now()))

        val finished = draft.finishedAt(clock.now())
        assertEquals(TimedDraftStatus.PENDING_REVIEW, finished.status)
        assertEquals(secondsOf(2_538), finished.accumulatedActive)
        assertEquals(clock.now().wallMillis, finished.finishedAtMillis)
        assertNull(finished.currentSegmentStartedAtMillis)
        assertNull(finished.currentSegmentStartedElapsedRealtimeMillis)

        clock.advance(hours(1))
        assertEquals(sound(2_538, ElapsedBasis.ACCUMULATED), TimerElapsed.of(finished, clock.now()))
    }

    @Test
    fun `several pause and resume cycles count the active periods and nothing else`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        val startedAtMillis = draft.startedAtMillis

        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now())
        assertEquals(secondsOf(600), draft.accumulatedActive)

        clock.advance(hours(1))
        assertEquals(sound(600, ElapsedBasis.ACCUMULATED), TimerElapsed.of(draft, clock.now()))

        draft = draft.resumedAt(clock.now())
        clock.advance(minutes(5))
        assertEquals(sound(900, ElapsedBasis.MONOTONIC), TimerElapsed.of(draft, clock.now()))
        draft = draft.pausedAt(clock.now())

        clock.advance(minutes(30))
        draft = draft.resumedAt(clock.now())
        clock.advance(minutes(2))
        draft = draft.pausedAt(clock.now())

        clock.advance(minutes(45))
        draft = draft.finishedAt(clock.now())
        assertEquals(secondsOf(1_020), draft.accumulatedActive)
        assertEquals(sound(1_020, ElapsedBasis.ACCUMULATED), TimerElapsed.of(draft, clock.now()))

        // PRD FR-TIMER-004: resuming never moves the original start.
        assertEquals(startedAtMillis, draft.startedAtMillis)
    }

    @Test
    fun `a reboot is detected by a shifted boot reference and the civil instants take over`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now()).resumedAt(clock.now())

        clock.advance(minutes(5))
        clock.reboot(downtimeMillis = minutes(2))

        // The monotonic reference restarted at boot, so the segment is measured between the
        // persisted wall instants instead — which counts the two minutes the phone was off.
        assertEquals(sound(1_020, ElapsedBasis.WALL_CLOCK), TimerElapsed.of(draft, clock.now()))
    }

    @Test
    fun `a reboot during a pause costs nothing at all`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now())

        clock.reboot(downtimeMillis = hours(8))
        assertEquals(sound(600, ElapsedBasis.ACCUMULATED), TimerElapsed.of(draft, clock.now()))

        draft = draft.resumedAt(clock.now())
        clock.advance(minutes(1))
        assertEquals(sound(660, ElapsedBasis.MONOTONIC), TimerElapsed.of(draft, clock.now()))
    }

    @Test
    fun `a boot reference that drifted less than ten seconds is still the same boot`() {
        val clock = FakeTimerClock()
        val draft = startedDraft(clock)
        clock.advance(minutes(5))

        clock.setWallClockBy(9_000)
        // The wall clock now disagrees by nine seconds; reading 300 rather than 309 is what
        // proves the monotonic clock answered (PRD FR-TIMER-003, tolerance of 10 s).
        assertEquals(sound(300, ElapsedBasis.MONOTONIC), TimerElapsed.of(draft, clock.now()))

        // Exactly at the tolerance, the gap is no longer inside it.
        assertEquals(10_000L, TimerElapsed.BOOT_REFERENCE_TOLERANCE_MILLIS)
        clock.setWallClockBy(1_000)
        assertEquals(sound(310, ElapsedBasis.WALL_CLOCK), TimerElapsed.of(draft, clock.now()))
    }

    @Test
    fun `a clock moved backwards is caught by the magnitude of the gap`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now()).resumedAt(clock.now())
        clock.advance(minutes(1))

        clock.setWallClockBy(-30_000)
        val measured = TimerElapsed.of(draft, clock.now())

        // Without `abs`, a delta of −30 000 passes `delta < 10_000`, the monotonic reference is
        // trusted through a clock change it should have invalidated, and the answer is 660 s.
        assertEquals(sound(630, ElapsedBasis.WALL_CLOCK), measured)
        assertNotEquals(ElapsedBasis.MONOTONIC, measured.basisOrNull)
    }

    @Test
    fun `a clock moved far backwards is incoherent rather than shorter`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now()).resumedAt(clock.now())
        clock.advance(minutes(1))

        clock.setWallClockBy(-hours(1))
        assertEquals(TimerElapsed.Incoherent(secondsOf(600)), TimerElapsed.of(draft, clock.now()))
    }

    @Test
    fun `a clock moved forwards past the ceiling is incoherent`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now()).resumedAt(clock.now())
        clock.advance(minutes(1))

        clock.setWallClockBy(hours(100))
        assertEquals(TimerElapsed.Incoherent(secondsOf(600)), TimerElapsed.of(draft, clock.now()))
    }

    @Test
    fun `a wall clock moved by more than a century cannot wrap into a plausible duration`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now()).resumedAt(clock.now())
        clock.advance(minutes(1))

        // Chosen so the total is 4 294 968 296 s: narrowed to an `Int` before being judged, it
        // would read as a perfectly ordinary 1 000 s and be shown as `00:16:40`.
        clock.setWallClockBy(4_294_967_636_000)
        assertEquals(TimerElapsed.Incoherent(secondsOf(600)), TimerElapsed.of(draft, clock.now()))
    }

    @Test
    fun `an incoherent reading always carries the accumulated total unchanged`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now()).resumedAt(clock.now())
        clock.advance(minutes(1))
        val accumulated = draft.accumulatedActive

        val shifts = listOf(-hours(1), hours(100), 4_294_967_636_000)
        shifts.forEach { shift ->
            val moved = clock.now().let { it.copy(wallMillis = it.wallMillis + shift) }
            assertEquals(TimerElapsed.Incoherent(accumulated), TimerElapsed.of(draft, moved))
        }

        // FR-TIMER-010: pausing on an incoherent reading writes back the last valid figure and
        // never a correction, so the measured ten minutes survive the clock that moved.
        val movedBack = clock.now().let { it.copy(wallMillis = it.wallMillis - hours(1)) }
        val paused = draft.pausedAt(movedBack)
        assertEquals(accumulated, paused.accumulatedActive)
        assertEquals(TimedDraftStatus.PAUSED, paused.status)
    }

    @Test
    fun `a running draft with no open segment is worth what was already measured`() {
        val clock = FakeTimerClock()
        val draft = startedDraft(clock).copy(
            accumulatedActive = secondsOf(600),
            currentSegmentStartedAtMillis = null,
            currentSegmentStartedElapsedRealtimeMillis = null,
        )
        clock.advance(minutes(5))
        assertEquals(sound(600, ElapsedBasis.ACCUMULATED), TimerElapsed.of(draft, clock.now()))

        // The monotonic reference alone is not enough once the boot reference no longer matches.
        val afterReboot = draft.copy(currentSegmentStartedElapsedRealtimeMillis = 0)
        clock.reboot(downtimeMillis = minutes(1))
        assertEquals(
            sound(600, ElapsedBasis.ACCUMULATED),
            TimerElapsed.of(afterReboot, clock.now()),
        )
    }

    @Test
    fun `a session measured in under a second reads as zero and is sound`() {
        val clock = FakeTimerClock()
        val draft = startedDraft(clock)
        clock.advance(900)
        assertEquals(sound(0, ElapsedBasis.MONOTONIC), TimerElapsed.of(draft, clock.now()))

        // The one-second floor of FR-TIMER-006 is a rule about saving, and it lives on the
        // duration rather than here: nothing about this reading is incoherent.
        assertEquals(ActivityDuration.ZERO, draft.finishedAt(clock.now()).accumulatedActive)
    }

    @Test
    fun `the ceiling is sound to the exact second and incoherent one second later`() {
        val clock = FakeTimerClock()
        var draft = startedDraft(clock)
        clock.advance(minutes(10))
        draft = draft.pausedAt(clock.now()).resumedAt(clock.now())

        clock.advance(seconds(ActivityDuration.SESSION_MAX_SECONDS - 600))
        assertEquals(
            sound(ActivityDuration.SESSION_MAX_SECONDS, ElapsedBasis.MONOTONIC),
            TimerElapsed.of(draft, clock.now()),
        )

        clock.advance(seconds(1))
        assertEquals(TimerElapsed.Incoherent(secondsOf(600)), TimerElapsed.of(draft, clock.now()))
    }

    @Test
    fun `every transition is idempotent under a repeatedly pressed button`() {
        val clock = FakeTimerClock()
        val running = startedDraft(clock)
        clock.advance(minutes(10))

        assertEquals(running, running.resumedAt(clock.now()))

        val paused = running.pausedAt(clock.now())
        clock.advance(minutes(5))
        assertEquals(paused, paused.pausedAt(clock.now()))

        val resumed = paused.resumedAt(clock.now())
        clock.advance(minutes(1))
        assertEquals(resumed, resumed.resumedAt(clock.now()))

        val finished = resumed.finishedAt(clock.now())
        clock.advance(minutes(1))
        assertEquals(finished, finished.finishedAt(clock.now()))
        assertEquals(finished, finished.pausedAt(clock.now()))
        assertEquals(finished, finished.resumedAt(clock.now()))
        assertEquals(secondsOf(660), finished.accumulatedActive)
    }
}

private fun sound(seconds: Int, basis: ElapsedBasis): TimerElapsed =
    TimerElapsed.Sound(secondsOf(seconds), basis)

private fun seconds(count: Int): Long = count * TimerElapsed.MILLIS_PER_SECOND

private fun minutes(count: Int): Long = seconds(count * ActivityDuration.SECONDS_PER_MINUTE)

private fun hours(count: Int): Long = seconds(count * ActivityDuration.SECONDS_PER_HOUR)

private fun startedDraft(clock: FakeTimerClock): TimedActivityDraft =
    StartTimerRequest(Movement.WALKING).startedAt(
        id = TimedDraftId("draft-1"),
        now = clock.now(),
        zone = ZoneId.of("Europe/Paris"),
    )

/**
 * The injected clock of PRD 9. Time passing, a reboot and a hand-set clock differ only in which
 * of the two numbers moves, which is exactly why the reconciliation of FR-TIMER-003 can tell the
 * last two apart from the first.
 */
private class FakeTimerClock(
    private var wallMillis: Long = 1_787_000_000_000,
    private var elapsedRealtimeMillis: Long = 86_400_000,
) : TimerClock {

    override fun now(): TimerInstant = TimerInstant(wallMillis, elapsedRealtimeMillis)

    /** The phone awake, or asleep: `elapsedRealtime` counts through deep sleep too. */
    fun advance(millis: Long) {
        wallMillis += millis
        elapsedRealtimeMillis += millis
    }

    /** The wall clock kept running while the phone was off; the monotonic clock starts again. */
    fun reboot(downtimeMillis: Long) {
        wallMillis += downtimeMillis
        elapsedRealtimeMillis = 4_000
    }

    /** A time set by hand: the wall clock jumps and the monotonic one does not. */
    fun setWallClockBy(millis: Long) {
        wallMillis += millis
    }
}
