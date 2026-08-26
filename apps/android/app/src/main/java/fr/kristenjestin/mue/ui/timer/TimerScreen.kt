package fr.kristenjestin.mue.ui.timer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.timer.TimerNotifications
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueHaptic
import fr.kristenjestin.mue.ui.components.MueHaptics
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSplitRow
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueValueChip
import fr.kristenjestin.mue.ui.components.rememberMueHaptics
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** The prototype's `h-4 w-4` glyph inside an action, and the `h-5 w-5` of the header. */
private val ActionIconSize: Dp = 16.dp
private val NavigationIconSize: Dp = 18.dp

/** Same slab height as [fr.kristenjestin.mue.ui.components.MuePrimaryButton]. */
private val ActionMinHeight: Dp = 56.dp

/** Contraction depth on touch, as on both design-system buttons. */
private const val PressedScale = 0.975f

/** The prototype's `grid-cols-[1fr_1.3fr]`: `Finish` is the wider of the two. */
private const val PauseWeight = 1f
private const val FinishWeight = 1.3f

/** The `Active` / `Paused` bullet, which never carries that state on its own (PRD 11). */
private val StatusDotSize: Dp = 6.dp

/** The tile the activity's glyph sits in, and the smaller one on the background card. */
private val ActivityTileSize: Dp = 48.dp
private val NoteTileSize: Dp = 36.dp

private val OverflowMenuWidth: Dp = 180.dp

/**
 * The running timer (PRD_ACTIVITY_TIMER 6.3).
 *
 * Everything on it is derived from one persisted draft, recomputed once a second while the
 * screen is on show and not at all while it is not (FR-TIMER-003). The screen writes nothing of
 * its own: the five transitions belong to [TimerViewModel], and leaving through back changes
 * nothing at all — a timer keeps running when its screen is closed.
 */
@Composable
fun TimerScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = timerViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberTimerHaptics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /*
     * The seventh place the ongoing notification is written (contract 8bis).
     *
     * Android 14 relaxed `FLAG_ONGOING_EVENT`, so the notification can be swiped away while the
     * timer keeps running perfectly well — and its only background control surface is then gone
     * until the next state change. Coming back to this screen is the moment to repair it, and
     * it also covers the return from the `POST_NOTIFICATIONS` prompt, which resumes the app the
     * instant the answer is known.
     */
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { TimerNotifications.refresh(context) }
    }

    val actions = remember(viewModel, haptics, onBack) {
        TimerScreenActions(
            onBack = onBack,
            onTogglePause = {
                if (viewModel.uiState.value.timer?.isRunning == true) {
                    viewModel.pause()
                } else {
                    viewModel.resume()
                }
            },
            // PRD 11: the button contracts, the phone answers, and only then does the form open.
            onFinish = {
                haptics.perform(MueHaptic.Confirm)
                viewModel.finish()
            },
            onRequestDiscard = viewModel::requestDiscard,
            onCancelDiscard = viewModel::cancelDiscard,
            onConfirmDiscard = viewModel::discard,
        )
    }

    TimerScreenContent(state = state, actions = actions, modifier = modifier)
}

/** Everything the timer screen can ask for, so its layout can be driven without a database. */
@Stable
internal class TimerScreenActions(
    val onBack: () -> Unit = {},
    val onTogglePause: () -> Unit = {},
    val onFinish: () -> Unit = {},
    val onRequestDiscard: () -> Unit = {},
    val onCancelDiscard: () -> Unit = {},
    val onConfirmDiscard: () -> Unit = {},
)

@Composable
internal fun TimerScreenContent(
    state: TimerUiState,
    actions: TimerScreenActions,
    modifier: Modifier = Modifier,
) {
    /*
     * The last live timer is kept, exactly as the catalogue sheet keeps its last state: `Finish`
     * and `Discard` both make the draft stop being live, and the screen is still on screen for
     * the length of the transition that takes it away. Blanking for those frames would read as
     * a crash.
     */
    var lastTimer by remember { mutableStateOf(state.timer) }
    state.timer?.let { lastTimer = it }
    val timer = lastTimer ?: return

    var overflowOpen by remember { mutableStateOf(false) }
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing

    MueSubScreenScaffold(
        title = TimerMessages.ACTIVITY_IN_PROGRESS,
        onNavigateBack = actions.onBack,
        navigationIcon = {
            // The prototype's chevron: the timer rises over the tab, so it is put back down.
            MueIcon(
                iconName = MueIcons.CHEVRON_DOWN,
                tint = colors.textSecondary,
                size = NavigationIconSize,
            )
        },
        modifier = modifier.testTag(TimerTestTags.SCREEN),
        navigationContentDescription = TimerMessages.BACK_TO_ACTIVITY,
        trailing = {
            TimerOverflow(
                expanded = overflowOpen,
                onExpandedChange = { overflowOpen = it },
                onDiscard = {
                    overflowOpen = false
                    actions.onRequestDiscard()
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = spacing.lg)
                    .size(ActivityTileSize)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the name directly below it says what this is.
                MueIcon(
                    iconName = ActivityIcons.forMovement(timer.draft.movement),
                    tint = colors.onAccentSoft,
                )
            }

            MueText(
                text = timer.activityLabel,
                style = type.sheetTitle,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = spacing.md)
                    .testTag(TimerTestTags.ACTIVITY_LABEL),
            )

            MueText(
                text = timer.contextLabel,
                style = type.caption,
                color = colors.textTertiary,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.xxs),
            )

            TimerHalo(
                active = timer.isRunning,
                modifier = Modifier.padding(top = spacing.xxl),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    /*
                     * PRD 11: the description is what TalkBack reads, and there is deliberately
                     * no live region on it — one here would speak a new figure every second.
                     * The text itself is left in place so a test can still read the value.
                     */
                    MueText(
                        text = timer.elapsedText,
                        style = type.metricDisplay,
                        maxLines = 1,
                        modifier = Modifier
                            .testTag(TimerTestTags.ELAPSED)
                            .semantics { contentDescription = timer.elapsedDescription },
                    )
                    // No ceiling: at the largest font size the dial read `Started at 12:20 …`,
                    // and the two glyphs it dropped are the ones that tell midday from midnight.
                    MueText(
                        text = timer.startedAtText,
                        style = type.caption,
                        color = colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = spacing.sm)
                            .testTag(TimerTestTags.STARTED_AT),
                    )
                }
            }

            StatusLine(timer, modifier = Modifier.padding(top = spacing.xl))

            // Contract decision 1: the timer's own status line carries the notice while this
            // screen is the surface on show; the chassis banner carries it everywhere else.
            state.notice?.let { notice ->
                MueText(
                    text = notice.message,
                    style = type.caption,
                    color = if (notice.isProblem) colors.error else colors.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = spacing.sm)
                        .testTag(TimerTestTags.NOTICE)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            TimerActions(
                timer = timer,
                actions = actions,
                modifier = Modifier.padding(top = spacing.xl),
            )

            BackgroundNote(modifier = Modifier.padding(top = spacing.xxl))
        }
    }

    if (state.discardConfirmationVisible) {
        DiscardTimerDialog(
            status = timer.status,
            onConfirm = actions.onConfirmDiscard,
            onDismiss = actions.onCancelDiscard,
        )
    }
}

// region Parts

/**
 * `Active` or `Paused` (PRD 6.3), and the one live region on this screen (PRD 11).
 *
 * The word is the state; the bullet beside it only agrees with it, so the reading never rests
 * on the accent colour alone.
 */
@Composable
private fun StatusLine(timer: LiveTimerUiState, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    Row(
        modifier = modifier
            .testTag(TimerTestTags.STATUS)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(StatusDotSize)
                .clip(CircleShape)
                .background(if (timer.isRunning) colors.accent else colors.textQuiet),
        )
        MueText(
            text = timer.statusLabel,
            style = MueTheme.typography.chip,
            maxLines = 1,
            modifier = Modifier.padding(start = MueTheme.spacing.sm),
        )
    }
}

/** PRD 6.3: the principal action, and `Finish` beside it — never folded into one another. */
@Composable
private fun TimerActions(
    timer: LiveTimerUiState,
    actions: TimerScreenActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        TimerActionButton(
            label = timer.primaryActionLabel,
            iconName = if (timer.isRunning) MueIcons.PAUSE else MueIcons.PLAY,
            primary = false,
            onClick = actions.onTogglePause,
            modifier = Modifier
                .weight(PauseWeight)
                .testTag(TimerTestTags.PRIMARY_ACTION),
        )
        TimerActionButton(
            label = TimerMessages.FINISH,
            iconName = MueIcons.STOP,
            primary = true,
            onClick = actions.onFinish,
            modifier = Modifier
                .weight(FinishWeight)
                .testTag(TimerTestTags.FINISH),
        )
    }
}

/**
 * PRD 11: an icon *and* the word, in a target that clears 48 dp.
 *
 * Neither design-system button takes a glyph, and widening both of them for the two controls of
 * one screen would put an optional icon on every save button in the app.
 */
@Composable
private fun TimerActionButton(
    label: String,
    iconName: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.button
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PressedScale else 1f,
        animationSpec = MueMotion.spec(MueMotion.PressMillis),
        label = "timerActionScale",
    )
    val content = if (primary) colors.onAccent else colors.textPrimary

    /*
     * The glyph moves above the word when the word will not fit beside it.
     *
     * `Pause` takes the narrower of the two weights, and at the largest font size on a 360 dp
     * phone what was left beside the glyph came to less than the word: the principal control of
     * the running timer was drawn `Pau…`. Dropping the ceiling would not have helped — `Pause` is
     * one word, and a line break in the middle of it is not an improvement on an ellipsis — and
     * PRD 11 asks for an icon *and* the word, so neither could go. Stacking them hands the word
     * the whole button and keeps both, which is what a taller button costs.
     *
     * The threshold is measured: the label is laid out at the button's own type style, at the
     * current density and font scale, against the width left beside the glyph. At the ordinary
     * size both labels fit and the control is the row it always was.
     */
    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (primary) colors.accent else colors.surface)
            .then(
                if (primary) Modifier else Modifier.border(1.dp, colors.surfaceBorder, shape),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = ActionMinHeight)
            .padding(horizontal = MueTheme.spacing.md, vertical = MueTheme.spacing.lg),
    ) {
        val glyph = @Composable { MueIcon(iconName = iconName, tint = content, size = ActionIconSize) }
        val word = @Composable { padding: Dp ->
            MueText(
                text = label,
                style = MueTheme.typography.button,
                color = content,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = padding),
            )
        }

        if (labelFitsBesideGlyph(label, maxWidth)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                glyph()
                word(MueTheme.spacing.sm)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                glyph()
                word(0.dp)
            }
        }
    }
}

/** Whether [label] can be drawn whole in [room] once the glyph and its gap are taken out. */
@Composable
private fun labelFitsBesideGlyph(label: String, room: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val style = MueTheme.typography.button
    val gap = MueTheme.spacing.sm
    val beside = with(LocalDensity.current) { (room - ActionIconSize - gap).roundToPx() }

    return remember(measurer, label, style, beside) {
        beside > 0 && measurer.measure(label, style, maxLines = 1).size.width <= beside
    }
}

/** PRD 6.3's overflow, which holds `Discard timer` and nothing else (FR-TIMER-009). */
@Composable
private fun TimerOverflow(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.field

    Box {
        Box(
            modifier = Modifier
                .size(MueMinTouchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                .testTag(TimerTestTags.OVERFLOW)
                .semantics { contentDescription = TimerMessages.TIMER_OPTIONS },
            contentAlignment = Alignment.Center,
        ) {
            MueIcon(iconName = MueIcons.MORE_HORIZONTAL, tint = colors.textTertiary)
        }

        if (!expanded) return@Box

        /*
         * A `Popup`, not Material's `DropdownMenu`: the four Material components this app dresses
         * up are settled (PRD 12.1 and PRD_ACTIVITIES 14), and a fifth would arrive with its own
         * elevation, ripple and shape to undo. `Popup` is a window and a dismiss rule, nothing
         * more, and the panel inside it is the design system's own card.
         */
        Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, with(LocalDensity.current) { MueMinTouchTarget.roundToPx() }),
            onDismissRequest = { onExpandedChange(false) },
            properties = PopupProperties(focusable = true),
        ) {
            Row(
                modifier = Modifier
                    .width(OverflowMenuWidth)
                    .clip(shape)
                    .background(colors.canvasElevated)
                    .border(1.dp, colors.surfaceBorder, shape)
                    .clickable(role = Role.Button, onClick = onDiscard)
                    .testTag(TimerTestTags.DISCARD_TIMER)
                    .heightIn(min = MueMinTouchTarget)
                    .padding(horizontal = MueTheme.spacing.lg, vertical = MueTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MueIcon(iconName = MueIcons.TRASH, tint = colors.error, size = ActionIconSize)
                // No ceiling: the one entry of this menu read `Discard …` at the largest font
                // size, which does not say what would be discarded.
                MueText(
                    text = TimerMessages.DISCARD_TIMER,
                    style = MueTheme.typography.chip,
                    color = colors.error,
                    modifier = Modifier.padding(start = MueTheme.spacing.sm),
                )
            }
        }
    }
}

/** The prototype's reassurance, which is PRD 12's screen-off promise written out. */
@Composable
private fun BackgroundNote(modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    val spacing = MueTheme.spacing

    MueSurfaceCard(modifier = modifier, contentPadding = PaddingValues(spacing.lg)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(NoteTileSize)
                    .clip(MueTheme.shapes.small)
                    .background(colors.surfaceStrong),
                contentAlignment = Alignment.Center,
            ) {
                MueIcon(
                    iconName = MueIcons.BELL_RING,
                    tint = colors.accent,
                    size = ActionIconSize,
                )
            }

            /*
             * The badge shares the title's line rather than the whole block's.
             *
             * Beside the pair it left the sentence 175 dp of the card's 302, and
             * `Pause or finish from the Mue notification.` needs 230 at this size — measured off
             * device at 390 dp in the real face. On the title's line it takes 66 dp from a line
             * the title only uses 158 of, and the sentence gets the full 254.
             */
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = spacing.md),
            ) {
                /*
                 * Measured rather than weighted, and with no ceiling on either line. At the
                 * largest font size the title came out `Availab…` beside its badge and the
                 * sentence stopped at `the Mue notificati…`, so the card that promises the timer
                 * keeps running with the screen off said neither what is available nor where.
                 */
                MueSplitRow(
                    modifier = Modifier.fillMaxWidth(),
                    gap = 0.dp,
                    stackedGap = spacing.xs,
                    start = {
                        MueText(
                            text = TimerMessages.BACKGROUND_TITLE,
                            style = MueTheme.typography.micro,
                        )
                    },
                    end = {
                        MueValueChip(
                            text = TimerMessages.SILENT_BADGE,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    },
                )
                MueText(
                    text = TimerMessages.BACKGROUND_BODY,
                    style = MueTheme.typography.micro,
                    color = colors.textQuiet,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}

// endregion

/**
 * Mue's vibrations for the timer, read from the app's own preference (PRD FR-PROFILE-004).
 *
 * The preference lives on `AppContainer` and not on `TimerContainer`, and [TimerViewModel] was
 * deliberately not widened to carry it: nothing about a vibration belongs to the state of a
 * timer. It is read here, where the button that causes it is.
 *
 * The initial value is `false` on purpose. Until the stored flag has been read, a phone that
 * buzzes when the owner asked it not to is a worse failure than one that misses a single
 * confirmation on the first frame after a cold start.
 */
@Composable
internal fun rememberTimerHaptics(): MueHaptics {
    val context = LocalContext.current
    val enabledFlow = remember(context) {
        (context.applicationContext as? MueApplication)
            ?.container
            ?.userPreferencesRepository
            ?.preferences
            ?.map { it.hapticsEnabled }
            ?: flowOf(false)
    }
    val enabled by enabledFlow.collectAsStateWithLifecycle(initialValue = false)
    return rememberMueHaptics(enabled)
}

// region Previews

private fun previewTimer(
    status: TimedDraftStatus = TimedDraftStatus.RUNNING,
    seconds: Int = 1_543,
): LiveTimerUiState {
    val draft = TimedActivityDraft(
        id = TimedDraftId("preview"),
        status = status,
        movement = Movement.WALKING,
        startedAtMillis = 0L,
        startedOn = LocalDate.of(2026, 8, 24),
        startedAtLocalTime = LocalTime.of(18, 32, 47),
        environment = ActivityEnvironment.INDOOR,
        equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
    )
    val duration = requireNotNull(ActivityDuration.ofSecondsOrNull(seconds))
    return LiveTimerUiState(
        draft = draft,
        elapsed = duration,
        basis = null,
        isIncoherent = false,
        activityLabel = TimerFormat.activityLabel(draft.movement, null, draft.equipment),
        contextLabel = TimerFormat.context(draft.environment, draft.equipment),
        elapsedText = TimerFormat.elapsed(duration),
        elapsedDescription = TimerFormat.elapsedDescription(status, duration),
        startedAtText = TimerFormat.startedAt(draft.startedAtLocalTime),
        statusLabel = TimerFormat.statusLabel(status),
        primaryActionLabel = TimerFormat.primaryAction(status),
        bannerValue = TimerFormat.bannerValue(status, duration),
    )
}

@Preview(name = "Timer — running", showBackground = true, heightDp = 844, widthDp = 390)
@Composable
private fun TimerRunningPreview() {
    MueTheme {
        TimerScreenContent(
            state = TimerUiState(timer = previewTimer(), isLoading = false),
            actions = TimerScreenActions(),
        )
    }
}

@Preview(name = "Timer — paused", showBackground = true, heightDp = 844, widthDp = 390)
@Composable
private fun TimerPausedPreview() {
    MueTheme {
        TimerScreenContent(
            state = TimerUiState(
                timer = previewTimer(TimedDraftStatus.PAUSED),
                notice = TimerNotice.CHECK_ACTIVITY_TIME,
                isLoading = false,
            ),
            actions = TimerScreenActions(),
        )
    }
}

// endregion
