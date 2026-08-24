package fr.kristenjestin.mue.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where the work a receiver hands off actually runs.
 *
 * `SupervisorJob` so one failed refresh cannot cancel the next one, and a handler that swallows
 * rather than rethrows: an uncaught exception inside a coroutine launched from `onReceive`
 * reaches the default handler and kills the process. A notification that could not be rewritten
 * is a stale notification; it must never be a crash on a phone that has just finished booting.
 */
private val timerReceiverScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> },
)

/**
 * Runs [block] off the main thread and only then declares the broadcast handled.
 *
 * `onReceive` returns in milliseconds and the process becomes killable the moment it does.
 * Without the [BroadcastReceiver.PendingResult] the Room write below would be racing the
 * process's own death — a `Pause` that appears to work, and is simply not there the next time
 * the app is opened. `finish()` is in a `finally` because a `PendingResult` that is never
 * finished leaks the receiver's ANR timer.
 */
internal fun BroadcastReceiver.PendingResult.completeWith(block: suspend () -> Unit) {
    timerReceiverScope.launch {
        try {
            block()
        } finally {
            finish()
        }
    }
}

/**
 * `Pause`, `Resume` and `Discard` from the ongoing notification (PRD_ACTIVITY_TIMER 6.5).
 *
 * These three write a row and start nothing, which is exactly what a receiver is allowed to do.
 * `Finish` is not here: it has to open the prefilled form (FR-TIMER-005), and an activity
 * started from a receiver is refused by the background-activity-start rules of Android 10, so
 * it travels as a `getActivity` `PendingIntent` straight to `MainActivity` instead.
 *
 * Declared `exported="false"`: nothing outside Mue has any business pausing a user's timer, and
 * the `PendingIntent`s that fire it are created by this same process.
 */
class TimerActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = TimerAction.fromIntentAction(intent.action) ?: return

        // Refused rather than served: `Finish` arriving here would mean the notification was
        // built with the wrong kind of `PendingIntent`, and half-doing it would hide that.
        if (action == TimerAction.FINISH) return

        val appContext = context.applicationContext
        val requestedId = TimerIntents.draftIdOf(intent)

        goAsync().completeWith {
            val container = appContext.timerContainer
            /*
             * The extra names the draft the notification was built for; the live row is what
             * answers when there is no extra — an action sent by hand, or by a future surface
             * that has no id to hand. Either way the repository's transitions are idempotent,
             * so a stale id writes nothing.
             */
            val draftId = requestedId ?: container.timedActivityRepository.findLiveDraft()?.id
            if (draftId != null) {
                action.applyTo(container, draftId)
            }
            TimerNotifications.refresh(appContext)
        }
    }
}
