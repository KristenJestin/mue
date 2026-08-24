package fr.kristenjestin.mue.data.repository

import android.os.SystemClock
import fr.kristenjestin.mue.domain.model.TimerClock
import fr.kristenjestin.mue.domain.model.TimerInstant

/**
 * The two platform clocks of PRD FR-TIMER-003, and the only place in the app that reads either.
 *
 * `elapsedRealtime()` and never `uptimeMillis()`: uptime freezes in deep sleep, so a timer built
 * on it would stop the moment the screen went off and the bug would only ever surface on a real
 * device, minutes later. `nanoTime()` and `Instant.now()` are out for the same kind of reason —
 * neither survives the process, which is what a timer has to do.
 *
 * The two reads sit in one expression so nothing can pair a wall clock taken before a manual
 * time change with a monotonic clock taken after it; `TimerInstant.bootReferenceMillis` is the
 * difference of the two, and a reference no single moment produced would decide FR-TIMER-003's
 * reboot test wrongly.
 *
 * Stateless, so it is an object: a clock has nothing to own, and the tests of PRD 14 replace it
 * with a value rather than reproducing a reboot.
 */
object AndroidTimerClock : TimerClock {

    override fun now(): TimerInstant = TimerInstant(
        wallMillis = System.currentTimeMillis(),
        elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
    )
}
