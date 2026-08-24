package fr.kristenjestin.mue

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import fr.kristenjestin.mue.timer.TimerAction
import fr.kristenjestin.mue.timer.TimerIntents
import fr.kristenjestin.mue.timer.TimerLaunch
import fr.kristenjestin.mue.timer.TimerNotifications
import fr.kristenjestin.mue.timer.timerContainer
import fr.kristenjestin.mue.ui.navigation.MueApp
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.launch

/**
 * The single activity, and — since the Activity Timer — the one place an intent from outside
 * the app arrives.
 *
 * `launchMode` stays `standard`. Changing it to `singleTop` or `singleTask` would rewrite the
 * back-stack semantics of every screen in Mue to serve one notification; the notification's own
 * `PendingIntent`s carry `FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP` instead, which
 * reuses the running instance for those intents alone and leaves everything else untouched.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        /*
         * Only on a genuinely new launch. A restored instance is handed the intent that started
         * it all over again, and routing it a second time would take the user off whatever
         * screen they had come back to.
         */
        if (savedInstanceState == null) route(intent)

        setContent {
            MueTheme {
                MueApp()
            }
        }
    }

    /**
     * Where the notification of PRD 6.5 is actually honoured.
     *
     * `CLEAR_TOP or SINGLE_TOP` delivers here rather than building a second copy of Mue — but
     * delivery alone changes nothing on screen. Without this routing the tap would simply
     * resume the app on whichever tab was last open, and the timer the user meant to reach
     * would be one they still had to go and find.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        when (val launch = TimerIntents.launchOf(intent)) {
            null -> Unit

            is TimerLaunch.OpenTimer -> TimerIntents.publish(launch)

            /*
             * FR-TIMER-005 from the notification, in the order PRD 10 asks for: the timer is
             * stopped, the notification goes, and only then is the review announced — so the
             * form never opens on a draft that is still running.
             *
             * The write happens here rather than in a receiver because this is the one place
             * allowed to both write and show a screen. Cancelling it (a user who leaves
             * immediately) loses nothing measured: the draft stays exactly as it was, with its
             * notification, and the same button finishes it next time.
             */
            is TimerLaunch.OpenReview -> lifecycleScope.launch {
                TimerAction.FINISH.applyTo(timerContainer, launch.draftId)
                TimerNotifications.refresh(applicationContext)
                TimerIntents.publish(launch)
            }
        }
    }
}
