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

/**
 * The screens of the Activity tab (PRD_ACTIVITIES section 7).
 *
 * Each route knows how to write itself as a single string, so the whole stack crosses a
 * `Bundle` as plain text and comes back after process death. `Edit activity` is not a route of
 * its own: PRD 7 says it reuses the form it edits, which is exactly [Log] carrying an id.
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

    data class Log(val sessionId: ActivityId?) : ActivityRoute {
        override val key: String
            get() = if (sessionId == null) LOG_KEY else "$LOG_KEY$SESSION_SEPARATOR${sessionId.value}"
    }

    data object Strength : ActivityRoute {
        override val key: String = "strength"
    }

    companion object {
        private const val LOG_KEY = "log"
        private const val SESSION_SEPARATOR = ':'

        /**
         * The inverse of [key]. An unreadable key falls back to the dashboard rather than
         * throwing: a saved stack outlives the code that wrote it, and losing a screen is a
         * better outcome than a crash on the first frame after an update.
         */
        fun fromKey(key: String): ActivityRoute = when {
            key == Dashboard.key -> Dashboard
            key == History.key -> History
            key == Strength.key -> Strength
            key == LOG_KEY -> Log(sessionId = null)
            key.startsWith("$LOG_KEY$SESSION_SEPARATOR") ->
                Log(ActivityId(key.substringAfter(SESSION_SEPARATOR)))
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
