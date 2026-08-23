package fr.kristenjestin.mue.ui.entry

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.components.MueAnimatedNumber
import fr.kristenjestin.mue.ui.components.MueAnimatedNumberSuffixGap
import fr.kristenjestin.mue.ui.components.MueFieldContainer
import fr.kristenjestin.mue.ui.components.MueHaptics
import fr.kristenjestin.mue.ui.components.MueHeaderChip
import fr.kristenjestin.mue.ui.components.MuePickerField
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenScaffold
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.rememberMueHaptics
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
private const val SaveSuccessLabel = "Saved ✓"
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
    LaunchedEffect(state.weightRevision) {
        // A gesture in flight owns the value: the history seed arriving mid-drag must not
        // snatch it back (PRD FR-ENTRY-001).
        if (!ruler.interacting) ruler.jumpTo(state.weight.hundredthsKg)
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

        MuePickerField(
            label = "Measurement date",
            value = EntryFormat.date(state.date),
            trailingText = "Change",
            onClick = onOpenDatePicker,
            onClickLabel = "Change the measurement date",
        )

        MuePrimaryButton(
            label = SaveLabel,
            successLabel = SaveSuccessLabel,
            success = state.justSaved,
            onSuccessFinished = onSaveConfirmationFinished,
            onClick = {
                // The scale reports where it stopped, so a save landed on mid-glide would
                // otherwise record the value the finger left rather than the one on screen.
                onWeightChange(Weight.ofHundredthsClamped(ruler.displayedHundredths))
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

// --- Previews -----------------------------------------------------------------------

private fun previewState(
    weight: Double = 74.05,
    greeting: String? = "Hello Kris,",
    manualEntry: Boolean = false,
    manualInput: String = "",
    manualError: String? = null,
    justSaved: Boolean = false,
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
