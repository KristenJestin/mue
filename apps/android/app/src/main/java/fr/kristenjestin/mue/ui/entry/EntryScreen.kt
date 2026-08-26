package fr.kristenjestin.mue.ui.entry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueAnimatedNumber
import fr.kristenjestin.mue.ui.components.MueAnimatedNumberSuffixGap
import fr.kristenjestin.mue.ui.components.MueFieldContainer
import fr.kristenjestin.mue.ui.components.MueHaptics
import fr.kristenjestin.mue.ui.components.MueHeaderChip
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePickerField
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenScaffold
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.rememberMueHaptics
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import fr.kristenjestin.mue.ui.scale.ScalePermissions
import fr.kristenjestin.mue.ui.scale.ScaleTestTags
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

private const val ScreenTitle = "Where are you today?"
private const val SlideHint = "SLIDE TO ADJUST"
private const val TypeHint = "TYPE YOUR WEIGHT"
private const val ManualEntryLabel = "Weight in kilograms"
private const val SaveLabel = "Save measurement"
private const val DecreaseLabel = "Decrease weight by 0.05 kilograms"
private const val IncreaseLabel = "Increase weight by 0.05 kilograms"
private const val WeightUnit = "kg"

/** One press of `−` or `+` moves the scale by a single step (PRD FR-ENTRY-003). */
private const val OneStep = 1

/** How far the ruler drops as it hands over to the keyboard (PRD 13, 180 ms). */
private val ManualEntrySlide: Dp = 24.dp

/** Share of the free height above and below the hero block; more of it below, as in the prototype. */
private const val HeroLeadWeight = 1f
private const val HeroTrailWeight = 1.5f

/**
 * The Entry tab: the hero readout, the touch scale, the date of the measurement and the save
 * action (PRD FR-ENTRY-001 to 007).
 *
 * The bottom tab bar is deliberately absent — it belongs to the navigation layer, which keeps
 * it immobile across tab changes (PRD 8).
 */
@Composable
fun EntryScreen(modifier: Modifier = Modifier) {
    val viewModel: EntryViewModel = viewModel(factory = EntryViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * FR-SCALE-020: the search runs while this screen is on screen, and not one moment longer.
     *
     * `LifecycleStartEffect` rather than `LaunchedEffect` because the two ways of leaving are
     * different events and both have to stop the scan: switching tab removes this composable,
     * and locking the phone leaves it composed while the process goes to the background. A
     * `LaunchedEffect` would survive the second one and keep the radio scanning in a pocket,
     * which is the one thing PRD_SCALE 3.7 rules out entirely.
     */
    LifecycleStartEffect(viewModel) {
        viewModel.onEntryVisible()
        onStopOrDispose { viewModel.onEntryHidden() }
    }

    /*
     * FR-SCALE-020: the phone stays awake while the search session runs, so it can be put down
     * before stepping on the scale, and goes back to normal the instant a stable weight lands,
     * the session expires or this screen leaves.
     *
     * Driven from the session state rather than from a flag of the Bluetooth layer, which
     * `ScaleSessionSource` does not expose: the four states that make up a search session are
     * exactly the window PRD_SCALE 20 describes, so the fact is derivable here and does not need
     * to cross the domain boundary. `onDispose` releases it unconditionally — a screen that goes
     * away mid-session must not leave the phone lit.
     */
    val view = LocalView.current
    DisposableEffect(view, state.scale.keepScreenOn) {
        view.keepScreenOn = state.scale.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    EntryContent(
        state = state,
        onWeightChange = viewModel::onWeightChanged,
        onStep = viewModel::onStep,
        onOpenManualEntry = viewModel::onManualEntryOpened,
        onDismissManualEntry = viewModel::onManualEntryDismissed,
        onManualInputChange = viewModel::onManualInputChanged,
        onConfirmManualEntry = viewModel::onManualEntryConfirmed,
        onOpenDatePicker = viewModel::onDatePickerOpened,
        onDismissDatePicker = viewModel::onDatePickerDismissed,
        onDateSelected = viewModel::onDateSelected,
        onSave = viewModel::onSave,
        onSaveConfirmationFinished = viewModel::onSaveConfirmationFinished,
        /*
         * FR-SCALE-025: a system screen only ever opens on a deliberate tap, and none of these
         * opens a dialog of Mue's own. The intents are built here rather than in the ViewModel
         * because a `ViewModel` has no `Context` and must not acquire one; the ViewModel is still
         * told, so it can retry the session or remember that the notice has been given.
         */
        onScaleStatusAction = { status ->
            viewModel.onScaleStatusAction(status)
            val intent = when (status) {
                EntryScaleStatus.NOT_FOUND -> null
                EntryScaleStatus.BLUETOOTH_OFF -> ScalePermissions.enableBluetoothIntent(context)
                EntryScaleStatus.PERMISSION_MISSING -> ScalePermissions.appSettingsIntent(context)
                EntryScaleStatus.SYSTEM_LOCATION_OFF ->
                    ScalePermissions.systemLocationSettingsIntent()
            }
            // A settings screen that no ROM ships is not an error worth showing anyone: the
            // status line stays, the weigh-in by hand was never blocked (BR-SCALE-011).
            intent?.let { runCatching { context.startActivity(it) } }
        },
        modifier = modifier,
    )
}

@Composable
internal fun EntryContent(
    state: EntryUiState,
    onWeightChange: (Weight) -> Unit,
    onStep: (Int) -> Unit,
    onOpenManualEntry: () -> Unit,
    onDismissManualEntry: () -> Unit,
    onManualInputChange: (String) -> Unit,
    onConfirmManualEntry: () -> Boolean,
    onOpenDatePicker: () -> Unit,
    onDismissDatePicker: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onSave: () -> Unit,
    onSaveConfirmationFinished: () -> Unit,
    onScaleStatusAction: (EntryScaleStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberMueHaptics(state.hapticsEnabled)

    /*
     * The scale's live position, owned here rather than by the ViewModel.
     *
     * Only the readout and the ruler's draw scope read it, which is what keeps a drag off the
     * recomposition path entirely (PRD 16.2). The screen's own weight is what everything else
     * uses and it catches up when the ruler stops.
     */
    val ruler = rememberRulerState(state.weight)

    /*
     * PRD_SCALE 19: a weigh-in arriving from the scale *moves* the ruler to its value instead of
     * teleporting it there, over the same 180 ms the rest of this screen moves in.
     *
     * Which revision travels and which one jumps is decided by `arrivalRevision` rather than by
     * the value: the history seed, `−` / `+` and the keyboard all reach this effect the same way,
     * and only the one the scale posted may glide. Reduced motion collapses it to the direct
     * change of value that section asks for.
     */
    val glide = MueMotion.spec<Float>(MueMotion.ManualEntryMillis)
    LaunchedEffect(state.weightRevision) {
        // A gesture in flight owns the value: the history seed arriving mid-drag must not
        // snatch it back (PRD FR-ENTRY-001).
        if (ruler.interacting) return@LaunchedEffect
        val arrived = state.scale.fromScale && state.scale.arrivalRevision == state.weightRevision
        if (arrived && !reduceMotion) {
            ruler.glideTo(state.weight.hundredthsKg, glide)
        } else {
            ruler.jumpTo(state.weight.hundredthsKg)
        }
    }

    LaunchedEffect(state.saveFlareCount) {
        if (state.saveFlareCount > 0) haptics.confirm()
    }

    val manualProgress by animateFloatAsState(
        targetValue = if (state.manualEntry) 1f else 0f,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "manualEntry",
    )
    val slidePx = with(LocalDensity.current) { ManualEntrySlide.toPx() }

    MueScreenScaffold(
        modifier = modifier,
        trailing = { MueHeaderChip(EntryFormat.headerDate(state.date, state.today)) },
    ) {
        MueScreenTitle(
            title = ScreenTitle,
            eyebrow = state.greeting,
            modifier = Modifier.padding(top = spacing.xl),
        )

        /*
         * The hero block floats between the title and the bottom actions rather than sitting
         * straight under the title.
         *
         * The prototype is 844 px tall and its single `mt-auto` gap reads as breathing room;
         * on a phone half again as tall the very same gap piles up under the ruler and reads
         * as a hole. Splitting the slack — most of it still below, as in the prototype —
         * keeps the readout near the optical centre at any height, while the date row and
         * the button stay pinned to the bottom.
         */
        Spacer(modifier = Modifier.weight(HeroLeadWeight))

        /*
         * The room reserved for the scale leaves with the scale.
         *
         * The keyboard claims about four tenths of the screen, so what is left has to hold the
         * readout, the field, the date row and the save action. The ruler's slot and the air
         * around it are the only part of this layout that exists for a control the keyboard has
         * just replaced, so they collapse on the same 180 ms curve the ruler fades on. The
         * weighted spacers redistribute whatever that frees, which is why the block reads no
         * tighter when there is height to spare.
         */
        val heroTopPadding = lerp(spacing.xxl, spacing.xs, manualProgress)
        val slotTopPadding = lerp(spacing.xl, spacing.sm, manualProgress)
        val slotMinHeight = lerp(WeightRulerHeight, 0.dp, manualProgress)

        HeroReadout(
            ruler = ruler,
            manualEntry = state.manualEntry,
            // Touching the value toggles, as in the prototype. It is also the way back out of a
            // value the keyboard refuses to accept, which `Done` deliberately will not do.
            onClick = if (state.manualEntry) onDismissManualEntry else onOpenManualEntry,
            onStep = { steps, held -> stepWithHaptics(state.weight, steps, held, haptics, onStep) },
            stepsEnabled = !state.manualEntry,
            atLowerStop = state.isAtLowerStop,
            atUpperStop = state.isAtUpperStop,
            controlsAlpha = 1f - manualProgress,
            modifier = Modifier.padding(top = heroTopPadding),
        )

        ScaleNote(
            scale = state.scale,
            weight = state.weight,
            modifier = Modifier.padding(top = spacing.sm),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = slotTopPadding)
                .heightIn(min = slotMinHeight),
        ) {
            if (manualProgress < 1f) {
                WeightRuler(
                    ruler = ruler,
                    weight = state.weight,
                    onWeightChange = onWeightChange,
                    enabled = !state.manualEntry,
                    onHapticTick = haptics::tick,
                    saveFlareCount = state.saveFlareCount,
                    modifier = Modifier
                        .fullBleed(spacing.screenHorizontal)
                        .graphicsLayer {
                            alpha = 1f - manualProgress
                            translationY = if (reduceMotion) 0f else manualProgress * slidePx
                        },
                )
            }
            if (manualProgress > 0f) {
                ManualWeightField(
                    value = state.manualInput,
                    errorMessage = state.manualError,
                    onValueChange = onManualInputChange,
                    onDone = onConfirmManualEntry,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer { alpha = manualProgress },
                )
            }
        }

        Spacer(modifier = Modifier.weight(HeroTrailWeight))

        state.saveError?.let { message ->
            MueText(
                text = message,
                style = MueTheme.typography.caption,
                color = colors.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = spacing.sm)
                    .semantics { error(message) },
            )
        }

        ScaleFootnotes(scale = state.scale, onStatusAction = onScaleStatusAction)

        MuePickerField(
            label = "Measurement date",
            value = EntryFormat.date(state.date),
            trailingText = "Change",
            onClick = onOpenDatePicker,
            onClickLabel = "Change the measurement date",
        )

        MuePrimaryButton(
            label = SaveLabel,
            success = state.justSaved,
            onSuccessFinished = onSaveConfirmationFinished,
            onClick = {
                // The scale reports where it stopped, so a save landed on mid-glide would
                // otherwise record the value the finger left rather than the one on screen.
                //
                // Except while the ruler is travelling to a weigh-in it was *sent* to
                // (PRD_SCALE 19): there the screen's weight is already the destination, and
                // publishing the position of a glide in progress would both record a value on
                // the way and read as a manual correction, stripping the provenance and the
                // impedance off the very measurement being saved (BR-SCALE-013).
                if (!ruler.gliding) {
                    onWeightChange(Weight.ofHundredthsClamped(ruler.displayedHundredths))
                }
                onSave()
            },
            modifier = Modifier.padding(top = spacing.md),
        )

        Spacer(modifier = Modifier.height(spacing.screenBottom))
    }

    EntryDateSheet(
        visible = state.datePickerVisible,
        selected = state.date,
        today = state.today,
        onDismiss = onDismissDatePicker,
        onConfirm = onDateSelected,
    )
}

/**
 * The value, the two step controls that flank it and the hint underneath.
 *
 * PRD FR-ENTRY-003 wants `−` and `+` permanently visible on either side of the balance and
 * leaves their placement to the implementation. They sit level with the readout rather than
 * with the graduations: that is where the eye already is while adjusting, and it hands the
 * whole screen width back to the ruler, which is the one element that only gets more legible
 * the wider it is.
 *
 * They are pinned to the gutter instead of hugging the number. A control that tracked the
 * width of `74.5` would shuffle sideways every time a digit came or went — on the readout the
 * user is staring at, during the very gesture that changes it.
 */
@Composable
private fun HeroReadout(
    ruler: RulerState,
    manualEntry: Boolean,
    onClick: () -> Unit,
    onStep: (steps: Int, held: Boolean) -> Unit,
    stepsEnabled: Boolean,
    atLowerStop: Boolean,
    atUpperStop: Boolean,
    controlsAlpha: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RulerStepButton(
                glyph = "−",
                stepDescription = DecreaseLabel,
                onStep = { held -> onStep(-OneStep, held) },
                enabled = stepsEnabled && !atLowerStop,
                modifier = Modifier.graphicsLayer { alpha = controlsAlpha },
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                HeroValue(
                    ruler = ruler,
                    style = rememberHeroValueStyle(),
                    manualEntry = manualEntry,
                    onClick = onClick,
                )
            }
            RulerStepButton(
                glyph = "+",
                stepDescription = IncreaseLabel,
                onStep = { held -> onStep(OneStep, held) },
                enabled = stepsEnabled && !atUpperStop,
                modifier = Modifier.graphicsLayer { alpha = controlsAlpha },
            )
        }
        MueText(
            text = if (manualEntry) TypeHint else SlideHint,
            style = MueTheme.typography.hint,
            color = MueTheme.colors.accent,
            modifier = Modifier.fillMaxWidth().padding(top = MueTheme.spacing.md),
        )
    }
}

/**
 * The number itself, and the only thing on the screen that recomposes while the finger moves.
 *
 * The live position is read here and nowhere above, so a drag rebuilds one text node instead
 * of the screen. The digit roll is dropped for as long as the scale is moving: rolling is how
 * one reading becomes another, and a drag has no readings, only a blur (PRD FR-ENTRY-002).
 */
@Composable
private fun HeroValue(
    ruler: RulerState,
    style: TextStyle,
    manualEntry: Boolean,
    onClick: () -> Unit,
) {
    val weight = Weight.ofHundredthsClamped(ruler.displayedHundredths)
    MueAnimatedNumber(
        text = EntryFormat.weight(weight),
        style = style,
        suffix = WeightUnit,
        contentDescription = EntryFormat.spokenWeight(weight),
        horizontalArrangement = Arrangement.Center,
        rolling = !ruler.interacting,
        modifier = Modifier
            .clip(MueTheme.shapes.field)
            .clickable(
                onClickLabel = if (manualEntry) "Go back to the scale" else "Type your weight",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = MueTheme.spacing.sm, vertical = MueTheme.spacing.xs),
    )
}

/**
 * The readout's type style, shrunk from the design system's `weightDisplay` only as far as
 * the phone's width demands.
 *
 * Two decimals made the widest reading of BR-003 six glyphs, and with tabular figures every
 * reading is then as wide as `250.00`. At the design system's own size that no longer clears
 * the `−` and `+` controls: the unit wrapped, and shaving padding could not buy back a whole
 * glyph. So the widest reading is measured against the room the row actually has, and the
 * style is scaled to fit — the ceiling stays the token, the floor is whatever the screen is.
 *
 * It is sized on the *widest* reading rather than the current one on purpose. Sizing on the
 * value on screen would resize the number as digits came and went, under the finger, during
 * the one gesture that must stay steady (PRD 16.2).
 */
@Composable
private fun rememberHeroValueStyle(): TextStyle {
    val base = MueTheme.typography.weightDisplay
    val unitStyle = MueTheme.typography.body
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spacing = MueTheme.spacing
    val containerWidth = LocalWindowInfo.current.containerSize.width

    return remember(base, unitStyle, measurer, density, spacing, containerWidth) {
        // Everything the row spends before the number gets any: the screen gutter, the two
        // step controls, the readout's own padding and the gap before the unit.
        val occupied = with(density) {
            (spacing.screenHorizontal * 2 + MueMinTouchTarget * 2 +
                spacing.sm * 2 + MueAnimatedNumberSuffixGap).roundToPx()
        }
        val unit = measurer.measure(WeightUnit, unitStyle).size.width
        val room = containerWidth - occupied - unit
        if (room <= 0) return@remember base

        val widest = EntryFormat.weight(WidestWeight)
        var style = base
        // Measured again after each shrink rather than trusted once: font sizes round to whole
        // pixels, so width does not follow the ratio exactly, and one pixel over is all it took
        // to push `kg` off the row.
        repeat(HeroFitAttempts) {
            val width = measurer.rolledWidthOf(widest, style)
            if (width <= room) return@remember style
            val scale = room.toFloat() / width
            style = style.copy(
                fontSize = style.fontSize * scale,
                lineHeight = style.lineHeight * scale,
            )
        }
        style
    }
}

/**
 * Width of [text] laid out the way the readout lays it out while it rolls: one node per glyph.
 *
 * A rolling digit is its own composable, so the row is the sum of six independently rounded
 * glyph boxes and comes out wider than the same string measured as a single run. Budgeting
 * from the single run is what left the unit with no room.
 */
private fun TextMeasurer.rolledWidthOf(text: String, style: TextStyle): Int {
    var width = 0
    for (character in text) width += measure(character.toString(), style).size.width
    return width
}

/** `250.00`: with tabular figures, no reading is wider (PRD BR-003). */
private val WidestWeight: Weight = Weight.ofHundredthsClamped(Weight.MAX_HUNDREDTHS)

/** Shrink passes allowed before the readout is accepted as it stands; two always suffice. */
private const val HeroFitAttempts = 4

/**
 * The keyboard alternative of PRD FR-ENTRY-004.
 *
 * Built on [MueFieldContainer] rather than on `MueTextField` for one reason: the field has to
 * take focus and raise the keyboard the instant it appears, which needs a [FocusRequester] on
 * the text field itself.
 */
@Composable
private fun ManualWeightField(
    value: String,
    errorMessage: String?,
    onValueChange: (String) -> Unit,
    onDone: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var field by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }

    LaunchedEffect(Unit) {
        // The field appears in the middle of a transition; one frame guarantees its focus node
        // is attached before it is asked to take focus.
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MueFieldContainer(
            label = ManualEntryLabel,
            focused = focused,
            isError = errorMessage != null,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = field,
                    onValueChange = { updated ->
                        field = updated
                        onValueChange(updated.text)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 32.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                    singleLine = true,
                    textStyle = MueTheme.typography.fieldValue.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    // `Decimal` raises a numeric keyboard; both `.` and `,` are then accepted
                    // by the parser whatever the phone's language is.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (onDone()) keyboard?.hide() },
                    ),
                )
                MueText(
                    text = "kg",
                    style = MueTheme.typography.body,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
            }
        }

        errorMessage?.let { message ->
            MueText(
                text = message,
                style = MueTheme.typography.caption,
                color = colors.error,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .semantics { error(message) },
            )
        }
    }
}

/**
 * Every deliberate press of `−` or `+` gets its own tick; a held repeat keeps the ruler's
 * half-kilogram cadence.
 *
 * The cadence of PRD FR-ENTRY-002 measures distance travelled, which is exactly what a hold
 * is. A single press is not travel but a discrete act, and it has to confirm itself: at
 * 0.05 kg a press it would otherwise stay silent eight times in ten, which reads as a control
 * that did not register. Ticking on each repeat instead would buzz rather than tick — the
 * repeat accelerates to one step every 36 ms — and put a vibrator call on every one of them.
 */
private fun stepWithHaptics(
    weight: Weight,
    steps: Int,
    held: Boolean,
    haptics: MueHaptics,
    onStep: (Int) -> Unit,
) {
    val landing = RulerPhysics.step(weight.hundredthsKg, steps)
    if (!held || RulerPhysics.crossesHapticStep(weight.hundredthsKg, landing)) haptics.tick()
    onStep(steps)
}

/**
 * Lets a child ignore the screen gutter and run edge to edge.
 *
 * The ruler wants every pixel of the phone's width: the wider it is, the more kilograms are
 * legible around the marker. Its own edge fade replaces the gutter visually.
 */
private fun Modifier.fullBleed(gutter: Dp): Modifier = layout { measurable, constraints ->
    val bleed = if (constraints.hasBoundedWidth) gutter.roundToPx() else 0
    val width = constraints.maxWidth + bleed * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
}

/**
 * The one line a paired scale adds beside the value, and the only one (PRD_SCALE 19).
 *
 * **Discreet by construction.** It is a caption in the quiet text colour, under the readout and
 * above the ruler, and it never becomes a card, an accent or a badge: the weight is the subject
 * of this screen and nothing here may compete with it.
 *
 * The slot is a fixed height so that a measurement arriving does not shove the ruler down the
 * screen under the finger — and the whole composable returns without emitting anything when no
 * scale is paired, which is PRD_SCALE 18.1's "strictly the base PRD's screen" taken literally:
 * not an invisible box, not a zero-height spacer, nothing.
 *
 * The provenance mark and the search indication share the slot because they are never both true:
 * the indication describes a weigh-in that has not landed, the mark a weigh-in that has.
 */
@Composable
private fun ScaleNote(
    scale: EntryScaleUiState,
    weight: Weight,
    modifier: Modifier = Modifier,
) {
    if (!scale.paired) return

    val colors = MueTheme.colors
    val typography = MueTheme.typography
    val spacing = MueTheme.spacing
    val fade = MueMotion.spec<Float>(MueMotion.ManualEntryMillis)

    Box(
        modifier = modifier.fillMaxWidth().height(ScaleNoteHeight),
        contentAlignment = Alignment.Center,
    ) {
        val indicator = scale.indicator
        if (indicator != null && !scale.fromScale) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier
                    .testTag(ScaleTestTags.ENTRY_INDICATOR)
                    /*
                     * PRD_SCALE 20 : « l'état de la balance est exposé aux services
                     * d'accessibilité ». Un libellé de zone, pas une région active.
                     *
                     * `paneTitle` est ce qui *nomme* une zone qui se met à jour d'elle-même —
                     * `AccessibilityNodeInfo.setPaneTitle` du côté de la plateforme — sans rien
                     * dire de ce qu'elle contient. C'est exactement ce qui manquait : sans lui,
                     * un lecteur d'écran qui tombe sur `Connecting` n'a aucun moyen de savoir de
                     * quoi cette ligne parle.
                     *
                     * Il ne pouvait pas s'agir d'une région active ici, et c'est toute la raison
                     * pour laquelle cette ligne-ci n'en porte pas : elle affiche le flux instable,
                     * qui change plusieurs fois par seconde pendant qu'on monte sur la balance.
                     * Le titre, lui, est constant, donc la plateforme n'émet rien tant que la
                     * ligne reste à l'écran — la seule chose qu'elle annonce est l'apparition de
                     * la zone, c'est-à-dire le début d'une session. « Jamais à chaque trame »
                     * reste vrai par construction.
                     */
                    .semantics { paneTitle = ScaleMessages.SCALE_STATUS_LABEL },
            ) {
                MueText(
                    text = indicator.message,
                    style = typography.hint,
                    color = colors.textTertiary,
                )
                /*
                 * PRD_SCALE 11: the unstable stream is visible and commits to nothing.
                 *
                 * It is shown *here*, in the indication, and never on the ruler. On the ruler it
                 * would become the value `Save measurement` records, and BR-SCALE-001 forbids an
                 * unstable reading from ever being saved. `ofHundredthsOrNull` and not the
                 * clamping constructor: a frame outside the range is dropped, never dragged to
                 * the nearest bound (contract §2).
                 */
                scale.liveHundredths?.let(Weight::ofHundredthsOrNull)?.let { live ->
                    MueText(
                        text = "${EntryFormat.weight(live)} $WeightUnit",
                        style = typography.hint,
                        color = colors.textQuiet,
                    )
                }
            }
        }

        // PRD_SCALE 19: the mark fades in, and fades out the moment the user takes the value
        // back. Under reduced motion `MueMotion.spec` already shortens it to the brief fade that
        // section allows, so there is nothing to branch on here.
        AnimatedVisibility(
            visible = scale.fromScale,
            enter = fadeIn(fade),
            exit = fadeOut(fade),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier
                    .testTag(ScaleTestTags.SOURCE_MARK)
                    /*
                     * PRD_SCALE 20: the arrival of a stable measurement is announced with its
                     * value. The live region sits on the mark and nowhere else — the mark exists
                     * exactly when a measurement has landed, so it speaks once per arrival and
                     * never once per frame, which is the distinction that section draws.
                     */
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = if (
                            scale.announcement == EntryScaleAnnouncement.MEASUREMENT_RECEIVED
                        ) {
                            ScaleMessages.measurementReceived(EntryFormat.spokenWeight(weight))
                        } else {
                            ScaleMessages.FROM_YOUR_SCALE
                        }
                    },
            ) {
                MueIcon(
                    iconName = ActivityIcons.TAB_ENTRY,
                    tint = colors.textTertiary,
                    size = ScaleMarkIconSize,
                )
                MueText(
                    text = ScaleMessages.FROM_YOUR_SCALE,
                    style = typography.hint,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

/**
 * What a paired scale may say above the date row: a value it refused, a hint, a way out.
 *
 * All three sit at the bottom, beside the save error and away from the weight, and every one of
 * them is absent by default. None of them disables anything: BR-SCALE-011 makes every function
 * of Mue available with no Bluetooth, no permission and no scale in range, so the ruler, the
 * keyboard, the date and `Save measurement` keep working underneath whatever is shown here.
 */
@Composable
private fun ScaleFootnotes(
    scale: EntryScaleUiState,
    onStatusAction: (EntryScaleStatus) -> Unit,
) {
    val colors = MueTheme.colors
    val typography = MueTheme.typography
    val spacing = MueTheme.spacing

    // FR-SCALE-024: said once, and the screen is otherwise left exactly as it was — the value on
    // the ruler, the date and the provenance are all untouched by a reading Mue will not record.
    if (scale.outOfRange) {
        MueText(
            text = ScaleMessages.MEASUREMENT_OUT_OF_RANGE,
            style = typography.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.sm)
                .testTag(ScaleTestTags.OUT_OF_RANGE_NOTICE),
        )
    }

    // PRD_SCALE 18.3: only when the driver reported an impedance it could not measure. A weight
    // saved before the impedance arrived, or a session that simply timed out, leaves this absent.
    if (scale.barefootHint) {
        MueText(
            text = ScaleMessages.BAREFOOT_HINT,
            style = typography.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.sm)
                .testTag(ScaleTestTags.BAREFOOT_HINT),
        )
    }

    scale.status?.let { status ->
        val announcement = if (scale.announcement == EntryScaleAnnouncement.UNAVAILABLE) {
            ScaleMessages.UNAVAILABLE_ANNOUNCEMENT
        } else {
            status.message
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.sm)
                .clip(MueTheme.shapes.small)
                // FR-SCALE-025: the tap is the whole point — nothing here opens a system screen
                // on its own, and nothing here opens a dialog of Mue's.
                .clickable(role = Role.Button, onClick = { onStatusAction(status) })
                .heightIn(min = MueMinTouchTarget)
                .padding(horizontal = spacing.xs)
                .testTag(ScaleTestTags.ENTRY_STATUS)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    // PRD_SCALE 20 : la même zone nommée que l'indication, dont cette ligne prend
                    // la place. Le libellé dit de quoi il est question ; la région active, elle,
                    // reste réglée sur le seul changement qui mérite d'être annoncé —
                    // l'indisponibilité —, et `announcement` n'est pas touché par ce libellé.
                    paneTitle = ScaleMessages.SCALE_STATUS_LABEL
                    contentDescription = announcement
                },
        ) {
            MueIcon(
                iconName = MueIcons.BLUETOOTH,
                tint = colors.textTertiary,
                size = ScaleMarkIconSize,
            )
            MueText(
                text = status.message,
                style = typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = spacing.xs),
            )
        }
    }
}

/**
 * The height the scale's line occupies, whatever it currently says — or nothing at all.
 *
 * Reserved rather than measured so that a weigh-in landing mid-adjustment cannot move the ruler
 * out from under a finger. It only exists once a scale is paired, so the screen of someone
 * without one is not a pixel taller (PRD_SCALE 18.1).
 */
private val ScaleNoteHeight: Dp = 22.dp

/** Small enough to read as punctuation beside the words, never as an indicator of its own. */
private val ScaleMarkIconSize: Dp = 14.dp

// --- Previews -----------------------------------------------------------------------

private fun previewState(
    weight: Double = 74.05,
    greeting: String? = "Hello Kris,",
    manualEntry: Boolean = false,
    manualInput: String = "",
    manualError: String? = null,
    justSaved: Boolean = false,
    scale: EntryScaleUiState = EntryScaleUiState.ABSENT,
): EntryUiState {
    val today = LocalDate.of(2026, 8, 23)
    return EntryUiState(
        weight = requireNotNull(Weight.ofKilogramsOrNull(weight)),
        date = today,
        today = today,
        greeting = greeting,
        manualEntry = manualEntry,
        manualInput = manualInput,
        manualError = manualError,
        justSaved = justSaved,
        scale = scale,
    )
}

@Composable
private fun EntryPreview(state: EntryUiState) {
    MueTheme {
        EntryContent(
            state = state,
            onWeightChange = {},
            onStep = {},
            onOpenManualEntry = {},
            onDismissManualEntry = {},
            onManualInputChange = {},
            onConfirmManualEntry = { true },
            onOpenDatePicker = {},
            onDismissDatePicker = {},
            onDateSelected = {},
            onSave = {},
            onSaveConfirmationFinished = {},
            onScaleStatusAction = {},
        )
    }
}

@Preview(name = "Entry · scale", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryScalePreview() = EntryPreview(previewState())

@Preview(name = "Entry · no greeting", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryNoGreetingPreview() = EntryPreview(previewState(greeting = null))

@Preview(name = "Entry · manual entry", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryManualPreview() = EntryPreview(
    previewState(manualEntry = true, manualInput = "74.05")
)

@Preview(name = "Entry · rejected value", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryManualErrorPreview() = EntryPreview(
    previewState(
        manualEntry = true,
        manualInput = "999",
        manualError = "Weight must be between 30.0 and 250.0 kg",
    )
)

@Preview(name = "Entry · saved", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntrySavedPreview() = EntryPreview(previewState(justSaved = true))

/*
 * The scale module on `Entry` (PRD_SCALE 12.2). Four previews for the four things it can add,
 * and the first of them is the one that matters most: the screen without a scale, which has to
 * stay pixel for pixel the one above (PRD_SCALE 18.1).
 */

@Preview(name = "Entry · looking", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryScaleSearchingPreview() = EntryPreview(
    previewState(
        scale = EntryScaleUiState(
            paired = true,
            indicator = EntryScaleIndicator.STEP_ON,
            keepScreenOn = true,
        ),
    )
)

@Preview(name = "Entry · from the scale", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryScaleReceivedPreview() = EntryPreview(
    previewState(
        weight = 74.35,
        scale = EntryScaleUiState(paired = true, fromScale = true),
    )
)

@Preview(name = "Entry · Bluetooth off", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryScaleUnavailablePreview() = EntryPreview(
    previewState(
        scale = EntryScaleUiState(paired = true, status = EntryScaleStatus.BLUETOOTH_OFF),
    )
)

@Preview(name = "Entry · out of range", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryScaleOutOfRangePreview() = EntryPreview(
    previewState(scale = EntryScaleUiState(paired = true, outOfRange = true))
)
