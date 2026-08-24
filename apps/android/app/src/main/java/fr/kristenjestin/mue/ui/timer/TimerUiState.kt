package fr.kristenjestin.mue.ui.timer

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.ElapsedBasis
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus

/**
 * The two things the timer ever has to say (PRD 6.4).
 *
 * One value, not one per surface: the timer screen shows it on its status line when it is open
 * and the chassis banner shows it otherwise, so both readings of the PRD are served by a single
 * flow. Neither uses a dialog, and neither is persisted — a notice is the result of an action,
 * not a fact about the timer, and it goes as soon as the user does anything else.
 */
enum class TimerNotice(val message: String) {

    /** FR-TIMER-002: a second `Start timer` opens the first one and says so. */
    ALREADY_IN_PROGRESS(TimerMessages.ALREADY_IN_PROGRESS),

    /** FR-TIMER-010: the duration could not be trusted, so the timer stopped where it was. */
    CHECK_ACTIVITY_TIME(TimerMessages.CHECK_ACTIVITY_TIME),
}

/**
 * The live timer as every surface of the module reads it (PRD 6.3, 6.4 and 6.5).
 *
 * The [draft] is carried whole so nothing here has to be duplicated to be reachable, and the
 * strings beside it are spelled once per beat rather than once per composition — the value moves
 * every second and three surfaces render it.
 */
@Immutable
data class LiveTimerUiState(
    val draft: TimedActivityDraft,

    /** What `TimerElapsed` answered, which is the only place the app works it out. */
    val elapsed: ActivityDuration,

    /** Which clock answered, or null when the reading was incoherent (PRD 14). */
    val basis: ElapsedBasis?,

    /**
     * FR-TIMER-010: the last reading was negative or past the ceiling. [elapsed] is then the
     * last figure that was honestly measured — never a correction, and never zero.
     */
    val isIncoherent: Boolean,

    val activityLabel: String,

    /** `Indoor · Treadmill`, the line under the name. */
    val contextLabel: String,

    /** The `HH:MM:SS` of PRD 6.3, in tabular figures wherever it is drawn. */
    val elapsedText: String,

    /**
     * PRD 11: what TalkBack says about the chronometer, as its `contentDescription` and **never**
     * as a live region — a live region here would read every second out loud. The status word
     * is the one live region on the screen.
     */
    val elapsedDescription: String,

    /** PRD 18: `Started at 18:32`, truncated to the minute by FR-TIMER-005. */
    val startedAtText: String,

    /** `Active` or `Paused` — a word, never the accent colour alone (PRD 11). */
    val statusLabel: String,

    /** `Pause` or `Resume`: one button offering the opposite of what is happening. */
    val primaryActionLabel: String,

    /** PRD 6.4: the elapsed time, or the word `Paused` in its place. */
    val bannerValue: String,
) {
    val id: TimedDraftId get() = draft.id

    val status: TimedDraftStatus get() = draft.status

    val isRunning: Boolean get() = status == TimedDraftStatus.RUNNING
}

/**
 * Everything the Activity Timer's surfaces read, from one owner (PRD 9).
 *
 * The timer screen and the chassis banner are two views of a single timer, so they share this
 * state rather than each deriving one; the start screen reads [timerToOpen] and the review
 * hand-off reads [reviewToOpen].
 */
@Immutable
data class TimerUiState(
    /** The one `running` or `paused` draft of FR-TIMER-001, or null when no timer exists. */
    val timer: LiveTimerUiState? = null,

    val notice: TimerNotice? = null,

    /** A transition is in flight; PRD 12's repeatedly pressed button does nothing more. */
    val isMutating: Boolean = false,

    /** True only until the first read of the database answers, so nothing flashes empty. */
    val isLoading: Boolean = true,

    /** FR-TIMER-009: `Discard` never happens without an explicit confirmation. */
    val discardConfirmationVisible: Boolean = false,

    /**
     * FR-TIMER-002: set by both answers to `Start timer` — the timer that was just created and
     * the one that was already running are opened the same way — and cleared by
     * [TimerViewModel.onTimerOpened].
     */
    val timerToOpen: TimedDraftId? = null,

    /** FR-TIMER-005: `Finish` opens the review form immediately. */
    val reviewToOpen: TimedDraftId? = null,
) {
    /** PRD 6.4: the banner exists exactly while a timer does. */
    val hasTimer: Boolean get() = timer != null

    val noticeMessage: String? get() = notice?.message

    companion object {
        /** Before the first row is read: no timer, and nothing said about it yet. */
        val LOADING: TimerUiState = TimerUiState()
    }
}
