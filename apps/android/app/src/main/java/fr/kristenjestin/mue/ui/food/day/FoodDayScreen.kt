package fr.kristenjestin.mue.ui.food.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueStickyActionRamp
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
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
    onAdd: (LocalDate) -> Unit,
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
        /*
         * The day, and **no moment**. That is the whole of the correction: what the sheet is
         * handed is the journal the reader is looking at, and the moment is left to the hour
         * (FR-FOOD-007) with the override still on the sheet where the owner asked for it.
         */
        onAdd = { onAdd(state.date) },
        onEditEntry = onEditEntry,
        onConfirmPlan = viewModel::onConfirmPlan,
        onSwapPlan = onSwapPlan,
        onDismissPlan = viewModel::onDismissPlan,
        modifier = modifier,
    )
}

/**
 * The journal of one day: the date, then the moments that hold something (PRD_FOOD 10.1).
 *
 * There is no header band, no daily summary and no permanent settings button — PRD_FOOD 7 is
 * explicit about all three, and PRD_FOOD 22 makes the absence an acceptance criterion.
 *
 * ## One add action, at the foot, where the rest of the app puts one
 *
 * This screen used to put an add row **inside each of the six moments**, which is what PRD_FOOD
 * 10.1 asks for — "un bouton d'ajout toujours présent", so that adding to breakfast is always
 * the same gesture in the same place. **The owner's instruction supersedes that**, and his
 * reasoning is the stronger one: *"je trouve ça bizarre que le + de lunch et le + de « add
 * something » ne soient pas le même […] vu que maintenant c'est géré via l'heure, ça a moins de
 * sens de partir du type de repas. Un bouton commun à tous ?"*
 *
 * It is the same defect as the one he reported at 00:26, when the sheet offered `Breakfast` while
 * showing its own window as 05:00–10:00. The domain was right — `MealSlot.forTime(00:26)` is
 * `EVENING_SNACK`, the window that wraps midnight — but he had pressed **breakfast's** `+`, which
 * passes the moment explicitly and pins it over the clock. A `+` per moment contradicts "the hour
 * decides", which is the rule he asked for the day before.
 *
 * **What §10.1 loses.** Adding *deliberately* to a moment other than the one the clock is in was
 * one tap on that moment's `+`; it is now the sheet's moment override, which is one step more.
 * That is the whole cost, and it is paid by the reader who wants to record last night's supper
 * this morning. It buys back the six greyed-out invitations, and it makes the sheet's own
 * `SLOT_FIELD` — "what the hour decided, and the way to overrule it" — the single place a moment
 * is ever chosen.
 *
 * **Where it went.** [MueStickyBottomAction] with a [MuePrimaryButton] inside it, which is what
 * `Foods` and `Recipes` — the other two views of this very switcher — already do, and what
 * `Log activity`, `Strength session` and `Add food` do behind them. The band is two parts and the
 * list's clearance is split at the seam, exactly as `FoodsScreen` splits it: the **solid block**
 * comes off the viewport through the `Modifier`, the **ramp** stays inside `contentPadding`.
 * Subtracting the whole band is what left the 112 dp of scroll no thumb could reach on
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
    onAdd: () -> Unit,
    onEditEntry: (FoodLogEntryId) -> Unit,
    onConfirmPlan: (MealPlanKey) -> Unit,
    onSwapPlan: (MealPlanKey) -> Unit,
    onDismissPlan: (MealPlanKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val density = LocalDensity.current
    val list = rememberLazyListState()
    var actionHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize()) {
        FoodViewScaffold(modifier = Modifier.fillMaxSize(), topFade = MueContentTopFade) {
            LazyColumn(
                state = list,
                modifier = Modifier
                    .fillMaxSize()
                    /*
                     * The pinned action's clearance, split exactly where the band is split. The
                     * viewport ends above the **solid** block, so no card comes to rest under
                     * chrome that eats touches; the ramp is left in, because it paints over live
                     * content and a thumb landing in the fade still has to scroll the list.
                     */
                    .padding(bottom = (actionHeight - MueStickyActionRamp).coerceAtLeast(0.dp))
                    .testTag(FoodTestTags.DAY),
                contentPadding = PaddingValues(
                    top = MueContentTopFade,
                    // Inside the scroll, so the last card rests clear of the fade.
                    bottom = MueStickyActionRamp,
                ),
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

                // PRD_FOOD 22 and 12: what a day still to come is, said once, above its moments.
                if (!state.canLog) item(key = "future") { FutureDayNote() }

                /*
                 * PRD_FOOD 17: a day nobody has written on has no headings at all now, so it says
                 * so in one line rather than drawing nothing. Only on a day that *could* hold a
                 * line — a future day has already explained itself directly above, and two
                 * statements about the same emptiness read as a fault.
                 */
                if (state.isBlank && state.canLog) item(key = "empty") { NothingLoggedYet() }

                // A moment appears when it holds something, in PRD_FOOD 10.1's order, and is
                // never keyed by position.
                items(items = state.visibleSlots, key = { it.slot.id }) { slot ->
                    FoodDaySlotSection(
                        state = slot,
                        onEditEntry = onEditEntry,
                        onConfirmPlan = onConfirmPlan,
                        onSwapPlan = onSwapPlan,
                        onDismissPlan = onDismissPlan,
                    )
                }
            }
        }

        MueStickyBottomAction(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size -> actionHeight = with(density) { size.height.toDp() } },
            coversContent = list.canScrollForward,
        ) {
            /*
             * PRD_FOOD 22: on a day still to come the action keeps its place and stops being a
             * control, rather than disappearing. A screen that reflows as the week is walked is
             * the defect the add row's own `enabled = false` was avoiding, and the reason is
             * unchanged — [FutureDayNote] above has already said why it is inert.
             */
            MuePrimaryButton(
                label = state.addLabel,
                enabled = state.canAdd,
                onClick = onAdd,
                modifier = Modifier.testTag(FoodTestTags.ADD_TO_DAY),
            )
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
 * PRD_FOOD 17: "aucune ligne aujourd'hui", now that six empty moments no longer say it.
 *
 * One line, in the quiet ink, stating a fact and asking for nothing — the action at the foot of
 * the screen is the invitation, and repeating it here would be the same control drawn twice.
 * It is deliberately not an error colour: a day nobody has eaten on yet is not a fault.
 */
@Composable
private fun NothingLoggedYet() {
    MueText(
        text = FoodDayMessages.NOTHING_LOGGED_YET,
        style = MueTheme.typography.body,
        color = MueTheme.colors.textTertiary,
        modifier = Modifier.fillMaxWidth().testTag(FoodTestTags.DAY_EMPTY),
    )
}

/**
 * What a day ahead of today is for, and what it is not (PRD_FOOD 12 and 22).
 *
 * Two sentences and no control. The moments below already say what each of them can hold, and a
 * refusal repeated five times on one screen reads as five errors rather than as one fact about the
 * day. It is deliberately not an error colour: nothing has gone wrong — the reader has simply
 * walked forward into a part of the module that keeps proposals rather than entries.
 *
 * Announced as one sentence, so a screen reader hears the fact and its consequence together
 * instead of two fragments (PRD_FOOD 18).
 */
@Composable
private fun FutureDayNote() {
    val colors = MueTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FoodTestTags.FUTURE_DAY)
            .announcedAs(
                FoodDayFormat.sentence(
                    FoodDayMessages.FUTURE_DAY,
                    FoodDayMessages.FUTURE_DAY_DETAIL,
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
    ) {
        // Neither is capped: at the largest font size these are the only words explaining why
        // the action at the foot of the screen has stopped being a button.
        MueText(
            text = FoodDayMessages.FUTURE_DAY,
            style = MueTheme.typography.bodyStrong,
            color = colors.textSecondary,
        )
        MueText(
            text = FoodDayMessages.FUTURE_DAY_DETAIL,
            style = MueTheme.typography.caption,
            color = colors.textTertiary,
        )
    }
}

/**
 * The date and the two steps either side of it (PRD_FOOD 10.1).
 *
 * `Next day` stops where both of the module's rules stop — the journal's ceiling *or* the sixty
 * days a proposal may be posed within — rather than on today, which is what left the planning half
 * of the module unreachable. It is disabled rather than hidden, so the row does not jump about as
 * the week is walked, and the guard is repeated in the ViewModel because a disabled control is
 * still reachable by an assistive service.
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
            onAdd = {},
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
            onAdd = {},
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
            onAdd = {},
            onEditEntry = {},
            onConfirmPlan = {},
            onSwapPlan = {},
            onDismissPlan = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A day still to come, which the module could not reach at all until today.
 *
 * What to look for: the date arrow on the right is live rather than faded, one line under the
 * date saying what this day is and is not, **only** the moments carrying a proposal — no empty
 * heading anywhere — the pinned action present and plainly inert, and the dinner`s proposal
 * carrying `Swap` and `Dismiss` but **not** `I ate this`, because nobody has eaten Thursday.
 */
@Preview(name = "Day — still to come", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodDayAheadPreview() {
    MuePreviewHost(padding = 0) {
        FoodDayScreen(
            state = futureDayState(),
            onPreviousDay = {},
            onNextDay = {},
            onOpenDatePicker = {},
            onDismissDatePicker = {},
            onDayPicked = {},
            onAdd = {},
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
            onAdd = {},
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

