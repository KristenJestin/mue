package fr.kristenjestin.mue.ui.food.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.FoodViewScaffold
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

/** The arrows either side of the date, at the touch minimum PRD_FOOD 18 sets. */
private val StepButtonSize: Dp = MueMinTouchTarget

/** How far a disabled `Next day` fades — visible, plainly inert, as in the prototype. */
private const val DisabledStepAlpha = 0.2f

/**
 * The `Day` screen (PRD_FOOD 10.1), wired to the journal.
 *
 * [viewModel] defaults to the one `AppContainer` builds, exactly as `Log activity` does, and is
 * a parameter only so a test can hand its own in. There is no storeless variant: the four Room
 * repositories are registered on `AppContainer.food`, so a screen that quietly drew an empty day
 * instead of reading them would be a fifth tab that never shows what was eaten.
 */
@Composable
internal fun FoodDayRoute(
    onAddToSlot: (LocalDate, MealSlot) -> Unit,
    onEditEntry: (FoodLogEntryId) -> Unit,
    onSwapPlan: (MealPlanKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodDayViewModel = foodDayViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FoodDayScreen(
        state = state,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onOpenDatePicker = viewModel::onShowDatePicker,
        onDismissDatePicker = viewModel::onDismissDatePicker,
        onDayPicked = viewModel::onDayPicked,
        onAddToSlot = { slot -> onAddToSlot(state.date, slot) },
        onEditEntry = onEditEntry,
        onConfirmPlan = viewModel::onConfirmPlan,
        onSwapPlan = onSwapPlan,
        onDismissPlan = viewModel::onDismissPlan,
        modifier = modifier,
    )
}

/**
 * The journal of one day: the date, then the four moments (PRD_FOOD 10.1).
 *
 * There is no header band, no daily summary and no permanent settings button — PRD_FOOD 7 is
 * explicit about all three, and PRD_FOOD 22 makes the absence an acceptance criterion. There is
 * no bottom-anchored action either: PRD_FOOD 10.1 puts the add button *inside* each moment,
 * always present, so the screen never grows the pinned band that ate 112 dp of scroll on
 * `Log activity`.
 *
 * State is handed in whole, so every test below drives what reaches the screen rather than how a
 * ViewModel got there.
 */
@Composable
internal fun FoodDayScreen(
    state: FoodDayUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onDismissDatePicker: () -> Unit,
    onDayPicked: (LocalDate) -> Unit,
    onAddToSlot: (MealSlot) -> Unit,
    onEditEntry: (FoodLogEntryId) -> Unit,
    onConfirmPlan: (MealPlanKey) -> Unit,
    onSwapPlan: (MealPlanKey) -> Unit,
    onDismissPlan: (MealPlanKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing

    FoodViewScaffold(modifier = modifier, topFade = MueContentTopFade) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(FoodTestTags.DAY),
            contentPadding = PaddingValues(top = MueContentTopFade, bottom = spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            item(key = "date") {
                DayNavigation(
                    state = state,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    onOpenDatePicker = onOpenDatePicker,
                )
            }

            // Four moments, always four, in PRD_FOOD 10.1's order and never keyed by position.
            items(items = state.slots, key = { it.slot.id }) { slot ->
                FoodDaySlotSection(
                    state = slot,
                    onAdd = { onAddToSlot(slot.slot) },
                    onEditEntry = onEditEntry,
                    onConfirmPlan = onConfirmPlan,
                    onSwapPlan = onSwapPlan,
                    onDismissPlan = onDismissPlan,
                )
            }
        }
    }

    FoodDayDateSheet(
        visible = state.isDatePickerVisible,
        selected = state.date,
        today = state.today,
        onDismiss = onDismissDatePicker,
        onConfirm = onDayPicked,
    )
}

/**
 * The date and the two steps either side of it (PRD_FOOD 10.1).
 *
 * `Next day` is disabled on today rather than hidden: PRD_FOOD 22 refuses a future day, and a
 * control that disappears leaves the row jumping about as the week is walked. The guard is
 * repeated in the ViewModel, because a disabled control is still reachable by an assistive
 * service.
 */
@Composable
private fun DayNavigation(
    state: FoodDayUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenDatePicker: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        StepButton(
            iconName = MueIcons.CHEVRON_RIGHT,
            label = FoodDayMessages.PREVIOUS_DAY,
            enabled = state.canGoBack,
            mirrored = true,
            testTag = FoodTestTags.PREVIOUS_DAY,
            onClick = onPreviousDay,
        )

        MueText(
            text = state.dateLabel,
            style = MueTheme.typography.body,
            color = MueTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clip(MueTheme.shapes.field)
                .clickable(
                    role = Role.Button,
                    onClickLabel = FoodDayMessages.CHOOSE_DAY,
                    onClick = onOpenDatePicker,
                )
                .heightIn(min = MueMinTouchTarget)
                .padding(MueTheme.spacing.md)
                // `Today` alone never says which day is about to be written to.
                .semantics { contentDescription = state.dateDescription }
                .testTag(FoodTestTags.DAY_DATE),
        )

        StepButton(
            iconName = MueIcons.CHEVRON_RIGHT,
            label = FoodDayMessages.NEXT_DAY,
            enabled = state.canGoForward,
            mirrored = false,
            testTag = FoodTestTags.NEXT_DAY,
            onClick = onNextDay,
        )
    }
}

/**
 * One step through the week.
 *
 * `chevron-left` is the one Lucide glyph of the prototype's day header that the app has never
 * imported, and a drawable is not this screen's to add — so the right-hand chevron is mirrored.
 * A stroked chevron is symmetrical about its own axis, so the reflection is the very shape the
 * missing vector would have drawn.
 */
@Composable
private fun StepButton(
    iconName: String,
    label: String,
    enabled: Boolean,
    mirrored: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = MueTheme.colors
    Box(
        modifier = Modifier
            .size(StepButtonSize)
            .clip(MueTheme.shapes.field)
            .background(colors.surface)
            .alpha(if (enabled) 1f else DisabledStepAlpha)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        MueIcon(
            iconName = iconName,
            contentDescription = label,
            tint = colors.textSecondary,
            size = 18.dp,
            modifier = if (mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
        )
    }
}

// region previews

@Preview(name = "Day — populated", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodDayScreenPreview() {
    MuePreviewHost(padding = 0) {
        FoodDayScreen(
            state = previewDayState(),
            onPreviousDay = {},
            onNextDay = {},
            onOpenDatePicker = {},
            onDismissDatePicker = {},
            onDayPicked = {},
            onAddToSlot = {},
            onEditEntry = {},
            onConfirmPlan = {},
            onSwapPlan = {},
            onDismissPlan = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The same day on the narrowest phone the app supports and at the largest font scale.
 *
 * This is where the journal line used to come apart: the energy figures on the right took the
 * row for themselves, the name was left a ribbon two characters wide, `Golden chicken grain
 * bowl…` broke *mid-word* over seventeen lines and `1 × serving` came out one letter per line.
 * Nothing in the suite could see it — `onNodeWithText` matches the semantics string, which is the
 * whole name however the glyphs fall — so the check is this preview, the screenshot beside it and
 * `FoodDayEntryCardLayoutTest`, which reads the text layout rather than the string.
 *
 * What to look for: every name wrapped at a space, every time and quantity whole, and the figures
 * on their own line under the facts rather than crushed against them.
 */
@Preview(
    name = "Day — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 900,
    fontScale = 2.0f,
)
@Composable
private fun FoodDayNarrowPreview() {
    MuePreviewHost(padding = 0) {
        FoodDayScreen(
            state = previewDayState(),
            onPreviousDay = {},
            onNextDay = {},
            onOpenDatePicker = {},
            onDismissDatePicker = {},
            onDayPicked = {},
            onAddToSlot = {},
            onEditEntry = {},
            onConfirmPlan = {},
            onSwapPlan = {},
            onDismissPlan = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "Day — nothing logged", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodDayEmptyPreview() {
    MuePreviewHost(padding = 0) {
        FoodDayScreen(
            state = emptyDayState(),
            onPreviousDay = {},
            onNextDay = {},
            onOpenDatePicker = {},
            onDismissDatePicker = {},
            onDayPicked = {},
            onAddToSlot = {},
            onEditEntry = {},
            onConfirmPlan = {},
            onSwapPlan = {},
            onDismissPlan = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The pair of the preview above, and the one to look at when PRD_FOOD 13.2 is in question.
 *
 * One line, whose protein nobody wrote down. Held beside `Day — nothing logged` it is the whole
 * rule in two pictures: a day with no line shows no total at all, and a day with an unknown shows
 * `≈ 420 kcal` beside `— protein`. Neither shows a `0`.
 */
@Preview(name = "Day — an unknown protein", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodDayUnknownProteinPreview() {
    MuePreviewHost(padding = 0) {
        FoodDayScreen(
            state = unknownProteinDayState(),
            onPreviousDay = {},
            onNextDay = {},
            onOpenDatePicker = {},
            onDismissDatePicker = {},
            onDayPicked = {},
            onAddToSlot = {},
            onEditEntry = {},
            onConfirmPlan = {},
            onSwapPlan = {},
            onDismissPlan = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion

/** A column that keeps its children from being read as loose fragments (PRD_FOOD 18). */
internal fun Modifier.announcedAs(description: String): Modifier =
    clearAndSetSemantics { contentDescription = description }

