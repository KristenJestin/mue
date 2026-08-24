package fr.kristenjestin.mue.ui.timer

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.domain.model.secondsOf
import fr.kristenjestin.mue.ui.profile.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ZONE: ZoneId = ZoneId.of("UTC")

/** A treadmill walk, PRD 6.2's own worked example of something worth timing. */
private val TREADMILL_WALK = StartTimerRequest(
    movement = Movement.WALKING,
    environment = ActivityEnvironment.INDOOR,
    equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
)

/**
 * The Activity Timer's state holder (PRD FR-TIMER-001 to 011), on a fake clock and a fake store.
 *
 * Everything here is driven by two numbers and the test scheduler's own virtual time: the clocks
 * move because the test moved them, and a beat happens because virtual time reached it. Nothing
 * sleeps, nothing polls, and the second boundary is a value rather than a wait.
 *
 * `advanceUntilIdle` appears nowhere on purpose. A running timer schedules a beat for ever, which
 * is the whole point of it, and a test that asked the scheduler to run until nothing was left
 * would never come back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    // region starting (FR-TIMER-001)

    @Test
    fun `starting writes the draft before the chronometer is ever drawn`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()

        val stored = assertNotNull(timer.repository.live)
        assertEquals(TimedDraftStatus.RUNNING, stored.status)
        assertEquals(1, timer.repository.writes)
        assertEquals(stored.id, timer.state.timerToOpen)
        assertNull(timer.state.notice)
    }

    @Test
    fun `the chronometer starts at zero and says so out loud`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()

        assertEquals(ActivityDuration.ZERO, timer.live.elapsed)
        assertEquals("00:00:00", timer.live.elapsedText)
        assertEquals("Active, 0 seconds", timer.live.elapsedDescription)
        assertEquals(TimerMessages.ACTIVE, timer.live.statusLabel)
        assertEquals(TimerMessages.PAUSE, timer.live.primaryActionLabel)
    }

    @Test
    fun `the axes and the frozen calendar reading reach the draft`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK.copy(customMovementName = "Morning loop"))
        runCurrent()

        val stored = assertNotNull(timer.repository.live)
        assertEquals(Movement.WALKING, stored.movement)
        assertEquals(ActivityEnvironment.INDOOR, stored.environment)
        assertEquals(listOf(EquipmentType.TREADMILL), stored.equipment.map { it.equipmentType })
        assertEquals(WALL_ORIGIN, stored.startedAtMillis)
        assertEquals(LocalDate.of(2026, 8, 24), stored.startedOn)
        assertEquals(LocalTime.of(18, 32, 47), stored.startedAtLocalTime)

        // FR-TIMER-005: the screen promises the minute the form will prefill, seconds and all.
        assertEquals("Started at 18:32", timer.live.startedAtText)
        assertEquals("Morning loop", timer.live.activityLabel)
    }

    @Test
    fun `the caller opens the timer once and only once`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        assertNotNull(timer.state.timerToOpen)

        timer.model.onTimerOpened()
        runCurrent()
        assertNull(timer.state.timerToOpen)
    }

    // endregion

    // region a timer already running (FR-TIMER-002)

    @Test
    fun `a second start opens the first timer and announces it`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        val first = assertNotNull(timer.repository.live)
        timer.model.onTimerOpened()

        timer.model.start(StartTimerRequest(Movement.RUNNING))
        runCurrent()

        assertEquals(1, timer.repository.all.size)
        assertEquals(first.id, timer.repository.live?.id)
        assertEquals(first.id, timer.state.timerToOpen)
        assertEquals(TimerNotice.ALREADY_IN_PROGRESS, timer.state.notice)
        assertEquals("An activity is already in progress.", timer.state.noticeMessage)
    }

    @Test
    fun `a refusal is a notice and never a failure`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.model.start(StartTimerRequest(Movement.RUNNING))
        runCurrent()

        // The timer on show is still the one that was measuring, untouched by the refusal.
        assertEquals(Movement.WALKING, timer.live.draft.movement)
        assertEquals(TimedDraftStatus.RUNNING, timer.live.status)
        assertFalse(timer.state.isMutating)
    }

    // endregion

    // region pause and resume (FR-TIMER-004)

    @Test
    fun `pausing freezes the value and names the opposite action`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.clock.advance(minutes(12))
        timer.model.pause()
        runCurrent()

        assertEquals(TimedDraftStatus.PAUSED, timer.live.status)
        assertEquals(secondsOf(12 * 60), timer.live.elapsed)
        assertEquals("00:12:00", timer.live.elapsedText)
        assertEquals(TimerMessages.PAUSED, timer.live.statusLabel)
        assertEquals(TimerMessages.PAUSED, timer.live.bannerValue)
        assertEquals(TimerMessages.RESUME, timer.live.primaryActionLabel)
    }

    @Test
    fun `a paused stretch never counts, and the start time never moves`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.clock.advance(minutes(10))
        timer.model.pause()
        runCurrent()

        timer.clock.advance(hours(3))
        timer.model.resume()
        runCurrent()
        timer.clock.advance(minutes(5))
        timer.model.pause()
        runCurrent()

        assertEquals(secondsOf(15 * 60), timer.live.elapsed)
        assertEquals(WALL_ORIGIN, timer.live.draft.startedAtMillis)
        assertEquals(LocalTime.of(18, 32, 47), timer.live.draft.startedAtLocalTime)
    }

    @Test
    fun `resuming reopens the banner's moving value`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.clock.advance(minutes(1))
        timer.model.pause()
        runCurrent()
        timer.model.resume()
        runCurrent()

        assertEquals(TimedDraftStatus.RUNNING, timer.live.status)
        assertEquals("00:01:00", timer.live.bannerValue)
    }

    // endregion

    // region finishing (FR-TIMER-005) and discarding (FR-TIMER-009)

    @Test
    fun `finishing stops the timer for good and opens the review`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.clock.advance(minutes(42) + seconds(18))
        timer.model.finish()
        runCurrent()

        val finished = assertNotNull(timer.repository.all.singleOrNull())
        assertEquals(TimedDraftStatus.PENDING_REVIEW, finished.status)
        assertEquals(secondsOf(42 * 60 + 18), finished.accumulatedActive)
        assertEquals(finished.id, timer.state.reviewToOpen)

        // A draft in review is no longer the live timer, so the banner goes with it (PRD 6.4).
        assertNull(timer.state.timer)

        timer.model.onReviewOpened()
        runCurrent()
        assertNull(timer.state.reviewToOpen)
    }

    @Test
    fun `discarding asks first, and Keep timer changes nothing`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.clock.advance(minutes(4))

        timer.model.requestDiscard()
        runCurrent()
        assertTrue(timer.state.discardConfirmationVisible)

        timer.model.cancelDiscard()
        runCurrent()
        assertFalse(timer.state.discardConfirmationVisible)
        assertEquals(1, timer.repository.all.size)
    }

    @Test
    fun `discarding removes the timer and the surfaces that showed it`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.model.requestDiscard()
        timer.model.discard()
        runCurrent()

        assertTrue(timer.repository.all.isEmpty())
        assertNull(timer.state.timer)
        assertFalse(timer.state.hasTimer)
        assertFalse(timer.state.discardConfirmationVisible)
    }

    // endregion

    // region a button pressed twice (PRD 12)

    @Test
    fun `three presses on Pause collapse into one write`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        val afterStart = timer.repository.writes

        timer.repository.holdWrites()
        timer.model.pause()
        timer.model.pause()
        timer.model.pause()
        runCurrent()
        assertTrue(timer.state.isMutating)

        timer.repository.releaseWrites()
        runCurrent()

        assertEquals(afterStart + 1, timer.repository.writes)
        assertFalse(timer.state.isMutating)
        assertEquals(TimedDraftStatus.PAUSED, timer.live.status)
    }

    @Test
    fun `two presses on Start timer create one timer`() = timerTest { timer ->
        timer.repository.holdWrites()
        timer.model.start(TREADMILL_WALK)
        timer.model.start(TREADMILL_WALK)
        runCurrent()

        timer.repository.releaseWrites()
        runCurrent()

        assertEquals(1, timer.repository.all.size)
        assertEquals(1, timer.repository.writes)
    }

    @Test
    fun `a press on a timer that is gone does nothing`() = timerTest { timer ->
        timer.model.pause()
        timer.model.finish()
        timer.model.discard()
        runCurrent()

        assertEquals(0, timer.repository.writes)
        assertNull(timer.state.reviewToOpen)
    }

    // endregion

    // region the per-second beat (FR-TIMER-003)

    @Test
    fun `the ticker beats on the whole second, however it started`() = runTest {
        val clock = FakeTimerClock { testScheduler.currentTime }
        val beats = mutableListOf<TimerInstant>()
        backgroundScope.launch { TimerTicker(clock).instants.collect { beats += it } }
        runCurrent()

        // The monotonic clock starts 400 ms into a second, so the first beat is where the caller
        // asked and every one after it lands on a boundary.
        assertEquals(listOf(ELAPSED_ORIGIN), beats.map { it.elapsedRealtimeMillis })
        tick(600)
        tick(1_000)
        tick(1_000)

        assertEquals(
            listOf(ELAPSED_ORIGIN, 5_001_000L, 5_002_000L, 5_003_000L),
            beats.map { it.elapsedRealtimeMillis },
        )
    }

    @Test
    fun `the ticker stops as soon as nobody is listening`() = runTest {
        val clock = FakeTimerClock { testScheduler.currentTime }
        val beats = mutableListOf<TimerInstant>()
        val job = backgroundScope.launch { TimerTicker(clock).instants.collect { beats += it } }
        runCurrent()
        tick(2_600)
        val heard = beats.size

        job.cancel()
        tick(30_000)

        assertEquals(heard, beats.size)
    }

    @Test
    fun `the wait to the next second is a whole second at worst and never nothing`() {
        assertEquals(600L, TimerTicker.untilNextSecond(instantAtElapsed(5_000_400)))
        assertEquals(1L, TimerTicker.untilNextSecond(instantAtElapsed(5_000_999)))
        assertEquals(1_000L, TimerTicker.untilNextSecond(instantAtElapsed(5_000_000)))
    }

    @Test
    fun `the display advances one digit at a time and skips none`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        assertEquals("00:00:00", timer.live.elapsedText)

        tick(600)
        assertEquals("00:00:00", timer.live.elapsedText)
        tick(1_000)
        assertEquals("00:00:01", timer.live.elapsedText)
        tick(3_000)
        assertEquals("00:00:04", timer.live.elapsedText)

        assertEquals(
            listOf("00:00:00", "00:00:01", "00:00:02", "00:00:03", "00:00:04"),
            timer.states.mapNotNull { it.timer?.elapsedText }.distinct(),
        )
    }

    @Test
    fun `no beat is spent on a paused timer`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        tick(2_600)
        timer.model.pause()
        runCurrent()

        val readsWhilePaused = timer.clock.reads
        tick(30_000)

        assertEquals(readsWhilePaused, timer.clock.reads)
        assertEquals("00:00:02", timer.live.elapsedText)
    }

    @Test
    fun `a beat never writes to the database`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        val afterStart = timer.repository.writes

        tick(30_000)

        assertEquals(afterStart, timer.repository.writes)
        assertEquals("00:00:29", timer.live.elapsedText)
    }

    @Test
    fun `the beat stops with the last surface that was watching`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        tick(2_600)

        timer.subscription.cancel()
        // Past `WhileSubscribed`'s grace, after which the shared flow and its beat are gone.
        tick(10_000)
        val readsAfterLeaving = timer.clock.reads
        tick(60_000)

        assertEquals(readsAfterLeaving, timer.clock.reads)
    }

    // endregion

    // region an incoherent reading (FR-TIMER-010)

    @Test
    fun `an incoherent reading pauses the timer and asks for a check`() = timerTest { timer ->
        val incoherent = runUntilIncoherent(timer)

        assertEquals(TimedDraftStatus.PAUSED, incoherent.status)
        assertEquals(TimerNotice.CHECK_ACTIVITY_TIME, timer.state.notice)
        assertEquals("Check activity time", timer.state.noticeMessage)
    }

    @Test
    fun `an incoherent reading keeps the last valid figure rather than correcting it`() =
        timerTest { timer ->
            val incoherent = runUntilIncoherent(timer)

            // Ten minutes were honestly measured before the clock moved; they are what is left.
            assertEquals(secondsOf(10 * 60), incoherent.accumulatedActive)
            assertEquals(secondsOf(10 * 60), timer.live.elapsed)
            assertEquals("00:10:00", timer.live.elapsedText)
            assertFalse(timer.live.isIncoherent)
        }

    @Test
    fun `an incoherent reading never shrinks what was measured and never resets it`() =
        timerTest { timer ->
            val incoherent = runUntilIncoherent(timer)

            assertTrue(incoherent.accumulatedActive > ActivityDuration.ZERO)
            assertEquals(secondsOf(10 * 60), timer.repository.live?.accumulatedActive)
            // Four writes in all — start, pause, resume, auto-pause — and none on any beat.
            assertEquals(4, timer.repository.writes)
        }

    @Test
    fun `the timer stays finishable after an incoherent reading`() = timerTest { timer ->
        runUntilIncoherent(timer)

        timer.model.finish()
        runCurrent()

        val finished = assertNotNull(timer.repository.all.singleOrNull())
        assertEquals(TimedDraftStatus.PENDING_REVIEW, finished.status)
        assertEquals(secondsOf(10 * 60), finished.accumulatedActive)
        assertEquals(finished.id, timer.state.reviewToOpen)
    }

    // endregion

    // region the notice (PRD 6.4)

    @Test
    fun `the notice goes as soon as the user does anything else`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.model.start(StartTimerRequest(Movement.RUNNING))
        runCurrent()
        assertEquals(TimerNotice.ALREADY_IN_PROGRESS, timer.state.notice)

        timer.model.pause()
        runCurrent()

        assertNull(timer.state.notice)
        assertNull(timer.state.noticeMessage)
    }

    @Test
    fun `a surface may retire the notice once it has been read`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.model.start(StartTimerRequest(Movement.RUNNING))
        runCurrent()

        timer.model.dismissNotice()
        runCurrent()

        assertNull(timer.state.notice)
    }

    @Test
    fun `the notice is never persisted with the timer`() = timerTest { timer ->
        timer.model.start(TREADMILL_WALK)
        runCurrent()
        timer.model.start(StartTimerRequest(Movement.RUNNING))
        runCurrent()

        assertNull(timer.repository.live?.reviewFormState)
        assertEquals(0, timer.repository.live?.reviewFormSchemaVersion)
    }

    // endregion

    // region no timer at all

    @Test
    fun `no timer is not a timer worth zero`() = timerTest { timer ->
        assertNull(timer.state.timer)
        assertFalse(timer.state.hasTimer)
        assertFalse(timer.state.isLoading)
        assertFalse(timer.state.isMutating)
        assertTrue(TimerUiState.LOADING.isLoading)
        assertNull(TimerUiState.LOADING.timer)
    }

    // endregion

    // region harness

    /** One timer under test: the ViewModel, what it writes to, and what it reads the time from. */
    private class Harness(
        val model: TimerViewModel,
        val repository: FakeTimedActivityRepository,
        val clock: FakeTimerClock,
        val states: List<TimerUiState>,
        val subscription: Job,
    ) {
        val state: TimerUiState get() = model.uiState.value

        val live: LiveTimerUiState get() = assertNotNull(state.timer, "no timer on show")
    }

    /**
     * Ten minutes measured, then the wall clock set back an hour while a segment is open.
     *
     * The backwards jump moves the boot reference well past FR-TIMER-003's ten-second tolerance,
     * so the monotonic clock is set aside and the open segment is measured between civil instants
     * that now run backwards — a negative total, which is FR-TIMER-010's own definition of an
     * incoherent duration. Returns the draft as the auto-pause left it.
     */
    private fun TestScope.runUntilIncoherent(timer: Harness) = with(timer) {
        model.start(TREADMILL_WALK)
        runCurrent()
        clock.advance(minutes(10))
        model.pause()
        runCurrent()
        model.resume()
        runCurrent()
        clock.advance(minutes(1))
        clock.jumpWallClock(-hours(1))
        tick(2_000)
        assertNotNull(repository.live)
    }

    private fun timerTest(body: suspend TestScope.(Harness) -> Unit) = runTest {
        val repository = FakeTimedActivityRepository()
        val clock = FakeTimerClock { testScheduler.currentTime }
        val model = TimerViewModel(
            timers = repository,
            clock = clock,
            zone = { ZONE },
            locale = { Locale.UK },
        )
        val states = mutableListOf<TimerUiState>()
        val subscription = backgroundScope.launch { model.uiState.collect { states += it } }
        runCurrent()
        body(Harness(model, repository, clock, states, subscription))
    }

    // endregion
}

/** Virtual time and the phone's clocks move together, then whatever was due is run. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.tick(millis: Long) {
    advanceTimeBy(millis)
    runCurrent()
}

private fun instantAtElapsed(elapsedRealtimeMillis: Long): TimerInstant =
    TimerInstant(wallMillis = WALL_ORIGIN, elapsedRealtimeMillis = elapsedRealtimeMillis)

private fun seconds(count: Int): Long = count * 1_000L

private fun minutes(count: Int): Long = seconds(count * 60)

private fun hours(count: Int): Long = minutes(count * 60)
