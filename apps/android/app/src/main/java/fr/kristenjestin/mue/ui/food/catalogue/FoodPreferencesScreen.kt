package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

private val TrackWidth = 46.dp
private val TrackHeight = 28.dp
private val ThumbSize = 22.dp
private val ThumbInset = 3.dp

/**
 * The module's preferences sheet (PRD_FOOD 7 and 6.7), wired to the stored preference.
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
 * Its handle is `FoodTestTags.HIDE_ENERGY_TOGGLE`, reserved before this screen was written. The
 * tag names the *effect* someone comes here for; the control names the state it is in. Renaming
 * a shared tag to match would have reopened a file three other screens are being built against.
 */
@Composable
internal fun FoodPreferencesScreen(
    state: FoodPreferencesUiState,
    onShowEnergyChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MueSubScreenScaffold(
        title = FoodCatalogueMessages.PREFERENCES_TITLE,
        onNavigateBack = onBack,
        navigationIcon = {
            MueIcon(MueIcons.ARROW_LEFT, tint = MueTheme.colors.textSecondary, size = 18.dp)
        },
        navigationContentDescription = FoodCatalogueMessages.BACK,
        modifier = modifier.testTag(FoodTestTags.PREFERENCES),
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
            FoodSwitchRow(
                title = FoodCatalogueMessages.SHOW_ENERGY_TITLE,
                description = FoodCatalogueMessages.SHOW_ENERGY_BODY,
                checked = state.showEnergy,
                onCheckedChange = onShowEnergyChange,
                modifier = Modifier.testTag(FoodTestTags.HIDE_ENERGY_TOGGLE),
            )
        }
    }
}

/**
 * One preference on its own card, the arrangement `ProfileSwitchRow` already ships.
 *
 * It is restated here rather than reused because `ProfileSwitchRow` is `internal` to the profile
 * package and promoting it would mean reopening a shipped screen that another chunk of work is
 * sweeping this week. The whole card is the switch, so the target is far above the 48 dp
 * PRD_FOOD 18 asks for, and TalkBack announces one `Switch` carrying both the title and its
 * explanation.
 */
@Composable
private fun FoodSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    MueSurfaceCard(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(MueTheme.spacing.lg),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MueText(title, MueTheme.typography.bodyStrong)
                MueText(
                    text = description,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = MueTheme.spacing.xxs),
                )
            }
            SwitchTrack(checked = checked)
        }
    }
}

/** Purely visual: the click and the semantics belong to the row that hosts it. */
@Composable
private fun SwitchTrack(checked: Boolean) {
    val colors = MueTheme.colors
    val track by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.surfaceStrong,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "foodSwitchTrack",
    )
    val thumb by animateColorAsState(
        targetValue = if (checked) colors.onAccent else colors.textTertiary,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "foodSwitchThumb",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) TrackWidth - ThumbSize - ThumbInset else ThumbInset,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "foodSwitchThumbOffset",
    )

    Box(
        modifier = Modifier
            .width(TrackWidth)
            .height(TrackHeight)
            .clip(MueTheme.shapes.pill)
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                .size(ThumbSize)
                .clip(CircleShape)
                .background(thumb),
        )
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

// endregion
