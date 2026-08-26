package fr.kristenjestin.mue.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.timer.TimerIntents
import fr.kristenjestin.mue.timer.TimerLaunch
import fr.kristenjestin.mue.timer.TimerNotifications
import fr.kristenjestin.mue.ui.activity.ActivityNavHost
import fr.kristenjestin.mue.ui.activity.ActivityRoute
import fr.kristenjestin.mue.ui.components.MueBottomBar
import fr.kristenjestin.mue.ui.components.MueTab
import fr.kristenjestin.mue.ui.entry.EntryScreen
import fr.kristenjestin.mue.ui.food.FoodNavHost
import fr.kristenjestin.mue.ui.profile.ProfileNavHost
import fr.kristenjestin.mue.ui.progress.ProgressScreen
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.timer.TimerBanner
import fr.kristenjestin.mue.ui.timer.timerViewModel

/**
 * Root of the application: five permanent tabs above a bar that never moves, and — since the
 * Activity Timer — a compact banner between the two (PRD 8, PRD_ACTIVITIES 7,
 * PRD_ACTIVITY_TIMER 6.4).
 *
 * The tabs are siblings — none of them opens another tab — so the shell itself is a single
 * saved selection rather than a navigation graph. Anything a library would add here (routes,
 * entry providers, a stack per tab to keep the five screens alive) would only re-describe that
 * one integer.
 *
 * Three tabs hold several screens — `Activity`, `Food` since PRD_FOOD 7, and `Profile` since
 * PRD_SCALE 8 — and each keeps its stack to itself in its own host: the shell stays a selection,
 * and the bar above it never learns that a sub-screen is open. The one thing `Activity` reports
 * back is which screen is on top, because the banner hides while the timer's own screen is showing
 * the very same timer. `Food` and `Profile` report nothing, because nothing in the chassis depends
 * on what they are showing.
 */
@Composable
fun MueApp() {
    val selection = rememberMueTabSelection()
    val timer = timerViewModel()
    val timerState by timer.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * The Activity tab's current screen, as a key rather than as a route: a `String` crosses a
     * `Bundle` on its own, and `ActivityRoute.fromKey` is total, so a key written by another
     * build reads back as the dashboard instead of taking the first frame down.
     */
    var activityRouteKey by rememberSaveable { mutableStateOf(ActivityRoute.Dashboard.key) }
    val timerOnTop = selection.current == MueDestination.ACTIVITY &&
        ActivityRoute.fromKey(activityRouteKey) == ActivityRoute.Timer

    /*
     * Where the in-app transitions write the ongoing notification (PRD 6.5).
     *
     * `Start`, `Pause`, `Resume`, `Finish` and `Discard` all change the pair below, and the
     * chassis is the one surface present for every one of them — the screen that caused the
     * change is often already leaving. The receivers write it for the notification's own
     * buttons, and `BOOT_COMPLETED` after a reboot; between them every write site is covered.
     *
     * No cost on a cold start with no timer: the banner is already collecting the live draft,
     * so the database is open either way.
     */
    LaunchedEffect(timerState.isLoading, timerState.timer?.id, timerState.timer?.status) {
        if (!timerState.isLoading) TimerNotifications.refresh(context)
    }

    // PRD 6.5: where a notification tap actually lands. `MainActivity` can deliver the intent
    // but not navigate; it posts here, and this is the only place that consumes it.
    val pendingLaunch by TimerIntents.pendingLaunch.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLaunch) {
        val requested = pendingLaunch ?: return@LaunchedEffect
        when (requested) {
            TimerLaunch.OpenTimer -> timer.openTimer()
            // The timer was already stopped by `MainActivity`, in PRD 10's order.
            is TimerLaunch.OpenReview -> timer.openReview(requested.draftId)
        }
        selection.select(MueDestination.ACTIVITY)
        TimerIntents.consume(requested)
    }

    MueNavigationHost(
        selection = selection,
        banner = {
            TimerBanner(
                timer = timerState.timer,
                // Contract decision 1: one notice, rendered by whichever surface is present.
                notice = timerState.notice.takeUnless { timerOnTop },
                visible = !timerOnTop,
                onOpen = {
                    selection.select(MueDestination.ACTIVITY)
                    timer.openTimer()
                },
            )
        },
    ) { destination ->
        when (destination) {
            MueDestination.ENTRY -> EntryScreen(Modifier.fillMaxSize())
            MueDestination.PROGRESS -> ProgressScreen(Modifier.fillMaxSize())
            MueDestination.ACTIVITY -> ActivityNavHost(
                modifier = Modifier.fillMaxSize(),
                onRouteChanged = { route -> activityRouteKey = route.key },
            )

            // PRD_FOOD 7. The second tab holding several screens, and it keeps that stack to
            // itself the way Activity does: the shell stays a selection, and the bar above it
            // never learns that a view was switched or a sheet opened. It reports nothing back —
            // the timer banner has no reason to hide for anything inside Food.
            MueDestination.FOOD -> FoodNavHost(Modifier.fillMaxSize())

            // PRD_SCALE 8. Le troisième onglet à tenir plusieurs écrans, et il garde sa pile
            // pour lui comme les deux autres : `Profile > Scales`, la fiche d'une balance et le
            // flux d'appairage sont des réglages d'appareil, invisibles depuis les écrans
            // principaux. Il ne rapporte rien au châssis — rien dans la coque ne dépend de ce
            // qu'il affiche.
            MueDestination.PROFILE -> ProfileNavHost(Modifier.fillMaxSize())
        }
    }
}

/**
 * Which of the five tabs is on screen.
 *
 * Hoisted out of [MueNavigationHost] so the chassis can move it: PRD 6.4 has the banner open
 * the timer from any tab, and the notification of PRD 6.5 lands on the Activity tab whichever
 * one was last open. It is still the same single saved integer the shell always was.
 */
@Stable
internal class MueTabSelection internal constructor(destination: MueDestination) {

    var current: MueDestination by mutableStateOf(destination)
        private set

    fun select(destination: MueDestination) {
        current = destination
    }
}

/** Survives rotation and process death, as the shell's selection always has (PRD 16.3). */
@Composable
internal fun rememberMueTabSelection(): MueTabSelection =
    rememberSaveable(saver = MueTabSelectionSaver) { MueTabSelection(MueDestination.ENTRY) }

/** An ordinal, read back defensively: the enum may have been reordered by another build. */
private val MueTabSelectionSaver: Saver<MueTabSelection, Int> = Saver(
    save = { it.current.ordinal },
    restore = { ordinal ->
        MueTabSelection(MueDestination.entries.getOrElse(ordinal) { MueDestination.ENTRY })
    },
)

/**
 * The shell itself, with the screens left to the caller so tests can drive it without a
 * database behind them.
 *
 * Each tab is composed inside its own [rememberSaveableStateHolder] slot: leaving a tab
 * unmounts it, and the slot is what brings the ruler position, the selected period, a
 * half-filled form or an open Activity sub-screen back on return, and again after the process
 * has been killed (PRD 16.3).
 * Longer-lived state lives in the `ViewModel`s, which are scoped to the activity.
 *
 * [banner] is drawn between the animated content and the bar — outside what the navigation
 * animates, so a tab change neither slides it nor drops it (PRD_ACTIVITY_TIMER 6.4). It needs
 * no window inset of its own: [MueBottomBar] sits below it and already owns the navigation bar
 * and the IME through its `union`.
 */
@Composable
internal fun MueNavigationHost(
    modifier: Modifier = Modifier,
    selection: MueTabSelection = rememberMueTabSelection(),
    banner: @Composable () -> Unit = {},
    content: @Composable (MueDestination) -> Unit,
) {
    val selected = selection.current
    val screenStates = rememberSaveableStateHolder()
    val tabs = remember { MueDestination.entries.map { MueTab(it.label, it.iconRes) } }

    /*
     * A tab is not a screen you came from; back leaves through the first one. A tab that opens
     * its own screens registers a handler deeper in the tree, and back reaches this one only
     * once that tab is back at its root.
     */
    BackHandler(enabled = selected != MueDestination.ENTRY) {
        selection.select(MueDestination.ENTRY)
    }

    // Both directions are resolved here because `transitionSpec` runs outside composition.
    val forward = MueMotion.tabTransition(forward = true)
    val backward = MueMotion.tabTransition(forward = false)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MueTheme.colors.canvas),
    ) {
        AnimatedContent(
            targetState = selected,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) forward else backward
            },
            label = "tab",
        ) { destination ->
            screenStates.SaveableStateProvider(destination.name) {
                content(destination)
            }
        }

        banner()

        MueBottomBar(
            tabs = tabs,
            selectedIndex = selected.ordinal,
            onTabSelected = { index -> selection.select(MueDestination.entries[index]) },
        )
    }
}
