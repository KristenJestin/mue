package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerInstant
import kotlin.math.abs

/** Which clock answered, so the tests of PRD 14 can assert more than a number. */
enum class ElapsedBasis {
    /** Nothing was measured now: the draft is paused, finished, or has no open segment. */
    ACCUMULATED,

    /** `elapsedRealtime`, valid while the boot reference matches (PRD FR-TIMER-003). */
    MONOTONIC,

    /** The persisted civil instants, after a reboot or a manual time change. */
    WALL_CLOCK,
}

/**
 * How long a timer has been active (PRD 8.3), and the only place in the app that works it out.
 *
 * [Incoherent] is FR-TIMER-010: a total that is negative or past `99 h 59 min` is never shown,
 * never corrected silently and never reset. What it carries instead is
 * [TimedActivityDraft.accumulatedActive] — the last figure that was actually measured — and the
 * screen puts the timer in pause on that value while it asks the user to check the time.
 */
sealed interface TimerElapsed {

    val duration: ActivityDuration

    data class Sound(
        override val duration: ActivityDuration,
        val basis: ElapsedBasis,
    ) : TimerElapsed

    data class Incoherent(override val duration: ActivityDuration) : TimerElapsed

    companion object {
        /**
         * PRD FR-TIMER-003: a gap of less than ten seconds between the stored boot reference and
         * the current one is the same boot.
         */
        const val BOOT_REFERENCE_TOLERANCE_MILLIS: Long = 10_000

        const val MILLIS_PER_SECOND: Long = 1_000

        /**
         * PRD 8.3. A running draft adds its open segment to what is already measured; a paused
         * or reviewed one is worth exactly what is already measured.
         *
         * A total of zero while running is [Sound]: the chronometer starts at `00:00:00` and the
         * one-second floor of FR-TIMER-006 is a rule about saving, not about displaying.
         */
        fun of(draft: TimedActivityDraft, now: TimerInstant): TimerElapsed {
            val segment = openSegment(draft, now)
            val total = draft.accumulatedActive.seconds.toLong() + (segment?.seconds ?: 0L)
            return ActivityDuration.ofElapsedOrNull(total)
                ?.let { Sound(it, segment?.basis ?: ElapsedBasis.ACCUMULATED) }
                ?: Incoherent(draft.accumulatedActive)
        }

        /**
         * PRD FR-TIMER-003, and the only test the PRD permits. `abs` is what makes it a gap
         * rather than a direction: a wall clock corrected *backwards* gives a negative delta,
         * which a bare `delta < tolerance` would accept, and the monotonic reference would then
         * keep answering as though nothing had happened.
         */
        private fun isSameBoot(storedBootReferenceMillis: Long, now: TimerInstant): Boolean =
            abs(now.bootReferenceMillis - storedBootReferenceMillis) <
                BOOT_REFERENCE_TOLERANCE_MILLIS

        /**
         * The segment opened by `Start` or `Resume`, or null when there is none to measure.
         *
         * A `running` draft with no open segment is a row this build cannot vouch for; it is
         * worth what was already measured rather than a fault, because FR-TIMER-010 defines an
         * incoherent duration by its value and not by a missing column.
         */
        private fun openSegment(draft: TimedActivityDraft, now: TimerInstant): Segment? {
            if (draft.status != TimedDraftStatus.RUNNING) return null

            val storedBoot = draft.bootReferenceMillis
            val monotonicStart = draft.currentSegmentStartedElapsedRealtimeMillis
            if (storedBoot != null && monotonicStart != null && isSameBoot(storedBoot, now)) {
                return Segment(
                    seconds = wholeSeconds(now.elapsedRealtimeMillis - monotonicStart),
                    basis = ElapsedBasis.MONOTONIC,
                )
            }

            val wallStart = draft.currentSegmentStartedAtMillis ?: return null
            return Segment(
                seconds = wholeSeconds(now.wallMillis - wallStart),
                basis = ElapsedBasis.WALL_CLOCK,
            )
        }

        /** Stays in `Long`: a wall clock moved by years is exactly what must not wrap. */
        private fun wholeSeconds(millis: Long): Long = millis / MILLIS_PER_SECOND

        private data class Segment(val seconds: Long, val basis: ElapsedBasis)
    }
}

val TimerElapsed.isSound: Boolean get() = this is TimerElapsed.Sound

val TimerElapsed.basisOrNull: ElapsedBasis? get() = (this as? TimerElapsed.Sound)?.basis

/**
 * PRD 8.3 and FR-TIMER-004: the open segment is added to what is already measured and the
 * segment is then cleared, in one write.
 *
 * Idempotent, as PRD 12 requires of a repeatedly pressed button. And never destructive: an
 * incoherent reading leaves [TimedActivityDraft.accumulatedActive] exactly as it was, so a clock
 * that moved cannot shorten a duration that was honestly measured.
 */
fun TimedActivityDraft.pausedAt(now: TimerInstant): TimedActivityDraft =
    if (status != TimedDraftStatus.RUNNING) {
        this
    } else {
        closedAt(now, TimedDraftStatus.PAUSED, finishedWallMillis = null)
    }

/**
 * FR-TIMER-005: the same closing arithmetic, plus the instant the review reads. A draft already
 * in review is left alone — finishing is definitive, and the form is where a duration is
 * corrected afterwards.
 */
fun TimedActivityDraft.finishedAt(now: TimerInstant): TimedActivityDraft =
    if (!isLive) this else closedAt(now, TimedDraftStatus.PENDING_REVIEW, now.wallMillis)

/**
 * FR-TIMER-004: a new segment opens on both clocks and the original start time is untouched.
 *
 * A draft already in review never reopens: its measured time is finished, and the form is where
 * it is corrected.
 */
fun TimedActivityDraft.resumedAt(now: TimerInstant): TimedActivityDraft =
    if (status != TimedDraftStatus.PAUSED) {
        this
    } else {
        copy(
            status = TimedDraftStatus.RUNNING,
            currentSegmentStartedAtMillis = now.wallMillis,
            currentSegmentStartedElapsedRealtimeMillis = now.elapsedRealtimeMillis,
            bootReferenceMillis = now.bootReferenceMillis,
        )
    }

private fun TimedActivityDraft.closedAt(
    now: TimerInstant,
    status: TimedDraftStatus,
    finishedWallMillis: Long?,
): TimedActivityDraft = copy(
    status = status,
    accumulatedActive = TimerElapsed.of(this, now).duration,
    currentSegmentStartedAtMillis = null,
    currentSegmentStartedElapsedRealtimeMillis = null,
    bootReferenceMillis = now.bootReferenceMillis,
    finishedAtMillis = finishedWallMillis,
)
