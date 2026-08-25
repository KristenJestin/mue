package fr.kristenjestin.mue.ui.timer

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.secondsOf
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val EN = Locale.US
private val FR = Locale.FRANCE

private val TODAY: LocalDate = LocalDate.of(2026, 8, 24)

private const val HOUR = ActivityDuration.SECONDS_PER_HOUR
private const val MINUTE = ActivityDuration.SECONDS_PER_MINUTE

/**
 * The display rules of the Activity Timer (PRD 6, 7 and 18).
 *
 * Both languages are exercised throughout, because the module splits the two directions the way
 * the rest of the app does: the labels stay English while the digits and the clock follow the
 * phone. A French assertion that only repeated the English output would prove nothing.
 */
class TimerFormatTest {

    // region the running chronometer (PRD 6.3, FR-TIMER-001)

    /** FR-TIMER-001: the chronometer starts here, and zero is a real reading, not a fault. */
    @Test
    fun `a timer starts at zero, padded to the full clock`() {
        assertEquals("00:00:00", TimerFormat.elapsed(ActivityDuration.ZERO, EN))
        assertEquals("00:00:00", TimerFormat.elapsed(ActivityDuration.ZERO, FR))
    }

    @Test
    fun `a duration under a minute still shows its hours and minutes`() {
        assertEquals("00:00:07", TimerFormat.elapsed(secondsOf(7), EN))
        assertEquals("00:00:07", TimerFormat.elapsed(secondsOf(7), FR))
        assertEquals("00:00:59", TimerFormat.elapsed(secondsOf(59), EN))
    }

    @Test
    fun `exactly an hour rolls the minutes over rather than counting past sixty`() {
        assertEquals("01:00:00", TimerFormat.elapsed(secondsOf(HOUR), EN))
        assertEquals("01:00:00", TimerFormat.elapsed(secondsOf(HOUR), FR))
        assertEquals("00:59:59", TimerFormat.elapsed(secondsOf(HOUR - 1), EN))
    }

    @Test
    fun `a long session keeps every field`() {
        assertEquals("02:15:18", TimerFormat.elapsed(secondsOf(2 * HOUR + 15 * MINUTE + 18), EN))
        assertEquals("02:15:18", TimerFormat.elapsed(secondsOf(2 * HOUR + 15 * MINUTE + 18), FR))
    }

    /** The ceiling PRD FR-TIMER-006 shares with manual entry, which still has to render. */
    @Test
    fun `the ninety-nine hour ceiling reads as itself`() {
        val ceiling = secondsOf(ActivityDuration.SESSION_MAX_SECONDS)
        assertEquals("99:59:00", TimerFormat.elapsed(ceiling, EN))
        assertEquals("99:59:00", TimerFormat.elapsed(ceiling, FR))
    }

    /**
     * FR-TIMER-010 puts a timer past the ceiling into pause on its last valid figure and asks
     * the user to check it; what it must never do is show a smaller number than was measured.
     * A two-digit field would wrap `100 h` to `00`, silently losing four days.
     */
    @Test
    fun `past ninety-nine hours the hours grow instead of wrapping`() {
        assertEquals("100:00:00", TimerFormat.elapsed(secondsOf(100 * HOUR), EN))
        assertEquals("100:00:00", TimerFormat.elapsed(secondsOf(100 * HOUR), FR))
        assertEquals("123:45:06", TimerFormat.elapsed(secondsOf(123 * HOUR + 45 * MINUTE + 6), EN))
    }

    /** Every reading is the same width once the hours are, which is what keeps it stable. */
    @Test
    fun `the clock is fixed width below a hundred hours`() {
        assertEquals(8, TimerFormat.elapsed(ActivityDuration.ZERO, EN).length)
        assertEquals(8, TimerFormat.elapsed(secondsOf(99 * HOUR + 59 * MINUTE + 59), EN).length)
    }

    // endregion

    // region the review summary (FR-TIMER-006)

    /** FR-TIMER-006's own example, word for word. */
    @Test
    fun `the review summary keeps its seconds`() {
        val duration = secondsOf(42 * MINUTE + 18)
        assertEquals("42 min 18 sec", TimerFormat.reviewSummary(duration, EN))
        assertEquals("42 min 18 sec", TimerFormat.reviewSummary(duration, FR))
    }

    /** A whole number of minutes says nothing about seconds, as manual entry would not. */
    @Test
    fun `a duration without seconds drops the seconds`() {
        assertEquals("42 min", TimerFormat.reviewSummary(secondsOf(42 * MINUTE), EN))
        assertEquals("42 min", TimerFormat.reviewSummary(secondsOf(42 * MINUTE), FR))
    }

    /** FR-TIMER-006: a timed session may last less than a minute and is still worth saving. */
    @Test
    fun `a sub-minute session reads in seconds alone`() {
        assertEquals("18 sec", TimerFormat.reviewSummary(secondsOf(18), EN))
        assertEquals("18 sec", TimerFormat.reviewSummary(secondsOf(18), FR))
        assertEquals("1 sec", TimerFormat.reviewSummary(secondsOf(1), EN))
    }

    @Test
    fun `a zero duration still says something`() {
        assertEquals("0 sec", TimerFormat.reviewSummary(ActivityDuration.ZERO, EN))
        assertEquals("0 sec", TimerFormat.reviewSummary(ActivityDuration.ZERO, FR))
    }

    @Test
    fun `an exact hour drops both smaller spans`() {
        assertEquals("1 h", TimerFormat.reviewSummary(secondsOf(HOUR), EN))
        assertEquals("1 h", TimerFormat.reviewSummary(secondsOf(HOUR), FR))
        assertEquals("2 h 15 min", TimerFormat.reviewSummary(secondsOf(2 * HOUR + 15 * MINUTE), EN))
    }

    /**
     * The one zero that has to survive: without its `0 min`, two hours and eighteen seconds
     * would read as two hours and eighteen minutes.
     */
    @Test
    fun `an interior zero is kept`() {
        assertEquals("2 h 0 min 18 sec", TimerFormat.reviewSummary(secondsOf(2 * HOUR + 18), EN))
        assertEquals("2 h 0 min 18 sec", TimerFormat.reviewSummary(secondsOf(2 * HOUR + 18), FR))
    }

    // endregion

    // region the start time (FR-TIMER-005, PRD 18)

    /**
     * PRD 18 and FR-TIMER-005: the session column is `HH:mm`, so a start at `18:32:47` is
     * announced — and later prefilled — as `18:32`, never rounded up to `18:33`.
     */
    @Test
    fun `the start time is truncated to the minute`() {
        val started = LocalTime.of(18, 32, 47)
        assertEquals("Started at 18:32", TimerFormat.startedAt(started, FR))
        assertEquals("18:32", TimerFormat.startTime(started, FR))
        assertEquals("18:32", TimerFormat.startTime(LocalTime.of(18, 32, 59), FR))
        assertEquals("18:32", TimerFormat.startTime(LocalTime.of(18, 32), FR))
    }

    /**
     * The label is English while the clock is the phone's own, exactly as `ActivityFormat.time`
     * already reads a stored start time. The English reading is asserted by its shape rather
     * than character for character, because the space before `PM` moves between JDK versions.
     */
    @Test
    fun `the start time follows the phone's clock`() {
        val english = TimerFormat.startedAt(LocalTime.of(18, 32, 47), EN)
        assertTrue(english.startsWith("Started at "), english)
        assertTrue(english.contains("6:32"), english)
        assertTrue(english.contains("PM"), english)
        assertFalse(english.contains("47"), english)
    }

    @Test
    fun `midnight is a start time like any other`() {
        assertEquals("Started at 00:07", TimerFormat.startedAt(LocalTime.of(0, 7, 31), FR))
    }

    // endregion

    // region states and actions (PRD 6.3, 6.4, 18)

    /** PRD 18: `Active` and `Paused`, not the prototype's `Active time`. */
    @Test
    fun `the state is a word`() {
        assertEquals("Active", TimerFormat.statusLabel(TimedDraftStatus.RUNNING))
        assertEquals("Paused", TimerFormat.statusLabel(TimedDraftStatus.PAUSED))
        assertEquals(
            "Activity ready to review",
            TimerFormat.statusLabel(TimedDraftStatus.PENDING_REVIEW),
        )
    }

    @Test
    fun `the principal action offers the opposite of what is happening`() {
        assertEquals("Pause", TimerFormat.primaryAction(TimedDraftStatus.RUNNING))
        assertEquals("Resume", TimerFormat.primaryAction(TimedDraftStatus.PAUSED))
    }

    /** PRD 6.4: the banner carries the elapsed time, or the word `Paused` in its place. */
    @Test
    fun `the banner shows the time while it runs and the word once it stops`() {
        val duration = secondsOf(42 * MINUTE + 18)
        assertEquals("00:42:18", TimerFormat.bannerValue(TimedDraftStatus.RUNNING, duration, EN))
        assertEquals("00:42:18", TimerFormat.bannerValue(TimedDraftStatus.RUNNING, duration, FR))
        assertEquals("Paused", TimerFormat.bannerValue(TimedDraftStatus.PAUSED, duration, EN))
        assertEquals("Paused", TimerFormat.bannerValue(TimedDraftStatus.PAUSED, duration, FR))
    }

    /**
     * PRD 10: while it runs, Android draws the chronometer itself, so the line is free for the
     * activity. Paused, that chronometer is off and the frozen figure has to be written out.
     */
    @Test
    fun `a paused notification writes out the figure the system stops drawing`() {
        val duration = secondsOf(42 * MINUTE + 18)
        assertEquals(
            "Treadmill walk",
            TimerFormat.notificationText("Treadmill walk", TimedDraftStatus.RUNNING, duration, EN),
        )
        assertEquals(
            "Treadmill walk · 00:42:18 · Paused",
            TimerFormat.notificationText("Treadmill walk", TimedDraftStatus.PAUSED, duration, EN),
        )
    }

    // endregion

    // region naming what is being timed (PRD 6.2 and 6.3)

    /** The prototype's own title for the first preset, rebuilt from the axes alone. */
    @Test
    fun `a treadmill walk is named by its preset`() {
        assertEquals(
            "Treadmill walk",
            TimerFormat.activityLabel(Movement.WALKING, equipment = listOf(treadmill())),
        )
        assertEquals("Outdoor walk", TimerFormat.activityLabel(Movement.WALKING))
    }

    /** Everything outside the six presets keeps its own name rather than reading `Other`. */
    @Test
    fun `a catalogue movement is named by itself`() {
        assertEquals("Yoga", TimerFormat.activityLabel(Movement.YOGA))
        assertEquals("Other", TimerFormat.activityLabel(Movement.OTHER))
    }

    @Test
    fun `a free name always wins`() {
        assertEquals(
            "Padel",
            TimerFormat.activityLabel(Movement.OTHER, customMovementName = " Padel "),
        )
        assertEquals("Outdoor walk", TimerFormat.activityLabel(Movement.WALKING, "   "))
    }

    /** The prototype's `Indoor · Treadmill`, and `Not set` shown as the real answer it is. */
    @Test
    fun `the context line joins the place and the gear`() {
        assertEquals(
            "Indoor · Treadmill",
            TimerFormat.context(ActivityEnvironment.INDOOR, listOf(treadmill())),
        )
        assertEquals("Not set", TimerFormat.context(ActivityEnvironment.UNKNOWN))
        assertEquals(
            "Outdoor · Bicycle",
            TimerFormat.context(
                ActivityEnvironment.OUTDOOR,
                listOf(SessionEquipment(EquipmentType.BICYCLE)),
            ),
        )
    }

    // endregion

    // region drafts waiting to be reviewed (FR-TIMER-008)

    @Test
    fun `a review card says when it started and how long it ran`() {
        val meta = TimerFormat.reviewCardMeta(
            date = TODAY,
            time = LocalTime.of(18, 32, 47),
            duration = secondsOf(42 * MINUTE + 18),
            today = TODAY,
            locale = FR,
        )
        assertEquals("Today · 18:32 · 42 min 18 sec", meta)
    }

    /** PRD 8.2 keeps the start time optional, so a card has to read without one. */
    @Test
    fun `a review card without a start time keeps its day`() {
        val meta = TimerFormat.reviewCardMeta(
            date = TODAY.minusDays(1),
            time = null,
            duration = secondsOf(HOUR),
            today = TODAY,
            locale = EN,
        )
        assertEquals("Yesterday · 1 h", meta)
    }

    @Test
    fun `the overflow line counts what it hides`() {
        assertEquals("+2 more to review", TimerMessages.moreToReview(2))
        assertEquals(3, TimerFormat.REVIEW_CARD_LIMIT)
    }

    // endregion

    // region what TalkBack hears (PRD 11)

    /** PRD 11: the state and the duration, in words, and never one digit at a time. */
    @Test
    fun `the spoken duration uses whole words and agrees in number`() {
        val long = secondsOf(42 * MINUTE + 18)
        assertEquals("42 minutes 18 seconds", TimerFormat.spokenElapsed(long, EN))
        assertEquals("42 minutes 18 seconds", TimerFormat.spokenElapsed(long, FR))
        val singular = secondsOf(HOUR + MINUTE + 1)
        assertEquals("1 hour 1 minute 1 second", TimerFormat.spokenElapsed(singular, EN))
        assertEquals("0 seconds", TimerFormat.spokenElapsed(ActivityDuration.ZERO, EN))
    }

    @Test
    fun `the chronometer announces its state before its value`() {
        assertEquals(
            "Active, 42 minutes 18 seconds",
            TimerFormat.elapsedDescription(
                TimedDraftStatus.RUNNING,
                secondsOf(42 * MINUTE + 18),
                EN,
            ),
        )
        assertEquals(
            "Paused, 7 seconds",
            TimerFormat.elapsedDescription(TimedDraftStatus.PAUSED, secondsOf(7), FR),
        )
    }

    // endregion

    private fun treadmill(): SessionEquipment = SessionEquipment(EquipmentType.TREADMILL)
}
