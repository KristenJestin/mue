package fr.kristenjestin.mue.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fr.kristenjestin.mue.ui.components.MueBottomBar
import fr.kristenjestin.mue.ui.components.MueTab
import fr.kristenjestin.mue.ui.entry.EntryScreen
import fr.kristenjestin.mue.ui.profile.ProfileScreen
import fr.kristenjestin.mue.ui.progress.ProgressScreen
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * Root of the application: three permanent tabs above a bar that never moves (PRD 8).
 *
 * There is no back stack to model — the tabs are siblings, none of them opens another
 * destination — so the shell is a single saved selection rather than a navigation graph.
 * Anything a library would add here (routes, entry providers, a stack per tab to keep the
 * three screens alive) would only re-describe that one integer.
 */
@Composable
fun MueApp() {
    MueNavigationHost { destination ->
        when (destination) {
            MueDestination.ENTRY -> EntryScreen(Modifier.fillMaxSize())
            MueDestination.PROGRESS -> ProgressScreen(Modifier.fillMaxSize())
            MueDestination.PROFILE -> ProfileScreen(Modifier.fillMaxSize())
        }
    }
}

/**
 * The shell itself, with the screens left to the caller so tests can drive it without a
 * database behind them.
 *
 * Each tab is composed inside its own [rememberSaveableStateHolder] slot: leaving a tab
 * unmounts it, and the slot is what brings the ruler position, the selected period or a
 * half-filled form back on return, and again after the process has been killed (PRD 16.3).
 * Longer-lived state lives in the `ViewModel`s, which are scoped to the activity.
 */
@Composable
internal fun MueNavigationHost(
    modifier: Modifier = Modifier,
    content: @Composable (MueDestination) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(MueDestination.ENTRY) }
    val screenStates = rememberSaveableStateHolder()
    val tabs = remember { MueDestination.entries.map { MueTab(it.label) } }

    // A tab is not a screen you came from; back leaves through the first one.
    BackHandler(enabled = selected != MueDestination.ENTRY) { selected = MueDestination.ENTRY }

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

        MueBottomBar(
            tabs = tabs,
            selectedIndex = selected.ordinal,
            onTabSelected = { index -> selected = MueDestination.entries[index] },
        )
    }
}
