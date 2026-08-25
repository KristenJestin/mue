package fr.kristenjestin.mue.timer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * `POST_NOTIFICATIONS` for the Activity Timer (FR-TIMER-012).
 *
 * The permission is asked for once, in the context of the first `Start timer`, and never again
 * on its own. Refusing costs the notification and nothing else: the timer starts, pauses,
 * resumes and finishes from inside Mue exactly as before, and the chassis banner still carries
 * it. Nothing here may ever gate a transition.
 */
internal object TimerNotificationPermission {

    /**
     * `POST_NOTIFICATIONS` only exists from Android 13. Below it there is nothing to ask for —
     * notifications are granted at install and can only be switched off in Settings — so the
     * first-start question of FR-TIMER-012 simply does not arise.
     */
    val IS_RUNTIME_PERMISSION: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Whether a notification would actually appear.
     *
     * Deliberately `areNotificationsEnabled()` and not `checkSelfPermission`: on Android 13 the
     * two agree, and below it only this one is true — the user can still have switched Mue's
     * notifications off from Settings on an Android 12 phone, and the app has to know.
     */
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * FR-TIMER-012's way back after a refusal: the profile offers
     * [TimerMessages.OPEN_NOTIFICATION_SETTINGS][fr.kristenjestin.mue.ui.timer.TimerMessages.OPEN_NOTIFICATION_SETTINGS]
     * rather than asking a second time, because a second system prompt after a denial is never
     * shown anyway.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

/**
 * What a screen needs to know about the notification permission, and the one thing it can do
 * about it.
 */
@Stable
internal class TimerNotificationPermissionState internal constructor(
    /** A notification would be shown. Re-read on every return to the app. */
    val isGranted: Boolean,
    /**
     * The system prompt is still worth showing: Android 13 or later, not granted, and never
     * asked before. Once this is false the only route left is
     * [TimerNotificationPermission.settingsIntent].
     */
    val canRequest: Boolean,
    private val onRequest: () -> Unit,
) {
    /** Shows the system prompt, at most once in the life of the install. */
    fun request() {
        if (canRequest) onRequest()
    }
}

/**
 * The permission, wired to the persisted flag that makes FR-TIMER-012 implementable.
 *
 * **`shouldShowRequestPermissionRationale` cannot answer this question.** It returns `false`
 * before the very first request and `false` again after a permanent denial, so the two states a
 * "do not ask again" rule has to tell apart are the same value. A boolean written the moment
 * the prompt is launched is the only implementation that distinguishes them — which is what
 * `TimerPreferencesRepository` exists for.
 *
 * The flag is written *before* the answer comes back, not after: a user who dismisses the
 * dialog by tapping outside it has still been asked, and the system counts that dismissal
 * against the two attempts it allows.
 */
// The permission name is a compile-time string, copied into the class file rather than looked
// up, and it is only ever passed to the launcher when `canRequest` is true — which requires
// Android 13. On an older release the constant is never read at all.
@SuppressLint("InlinedApi")
@Composable
internal fun rememberTimerNotificationPermission(): TimerNotificationPermissionState {
    val context = LocalContext.current
    val preferences = remember(context) { context.timerContainer.timerPreferencesRepository }
    val scope = rememberCoroutineScope()

    // `true` while the stored value is still being read: the safe default is not to ask.
    val alreadyRequested by preferences.notificationPermissionRequested
        .collectAsStateWithLifecycle(initialValue = true)

    var granted by remember { mutableStateOf(TimerNotificationPermission.isGranted(context)) }

    // Revocable from Settings while Mue is in the background, so it is re-read on every return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = TimerNotificationPermission.isGranted(context)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    return TimerNotificationPermissionState(
        isGranted = granted,
        canRequest = TimerNotificationPermission.IS_RUNTIME_PERMISSION &&
            !granted &&
            !alreadyRequested,
        onRequest = {
            // The prompt puts the user one tap from the app's notification settings; the
            // channel is created first so that page is not empty when they arrive.
            TimerNotifications.ensureChannel(context)
            scope.launch { preferences.setNotificationPermissionRequested(true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
    )
}
