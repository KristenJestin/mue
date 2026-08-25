package fr.kristenjestin.mue.ui.timer

import fr.kristenjestin.mue.domain.logic.finishedAt
import fr.kristenjestin.mue.domain.logic.pausedAt
import fr.kristenjestin.mue.domain.logic.resumedAt
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.StartTimerOutcome
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerClock
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.domain.model.startedAt
import fr.kristenjestin.mue.domain.repository.TimedActivityRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/**
 * Where the two clocks stand when a test begins.
 *
 * The wall clock reads `2026-08-24T18:32:47Z`, FR-TIMER-005's own example, so a start time that
 * has to be truncated to the minute is truncated from a real value. The monotonic clock sits
 * `400 ms` off a whole second on purpose: a ticker aligned only by accident would pass every
 * assertion if both clocks started on a boundary.
 */
const val WALL_ORIGIN: Long = 1_787_596_367_000L
const val ELAPSED_ORIGIN: Long = 5_000_400L

/**
 * Both clocks of PRD FR-TIMER-003 under the test's control.
 *
 * [virtualMillis] is what makes the ticker testable: pointing it at the scheduler's own clock
 * means advancing virtual time advances the phone's clocks by exactly as much, so a beat and the
 * value it computes agree the way they do on a device. The two shifts on top of it are the events
 * a test cannot reproduce — time passing while nothing runs, and a clock set by hand.
 */
class FakeTimerClock(private val virtualMillis: () -> Long = { 0L }) : TimerClock {

    private var wallShift = 0L
    private var elapsedShift = 0L

    /** How often the clock was asked, which is how a test sees a beat that should not happen. */
    var reads: Int = 0
        private set

    override fun now(): TimerInstant {
        reads++
        val moved = virtualMillis()
        return TimerInstant(
            wallMillis = WALL_ORIGIN + moved + wallShift,
            elapsedRealtimeMillis = ELAPSED_ORIGIN + moved + elapsedShift,
        )
    }

    /** Time passing on both clocks at once, which is the only ordinary thing that happens. */
    fun advance(millis: Long) {
        wallShift += millis
        elapsedShift += millis
    }

    /**
     * A time set by hand: the wall clock moves and the monotonic one does not notice, which is
     * precisely what shifts the boot reference of FR-TIMER-003 and invalidates it.
     */
    fun jumpWallClock(millis: Long) {
        wallShift += millis
    }
}

/**
 * The timer's storage, in memory, behaving as the Room implementation is specified to behave.
 *
 * The two rules that matter here are the ones the ViewModel is built on: only one draft is ever
 * `running` or `paused` (FR-TIMER-001), and every transition is idempotent, so asking for a
 * status a draft already has writes nothing (PRD 12). [writes] counts what actually reached
 * storage, which is how a test tells a collapsed double tap from a second round trip.
 */
class FakeTimedActivityRepository(
    initial: List<TimedActivityDraft> = emptyList(),
) : TimedActivityRepository {

    private val drafts = MutableStateFlow(initial)
    private val lastTimedStart = MutableStateFlow<StartTimerRequest?>(null)
    private var nextId = 0
    private var gate: CompletableDeferred<Unit>? = null

    val all: List<TimedActivityDraft> get() = drafts.value

    val live: TimedActivityDraft? get() = drafts.value.firstOrNull { it.isLive }

    var writes: Int = 0
        private set

    var committed: List<ActivitySessionDetail> = emptyList()
        private set

    /** Holds every write open, so a test can press the same button again while one is in flight. */
    fun holdWrites() {
        gate = CompletableDeferred()
    }

    fun releaseWrites() {
        gate?.complete(Unit)
        gate = null
    }

    override fun observeLiveDraft(): Flow<TimedActivityDraft?> =
        drafts.map { all -> all.firstOrNull { it.isLive } }

    override suspend fun findLiveDraft(): TimedActivityDraft? = live

    override fun observeDraftsToReview(): Flow<List<TimedActivityDraft>> = drafts.map { all ->
        all.filter { it.status == TimedDraftStatus.PENDING_REVIEW }
            .sortedByDescending { it.startedAtMillis }
    }

    override suspend fun findDraft(id: TimedDraftId): TimedActivityDraft? =
        drafts.value.firstOrNull { it.id == id }

    override fun observeLastTimedStart(): Flow<StartTimerRequest?> = lastTimedStart

    /**
     * What saving a session with `source = timer` leaves behind for the `Start again` shortcut
     * of PRD 6.1. Set directly, because nothing in this fake writes a session.
     */
    fun setLastTimedStart(request: StartTimerRequest?) {
        lastTimedStart.value = request
    }

    override suspend fun start(
        request: StartTimerRequest,
        now: TimerInstant,
        zone: ZoneId,
    ): StartTimerOutcome {
        awaitGate()
        live?.let { return StartTimerOutcome.AlreadyLive(it) }
        val draft = request.startedAt(TimedDraftId("draft-${nextId++}"), now, zone)
        write { it + draft }
        return StartTimerOutcome.Started(draft)
    }

    override suspend fun pause(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? =
        transition(id) { it.pausedAt(now) }

    override suspend fun resume(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? =
        transition(id) { it.resumedAt(now) }

    override suspend fun finish(id: TimedDraftId, now: TimerInstant): TimedActivityDraft? =
        transition(id) { it.finishedAt(now) }

    override suspend fun discard(id: TimedDraftId) {
        awaitGate()
        if (drafts.value.none { it.id == id }) return
        write { all -> all.filterNot { it.id == id } }
    }

    override suspend fun saveReviewFormState(id: TimedDraftId, state: String?, schemaVersion: Int) {
        awaitGate()
        transitionRow(id) {
            it.copy(reviewFormState = state, reviewFormSchemaVersion = schemaVersion)
        }
    }

    override suspend fun commitToSession(id: TimedDraftId, detail: ActivitySessionDetail) {
        awaitGate()
        committed = committed + detail
        write { all -> all.filterNot { it.id == id } }
    }

    /** PRD 12: a transition that changes nothing writes nothing and returns what it read. */
    private suspend fun transition(
        id: TimedDraftId,
        transform: (TimedActivityDraft) -> TimedActivityDraft,
    ): TimedActivityDraft? {
        awaitGate()
        return transitionRow(id, transform)
    }

    private fun transitionRow(
        id: TimedDraftId,
        transform: (TimedActivityDraft) -> TimedActivityDraft,
    ): TimedActivityDraft? {
        val current = drafts.value.firstOrNull { it.id == id } ?: return null
        val next = transform(current)
        if (next == current) return current
        write { all -> all.map { if (it.id == id) next else it } }
        return next
    }

    private suspend fun awaitGate() {
        gate?.await()
    }

    private fun write(block: (List<TimedActivityDraft>) -> List<TimedActivityDraft>) {
        writes++
        drafts.value = block(drafts.value)
    }
}
