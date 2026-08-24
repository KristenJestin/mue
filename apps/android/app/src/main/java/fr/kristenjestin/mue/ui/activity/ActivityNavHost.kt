package fr.kristenjestin.mue.ui.activity

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion

/**
 * The Activity tab, which is the one tab that is not a single screen (PRD_ACTIVITIES 7).
 *
 * The tab bar stays above this host and never learns that a sub-screen is open: `Log activity`
 * and `Strength session` keep it visible like every other screen, and a draft left behind
 * survives being abandoned through a tab.
 */
@Composable
fun ActivityNavHost(modifier: Modifier = Modifier) {
    val stack = rememberActivityStack()
    ActivityNavHost(stack = stack, modifier = modifier) { route ->
        ActivityDestination(route = route, stack = stack, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The stack mechanics, with the screens left to the caller so tests can drive them without a
 * database behind them — the same split the tab shell uses.
 *
 * Each route is composed inside its own [rememberSaveableStateHolder] slot, so returning to the
 * history finds it scrolled where it was left, and a popped screen gives its slot up rather than
 * greeting the next visit with an abandoned form.
 */
@Composable
internal fun ActivityNavHost(
    stack: ActivityStack,
    modifier: Modifier = Modifier,
    content: @Composable (ActivityRoute) -> Unit,
) {
    val screenStates = rememberSaveableStateHolder()
    val keys = stack.entries.map(ActivityRoute::key)

    /*
     * Nested handlers resolve innermost first, so this one answers before the tab shell's and
     * back moves within the module instead of leaving it. On the dashboard it is disabled and
     * the shell takes over, which is what returns to `Entry`.
     */
    BackHandler(enabled = stack.canGoBack) { stack.pop() }

    val live = remember { mutableSetOf<String>() }
    LaunchedEffect(keys) {
        live.filterNot(keys::contains).forEach { gone ->
            screenStates.removeState(gone)
            live.remove(gone)
        }
        live.addAll(keys)
    }

    // Both directions are resolved here because `transitionSpec` runs outside composition.
    val deeper = activityStackTransition(deeper = true)
    val shallower = activityStackTransition(deeper = false)

    AnimatedContent(
        targetState = stack.entries,
        modifier = modifier,
        transitionSpec = { if (targetState.size >= initialState.size) deeper else shallower },
        contentKey = { entries -> entries.last().key },
        label = "activityStack",
    ) { entries ->
        val route = entries.last()
        screenStates.SaveableStateProvider(route.key) { content(route) }
    }
}

/** Where each screen's callbacks land on the stack. */
@Composable
private fun ActivityDestination(
    route: ActivityRoute,
    stack: ActivityStack,
    modifier: Modifier = Modifier,
) {
    when (route) {
        ActivityRoute.Dashboard -> ActivityScreen(
            onLogActivity = { stack.push(ActivityRoute.Log(sessionId = null)) },
            onSeeAll = { stack.push(ActivityRoute.History) },
            onOpenSession = { sessionId -> stack.push(ActivityRoute.Log(sessionId)) },
            modifier = modifier,
        )

        ActivityRoute.History -> ActivityHistoryScreen(
            onBack = { stack.pop() },
            onOpenSession = { sessionId -> stack.push(ActivityRoute.Log(sessionId)) },
            modifier = modifier,
        )

        // A save returns to whatever opened the form — the dashboard, or the history it was
        // reached through — rather than always to the dashboard, which would lose the reader's
        // place in a list they may have scrolled a long way down.
        is ActivityRoute.Log -> LogActivityScreen(
            sessionId = route.sessionId,
            onBack = { stack.pop() },
            onOpenStrengthSession = { stack.push(ActivityRoute.Strength) },
            onSaved = { stack.pop() },
            onDeleted = { stack.pop() },
            modifier = modifier,
        )

        ActivityRoute.Strength -> StrengthSessionScreen(
            onBack = { stack.pop() },
            onSaved = { stack.pop(count = 2) },
            modifier = modifier,
        )
    }
}

/**
 * Going deeper raises the new screen over the old one (PRD_ACTIVITIES 14.2); coming back lets it
 * settle again. Reduced motion drops the movement and keeps the cross-fade, as everywhere else.
 */
@Composable
@ReadOnlyComposable
private fun activityStackTransition(deeper: Boolean): ContentTransform {
    val enterSpec = MueMotion.spec<Float>(MueMotion.ActivityOpenMillis, MueMotion.Enter)
    val exitSpec = MueMotion.spec<Float>(MueMotion.ActivityOpenMillis, MueMotion.Exit)
    if (LocalReduceMotion.current) {
        return fadeIn(enterSpec) togetherWith fadeOut(exitSpec)
    }
    val offsetSpec = MueMotion.spec<IntOffset>(MueMotion.ActivityOpenMillis, MueMotion.Standard)
    val direction = if (deeper) 1 else -1
    return (
        slideInVertically(offsetSpec) { height -> direction * height / 8 } + fadeIn(enterSpec)
        ) togetherWith (
        slideOutVertically(offsetSpec) { height -> -direction * height / 8 } + fadeOut(exitSpec)
        )
}
