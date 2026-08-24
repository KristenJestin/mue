package fr.kristenjestin.mue.ui.timer

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.ui.activity.ActivityFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Display strings of the Activity Timer — its start screen, its own screen, the chassis banner,
 * the ongoing notification and the drafts waiting to be reviewed (PRD 6 and 7).
 *
 * The labels are English and live in [TimerMessages]; the numbers and the clock readings follow
 * the phone's language, so every entry point takes an explicit [Locale] defaulting to the
 * platform one — exactly as `ActivityFormat` and `ProgressFormat` do, and exactly what makes
 * these rules testable without an emulator.
 *
 * Nothing here decides *what* the elapsed value is: `TimerElapsed` is the only place in the app
 * that computes it. This file only spells a duration that has already been worked out, which is
 * why an incoherent reading (FR-TIMER-010) still renders — it arrives here as the last figure
 * that was honestly measured, beside [TimerMessages.CHECK_ACTIVITY_TIME].
 */
object TimerFormat {

    /** Between two facts on one line, as everywhere else in the app. */
    const val SEPARATOR: String = ActivityFormat.FACT_SEPARATOR

    /** What TalkBack hears between two facts; a middle dot is not a pause it can read. */
    private const val SPOKEN_SEPARATOR: String = ", "

    /** FR-TIMER-008: at most three review cards, and one expandable line for the rest. */
    const val REVIEW_CARD_LIMIT: Int = 3

    /**
     * The running chronometer of PRD 6.3, `HH:MM:SS`, starting at `00:00:00` (FR-TIMER-001).
     *
     * The hours field grows instead of wrapping: `%02d` is a floor and not a width, so a timer
     * that somehow passed the `99 h 59 min` ceiling reads `100:00:00` rather than `00:00:00`.
     * Losing four days of measured time to a format string would be the worst possible way to
     * fail FR-TIMER-010, which exists precisely so that no figure is ever quietly rewritten.
     *
     * The digits are the locale's own, and the caller sets them in tabular figures (PRD 6.3) so
     * the value does not shuffle sideways once a second.
     */
    fun elapsed(duration: ActivityDuration, locale: Locale = Locale.getDefault()): String =
        String.format(
            locale,
            "%02d:%02d:%02d",
            duration.hoursPart,
            duration.minutesPart,
            duration.secondsPart,
        )

    /**
     * The duration as the prefilled form summarises it (FR-TIMER-006), e.g. `42 min 18 sec`.
     *
     * The timer keeps its seconds where manual entry cannot express them (PRD 16), so this is
     * deliberately not `ActivityFormat.duration`: `2h 15m` would throw away the very precision
     * the module exists to record.
     *
     * One rule produces every spelling. The three spans are written from the largest one that
     * is not zero down to the smallest, so a sub-minute session reads `47 sec` rather than
     * `0 min 47 sec` and a round hour reads `1 h` rather than `1 h 0 min 0 sec` — while an
     * interior zero is kept, because `2 h 18 sec` would read as two hours and eighteen minutes.
     * A total of zero is a real reading and answers `0 sec`.
     *
     * Each span is written `number space unit`, which is how FR-TIMER-006 writes its own
     * example; a summary mixing `2h` with `15 min` would carry two conventions on one line.
     */
    fun reviewSummary(duration: ActivityDuration, locale: Locale = Locale.getDefault()): String =
        spans(duration).joinToString(" ") { (field, value) ->
            "${integer(value, locale)} ${field.suffix}"
        }

    /**
     * PRD 18: `Started at 18:32`, in the phone's own clock convention.
     *
     * Truncated to the minute, and truncated *here* rather than left to the formatter, because
     * FR-TIMER-005 makes it a fact about the value and not about its display: the session column
     * is `HH:mm`, so what the screen promises and what the form will prefill have to be the same
     * minute. A start at `18:32:47` reads `18:32` and never rounds up to `18:33`.
     */
    fun startedAt(time: LocalTime, locale: Locale = Locale.getDefault()): String =
        "${TimerMessages.STARTED_AT_PREFIX} ${startTime(time, locale)}"

    /** The same minute on its own, for a card that has no room for the sentence. */
    fun startTime(time: LocalTime, locale: Locale = Locale.getDefault()): String =
        ActivityFormat.time(time.truncatedTo(ChronoUnit.MINUTES), locale)

    /** PRD 18 and 11: the state is a word, so it never depends on the accent colour alone. */
    fun statusLabel(status: TimedDraftStatus): String = when (status) {
        TimedDraftStatus.RUNNING -> TimerMessages.ACTIVE
        TimedDraftStatus.PAUSED -> TimerMessages.PAUSED
        TimedDraftStatus.PENDING_REVIEW -> TimerMessages.READY_TO_REVIEW
    }

    /**
     * PRD 6.3 and FR-TIMER-004: one button that offers the opposite of what is happening. A
     * draft already in review has nothing left to resume, so `Resume` is what it would refuse.
     */
    fun primaryAction(status: TimedDraftStatus): String =
        if (status == TimedDraftStatus.RUNNING) TimerMessages.PAUSE else TimerMessages.RESUME

    /** PRD 6.4: the banner carries the elapsed time, or the word `Paused` in its place. */
    fun bannerValue(
        status: TimedDraftStatus,
        duration: ActivityDuration,
        locale: Locale = Locale.getDefault(),
    ): String = if (status == TimedDraftStatus.RUNNING) {
        elapsed(duration, locale)
    } else {
        TimerMessages.PAUSED
    }

    /**
     * The second line of the ongoing notification (PRD 6.5).
     *
     * While the timer runs, Android draws the chronometer itself from the notification's own
     * reference (PRD 10), so the line is free to name the activity. Paused, that chronometer is
     * switched off and the frozen figure has to be written out — with the word that explains
     * why it stopped, since a notification has no halo to go dark (PRD 11).
     */
    fun notificationText(
        activityLabel: String,
        status: TimedDraftStatus,
        duration: ActivityDuration,
        locale: Locale = Locale.getDefault(),
    ): String = if (status == TimedDraftStatus.RUNNING) {
        activityLabel
    } else {
        listOf(activityLabel, elapsed(duration, locale), TimerMessages.PAUSED)
            .joinToString(SEPARATOR)
    }

    /**
     * How the timer names what is being timed (PRD 6.3).
     *
     * A free name always wins, as it does on a stored session. Otherwise the preset speaks,
     * because it is what distinguishes a `Treadmill walk` from an `Outdoor walk` — the same
     * `Movement.WALKING` twice — and the movement itself answers for everything the six presets
     * do not cover, so a yoga draft reads `Yoga` rather than the builder's `Other`.
     */
    fun activityLabel(
        movement: Movement,
        customMovementName: String? = null,
        equipment: List<SessionEquipment> = emptyList(),
    ): String {
        val custom = customMovementName?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        val preset = ActivityPreset.of(movement, equipment)
        return if (preset == ActivityPreset.OTHER) movement.displayName else preset.label
    }

    /**
     * The line under that name, e.g. `Indoor · Treadmill`.
     *
     * `Not set` is a real place (PRD FR-ACTIVITY-008) and is shown as such; equipment that was
     * never chosen is simply absent, because a card has no room to say what a session is *not*.
     */
    fun context(
        environment: ActivityEnvironment,
        equipment: List<SessionEquipment> = emptyList(),
    ): String = (listOf(environment.displayName) + equipment.map { it.displayName })
        .joinToString(SEPARATOR)

    /**
     * The second line of a review card (FR-TIMER-008): when the session started, and how long
     * it was measured for. The day is named as the rest of the app names it, and the start time
     * keeps FR-TIMER-005's truncation so the card and the form it opens agree to the minute.
     */
    fun reviewCardMeta(
        date: LocalDate,
        time: LocalTime?,
        duration: ActivityDuration,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String {
        val day = ActivityFormat.dayAndTime(
            date = date,
            time = time?.truncatedTo(ChronoUnit.MINUTES),
            today = today,
            locale = locale,
        )
        return "$day$SEPARATOR${reviewSummary(duration, locale)}"
    }

    /**
     * The duration in full words, e.g. `42 minutes 18 seconds`.
     *
     * PRD 11 has TalkBack announce the state and the duration without reading every second, so
     * the running figure carries this as its description and no live region: the abbreviations
     * a sighted reader wants are not what a screen reader should be left to guess at.
     */
    fun spokenElapsed(duration: ActivityDuration, locale: Locale = Locale.getDefault()): String =
        spans(duration).joinToString(" ") { (field, value) ->
            "${integer(value, locale)} ${field.word(value)}"
        }

    /** What the chronometer of PRD 6.3 says out loud: the state first, then the duration. */
    fun elapsedDescription(
        status: TimedDraftStatus,
        duration: ActivityDuration,
        locale: Locale = Locale.getDefault(),
    ): String = "${statusLabel(status)}$SPOKEN_SEPARATOR${spokenElapsed(duration, locale)}"

    /**
     * The three spans of a duration, trimmed to the range that carries information: leading
     * zeros dropped, trailing zeros dropped, everything between them kept. A duration of zero
     * keeps its seconds, since something has to be said.
     */
    private fun spans(duration: ActivityDuration): List<Pair<DurationField, Int>> {
        val all = listOf(
            DurationField.HOURS to duration.hoursPart,
            DurationField.MINUTES to duration.minutesPart,
            DurationField.SECONDS to duration.secondsPart,
        )
        val first = all.indexOfFirst { it.second > 0 }
        if (first < 0) return listOf(DurationField.SECONDS to 0)
        return all.subList(first, all.indexOfLast { it.second > 0 } + 1)
    }

    private fun integer(value: Int, locale: Locale): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    /** One span of a duration, in the two registers the module needs it in. */
    private enum class DurationField(
        val suffix: String,
        private val singular: String,
        private val plural: String,
    ) {
        HOURS(
            TimerMessages.HOURS_SUFFIX,
            TimerMessages.HOUR_UNIT,
            TimerMessages.HOURS_UNIT,
        ),
        MINUTES(
            TimerMessages.MINUTES_SUFFIX,
            TimerMessages.MINUTE_UNIT,
            TimerMessages.MINUTES_UNIT,
        ),
        SECONDS(
            TimerMessages.SECONDS_SUFFIX,
            TimerMessages.SECOND_UNIT,
            TimerMessages.SECONDS_UNIT,
        ),
        ;

        fun word(value: Int): String = if (value == 1) singular else plural
    }
}
