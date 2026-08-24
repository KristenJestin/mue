package fr.kristenjestin.mue.domain.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Both clocks of the timer, read at one point in time (PRD FR-TIMER-003).
 *
 * They travel together because they are only ever meaningful together. Reading them through two
 * separate calls would let a wall clock taken before a correction pair with a monotonic clock
 * taken after it, and [bootReferenceMillis] — the single comparison FR-TIMER-003 allows for
 * deciding whether the phone rebooted — would then be a number no event ever produced.
 *
 * The suffix carries the clock, as the unit does everywhere else in this codebase: `…AtMillis`
 * is the wall clock, `…ElapsedRealtimeMillis` is monotonic, and no two longs of different clocks
 * share a suffix.
 */
data class TimerInstant(
    val wallMillis: Long,
    val elapsedRealtimeMillis: Long,
) {
    /**
     * When the phone booted, derived exactly as FR-TIMER-003 prescribes and rewritten with every
     * draft update. It moves both when the phone reboots and when the wall clock is set by hand,
     * which is the pair of events that invalidates the monotonic reference — one test for both.
     */
    val bootReferenceMillis: Long get() = wallMillis - elapsedRealtimeMillis

    /**
     * The calendar reading of the wall clock. FR-TIMER-005 resolves it once at `Start timer` and
     * freezes it, so a flight taken between `Finish` and `Save activity` cannot move the session
     * to another day.
     */
    fun atZone(zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(wallMillis), zone)
}

/**
 * The injected clock of PRD 9, so a reboot or a manual time change is a value a test supplies
 * rather than an event a test has to reproduce (PRD 14).
 *
 * `domain/` knows no Android, so [TimerInstant.elapsedRealtimeMillis] is a plain `Long`. The
 * implementation reads `System.currentTimeMillis()` and `SystemClock.elapsedRealtime()` — never
 * `uptimeMillis()`, which stops counting in deep sleep and would freeze the timer the moment
 * the screen goes off.
 */
fun interface TimerClock {
    fun now(): TimerInstant
}
