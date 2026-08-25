package fr.kristenjestin.mue.timer

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.di.TimerContainer
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.ui.timer.TimerMessages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import kotlin.math.abs

/**
 * The ongoing notification of PRD_ACTIVITY_TIMER 6.5, posted for real and read back off the
 * system.
 *
 * These are the assertions no JVM test can make: whether the platform was actually handed a
 * chronometer, whether the reference it was given points at the right instant, and whether a
 * broadcast sent from outside the app reaches the receiver and survives the return of
 * `onReceive`.
 *
 * The clock is supplied as a value on the way in — the repository takes the [TimerInstant]
 * rather than reading one — so a timer that has been running for an hour and a half is two
 * numbers here rather than an hour and a half of waiting.
 */
@RunWith(AndroidJUnit4::class)
class TimerNotificationsTest {

    private lateinit var context: Context
    private lateinit var container: TimerContainer

    private val repository get() = container.timedActivityRepository

    /** A running timer that started 1 h 23 min 20 s ago, on both clocks at once. */
    private val elapsedMillis = 5_000_000L

    /** The instant the draft was started with, so a pause can be an exact figure. */
    private lateinit var startedAt: TimerInstant

    @Before
    fun grantAndClear() = runTest {
        context = ApplicationProvider.getApplicationContext<Context>().applicationContext
        container = context.timerContainer
        grantNotifications()
        clearDrafts()
        TimerNotifications.cancel(context)
    }

    @After
    fun clear() = runTest {
        clearDrafts()
        TimerNotifications.cancel(context)
    }

    @Test
    fun aRunningTimerIsDrawnByThePlatformsOwnChronometer() = runTest {
        startRunning()

        TimerNotifications.refresh(context)

        val posted = awaitNotification()
        assertEquals(TimerMessages.ACTIVITY_IN_PROGRESS, posted.title)
        // PRD 6.5: while it runs, the text line is free to name the activity.
        assertEquals("Treadmill walk", posted.text)
        assertTrue(
            "the running notification must carry a chronometer",
            posted.usesChronometer,
        )

        /*
         * `setWhen` is a wall-clock instant and the platform derives the chronometer's base
         * from it. Converting it here as well would subtract the boot offset twice, so the
         * assertion is deliberately against `currentTimeMillis` and not `elapsedRealtime`.
         */
        val expected = System.currentTimeMillis() - elapsedMillis
        val drift = abs(posted.notification.`when` - expected)
        assertTrue("the chronometer starts $drift ms away from the elapsed value", drift < 5_000)

        assertEquals(listOf(TimerMessages.PAUSE, TimerMessages.FINISH), posted.actionTitles)
    }

    @Test
    fun aPausedTimerFreezesItsFigureAndSaysWhy() = runTest {
        val draft = startRunning()
        // Exactly `elapsedMillis` after the start, so the frozen figure is a fixed string and
        // not a race against however long the write above took.
        repository.pause(draft.id, startedAt.plus(elapsedMillis))

        TimerNotifications.refresh(context)

        val posted = awaitNotification { !it.usesChronometer }
        assertFalse(
            "the chronometer must be switched off when nothing is being measured",
            posted.usesChronometer,
        )
        // PRD 11: the state is a word, never a colour or a missing animation.
        assertEquals("Treadmill walk · 01:23:20 · ${TimerMessages.PAUSED}", posted.text)
        assertEquals(listOf(TimerMessages.RESUME, TimerMessages.FINISH), posted.actionTitles)
    }

    /** PRD 10: it is the notification the timer is controlled from, so it is not swiped away. */
    @Test
    fun theNotificationIsOngoingSilentAndAStopwatch() = runTest {
        startRunning()

        TimerNotifications.refresh(context)

        val notification = awaitNotification().notification
        assertEquals(TimerNotifications.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.CATEGORY_STOPWATCH, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    /** FR-TIMER-005 and 009 both arrive here as an absent live draft. */
    @Test
    fun aRefreshWithNothingLiveTakesTheNotificationAway() = runTest {
        val draft = startRunning()
        TimerNotifications.refresh(context)
        awaitNotification()

        repository.discard(draft.id)
        TimerNotifications.refresh(context)

        awaitNoNotification()
    }

    /**
     * What `BootCompletedReceiver` does, minus the reboot: Android drops every notification at
     * boot, and the whole content of the replacement is derived from the stored row.
     */
    @Test
    fun aClearedNotificationIsRebuiltFromTheStoredRowAlone() = runTest {
        startRunning()
        TimerNotifications.refresh(context)
        awaitNotification()
        TimerNotifications.cancel(context)
        awaitNoNotification()

        TimerNotifications.refresh(context)

        val posted = awaitNotification()
        assertEquals("Treadmill walk", posted.text)
        assertTrue(posted.usesChronometer)
    }

    /**
     * The receiver, reached the way the notification reaches it — a real broadcast — so this
     * covers the `goAsync` hand-off as well as the write. Without the `PendingResult` the write
     * would be racing the death of the process that `onReceive` returning makes killable.
     */
    @Test
    fun aBroadcastPausesTheTimerAndRewritesTheNotification() = runTest {
        val draft = startRunning()
        TimerNotifications.refresh(context)

        context.sendBroadcast(TimerIntents.broadcastIntent(context, TimerAction.PAUSE, draft.id))

        val paused = awaitStatus(TimedDraftStatus.PAUSED)
        /*
         * FR-TIMER-004: the closed segment is banked, never re-measured. The receiver reads the
         * real clock, so the figure is the elapsed value plus however long the broadcast took —
         * a few hundred milliseconds, and never less than the value that was already measured.
         */
        val banked = paused.accumulatedActive.seconds.toLong()
        assertTrue("banked $banked seconds", banked in 5_000L..5_010L)

        val posted = awaitNotification { !it.usesChronometer }
        assertEquals(listOf(TimerMessages.RESUME, TimerMessages.FINISH), posted.actionTitles)
    }

    /** FR-TIMER-004 again, the other way, on the request code that must not be `Pause`'s. */
    @Test
    fun aBroadcastResumesTheTimerAgain() = runTest {
        val draft = startRunning()
        repository.pause(draft.id, now())

        context.sendBroadcast(TimerIntents.broadcastIntent(context, TimerAction.RESUME, draft.id))

        awaitStatus(TimedDraftStatus.RUNNING)
    }

    /** FR-TIMER-009 travels as a broadcast too: it writes a row and starts nothing. */
    @Test
    fun aBroadcastDiscardsTheTimerAndItsNotification() = runTest {
        val draft = startRunning()
        TimerNotifications.refresh(context)
        awaitNotification()

        context.sendBroadcast(TimerIntents.broadcastIntent(context, TimerAction.DISCARD, draft.id))

        awaitNoNotification()
        assertEquals(null, repository.findLiveDraft())
    }

    /**
     * `Finish` is delivered to the activity, never here: a receiver cannot start one from the
     * background on Android 10 and later, and half-serving it would hide that.
     */
    @Test
    fun theReceiverRefusesFinish() = runTest {
        val draft = startRunning()

        context.sendBroadcast(TimerIntents.broadcastIntent(context, TimerAction.FINISH, draft.id))

        // Nothing to wait for, so the receiver is given the same grace the other cases get.
        repeat(20) {
            assertEquals(
                TimedDraftStatus.RUNNING,
                requireNotNull(repository.findLiveDraft()).status,
            )
            Thread.sleep(POLL_MILLIS)
        }
    }

    // region driving

    private suspend fun startRunning(): TimedActivityDraft {
        val now = now()
        val started = TimerInstant(
            wallMillis = now.wallMillis - elapsedMillis,
            elapsedRealtimeMillis = now.elapsedRealtimeMillis - elapsedMillis,
        )
        startedAt = started
        return repository.start(
            request = StartTimerRequest(
                movement = Movement.WALKING,
                environment = ActivityEnvironment.INDOOR,
                equipment = listOf(SessionEquipment(equipmentType = EquipmentType.TREADMILL)),
            ),
            now = started,
        ).draft
    }

    private fun now(): TimerInstant = container.clock.now()

    /** Both clocks moved by the same amount, so the boot reference is untouched. */
    private fun TimerInstant.plus(millis: Long): TimerInstant =
        TimerInstant(wallMillis + millis, elapsedRealtimeMillis + millis)

    private suspend fun clearDrafts() {
        repository.findLiveDraft()?.let { repository.discard(it.id) }
        repository.observeDraftsToReview().first().forEach { repository.discard(it.id) }
    }

    private suspend fun awaitStatus(status: TimedDraftStatus): TimedActivityDraft {
        repeat(POLL_ATTEMPTS) {
            val draft = repository.findLiveDraft()
            if (draft?.status == status) return draft
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("the draft never reached $status")
    }

    private fun awaitNotification(matching: (Posted) -> Boolean = { true }): Posted {
        repeat(POLL_ATTEMPTS) {
            posted()?.takeIf(matching)?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("no matching notification was ever posted")
    }

    private fun awaitNoNotification() {
        repeat(POLL_ATTEMPTS) {
            if (posted() == null) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("the notification is still showing")
    }

    private fun posted(): Posted? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications
        .firstOrNull { it.id == TimerNotifications.NOTIFICATION_ID }
        ?.let(::Posted)

    /** The fields of a posted notification, read back the way the shade reads them. */
    private class Posted(status: StatusBarNotification) {
        val notification: Notification = status.notification

        val title: String?
            get() = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        val text: String?
            get() = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        val usesChronometer: Boolean
            get() = notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)

        val actionTitles: List<String>
            get() = notification.actions?.map { it.title.toString() } ?: emptyList()
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }

    // endregion

    private companion object {
        const val POLL_MILLIS = 50L
        const val POLL_ATTEMPTS = 100
    }
}
