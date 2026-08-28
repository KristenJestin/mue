package fr.kristenjestin.mue.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * Every word `Food preferences` puts on screen (PRD_FOOD 6.7, 13.2 and FR-FOOD-010).
 *
 * Constants rather than resources, for the reason `FoodCatalogueMessages` gives: the app ships in
 * one language, and a sentence a test can name is a sentence a test cannot mistype.
 *
 * They live here rather than in `FoodCatalogueMessages` because that object is documented as
 * "every word the **catalogue** puts on screen", and this screen is no longer reached from the
 * catalogue — nor from anywhere else in the Food module. Leaving them there would have been a
 * screen in `Profile` reading its copy out of a Food view's dictionary.
 */
internal object FoodPreferencesMessages {

    /** What the sub-screen calls itself, and what the row in `Profile` calls the way in. */
    const val TITLE: String = "Food preferences"

    const val BACK: String = "Back"

    /**
     * The card in `Profile`'s own `Preferences` section, worded as a door rather than as a
     * setting: PRD_FOOD 6.7 asks that the options live in the preferences and not on a screen,
     * and a door is not an option.
     */
    const val OPEN_BODY: String =
        "Whether energy and macronutrient figures are shown throughout the Food tab."

    const val OPEN: String = "Open food preferences"

    /**
     * PRD_FOOD 13.2 names the preference `Show energy`, so that is what the switch says, and it
     * is phrased the way it reads when it is **on** — as every other switch in the app is.
     */
    const val SHOW_ENERGY_TITLE: String = "Show energy"

    const val SHOW_ENERGY_BODY: String =
        "Energy and macronutrient figures throughout Food. Turn it off and every screen, search " +
            "and entry keeps working: only the numbers go."
}

/**
 * The way into [FoodPreferencesScreen], on the tab that holds the app's other preferences.
 *
 * It is a card with a button and not a switch of its own, deliberately. `Show energy` belongs to
 * a screen that PRD_FOOD 13.2 will grow — it names the module's preferences in the plural — and
 * lifting the one switch it holds today onto `Profile` would have to be undone the moment the
 * second one lands, taking its handle and its test with it.
 *
 * Shaped after [NotificationSettingsCard] and after Sync PRD 9.1's `Server settings`, which are
 * the two doors this screen already draws: a title, a line saying what is behind it, and a
 * secondary action. Nothing about the arrangement is new.
 */
@Composable
internal fun FoodPreferencesCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MueTheme.spacing
    MueSurfaceCard(
        modifier = modifier,
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
    ) {
        MueText(FoodPreferencesMessages.TITLE, MueTheme.typography.sectionTitle)
        MueText(
            text = FoodPreferencesMessages.OPEN_BODY,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.textSecondary,
            modifier = Modifier.padding(top = spacing.xs),
        )
        MueSecondaryButton(
            label = FoodPreferencesMessages.OPEN,
            onClick = onOpen,
            modifier = Modifier
                .padding(top = spacing.md)
                .testTag(ProfileTestTags.OPEN_FOOD_PREFERENCES),
        )
    }
}

/**
 * The Food module's preferences, wired to the stored preference.
 *
 * ## Why it is a `Profile` screen and not a Food one
 *
 * It used to be `FoodRoute.Preferences`, a sheet in the Food tab's own stack reached from a
 * settings button in that tab's header — *"déplace juste le bouton de settings dans foods et
 * mets-le dans profile"*. Moving only the button would have left the screen in Food's stack and
 * made `Profile` push onto another tab's navigation, which is worse than either alternative: the
 * bottom bar would still say `Food` while `Profile`'s own back handler knew nothing about what
 * was open. So the screen came too.
 *
 * Nothing about it was Food-specific to begin with. [FoodPreferencesViewModel] reads
 * [fr.kristenjestin.mue.domain.repository.UserPreferencesRepository] — the app's own preference
 * file, the one `hapticsEnabled` lives in and the one `Profile` was already writing — so the move
 * is a change of package and of nothing else. What it *gains* is [ProfileSwitchRow]: this file
 * used to restate that composable verbatim, because it was `internal` to this package and
 * promoting it would have meant reopening a shipped screen. Inside the package, the copy has no
 * reason to exist and is gone.
 */
@Composable
internal fun FoodPreferencesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodPreferencesViewModel = foodPreferencesViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FoodPreferencesScreen(
        state = state,
        onShowEnergyChange = viewModel::onShowEnergyChange,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * `Show energy`, and nothing else yet (PRD_FOOD 13.2, FR-FOOD-010).
 *
 * PRD_FOOD 6.7 is why this screen exists at all: "rien de permanent à l'écran pour un réglage
 * occasionnel — les options vivent dans les préférences". The switch is phrased the way it reads
 * when it is **on**, which is the name PRD_FOOD 13.2 gives the preference and the way every other
 * switch in the app is worded.
 *
 * Its handle is [ProfileTestTags.HIDE_ENERGY_TOGGLE]. The tag names the *effect* someone comes
 * here for; the control names the state it is in. It moved out of `FoodTestTags` with the node it
 * names — a tag belongs to whoever draws it, which is the rule `MueScaffoldTestTags` states — and
 * kept the name it was reserved under, because the setting is still about Food and the string a
 * test matches on is not the place to say so twice.
 */
@Composable
internal fun FoodPreferencesScreen(
    state: FoodPreferencesUiState,
    onShowEnergyChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MueSubScreenScaffold(
        title = FoodPreferencesMessages.TITLE,
        onNavigateBack = onBack,
        navigationIcon = {
            MueIcon(MueIcons.ARROW_LEFT, tint = MueTheme.colors.textSecondary, size = 18.dp)
        },
        navigationContentDescription = FoodPreferencesMessages.BACK,
        modifier = modifier.testTag(ProfileTestTags.FOOD_PREFERENCES),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                /*
                 * The header's ramp. This screen is the sharpest case of the defect: one card,
                 * shorter than the viewport, so the scroll range is zero and the top of the
                 * switch row was dissolved with *no* gesture able to bring it back.
                 */
                .padding(top = MueContentTopFade),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            ProfileSwitchRow(
                title = FoodPreferencesMessages.SHOW_ENERGY_TITLE,
                description = FoodPreferencesMessages.SHOW_ENERGY_BODY,
                checked = state.showEnergy,
                onCheckedChange = onShowEnergyChange,
                modifier = Modifier.testTag(ProfileTestTags.HIDE_ENERGY_TOGGLE),
            )
        }
    }
}

// region previews

@Preview(name = "Food preferences", showBackground = true, backgroundColor = 0xFF101012, heightDp = 420)
@Composable
private fun FoodPreferencesPreview() {
    MuePreviewHost(padding = 0) {
        FoodPreferencesScreen(
            state = FoodPreferencesUiState(showEnergy = true),
            onShowEnergyChange = {},
            onBack = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Food preferences — energy hidden", showBackground = true, backgroundColor = 0xFF101012, heightDp = 420)
@Composable
private fun FoodPreferencesOffPreview() {
    MuePreviewHost(padding = 0) {
        FoodPreferencesScreen(
            state = FoodPreferencesUiState(showEnergy = false),
            onShowEnergyChange = {},
            onBack = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The screen on the narrowest phone at the largest text size.
 *
 * What to look for: the explanation wrapped at spaces and never mid-word, the switch track still
 * beside the words rather than crushing them, and the top of the card clear of the header's ramp.
 */
@Preview(
    name = "Food preferences — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 520,
    fontScale = 2.0f,
)
@Composable
private fun FoodPreferencesNarrowPreview() {
    MuePreviewHost(padding = 0) {
        FoodPreferencesScreen(
            state = FoodPreferencesUiState(showEnergy = true),
            onShowEnergyChange = {},
            onBack = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// endregion
