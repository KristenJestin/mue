package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.TimedDraftId
import org.junit.Assert.assertEquals
import org.junit.Test

private val SESSION = ActivityId("7b6a2f1e-0000-4000-8000-000000000001")
private val DRAFT = TimedDraftId("2c1d4e5f-0000-4000-8000-0000000000aa")

/**
 * The Activity tab's routes and the two handovers the timer adds (PRD_ACTIVITIES 7,
 * PRD_ACTIVITY_TIMER 6.2, 6.3, FR-TIMER-001 and 005).
 *
 * All of it is plain Kotlin — a stack is a list and a key is a string — so none of it needs a
 * device to be proved.
 */
class ActivityRouteTest {

    // region keys

    /** Every route has to survive a `Bundle` as text and come back as itself. */
    @Test
    fun `every route round trips through its key`() {
        val routes = listOf(
            ActivityRoute.Dashboard,
            ActivityRoute.History,
            ActivityRoute.Start,
            ActivityRoute.Timer,
            ActivityRoute.Strength,
            ActivityRoute.Log(),
            ActivityRoute.Log(sessionId = SESSION),
            ActivityRoute.Log(draftId = DRAFT),
        )

        routes.forEach { route ->
            assertEquals(route, ActivityRoute.fromKey(route.key))
        }
    }

    /** The two ids share one route and must never be read back as each other. */
    @Test
    fun `a session key and a draft key are told apart`() {
        val session = ActivityRoute.fromKey(ActivityRoute.Log(sessionId = SESSION).key)
        val draft = ActivityRoute.fromKey(ActivityRoute.Log(draftId = DRAFT).key)

        assertEquals(ActivityRoute.Log(sessionId = SESSION), session)
        assertEquals(ActivityRoute.Log(draftId = DRAFT), draft)
    }

    /** No two routes may answer the same key, or a saved stack would restore the wrong screen. */
    @Test
    fun `the keys are distinct`() {
        val keys = listOf(
            ActivityRoute.Dashboard,
            ActivityRoute.History,
            ActivityRoute.Start,
            ActivityRoute.Timer,
            ActivityRoute.Strength,
            ActivityRoute.Log(),
        ).map(ActivityRoute::key)

        assertEquals(keys.size, keys.toSet().size)
    }

    /**
     * Total, and it must stay total as routes are added: a stack written by a build that knew
     * `timer` and restored by one that did not would otherwise crash on the first frame.
     */
    @Test
    fun `an unreadable key falls back on the dashboard`() {
        listOf("", "  ", "timer:extra", "unknown", "logger", "#", ":").forEach { key ->
            assertEquals("`$key`", ActivityRoute.Dashboard, ActivityRoute.fromKey(key))
        }
    }

    // endregion

    // region the stack

    @Test
    fun `the dashboard is never popped or replaced away`() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard))

        stack.replaceTop(ActivityRoute.Timer)

        assertEquals(listOf(ActivityRoute.Dashboard, ActivityRoute.Timer), stack.entries)
    }

    @Test
    fun `replacing the top leaves nothing behind it`() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard, ActivityRoute.Start))

        stack.replaceTop(ActivityRoute.Timer)

        assertEquals(listOf(ActivityRoute.Dashboard, ActivityRoute.Timer), stack.entries)
    }

    /** FR-TIMER-001: the chooser hands over, so back from the timer reaches the dashboard. */
    @Test
    fun `starting a timer replaces the chooser`() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard))
        stack.push(ActivityRoute.Start)

        stack.showTimer()

        assertEquals(listOf(ActivityRoute.Dashboard, ActivityRoute.Timer), stack.entries)
        stack.pop()
        assertEquals(ActivityRoute.Dashboard, stack.current)
    }

    /** PRD 6.4: the banner opens the timer from wherever the tab happened to be. */
    @Test
    fun `opening the timer from elsewhere pushes it`() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard))

        stack.showTimer()

        assertEquals(listOf(ActivityRoute.Dashboard, ActivityRoute.Timer), stack.entries)
    }

    /** PRD 12: asked for twice, it is still one timer screen. */
    @Test
    fun `opening the timer twice changes nothing`() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard))

        stack.showTimer()
        stack.showTimer()

        assertEquals(listOf(ActivityRoute.Dashboard, ActivityRoute.Timer), stack.entries)
    }

    /** FR-TIMER-005: the form takes the stopped timer's place rather than sitting on it. */
    @Test
    fun `finishing replaces the timer with its review`() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard))
        stack.showTimer()

        stack.showReview(DRAFT)

        assertEquals(
            listOf(ActivityRoute.Dashboard, ActivityRoute.Log(draftId = DRAFT)),
            stack.entries,
        )
    }

    /**
     * The same review asked for from the dashboard — a card of FR-TIMER-008 — is a journey and
     * not a handover, so back returns to the list it was opened from.
     */
    @Test
    fun `a review opened from the dashboard is pushed`() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard))

        stack.showReview(DRAFT)

        assertEquals(2, stack.entries.size)
        assertEquals(ActivityRoute.Log(draftId = DRAFT), stack.current)
    }

    @Test
    fun `asking for the review already on screen changes nothing`() {
        val stack = ActivityStack(listOf(ActivityRoute.Dashboard))
        stack.showReview(DRAFT)

        stack.showReview(DRAFT)

        assertEquals(2, stack.entries.size)
    }

    // endregion
}
