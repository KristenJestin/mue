package fr.kristenjestin.mue.ui.timer

import fr.kristenjestin.mue.domain.logic.TimerElapsed
import fr.kristenjestin.mue.domain.model.TimerClock
import fr.kristenjestin.mue.domain.model.TimerInstant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The one-second heartbeat of the running chronometer (PRD FR-TIMER-003).
 *
 * It is **presentation only**. Nothing here writes anything: the elapsed value is derived from
 * the persisted instants at every beat, so a screen that is not on show simply stops asking. The
 * flow is cold, and the ViewModel shares it under `SharingStarted.WhileSubscribed`, which is what
 * makes FR-TIMER-003's "the second-by-second rhythm stops in the background and resumes on
 * return, recomputed rather than caught up" fall out of the plumbing rather than be arranged.
 *
 * Hand-written rather than `kotlinx.coroutines.channels.ticker`, which is obsolete, and — more
 * to the point — would be wrong here: a bare `delay(1000)` loop pays the cost of each iteration
 * *after* the delay, so the beats drift later and later, and once the drift passes a second two
 * digits change between two emissions. A chronometer that skips from `00:00:07` to `00:00:09` is
 * the one failure a stopwatch may not have. Aligning every beat to a whole second of the clock
 * bounds the interval at one second no matter how long the work in between took.
 *
 * The alignment reads the **monotonic** clock, not the wall clock: a wall clock corrected
 * backwards by an hour would otherwise leave the next beat an hour away, freezing the display of
 * a timer that is still running perfectly well. Which second boundary the beats land on does not
 * matter — the segment did not start on one either — only that consecutive beats are one second
 * apart.
 */
class TimerTicker(private val clock: TimerClock) {

    /**
     * Emits the current instant immediately, then on every whole second after it.
     *
     * The instant is carried rather than left for the collector to read, so the pair of clocks
     * the display is computed from is the very pair the beat was scheduled on — the contract's
     * rule that both clocks are read together and never through two calls.
     */
    val instants: Flow<TimerInstant> = flow {
        while (true) {
            val now = clock.now()
            emit(now)
            delay(untilNextSecond(now))
        }
    }

    companion object {
        /**
         * How long until the monotonic clock reaches its next whole second — always between one
         * millisecond and one full second, so a beat that lands exactly on a boundary waits a
         * whole second rather than spinning.
         */
        fun untilNextSecond(now: TimerInstant): Long = TimerElapsed.MILLIS_PER_SECOND -
            now.elapsedRealtimeMillis.mod(TimerElapsed.MILLIS_PER_SECOND)
    }
}
