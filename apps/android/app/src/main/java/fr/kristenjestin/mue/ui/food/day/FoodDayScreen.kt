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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import fr.kristenjestin.mue.ui.components.MueScreenScaffold
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

/** The arrows either side of the date, at the touch minimum PRD_FOOD 18 sets. */
private val StepButtonSize: Dp = MueMinTouchTarget

/** How far a disabled `Next day` fades — visible, plainly inert, as in the prototype. */
private const val DisabledStepAlpha = 0.2f

/**
 * The `Day` screen (PRD_FOOD 10.1), wired to its own state.
 *
 * [viewModel] is null until the journal has a store behind it: PRD_FOOD 20's Room tables and the
 * repository implementations land in a parallel change, and the domain interfaces shipped first
 * so that [FoodDayViewModel] could be written and proved against them in the meantime. With no
 * store the screen still opens on today and still walks the week — it simply reads a journal
 * that has nothing in it, which is a real state of this screen (PRD_FOOD 17) rather than an
 * error card. Wiring it is then one argument at this call site, and nothing below it moves.
 */
@Composable
internal fun FoodDayRoute(
    onAddToSlot: (LocalDate, MealSlot) -> Unit,
    onEditEntry: (FoodLogEntryId) -> Unit,
    onSwapPlan: (MealPlanKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodDayViewModel? = null,
) {
    if (viewModel == null) {
        UnstoredFoodDay(
            onAddToSlot = onAddToSlot,
            onEditEntry = onEditEntry,
            onSwapPlan = onSwapPlan,
            modifier = modifier,
        )
        return
    }

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
 * The same screen with the date held in composition instead of in a ViewModel, for as long as
 * there is no journal to read.
 *
 * It invents nothing: [FoodDayUiState.of] is handed no lines and no proposals, and answers with
 * the four empty moments PRD_FOOD 17 describes.
 */
@Composable
private fun UnstoredFoodDay(
    onAddToSlot: (LocalDate, MealSlot) -> Unit,
    onEditEntry: (FoodLogEntryId) -> Unit,
    onSwapPlan: (MealPlanKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    var date by rememberSaveable { mutableStateOf(today) }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    val state = remember(date, datePicker, today) {
        FoodDayUiState.of(date = date, today = today, isDatePickerVisible = datePicker)
    }

    FoodDayScreen(
        state = state,
        onPreviousDay = { date = date.minusDays(1) },
        onNextDay = { if (state.canGoForward) date = date.plusDays(1) },
        onOpenDatePicker = { datePicker = true },
        onDismissDatePicker = { datePicker = false },
        onDayPicked = {
            date = if (it.isAfter(today)) today else it
            datePicker = false
        },
        onAddToSlot = { slot -> onAddToSlot(date, slot) },
        onEditEntry = onEditEntry,
        onConfirmPlan = {},
        onSwapPlan = onSwapPlan,
        onDismissPlan = {},
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

    MueScreenScaffold(modifier = modifier, topFade = MueContentTopFade) {
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

private val PreviewToday: LocalDate = FoodDayPreviewData.TODAY

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

@Preview(name = "Day — nothing logged", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodDayEmptyPreview() {
    MuePreviewHost(padding = 0) {
        FoodDayScreen(
            state = FoodDayUiState.of(date = PreviewToday, today = PreviewToday),
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

