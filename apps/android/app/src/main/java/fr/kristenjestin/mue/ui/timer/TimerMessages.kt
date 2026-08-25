package fr.kristenjestin.mue.ui.timer

/**
 * The words of the Activity Timer.
 *
 * Constants rather than resources, as everywhere else in Mue: the app ships in English only
 * (PRD 1) and the tests assert these character for character. Every entry below is quoted from
 * the timer's PRD, or — where the PRD describes a surface in prose without spelling its English
 * out — from the two prototypes it cites; those are marked, so a copywriting pass knows exactly
 * which lines were never written down.
 *
 * PRD 18 wins over the prototypes wherever they disagree, which is why the state reads `Active`
 * rather than the prototype's `Active time` and the start time carries no seconds.
 */
object TimerMessages {

    // region Activity dashboard (PRD 6.1)

    /** PRD 6.1: the primary, full-width action that replaces the single `Log activity`. */
    const val START_ACTIVITY: String = "Start activity"

    /** PRD 6.1: the secondary, quieter action onto the existing manual form. */
    const val LOG_PAST_ACTIVITY: String = "Log past activity"

    /** PRD 6.1 and 16: it reopens the filled start screen rather than starting anything. */
    const val START_AGAIN: String = "Start again"

    // endregion

    // region Choosing what to start (PRD 6.2)

    /**
     * The heading of the choice screen. PRD 6.2 describes this screen without naming it; these
     * three lines are the `start-activity` prototype's own, kept because nothing else was ever
     * written down for them.
     */
    const val CHOICE_TITLE: String = "Start activity"
    const val CHOICE_EYEBROW: String = "Starting now"
    const val CHOICE_QUESTION: String = "What are you doing?"

    /**
     * The summary card of the prototype, kept by decision: PRD 6.2 forbids *recalling* a
     * previous session, and naming what is about to start is not a recall.
     */
    const val READY_TO_START: String = "Ready to start"

    /** The prototype's badge on that card: PRD 6.2 collects no measurement before the start. */
    const val NO_METRICS_YET: String = "NO METRICS YET"

    /** What that card puts after the date, since the session begins at the moment it is read. */
    const val NOW: String = "now"

    /** PRD 6.2's final action. */
    const val START_TIMER: String = "Start timer"

    // endregion

    // region The timer screen (PRD 6.3, FR-TIMER-004, PRD 18)

    /** PRD 18: the state is a word, never the accent colour alone (PRD 11). */
    const val ACTIVE: String = "Active"
    const val PAUSED: String = "Paused"

    /** PRD 6.3: the principal action, whichever way round the timer currently is. */
    const val PAUSE: String = "Pause"
    const val RESUME: String = "Resume"

    /** PRD 6.3: distinct from the principal action, never folded into it. */
    const val FINISH: String = "Finish"

    /** PRD 6.3: the secondary action, in the overflow menu. */
    const val DISCARD_TIMER: String = "Discard timer"

    /** PRD 18: `Started at 18:32`, the start time truncated to the minute by FR-TIMER-005. */
    const val STARTED_AT_PREFIX: String = "Started at"

    /** Prototype labels for the two controls PRD 6.3 names without wording them. */
    const val TIMER_OPTIONS: String = "Timer options"
    const val BACK_TO_ACTIVITY: String = "Back to activity"

    /** The prototype's reassurance card, which is also PRD 12's screen-off promise in words. */
    const val BACKGROUND_TITLE: String = "Available in the background"
    const val BACKGROUND_BODY: String = "Pause or finish from the Mue notification."
    const val SILENT_BADGE: String = "SILENT"

    // endregion

    // region The chassis banner (PRD 6.4)

    /** PRD 6.4: the whole surface is one implicit action back to the timer. */
    const val OPEN: String = "Open"

    /** FR-TIMER-002, word for word. A second timer is announced, never raised. */
    const val ALREADY_IN_PROGRESS: String = "An activity is already in progress."

    /** FR-TIMER-010, word for word. The figure behind it is never corrected silently. */
    const val CHECK_ACTIVITY_TIME: String = "Check activity time"

    /**
     * What a failed `Pause`, `Resume`, `Finish` or `Discard` says.
     *
     * The PRD writes no message for it — it does not admit the case — so this follows the
     * Activities module's own rule for the same situation (PRD_ACTIVITIES 13.4): no success
     * confirmation anywhere, a clear sentence when a write fails, and the action still there to
     * try again. The second clause is the important one: a transition has no half state, so
     * the measured time is exactly where it was.
     */
    const val TRANSITION_FAILED: String = "Couldn’t update the timer. Your time is still here."

    // endregion

    // region Notification (PRD 6.5, 10 and FR-TIMER-012)

    /** PRD 6.5: the title of the silent ongoing notification. */
    const val ACTIVITY_IN_PROGRESS: String = "Activity in progress"

    /** PRD 10: the name of the low-importance channel the user meets in Android settings. */
    const val NOTIFICATION_CHANNEL: String = "Ongoing activity"

    /**
     * FR-TIMER-012 asks for a short contextual explanation before the first
     * `POST_NOTIFICATIONS` request but never writes one. This is the `start-activity`
     * prototype's own note about the background, which states the same fact forwards.
     */
    const val NOTIFICATION_RATIONALE: String =
        "The timer keeps its time with the screen off. Mue may ask permission to show pause " +
            "and finish controls in a notification."

    /** FR-TIMER-012: the way back once a refusal is recorded and is never asked again. */
    const val OPEN_NOTIFICATION_SETTINGS: String = "Open notification settings"

    /**
     * Why that link is on the profile at all.
     *
     * FR-TIMER-012 names the link and never words the row around it. This says the two things
     * the reader needs: nothing is broken — the timer works either way — and Settings is now
     * the only route, because Mue does not ask a second time.
     */
    const val NOTIFICATION_SETTINGS_TITLE: String = "Timer notification"
    const val NOTIFICATION_SETTINGS_BODY: String =
        "Notifications are off, so the timer has no pause and finish controls outside Mue. It " +
            "still keeps its time. Mue will not ask again — turn them on in Android settings."

    // endregion

    // region Discarding (FR-TIMER-009)

    /** FR-TIMER-009, word for word — the whole message, for a banner or a screen reader. */
    const val DISCARD_TIMER_MESSAGE: String = "Discard this timer? The elapsed time will be lost."

    /**
     * The same sentence, split where a dialog splits it. The prototype adds a third clause,
     * `This cannot be undone.`, which FR-TIMER-009 does not carry; the PRD is the authority.
     */
    const val DISCARD_TIMER_TITLE: String = "Discard this timer?"
    const val DISCARD_TIMER_BODY: String = "The elapsed time will be lost."

    /** FR-TIMER-009's other wording, for a draft whose time is already measured and stopped. */
    const val DISCARD_DRAFT_TITLE: String = "Discard this activity draft?"

    /** FR-TIMER-009: the confirmation's two answers. `Keep timer` is the safe one. */
    const val KEEP_TIMER: String = "Keep timer"
    const val DISCARD: String = "Discard"

    // endregion

    // region Drafts waiting to be reviewed (FR-TIMER-008)

    /** FR-TIMER-008: the card the dashboard shows for a `pending_review` draft. */
    const val READY_TO_REVIEW: String = "Activity ready to review"

    /** FR-TIMER-008's own example is `+2 more to review`, and it expands in place. */
    fun moreToReview(count: Int): String = "+$count more to review"

    // endregion

    // region The review form's seconds (FR-TIMER-006)

    /**
     * The units of a timed duration. `h` and `min` are the manual form's own spelling; `sec` is
     * FR-TIMER-006's, from its `42 min 18 sec` example. They are repeated here rather than
     * borrowed from `LogActivityMessages` so the timer's vocabulary stands on one file.
     */
    const val HOURS_SUFFIX: String = "h"
    const val MINUTES_SUFFIX: String = "min"
    const val SECONDS_SUFFIX: String = "sec"

    /** The same three spans in full words, which is how a screen reader has to hear them. */
    const val HOUR_UNIT: String = "hour"
    const val HOURS_UNIT: String = "hours"
    const val MINUTE_UNIT: String = "minute"
    const val MINUTES_UNIT: String = "minutes"
    const val SECOND_UNIT: String = "second"
    const val SECONDS_UNIT: String = "seconds"

    /** FR-TIMER-006: the summary opens a three-field correction, so the seconds get a wheel. */
    const val DURATION_SECONDS_LABEL: String = "Duration in seconds"

    /** What the tappable duration summary of FR-TIMER-006 offers, as `Change` does elsewhere. */
    const val EDIT_DURATION: String = "Change"

    // endregion
}
