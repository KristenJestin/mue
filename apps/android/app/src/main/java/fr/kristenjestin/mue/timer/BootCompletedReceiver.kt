package fr.kristenjestin.mue.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reposts the ongoing notification after the phone restarts (PRD_ACTIVITY_TIMER 6.5).
 *
 * Android drops every notification at boot. A timer restored from Room would therefore come
 * back invisible outside the app — and the notification is precisely how it is controlled from
 * outside the app. This receiver reads the stored state and rebuilds a notification whose whole
 * content is derived from it; it writes nothing, starts no service and schedules no work.
 *
 * It is also the only mechanism that could ever have served this case: a foreground service
 * cannot be started from `BOOT_COMPLETED` on API 31 and above, so the section 16 fallback would
 * not have helped here even if the app had been allowed to use it.
 *
 * Two manifest details are load-bearing:
 *
 * - `exported="true"`. A receiver that is not exported never receives a broadcast sent by the
 *   system, and the failure is silent — the timer simply has no notification after a reboot,
 *   which is a thing you only find out on a real phone, hours later.
 * - `directBootAware="false"`, and no `LOCKED_BOOT_COMPLETED` filter. Mue's database is
 *   credential-encrypted; reading it before the user has unlocked the phone would fail. The
 *   plain `BOOT_COMPLETED` broadcast arrives after unlock, which is when the timer can actually
 *   be read.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        // `refresh` cancels when nothing is live, so a boot with no timer costs one read.
        goAsync().completeWith { TimerNotifications.refresh(appContext) }
    }
}
