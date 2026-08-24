package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.TimedDraftId

/**
 * The screens of the Activity tab (PRD_ACTIVITIES section 7, PRD_ACTIVITY_TIMER 6.2 and 6.3).
 *
 * Each route knows how to write itself as a single string, so the whole stack crosses a
 * `Bundle` as plain text and comes back after process death. `Edit activity` is not a route of
 * its own: PRD 7 says it reuses the form it edits, which is exactly [Log] carrying an id — and
 * neither is the timer's review, which is the same form carrying a draft id instead.
 */
@Immutable
sealed interface ActivityRoute {

    /** Identifies the route in the saved stack, in its state slot and to `AnimatedContent`. */
    val key: String

    data object Dashboard : ActivityRoute {
        override val key: String = "dashboard"
    }

    data object History : ActivityRoute {
        override val key: String = "history"
    }

    /** PRD_ACTIVITY_TIMER 6.2: choosing what to start, before any timer exists. */
    data object Start : ActivityRoute {
        override val key: String = "start"
    }

    /** PRD_ACTIVITY_TIMER 6.3: the one running timer, whichever surface asked for it. */
    data object Timer : ActivityRoute {
        override val key: String = "timer"
    }

    /**
     * The log form, in its three readings: empty, on a stored session, or on a finished timer
     * (PRD_ACTIVITY_TIMER FR-TIMER-005 and 008).
     *
     * The two ids are exclusive by construction — nothing ever builds one carrying both — and
     * they use different separators so the key says which of the two it is.
     */
    data class Log(
        val sessionId: ActivityId? = null,
        val draftId: TimedDraftId? = null,
    ) : ActivityRoute {
        override val key: String
            get() = when {
                sessionId != null -> "$LOG_KEY$SESSION_SEPARATOR${sessionId.value}"
                draftId != null -> "$LOG_KEY$DRAFT_SEPARATOR${draftId.value}"
                else -> LOG_KEY
            }
    }

    data object Strength : ActivityRoute {
        override val key: String = "strength"
    }

    companion object {
        private const val LOG_KEY = "log"
        private const val SESSION_SEPARATOR = ':'

        /** Neither separator can occur in the other's id, which are both stored UUIDs. */
        private const val DRAFT_SEPARATOR = '#'

        /**
         * The inverse of [key]. An unreadable key falls back to the dashboard rather than
         * throwing: a saved stack outlives the code that wrote it, and losing a screen is a
         * better outcome than a crash on the first frame after an update.
         *
         * This is why it stays **total** as routes are added: a stack saved by a build that
         * knew `timer` and restored by one that did not would otherwise take the app down on
         * its first frame.
         */
        fun fromKey(key: String): ActivityRoute = when {
            key == Dashboard.key -> Dashboard
            key == History.key -> History
            key == Strength.key -> Strength
            key == Start.key -> Start
            key == Timer.key -> Timer
            key == LOG_KEY -> Log()
            key.startsWith("$LOG_KEY$SESSION_SEPARATOR") ->
                Log(sessionId = ActivityId(key.substringAfter(SESSION_SEPARATOR)))

            key.startsWith("$LOG_KEY$DRAFT_SEPARATOR") ->
                Log(draftId = TimedDraftId(key.substringAfter(DRAFT_SEPARATOR)))

            else -> Dashboard
        }
    }
}

/**
 * The Activity tab's own back stack.
 *
 * The base shell has none — its tabs are siblings — but this tab holds four destinations, so it
 * carries the smallest thing that can model them: a list whose last entry is what is on screen.
 * Everything a navigation library would add here (a graph, entry providers, a lifecycle per
 * entry) would only re-describe that list.
 */
@Stable
class ActivityStack internal constructor(entries: List<ActivityRoute>) {

    var entries: List<ActivityRoute> by mutableStateOf(
        entries.ifEmpty { listOf(ActivityRoute.Dashboard) },
    )
        private set

    val current: ActivityRoute get() = entries.last()

    /** False on the dashboard, where back belongs to the tab shell and leaves the module. */
    val canGoBack: Boolean get() = entries.size > 1

    fun push(route: ActivityRoute) {
        entries = entries + route
    }

    /**
     * Drops the top [count] screens, never the dashboard.
     *
     * Saving from the detailed strength editor pops two at once: the form underneath it holds
     * the very same session (PRD 9.1), so returning to it after a save would offer to save it
     * again.
     */
    fun pop(count: Int = 1) {
        entries = entries.take((entries.size - count).coerceAtLeast(1))
    }

    /**
     * Swaps the screen on top for another, leaving nothing behind it.
     *
     * `Start timer` and `Finish` are both handovers rather than journeys: going back from the
     * running timer must reach the dashboard and not the chooser that would try to start a
     * second one, and going back from the review form must not reach a timer that has already
     * stopped. On the dashboard — which is never popped — this is simply a push.
     */
    fun replaceTop(route: ActivityRoute) {
        entries = entries.take((entries.size - 1).coerceAtLeast(1)) + route
    }
}

private val ActivityStackSaver: Saver<ActivityStack, Any> = listSaver(
    save = { stack -> stack.entries.map(ActivityRoute::key) },
    restore = { keys -> ActivityStack(keys.map { key -> ActivityRoute.fromKey(key) }) },
)

/** A stack that survives rotation, a trip through another tab, and process death. */
@Composable
fun rememberActivityStack(): ActivityStack = rememberSaveable(saver = ActivityStackSaver) {
    ActivityStack(listOf(ActivityRoute.Dashboard))
}
