package fr.kristenjestin.mue.ui.entry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
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

    /*
     * PRD_SCALE 11: « la valeur suit le flux, marquée comme non définitive ».
     *
     * **Le flux instable pilote la règle sans jamais devenir [EntryUiState.weight]**, qui est ce
     * que `Save measurement` enregistre. C'est la contrainte entière de cette section, et elle est
     * tenue par le chemin et non par un garde : une trame ne passe pas par `postWeight`, donc
     * n'incrémente pas `weightRevision`, donc n'entre pas dans la valeur enregistrable — elle
     * arrive ici, sur l'objet qui détient la position vivante de la règle, exactement là où
     * arrivent les pixels d'un glissement du doigt. BR-SCALE-001 est vrai par construction et pas
     * seulement parce qu'un bouton est grisé.
     *
     * Deux raisons de ne pas emprunter l'effet ci-dessus. Il n'a qu'une clé, `weightRevision`, que
     * le flux ne doit surtout pas faire avancer ; et il *glisse* vers sa cible sur 180 ms, ce qui
     * pour une trame toutes les 200 ms produirait une règle perpétuellement en retard d'une
     * animation. Une trame est un fait déjà arrivé : elle se pose (`jumpTo`), et c'est la mesure
     * stable qui suit qui a droit au glissement de PRD_SCALE 19.
     *
     * `ofHundredthsOrNull` et non le constructeur qui borne : une trame hors domaine est
     * abandonnée, jamais tirée vers la borne la plus proche.
     */
    val live = state.scale.liveHundredths
    LaunchedEffect(live) {
        // Un doigt sur la règle a repris la valeur (FR-SCALE-022) : le `ViewModel` a déjà clos la
        // session, et la trame en vol ne doit pas la lui arracher entre-temps.
        if (live == null || ruler.interacting) return@LaunchedEffect
        Weight.ofHundredthsOrNull(live)?.let { ruler.jumpTo(it.hundredthsKg) }
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
        trailing = { EntryHeaderChips(state = state, onStatusAction = onScaleStatusAction) },
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

        /*
         * Exactement trois choses s'éteignent pendant le flux instable, et ce sont les trois qui
         * se battraient avec lui pour la même valeur : `−` / `+`, la saisie au clavier et
         * `Save measurement`. La règle, elle, reste tactile — un glissement est la façon
         * documentée de reprendre la valeur (FR-SCALE-022) — et le champ de date, les onglets et
         * tout le reste ne bougent pas (BR-SCALE-011).
         */
        val streaming = state.scale.streaming

        HeroReadout(
            ruler = ruler,
            manualEntry = state.manualEntry,
            // Touching the value toggles, as in the prototype. It is also the way back out of a
            // value the keyboard refuses to accept, which `Done` deliberately will not do.
            onClick = if (state.manualEntry) onDismissManualEntry else onOpenManualEntry,
            onStep = { steps, held -> stepWithHaptics(state.weight, steps, held, haptics, onStep) },
            stepsEnabled = !state.manualEntry && !streaming,
            keyboardEnabled = !streaming,
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

        ScaleFootnotes(scale = state.scale)

        MuePickerField(
            label = "Measurement date",
            value = EntryFormat.date(state.date),
            trailingText = "Change",
            onClick = onOpenDatePicker,
            onClickLabel = "Change the measurement date",
        )

        MuePrimaryButton(
            label = SaveLabel,
            // BR-SCALE-001 : une valeur qui bouge n'est pas enregistrable. Le bouton est éteint,
            // et `EntryViewModel.onSave` refuse de son côté — un bouton grisé est une protection
            // d'interface, la règle, elle, est métier.
            enabled = !streaming,
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

        SaveBlockedReason(scale = state.scale)

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
    keyboardEnabled: Boolean,
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
                    enabled = keyboardEnabled,
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
 *
 * That same live position is what the unstable stream writes into (PRD_SCALE 11), so this node —
 * and the ruler's draw scope — is the entire cost of a frame arriving. The number the reader
 * watches move is therefore never [EntryUiState.weight], which is what gets saved.
 *
 * [enabled] is the keyboard's gate and nothing else: while frames arrive, opening the field would
 * put a second author on a value the scale is already writing.
 */
@Composable
private fun HeroValue(
    ruler: RulerState,
    style: TextStyle,
    manualEntry: Boolean,
    enabled: Boolean,
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
                enabled = enabled,
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
 * **It no longer reports the link.** Searching, connecting, the radio being off — all of that
 * moved to the header chip, which answers the question actually being asked ("is it talking to my
 * scale?") in the place the eye goes for it. What is left here belongs to the *value*: what to do
 * to produce one, and what the one on screen is worth. So the slot holds exactly three things,
 * never two at once — the invitation to step on, the mark that the number is not final, and the
 * provenance of a number that is.
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
        /*
         * PRD_SCALE 11 : la valeur suit le flux, *marquée comme non définitive*. Cette ligne est
         * la marque, et elle est constante pendant toute la mesure — c'est ce qui la rend lisible
         * pendant que le grand chiffre, lui, change à chaque trame.
         */
        val note = when {
            scale.streaming -> ScaleMessages.NOT_FINAL_YET
            scale.indicator == EntryScaleIndicator.STEP_ON -> ScaleMessages.STEP_ON_THE_SCALE
            else -> null
        }
        if (note != null && !scale.fromScale) {
            MueText(
                text = note,
                style = typography.hint,
                color = colors.textTertiary,
                modifier = Modifier.testTag(ScaleTestTags.ENTRY_INDICATOR),
            )
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
 * What a paired scale may say above the date row: a value it refused, and a hint.
 *
 * Both sit at the bottom, beside the save error and away from the weight, and both are absent by
 * default. Neither disables anything: BR-SCALE-011 makes every function of Mue available with no
 * Bluetooth, no permission and no scale in range, so the ruler, the keyboard, the date and
 * `Save measurement` keep working underneath whatever is shown here.
 *
 * The third footnote — the actionable line of PRD_SCALE 18.5 — is gone from this block. It says
 * where the *link* stands, and the link now lives in the header chip, whole and in one place.
 */
@Composable
private fun ScaleFootnotes(scale: EntryScaleUiState) {
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
}

/**
 * The one sentence under `Save measurement`, and the only thing on this screen that explains a
 * control being off (BR-SCALE-001, FR-SCALE-023).
 *
 * The slot is reserved for as long as a scale is paired rather than appearing with the sentence:
 * a line materialising under the button would push the whole hero block up by its own height in
 * the middle of a weigh-in. It does not exist at all without a scale, so nobody's screen grew
 * (PRD_SCALE 18.1).
 */
@Composable
private fun SaveBlockedReason(scale: EntryScaleUiState) {
    if (!scale.paired) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MueTheme.spacing.sm)
            .height(ScaleNoteHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (scale.streaming) {
            MueText(
                text = ScaleMessages.WAITING_TO_SETTLE,
                style = MueTheme.typography.caption,
                color = MueTheme.colors.textTertiary,
                modifier = Modifier.testTag(ScaleTestTags.SAVE_BLOCKED_REASON),
            )
        }
    }
}

/**
 * The right of the header: the link, then the date — and each of them only when it has something
 * to say.
 *
 * **The link chip exists only with a scale registered** (FR-SCALE-020, PRD_SCALE 18.1): without
 * one this row is the single date chip the base PRD shipped, in the slot it has always had.
 *
 * **The date chip exists only away from today.** `Today` is the default state of this screen, and
 * a permanent chip repeating a default is a chip nobody reads — which is also what made the slot
 * available. It costs nothing: a received weigh-in selects today (BR-SCALE-009), so there is no
 * moment where the two are competing for attention with anything to say.
 */
@Composable
private fun EntryHeaderChips(
    state: EntryUiState,
    onStatusAction: (EntryScaleStatus) -> Unit,
) {
    val chip = state.scale.linkChip
    val dated = !state.isToday
    // Rien à dire, donc rien du tout — pas une `Row` vide, pas un espace réservé. C'est le même
    // littéralisme que `ScaleNote`, et c'est ce qui rend vrai « l'en-tête est celui d'avant le
    // module » pour quelqu'un sans balance, un jour où la date est aujourd'hui (PRD_SCALE 18.1).
    if (chip == null && !dated) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chip?.let { ScaleLinkChip(chip = it, onAction = onStatusAction) }
        if (dated) MueHeaderChip(EntryFormat.headerDate(state.date))
    }
}

/**
 * The whole state of the link, in a chip the size of the one that used to say `Today`.
 *
 * Three things carry it and they are deliberately not words: the colour (amber while the session
 * lives, grey otherwise), the dot, and whether that dot breathes. The label is the fourth and the
 * first to go — once the weight has landed there is nothing useful left to write there, and the
 * one thing that could be written, the scale's name, defaults to the model (`HB BODY FAT`) and
 * would burst the chip. Identity belongs to `Profile > Scales` (FR-SCALE-012).
 *
 * **A screen reader loses none of that** (PRD_SCALE 20). The description is the full sentence in
 * every state, label or no label, and the pane title names the region so `Connecting` is heard as
 * a fact about the scale rather than about the screen. The live region is set for exactly one
 * change — the scale becoming unusable — because that is the one the reader has to be told about
 * without looking; the arrival of a measurement is announced by the provenance mark, with its
 * value, once.
 *
 * The tap of FR-SCALE-025 comes with the three actionable states and with nothing else. It opens
 * no dialog and no system screen by itself: [onAction] carries the intent back to the screen,
 * which is the only place a `Context` exists.
 */
@Composable
private fun ScaleLinkChip(
    chip: EntryLinkChip,
    onAction: (EntryScaleStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val spacing = MueTheme.spacing
    val shape = MueTheme.shapes.pill
    val container = if (chip.active) colors.accentSoft else colors.surface
    val content = if (chip.active) colors.onAccentSoft else colors.textTertiary
    val action = chip.action

    Box(
        // FR-SCALE-025 : le geste est offert, donc il a la taille d'une cible tactile. Les états
        // qui n'attendent rien gardent la hauteur de la pastille de date, et l'en-tête avec.
        modifier = modifier.then(
            if (action != null) Modifier.heightIn(min = MueMinTouchTarget) else Modifier
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            modifier = Modifier
                .clip(shape)
                .background(container)
                .border(1.dp, colors.surfaceBorder, shape)
                .then(
                    if (action != null) {
                        Modifier.clickable(role = Role.Button, onClick = { onAction(action) })
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = LinkChipHorizontalPadding, vertical = spacing.sm)
                .testTag(ScaleTestTags.ENTRY_STATUS)
                .semantics(mergeDescendants = true) {
                    paneTitle = ScaleMessages.SCALE_STATUS_LABEL
                    contentDescription = chip.description
                    if (chip.announce) liveRegion = LiveRegionMode.Polite
                },
        ) {
            ScaleLinkDot(
                tint = if (chip.active) colors.accent else colors.textQuiet,
                pulsing = chip.pulsing,
            )
            chip.label?.let { label ->
                MueText(
                    text = label,
                    style = MueTheme.typography.chip,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The dot, which breathes while something is happening and holds still once it has happened.
 *
 * The breathing branch is a branch and not a flag so that no infinite animation runs on a screen
 * where nothing is in flight — an `InfiniteTransition` invalidates its reader on every frame, and
 * this screen has a gesture that cannot afford neighbours. The value is read inside
 * [Modifier.graphicsLayer], so even while it does run the cost is a layer property and never a
 * recomposition.
 *
 * Under reduced animations the dot simply stops breathing: the colour and the label already carry
 * the state, so nothing is lost by removing the movement (PRD 14).
 */
@Composable
private fun ScaleLinkDot(tint: Color, pulsing: Boolean) {
    val dot = Modifier.size(LinkDotSize).clip(CircleShape).background(tint)
    if (pulsing && !LocalReduceMotion.current) {
        val transition = rememberInfiniteTransition(label = "scaleLinkDot")
        val breath = transition.animateFloat(
            initialValue = LinkDotRestAlpha,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(LinkDotBreathMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "scaleLinkDotAlpha",
        )
        Box(modifier = Modifier.graphicsLayer { alpha = breath.value }.then(dot))
    } else {
        Box(modifier = dot)
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

/**
 * The link chip's own gutter, narrower than [MueHeaderChip]'s fourteen: it carries a dot as well
 * as a word, and the two of them at the date chip's padding would make it the widest thing in the
 * header — which is exactly what PRD_SCALE 19 rules out.
 */
private val LinkChipHorizontalPadding: Dp = 10.dp

/** Just large enough to read as a state, small enough never to read as a control. */
private val LinkDotSize: Dp = 6.dp

/** How faint the dot goes at the bottom of a breath, and how long one breath takes. */
private const val LinkDotRestAlpha = 0.35f
private const val LinkDotBreathMillis = 750

// --- Previews -----------------------------------------------------------------------

private fun previewState(
    weight: Double = 74.05,
    greeting: String? = "Hello Kris,",
    manualEntry: Boolean = false,
    manualInput: String = "",
    manualError: String? = null,
    justSaved: Boolean = false,
    daysBack: Long = 0,
    scale: EntryScaleUiState = EntryScaleUiState.ABSENT,
): EntryUiState {
    val today = LocalDate.of(2026, 8, 23)
    return EntryUiState(
        weight = requireNotNull(Weight.ofKilogramsOrNull(weight)),
        date = today.minusDays(daysBack),
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

/** The date chip, which only exists here: away from today, where it finally says something. */
@Preview(name = "Entry · another day", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryAnotherDayPreview() = EntryPreview(previewState(daysBack = 3))

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

/**
 * The unstable stream, which is the state the whole layout is arbitrated around: the value on the
 * ruler is the scale's, the three controls that would fight it are off and everything else — the
 * ruler under the finger first of all — is untouched.
 */
@Preview(name = "Entry · measuring", showBackground = true, backgroundColor = 0xFF101012, heightDp = 780)
@Composable
private fun EntryScaleMeasuringPreview() = EntryPreview(
    previewState(
        weight = 85.75,
        scale = EntryScaleUiState(
            paired = true,
            indicator = EntryScaleIndicator.MEASURING,
            liveHundredths = 8_575,
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
