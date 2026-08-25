package fr.kristenjestin.mue.timer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.kristenjestin.mue.R
import fr.kristenjestin.mue.domain.logic.TimerElapsed
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.ui.timer.TimerFormat
import fr.kristenjestin.mue.ui.timer.TimerMessages

/**
 * The silent ongoing notification of PRD_ACTIVITY_TIMER 6.5, and the only thing in Mue that
 * talks to the notification manager.
 *
 * **Stateless, and deliberately so.** [refresh] reads the live draft once, derives the elapsed
 * value and posts or cancels; there is no collector anywhere. A long-lived observer of
 * `observeLiveDraft()` would have to be started from `Application.onCreate`, which would open
 * the database on every cold start — including the ones that show no timer at all — and undo the
 * reason `AppContainer` is lazy from top to bottom.
 *
 * It is called at the six moments the notification can be wrong: `Start`, `Pause`, `Resume`,
 * `Finish`, `Discard`, and `BOOT_COMPLETED`. Between them nothing runs: while the timer is
 * active the figure on screen is drawn by the system's own chronometer, from a reference this
 * process wrote once (PRD 10), so no periodic wake-up and no per-second update exist.
 */
internal object TimerNotifications {

    /** PRD 10; the id is the app's, the name the user reads is [TimerMessages.NOTIFICATION_CHANNEL]. */
    const val CHANNEL_ID: String = "mue.timer.ongoing"

    /** FR-TIMER-001 allows exactly one timer, so exactly one notification, reused forever. */
    const val NOTIFICATION_ID: Int = 1

    /**
     * Creates the low-importance channel, idempotently.
     *
     * Called before every post and, more importantly, immediately before the first
     * `POST_NOTIFICATIONS` request (FR-TIMER-012): the system dialog puts the user one tap from
     * the app's notification settings, and a channel that does not exist yet makes that page
     * empty — the user would be looking at nothing to decide about.
     */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat
            .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(TimerMessages.NOTIFICATION_CHANNEL)
            .setVibrationEnabled(false)
            .setSound(null, null)
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /**
     * Reads the one live draft and makes the notification agree with it.
     *
     * No timer, or a draft that has moved to `pending_review`, means the notification goes: PRD
     * 10 has `Finish` and `Discard` cancel it, and both arrive here as an absent live draft.
     */
    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        val container = appContext.timerContainer
        val draft = container.timedActivityRepository.findLiveDraft()
        if (draft == null) {
            cancel(appContext)
            return
        }
        val now = container.clock.now()
        post(appContext, draft, TimerElapsed.of(draft, now).duration, now)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun post(
        context: Context,
        draft: TimedActivityDraft,
        duration: ActivityDuration,
        now: TimerInstant,
    ) {
        val manager = NotificationManagerCompat.from(context)

        /*
         * Checked on every post rather than once at start-up. `POST_NOTIFICATIONS` is revocable
         * from Settings at any moment, and a revoked post is dropped by the OS in silence: with
         * no check here a paused timer would keep showing a running chronometer until something
         * else redrew it. `areNotificationsEnabled` is also the only question that means
         * anything below API 33, where the permission does not exist but the user can still
         * switch the app off.
         */
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permitted || !manager.areNotificationsEnabled()) return

        ensureChannel(context)

        val running = draft.status == TimedDraftStatus.RUNNING
        val label = TimerFormat.activityLabel(
            movement = draft.movement,
            customMovementName = draft.customMovementName,
            equipment = draft.equipment,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon(running))
            .setContentTitle(TimerMessages.ACTIVITY_IN_PROGRESS)
            .setContentText(TimerFormat.notificationText(label, draft.status, duration))
            .setContentIntent(TimerIntents.openTimer(context, draft.id))
            // PRD 6.5: the timer is controlled from here, so it is not swiped away by accident.
            .setOngoing(true)
            // PRD 10: low importance is the channel; this is the per-post promise on top of it.
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Mue measures on this phone alone; there is no companion surface to mirror to.
            .setLocalOnly(true)
            .setShowWhen(running)
            .setUsesChronometer(running)

        if (running) {
            /*
             * The system draws the ticking figure itself, from this one instant, and keeps
             * drawing it with the app's process dead — which is why no service is needed to
             * make a number move.
             *
             * `setWhen` takes a wall-clock epoch and the platform converts it to the
             * chronometer's own base; converting it here as well would subtract the boot
             * offset twice and start the count from somewhere in the last reboot.
             */
            builder.setWhen(now.wallMillis - duration.seconds * TimerElapsed.MILLIS_PER_SECOND)
        }

        /*
         * PRD 6.5 gives the notification two buttons. The paused state carries its frozen
         * `HH:MM:SS` and the word `Paused` in the text line (PRD 11: never the icon alone), and
         * `Discard` is not among them on purpose — FR-TIMER-009 requires an explicit
         * confirmation, which a notification button cannot ask for.
         */
        val primary = if (running) TimerAction.PAUSE else TimerAction.RESUME
        builder.addAction(
            NotificationCompat.Action.Builder(
                if (running) R.drawable.ic_pause else R.drawable.ic_play,
                TimerFormat.primaryAction(draft.status),
                TimerIntents.broadcast(context, primary, draft.id),
            ).build(),
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_square,
                TimerMessages.FINISH,
                TimerIntents.finish(context, draft.id),
            ).build(),
        )

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * The status bar already says which of the two states the timer is in, before the shade is
     * ever pulled down: a ringing bell while it runs, a still one while it waits.
     */
    @DrawableRes
    private fun smallIcon(running: Boolean): Int =
        if (running) R.drawable.ic_bell_ring else R.drawable.ic_bell
}
