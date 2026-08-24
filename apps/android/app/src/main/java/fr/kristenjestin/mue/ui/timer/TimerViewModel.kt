package fr.kristenjestin.mue.ui.timer

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.TimerElapsed
import fr.kristenjestin.mue.domain.logic.basisOrNull
import fr.kristenjestin.mue.domain.model.StartTimerOutcome
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerClock
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.domain.repository.TimedActivityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/**
 * The one owner of the running timer (PRD FR-TIMER-001 to 011).
 *
 * Scoped to the hosting activity under a fixed key rather than to a screen, for the same reason
 * `LogActivityViewModel` is: the timer screen and the chassis banner are two views of a single
 * timer (PRD 6.4), and the banner outlives every screen it is drawn over.
 *
 * Nothing durable lives here. The draft is in Room, which is what survives a process death and a
 * reboot, and the elapsed value is derived from its persisted instants at every beat — never
 * incremented, and never written once a second (FR-TIMER-003). What this class owns is the
 * transient part of PRD 6.4: the notice, the confirmation, and the two one-shot signals that
 * take the caller to the timer or to the review form.
 *
 * It holds no `SavedStateHandle`, deliberately. `LogActivityViewModel` needs one because the
 * text being typed exists nowhere else; here every value is either in the database or is exactly
 * what that class keeps out of its handle — the result of pressing a button, which re-showing
 * after a process death would be noise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModel(
    private val timers: TimedActivityRepository,
    private val clock: TimerClock,
    private val ticker: TimerTicker = TimerTicker(clock),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: () -> Locale = Locale::getDefault,
) : ViewModel() {

    private val transient = MutableStateFlow(Transient())

    /**
     * The live draft and its elapsed value, recomputed on every beat while it runs.
     *
     * `transformLatest` rather than `combine`, so the one-second rhythm belongs to a *running*
     * timer alone: a paused draft is worth exactly what was already measured and is computed
     * once, and no timer at all costs no beat. Changing draft cancels the previous rhythm, which
     * is what stops the ticker the moment `Pause`, `Finish` or `Discard` lands.
     */
    private val readings: Flow<Reading?> = timers.observeLiveDraft()
        .distinctUntilChanged()
        .transformLatest { draft ->
            when {
                draft == null -> emit(null)
                draft.status == TimedDraftStatus.RUNNING ->
                    ticker.instants.collect { now -> emit(readingOf(draft, now)) }

                else -> emit(readingOf(draft, clock.now()))
            }
        }
        .onEach { reading -> pauseOnIncoherentReading(reading) }

    /**
     * What every surface of the module reads.
     *
     * `WhileSubscribed` is FR-TIMER-003's background rule for free: the last surface to leave
     * takes the beat with it, and the first to come back recomputes the value from the persisted
     * instants rather than catching up on the beats it missed.
     */
    val uiState: StateFlow<TimerUiState> = combine(readings, transient) { reading, flags ->
        stateOf(reading, flags)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = TimerUiState.LOADING,
    )

    // --- The five transitions ---------------------------------------------------------

    /**
     * FR-TIMER-001 and 002.
     *
     * A second timer is never created and never raises: [StartTimerOutcome.AlreadyLive] carries
     * the timer that is already running, which is the one the caller opens, and the refusal is
     * announced as a notice rather than as a failure.
     */
    fun start(request: StartTimerRequest) = mutate(clock.now()) { now ->
        val outcome = timers.start(request, now, zone())
        transient.update {
            it.copy(
                timerToOpen = outcome.draft.id,
                notice = when (outcome) {
                    is StartTimerOutcome.Started -> null
                    is StartTimerOutcome.AlreadyLive -> TimerNotice.ALREADY_IN_PROGRESS
                },
            )
        }
    }

    /** FR-TIMER-004: the open segment closes into the accumulated total, in one write. */
    fun pause() = onLiveTimer { id, now -> timers.pause(id, now) }

    /** FR-TIMER-004: a new segment opens; the original start time is untouched. */
    fun resume() = onLiveTimer { id, now -> timers.resume(id, now) }

    /** FR-TIMER-005: the chronometer stops for good and the review form opens on the draft. */
    fun finish() = onLiveTimer { id, now ->
        timers.finish(id, now)?.let { finished ->
            transient.update { it.copy(reviewToOpen = finished.id) }
        }
    }

    /**
     * FR-TIMER-009: only ever reached through [requestDiscard] and its confirmation.
     *
     * The confirmation closes before the write rather than after it, so a double tap cannot
     * leave the dialog standing over a timer that is already gone.
     */
    fun discard() = onLiveTimer { id, _ ->
        transient.update { it.copy(discardConfirmationVisible = false) }
        timers.discard(id)
    }

    // --- Transient state --------------------------------------------------------------

    /** FR-TIMER-009: `Discard timer` asks before it destroys a measured duration. */
    fun requestDiscard() = clearNotice { it.copy(discardConfirmationVisible = true) }

    /** `Keep timer` closes the confirmation and changes nothing. */
    fun cancelDiscard() = clearNotice { it.copy(discardConfirmationVisible = false) }

    /** PRD 6.4: the notice is transient, and any surface may retire it once it has been read. */
    fun dismissNotice() = clearNotice()

    /**
     * Asks the caller to show the timer that already exists — PRD 6.4's `Open` on the chassis
     * banner, and contract decision 5's `Start activity` pressed while one is already running.
     *
     * [announceAlreadyLive] is what tells the two apart. Tapping the banner is not an attempt to
     * start anything and says nothing; `Start activity` **is** that attempt, and FR-TIMER-002
     * has it open the running timer carrying `An activity is already in progress.` — before a
     * request has been built, so it never reaches [start] to be refused there.
     */
    fun openTimer(announceAlreadyLive: Boolean = false) {
        val id = uiState.value.timer?.id ?: return
        transient.update {
            it.copy(
                timerToOpen = id,
                notice = if (announceAlreadyLive) TimerNotice.ALREADY_IN_PROGRESS else it.notice,
            )
        }
    }

    /**
     * FR-TIMER-005 from the ongoing notification: `MainActivity` has already stopped the timer
     * by the time Mue is on screen, so the review form is asked for rather than derived.
     */
    fun openReview(id: TimedDraftId) = transient.update { it.copy(reviewToOpen = id) }

    /** Consumes [TimerUiState.timerToOpen] once the caller has opened the timer. */
    fun onTimerOpened() = transient.update { it.copy(timerToOpen = null) }

    /** Consumes [TimerUiState.reviewToOpen] once the caller has opened the review form. */
    fun onReviewOpened() = transient.update { it.copy(reviewToOpen = null) }

    // --- Derivation -------------------------------------------------------------------

    private fun readingOf(draft: TimedActivityDraft, now: TimerInstant): Reading =
        Reading(draft, now, TimerElapsed.of(draft, now))

    /**
     * FR-TIMER-010, and the only reason this flow has a side effect.
     *
     * A duration that is negative or past `99 h 59 min` puts the timer in pause **on the last
     * valid figure**: `pausedAt` closes the segment through `TimerElapsed`, which answers
     * `accumulatedActive` for an incoherent reading, so the write cannot shorten a duration that
     * was honestly measured and cannot silently correct it either. What the user gets is a
     * stopped timer, the figure it stopped on, and `Check activity time`.
     *
     * Re-entry is bounded by the same [Transient.isMutating] guard the buttons use, and the
     * write is idempotent, so a second beat arriving before the paused row does costs nothing.
     */
    private fun pauseOnIncoherentReading(reading: Reading?) {
        if (reading == null || !reading.isIncoherentWhileRunning) return
        mutate(reading.now) { now ->
            timers.pause(reading.draft.id, now)
            transient.update { it.copy(notice = TimerNotice.CHECK_ACTIVITY_TIME) }
        }
    }

    private fun stateOf(reading: Reading?, flags: Transient): TimerUiState = TimerUiState(
        timer = reading?.let(::liveStateOf),
        notice = flags.notice,
        isMutating = flags.isMutating,
        isLoading = false,
        discardConfirmationVisible = flags.discardConfirmationVisible,
        timerToOpen = flags.timerToOpen,
        reviewToOpen = flags.reviewToOpen,
    )

    private fun liveStateOf(reading: Reading): LiveTimerUiState {
        val locale = locale()
        val draft = reading.draft
        val duration = reading.elapsed.duration
        return LiveTimerUiState(
            draft = draft,
            elapsed = duration,
            basis = reading.elapsed.basisOrNull,
            isIncoherent = reading.elapsed is TimerElapsed.Incoherent,
            activityLabel = TimerFormat.activityLabel(
                movement = draft.movement,
                customMovementName = draft.customMovementName,
                equipment = draft.equipment,
            ),
            contextLabel = TimerFormat.context(draft.environment, draft.equipment),
            elapsedText = TimerFormat.elapsed(duration, locale),
            elapsedDescription = TimerFormat.elapsedDescription(draft.status, duration, locale),
            startedAtText = TimerFormat.startedAt(draft.startedAtLocalTime, locale),
            statusLabel = TimerFormat.statusLabel(draft.status),
            primaryActionLabel = TimerFormat.primaryAction(draft.status),
            bannerValue = TimerFormat.bannerValue(draft.status, duration, locale),
        )
    }

    // --- Plumbing ---------------------------------------------------------------------

    private fun onLiveTimer(block: suspend (TimedDraftId, TimerInstant) -> Unit) {
        val id = uiState.value.timer?.id ?: return
        mutate(clock.now()) { now -> block(id, now) }
    }

    /**
     * PRD 12, a button pressed twice.
     *
     * The repository's transitions are already idempotent, so this guard is about the interface
     * and not about correctness: it stops a double tap from queueing a second round trip whose
     * answer would arrive after the first had already redrawn the screen. The instant is read
     * once, at the press, so what is stored is when the user acted rather than when the write
     * got its turn — and both clocks come from that one reading, never from two.
     *
     * A failed write leaves the draft exactly as the database has it — a transition has no half
     * state — but silence would leave the user watching a button that appeared to do nothing.
     * PRD_ACTIVITIES 13.4 is the rule the module already follows for this: no confirmation on
     * success, a clear sentence on failure, and the same action still there to try again.
     */
    private fun mutate(at: TimerInstant, block: suspend (TimerInstant) -> Unit) {
        if (transient.value.isMutating) return
        transient.update { it.copy(isMutating = true, notice = null) }
        viewModelScope.launch {
            val failed = runCatching { block(at) }.isFailure
            transient.update { flags ->
                flags.copy(
                    isMutating = false,
                    // A notice the block itself raised — FR-TIMER-002's, FR-TIMER-010's — is
                    // only reached when the write succeeded, so it is never overwritten.
                    notice = if (failed) TimerNotice.TRANSITION_FAILED else flags.notice,
                )
            }
        }
    }

    /** Any interaction retires the notice, which is what makes it transient (PRD 6.4). */
    private fun clearNotice(block: (Transient) -> Transient = { it }) =
        transient.update { block(it).copy(notice = null) }

    /** A draft and what it was worth at one instant; never part of the state, which moves less. */
    private data class Reading(
        val draft: TimedActivityDraft,
        val now: TimerInstant,
        val elapsed: TimerElapsed,
    ) {
        val isIncoherentWhileRunning: Boolean
            get() = elapsed is TimerElapsed.Incoherent && draft.status == TimedDraftStatus.RUNNING
    }

    /** What is not the draft: the notice, the confirmation, and the two one-shot signals. */
    private data class Transient(
        val isMutating: Boolean = false,
        val notice: TimerNotice? = null,
        val discardConfirmationVisible: Boolean = false,
        val timerToOpen: TimedDraftId? = null,
        val reviewToOpen: TimedDraftId? = null,
    )

    companion object {

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The key every surface asks for. The timer screen, the start screen and the chassis
         * banner share one instance because they share one timer (PRD 6.4).
         */
        const val KEY: String = "timer.live"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                TimerViewModel(
                    timers = app.container.timer.timedActivityRepository,
                    clock = app.container.timer.clock,
                )
            }
        }
    }
}

/**
 * The shared instance of the timer's ViewModel.
 *
 * Every caller gets the same object: the banner, the start screen and the timer screen are
 * three views of one timer, and none of them may create one of its own.
 */
@Composable
fun timerViewModel(): TimerViewModel =
    viewModel(key = TimerViewModel.KEY, factory = TimerViewModel.Factory)
