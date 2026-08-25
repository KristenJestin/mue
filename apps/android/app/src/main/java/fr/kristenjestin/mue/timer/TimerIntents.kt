package fr.kristenjestin.mue.timer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import fr.kristenjestin.mue.MainActivity
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.di.TimerContainer
import fr.kristenjestin.mue.domain.model.TimedDraftId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Activity Timer's dependencies, reached from the places that have a [Context] and nothing
 * else: the two receivers, the notification and `MainActivity`.
 *
 * Still lazy — `AppContainer.timer` and everything inside it only builds on first touch — so a
 * cold start that routes no timer intent never opens the database.
 */
internal val Context.timerContainer: TimerContainer
    get() = (applicationContext as MueApplication).container.timer

/**
 * The four things the ongoing notification can ask for (PRD_ACTIVITY_TIMER 6.5), each with the
 * `PendingIntent` request code that keeps it apart from the others.
 *
 * **The request codes are the load-bearing part.** `PendingIntent.getBroadcast` matches an
 * existing instance on requestCode plus the intent's *filter* — action, data, type, class,
 * categories — and extras are not part of that comparison. `Pause` and `Resume` differ only by
 * action, but had they shared a code, `FLAG_UPDATE_CURRENT` would have rewritten one into the
 * other and the second button would silently have fired the first one's action.
 *
 * [FINISH] is here for its identity, never for a broadcast: it is delivered by `getActivity`
 * because a receiver that starts an activity is refused from Android 10 (PRD 6.5 wants the
 * prefilled form open, which only an activity can do).
 */
internal enum class TimerAction(val requestCode: Int) {
    PAUSE(1),
    RESUME(2),
    DISCARD(3),
    FINISH(4),
    ;

    val intentAction: String get() = "${TimerIntents.ACTION_PREFIX}$name"

    /**
     * FR-TIMER-004, 005 and 009, all four idempotent by the repository's own contract, so a
     * button pressed twice — or a stale `PendingIntent` fired after the screen did the same
     * thing — writes nothing the second time.
     */
    suspend fun applyTo(container: TimerContainer, draftId: TimedDraftId) {
        val repository = container.timedActivityRepository
        when (this) {
            PAUSE -> repository.pause(draftId, container.clock.now())
            RESUME -> repository.resume(draftId, container.clock.now())
            FINISH -> repository.finish(draftId, container.clock.now())
            DISCARD -> repository.discard(draftId)
        }
    }

    companion object {
        private val byIntentAction: Map<String, TimerAction> =
            entries.associateBy { it.intentAction }

        /** Total and non-throwing: a receiver is handed whatever the system delivers to it. */
        fun fromIntentAction(action: String?): TimerAction? = byIntentAction[action]
    }
}

/**
 * Where the notification of PRD 6.5 wants Mue to open, once it has opened.
 *
 * An `Intent` arrives at the activity, and the screen that has to react to it is several
 * composables below — so the activity posts the destination here and the shell picks it up.
 * One value, consumed once: a recomposition, a tab change or a configuration change must not
 * make the app navigate a second time.
 */
internal sealed interface TimerLaunch {

    /** Tapping the notification body: the timer screen, not whichever tab was last open. */
    data object OpenTimer : TimerLaunch

    /** FR-TIMER-005: `Finish` lands on the prefilled review form for this draft. */
    data class OpenReview(val draftId: TimedDraftId) : TimerLaunch
}

/**
 * Everything the notification, the receivers and `MainActivity` have to agree on: the action
 * names, the one extra, the flags, and the inbox the activity drops a routed intent into.
 */
internal object TimerIntents {

    /** Fully qualified, because an implicit-looking action name is a name someone else can use. */
    const val ACTION_PREFIX: String = "fr.kristenjestin.mue.timer.action."

    /** Tapping the notification itself; distinct from the four [TimerAction]s. */
    const val ACTION_OPEN_TIMER: String = "${ACTION_PREFIX}OPEN_TIMER"

    const val EXTRA_DRAFT_ID: String = "fr.kristenjestin.mue.timer.extra.DRAFT_ID"

    /** The notification body, which is neither of the two action buttons. */
    private const val CONTENT_REQUEST_CODE: Int = 0

    /**
     * `FLAG_IMMUTABLE` is not advice: from API 31 a `PendingIntent` created without it or
     * `FLAG_MUTABLE` throws at construction, and nothing here needs the system to fill anything
     * in. `FLAG_UPDATE_CURRENT` is what keeps the draft id in the extras current when a second
     * timer replaces the first without the notification ever being cancelled in between.
     */
    private const val FLAGS: Int =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /**
     * `SINGLE_TOP` plus `CLEAR_TOP` reuses the running instance and delivers to `onNewIntent`
     * instead of stacking a second copy of Mue behind the first — which is the whole reason
     * `MainActivity` can keep `launchMode="standard"` and leave back-stack behaviour on every
     * other screen exactly as it shipped.
     */
    private const val ACTIVITY_FLAGS: Int =
        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

    /** PRD 6.5: touching the notification opens the timer screen. */
    fun openTimer(context: Context, draftId: TimedDraftId): PendingIntent =
        PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE,
            activityIntent(context, ACTION_OPEN_TIMER, draftId),
            FLAGS,
        )

    /**
     * FR-TIMER-005 from the notification. An activity, never a broadcast: background activity
     * starts from a receiver are refused from Android 10, so a `Finish` that went through
     * `TimerActionReceiver` would stop the timer and then fail to show the form it promised.
     */
    fun finish(context: Context, draftId: TimedDraftId): PendingIntent =
        PendingIntent.getActivity(
            context,
            TimerAction.FINISH.requestCode,
            activityIntent(context, TimerAction.FINISH.intentAction, draftId),
            FLAGS,
        )

    /** `Pause`, `Resume` and `Discard`: they write a row and start nothing. */
    fun broadcast(
        context: Context,
        action: TimerAction,
        draftId: TimedDraftId,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.requestCode,
        broadcastIntent(context, action, draftId),
        FLAGS,
    )

    fun broadcastIntent(context: Context, action: TimerAction, draftId: TimedDraftId): Intent =
        Intent(context, TimerActionReceiver::class.java)
            .setAction(action.intentAction)
            .putExtra(EXTRA_DRAFT_ID, draftId.value)

    fun draftIdOf(intent: Intent?): TimedDraftId? =
        intent?.getStringExtra(EXTRA_DRAFT_ID)?.takeIf { it.isNotBlank() }?.let(::TimedDraftId)

    /**
     * What an intent handed to `MainActivity` asks for, or null when it is an ordinary launch.
     *
     * Reads nothing but the intent: the launcher's own `MAIN` intent must not cost a database
     * open, and this runs on every `onCreate`.
     */
    fun launchOf(intent: Intent?): TimerLaunch? = when (intent?.action) {
        ACTION_OPEN_TIMER -> TimerLaunch.OpenTimer
        TimerAction.FINISH.intentAction ->
            draftIdOf(intent)?.let(TimerLaunch::OpenReview)
        else -> null
    }

    private val _pendingLaunch = MutableStateFlow<TimerLaunch?>(null)

    /** Collected by the shell; null whenever there is nothing left to route. */
    val pendingLaunch: StateFlow<TimerLaunch?> = _pendingLaunch.asStateFlow()

    fun publish(launch: TimerLaunch) {
        _pendingLaunch.value = launch
    }

    /**
     * Clears the value that was acted on, and only that one: a second intent arriving while the
     * first is being routed must survive the first one's acknowledgement.
     */
    fun consume(launch: TimerLaunch) {
        _pendingLaunch.compareAndSet(launch, null)
    }

    private fun activityIntent(
        context: Context,
        action: String,
        draftId: TimedDraftId,
    ): Intent = Intent(context, MainActivity::class.java)
        .setAction(action)
        .setFlags(ACTIVITY_FLAGS)
        .putExtra(EXTRA_DRAFT_ID, draftId.value)
}
