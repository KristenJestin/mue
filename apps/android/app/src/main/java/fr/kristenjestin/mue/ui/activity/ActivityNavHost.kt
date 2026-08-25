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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.timer.StartActivityScreen
import fr.kristenjestin.mue.ui.timer.TimerScreen
import fr.kristenjestin.mue.ui.timer.TimerViewModel
import fr.kristenjestin.mue.ui.timer.timerViewModel

/**
 * The Activity tab, which is the one tab that is not a single screen (PRD_ACTIVITIES 7).
 *
 * The tab bar stays above this host and never learns that a sub-screen is open: `Log activity`,
 * `Strength session` and now the timer's two screens keep it visible like every other screen,
 * and a draft left behind survives being abandoned through a tab.
 *
 * [onRouteChanged] is the one thing the chassis is told, and it is told nothing else: the
 * banner of PRD 6.4 hides while the timer screen is on top and has to know when that is. One
 * callback rather than a global or a `CompositionLocal` — the stack stays this tab's business.
 */
@Composable
fun ActivityNavHost(
    modifier: Modifier = Modifier,
    onRouteChanged: (ActivityRoute) -> Unit = {},
) {
    val stack = rememberActivityStack()
    val timer = timerViewModel()
    val timerState by timer.uiState.collectAsStateWithLifecycle()

    /*
     * `Start again` (contract decision 4). The copied request only has to survive as far as the
     * start screen's own state holder, which saves it from there — so this is `remember` and
     * not `rememberSaveable`: after a process death the restored builder already holds it.
     */
    var pendingStart by remember { mutableStateOf<StartTimerRequest?>(null) }

    LaunchedEffect(stack.current) { onRouteChanged(stack.current) }

    // FR-TIMER-002 and PRD 6.4: `Start timer`, the banner's `Open` and the notification's tap
    // all arrive as this one signal, and all three end on the same screen.
    LaunchedEffect(timerState.timerToOpen) {
        if (timerState.timerToOpen != null) {
            stack.showTimer()
            timer.onTimerOpened()
        }
    }

    // FR-TIMER-005: `Finish` opens the prefilled form immediately, from the screen or from the
    // notification.
    LaunchedEffect(timerState.reviewToOpen) {
        timerState.reviewToOpen?.let { draftId ->
            stack.showReview(draftId)
            timer.onReviewOpened()
        }
    }

    /*
     * The timer stopped somewhere this stack cannot see — `Discard` from the notification, or a
     * `Finish` fired from it — and the screen showing it is now showing nothing. The guard on
     * the current route is what makes this safe next to the two effects above: whichever of
     * them lands first, the other finds a route it no longer owns and does nothing.
     */
    LaunchedEffect(timerState.hasTimer, timerState.isLoading, timerState.reviewToOpen) {
        val stranded = !timerState.isLoading &&
            !timerState.hasTimer &&
            timerState.reviewToOpen == null &&
            stack.current == ActivityRoute.Timer
        if (stranded) stack.pop()
    }

    ActivityNavHost(stack = stack, modifier = modifier) { route ->
        ActivityDestination(
            route = route,
            stack = stack,
            timer = timer,
            hasTimer = timerState.hasTimer,
            pendingStart = pendingStart,
            onStartAgain = { request ->
                pendingStart = request
                stack.push(ActivityRoute.Start)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** FR-TIMER-001: the chooser is a handover to the timer, not a screen to come back to. */
internal fun ActivityStack.showTimer() {
    when (current) {
        ActivityRoute.Timer -> Unit
        ActivityRoute.Start -> replaceTop(ActivityRoute.Timer)
        else -> push(ActivityRoute.Timer)
    }
}

/** FR-TIMER-005: the timer has stopped, so the form takes its place rather than sitting on it. */
internal fun ActivityStack.showReview(draftId: TimedDraftId) {
    val route = ActivityRoute.Log(draftId = draftId)
    when (current) {
        route -> Unit
        ActivityRoute.Timer -> replaceTop(route)
        else -> push(route)
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
    timer: TimerViewModel,
    hasTimer: Boolean,
    pendingStart: StartTimerRequest?,
    onStartAgain: (StartTimerRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (route) {
        ActivityRoute.Dashboard -> ActivityScreen(
            /*
             * Contract decision 5: the action stays visible while a timer runs, and pressing it
             * is FR-TIMER-002 happening — the attempt opens the timer that already exists,
             * carrying `An activity is already in progress.` It never reaches the chooser, so a
             * whole activity is not described only to be refused at the end of it.
             */
            onStartActivity = {
                if (hasTimer) {
                    timer.openTimer(announceAlreadyLive = true)
                } else {
                    stack.push(ActivityRoute.Start)
                }
            },
            onLogPastActivity = { stack.push(ActivityRoute.Log()) },
            onStartAgain = onStartAgain,
            onOpenReview = { draftId -> stack.push(ActivityRoute.Log(draftId = draftId)) },
            onSeeAll = { stack.push(ActivityRoute.History) },
            onOpenSession = { sessionId -> stack.push(ActivityRoute.Log(sessionId = sessionId)) },
            modifier = modifier,
        )

        // PRD 6.2. The timer it starts is announced through `timerToOpen`, which is what takes
        // this screen's place — the screen itself navigates nowhere.
        ActivityRoute.Start -> StartActivityScreen(
            onBack = { stack.pop() },
            modifier = modifier,
            prefill = pendingStart,
        )

        // PRD 6.3. Back leaves the timer running: closing its screen is not stopping it.
        ActivityRoute.Timer -> TimerScreen(onBack = { stack.pop() }, modifier = modifier)

        ActivityRoute.History -> ActivityHistoryScreen(
            onBack = { stack.pop() },
            onOpenSession = { sessionId -> stack.push(ActivityRoute.Log(sessionId)) },
            modifier = modifier,
        )

        // A save returns to whatever opened the form — the dashboard, the history it was
        // reached through, or the timer it replaced — rather than always to the dashboard,
        // which would lose the reader's place in a list they may have scrolled a long way down.
        //
        // FR-TIMER-008: leaving through back keeps the draft; only a save turns it into a
        // session, and only `Discard` destroys it.
        is ActivityRoute.Log -> LogActivityScreen(
            sessionId = route.sessionId,
            onBack = { stack.pop() },
            onOpenStrengthSession = { stack.push(ActivityRoute.Strength) },
            onSaved = { stack.pop() },
            onDeleted = { stack.pop() },
            modifier = modifier,
            draftId = route.draftId,
        )

        // The adapter over `LogActivityViewModel` is what makes this editor and the log form two
        // views of one draft (PRD 9.1); without it the editor would keep a draft of its own and
        // never write.
        ActivityRoute.Strength -> StrengthSessionScreen(
            onBack = { stack.pop() },
            onSaved = { stack.pop(count = 2) },
            state = rememberSharedStrengthSessionState(),
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
