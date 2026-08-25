package fr.kristenjestin.mue.timer

import fr.kristenjestin.mue.domain.model.TimedDraftId
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The parts of the Android surface that are decidable without a device.
 *
 * Every rule asserted here produces, when broken, a bug that shows up only on a phone and only
 * some of the time — a `Resume` button that pauses, an action name another app can claim, a
 * notification tap that lands on the wrong screen. They are cheap to lock down here and
 * expensive to find anywhere else.
 */
class TimerIntentsTest {

    @After
    fun clearInbox() {
        TimerIntents.pendingLaunch.value?.let(TimerIntents::consume)
    }

    /**
     * The trap this file exists for. `PendingIntent` equality ignores extras and compares the
     * request code with the intent's filter, and `Pause` and `Resume` differ only by action —
     * so a shared request code plus `FLAG_UPDATE_CURRENT` rewrites one into the other, and the
     * second button silently fires the first one's action.
     */
    @Test
    fun `every action has its own request code`() {
        val codes = TimerAction.entries.map { it.requestCode }

        assertEquals(codes.size, codes.toSet().size, "request codes collide: $codes")
    }

    /** Zero belongs to the notification body, which is not one of the buttons. */
    @Test
    fun `no action claims the request code of the notification body`() {
        assertTrue(TimerAction.entries.none { it.requestCode == 0 })
    }

    @Test
    fun `action names are fully qualified and distinct`() {
        val actions = TimerAction.entries.map { it.intentAction } + TimerIntents.ACTION_OPEN_TIMER

        assertTrue(actions.all { it.startsWith("fr.kristenjestin.mue.timer.action.") })
        assertEquals(actions.size, actions.toSet().size, "action names collide: $actions")
    }

    @Test
    fun `the four actions of the notification keep their names`() {
        assertEquals(
            listOf(
                "fr.kristenjestin.mue.timer.action.PAUSE",
                "fr.kristenjestin.mue.timer.action.RESUME",
                "fr.kristenjestin.mue.timer.action.DISCARD",
                "fr.kristenjestin.mue.timer.action.FINISH",
            ),
            TimerAction.entries.map { it.intentAction },
        )
    }

    /** A receiver is handed whatever the system delivers, including nothing at all. */
    @Test
    fun `an unknown action resolves to nothing rather than throwing`() {
        assertNull(TimerAction.fromIntentAction(null))
        assertNull(TimerAction.fromIntentAction(""))
        assertNull(TimerAction.fromIntentAction("android.intent.action.BOOT_COMPLETED"))
        assertNull(TimerAction.fromIntentAction(TimerIntents.ACTION_OPEN_TIMER))
    }

    @Test
    fun `each action resolves back to itself`() {
        TimerAction.entries.forEach { action ->
            assertSame(action, TimerAction.fromIntentAction(action.intentAction))
        }
    }

    /** PRD 10: one timer, one notification, and an id that must never drift. */
    @Test
    fun `the channel and the notification keep their identifiers`() {
        assertEquals("mue.timer.ongoing", TimerNotifications.CHANNEL_ID)
        assertEquals(1, TimerNotifications.NOTIFICATION_ID)
    }

    @Test
    fun `a published destination is readable once`() {
        val launch = TimerLaunch.OpenReview(TimedDraftId("draft-1"))

        TimerIntents.publish(launch)
        assertEquals(launch, TimerIntents.pendingLaunch.value)

        TimerIntents.consume(launch)
        assertNull(TimerIntents.pendingLaunch.value)
    }

    /**
     * A second notification tap arriving while the first is still being routed must survive the
     * first one's acknowledgement, or the app would swallow the destination the user just chose.
     */
    @Test
    fun `acknowledging a stale destination leaves a newer one alone`() {
        val first = TimerLaunch.OpenReview(TimedDraftId("draft-1"))
        val second = TimerLaunch.OpenTimer

        TimerIntents.publish(first)
        TimerIntents.publish(second)
        TimerIntents.consume(first)

        assertEquals(second, TimerIntents.pendingLaunch.value)
    }

    /** Two taps on the same draft are the same destination, so the shell navigates once. */
    @Test
    fun `the same review destination is one value`() {
        assertEquals(
            TimerLaunch.OpenReview(TimedDraftId("draft-1")),
            TimerLaunch.OpenReview(TimedDraftId("draft-1")),
        )
    }
}
