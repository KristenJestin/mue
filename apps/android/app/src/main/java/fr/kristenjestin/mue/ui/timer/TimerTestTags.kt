package fr.kristenjestin.mue.ui.timer

/**
 * Handles for the Compose tests of the timer, in the shape `ActivityTestTags` already uses.
 *
 * They exist for the parts a test cannot address by their visible text: the chronometer, whose
 * value changes once a second; the banner, which has no label of its own beyond the activity it
 * names; and the review cards, which repeat.
 *
 * Fields keyed by an id build their tag from that id, so a test can name one draft among several
 * without counting positions — FR-TIMER-008 allows several at once.
 */
internal object TimerTestTags {

    // The two dashboard actions of PRD 6.1, and the shortcut under them.
    const val START_ACTIVITY: String = "timer:startActivity"
    const val LOG_PAST_ACTIVITY: String = "timer:logPastActivity"
    const val START_AGAIN: String = "timer:startAgain"

    // The choice screen of PRD 6.2.
    const val START_SCREEN: String = "timer:startScreen"
    const val PRESET_ROW: String = "timer:presetRow"
    const val READY_CARD: String = "timer:readyCard"
    const val START_TIMER: String = "timer:startTimer"

    // The timer screen of PRD 6.3.
    const val SCREEN: String = "timer:screen"
    const val ACTIVITY_LABEL: String = "timer:activityLabel"

    /** The `HH:MM:SS` value; its text is never stable, so no test can look for it by name. */
    const val ELAPSED: String = "timer:elapsed"
    const val STARTED_AT: String = "timer:startedAt"

    /** `Active` or `Paused` — the live region of PRD 11, and the only one on the screen. */
    const val STATUS: String = "timer:status"

    /** `Pause` or `Resume`, which is why it cannot be found by either word. */
    const val PRIMARY_ACTION: String = "timer:primaryAction"
    const val FINISH: String = "timer:finish"
    const val OVERFLOW: String = "timer:overflow"
    const val DISCARD_TIMER: String = "timer:discardTimer"

    /** Where FR-TIMER-002 and FR-TIMER-010 land when the timer screen is the one on show. */
    const val NOTICE: String = "timer:notice"

    // The confirmation of FR-TIMER-009.
    const val DISCARD_DIALOG: String = "timer:discardDialog"
    const val KEEP_TIMER: String = "timer:keepTimer"
    const val CONFIRM_DISCARD: String = "timer:confirmDiscard"

    // The chassis banner of PRD 6.4, which sits outside the animated content.
    const val BANNER: String = "timer:banner"
    const val BANNER_LABEL: String = "timer:bannerLabel"

    /** The elapsed time or `Paused`; the same moving value as [ELAPSED]. */
    const val BANNER_VALUE: String = "timer:bannerValue"

    // The drafts of FR-TIMER-008 waiting on the dashboard.
    const val REVIEW_LIST: String = "timer:reviewList"
    const val MORE_TO_REVIEW: String = "timer:moreToReview"

    /** The tappable duration summary of FR-TIMER-006, and the seconds wheel it opens. */
    const val DURATION_SUMMARY: String = "timer:durationSummary"
    const val DURATION_SECONDS_FIELD: String = "timer:durationSeconds"

    /** The contextual explanation FR-TIMER-012 shows before the first permission request. */
    const val NOTIFICATION_RATIONALE: String = "timer:notificationRationale"

    fun preset(presetId: String): String = "timer:preset:$presetId"

    fun reviewCard(draftId: String): String = "timer:reviewCard:$draftId"
}
